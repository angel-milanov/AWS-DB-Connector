package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.tunnel.BackendSession
import com.angelmilanov.awsdbconnector.tunnel.TunnelBackend
import com.angelmilanov.awsdbconnector.util.DesktopShell
import com.angelmilanov.awsdbconnector.util.Platform
import com.angelmilanov.awsdbconnector.util.SystemDesktop
import com.angelmilanov.awsdbconnector.util.TrayHandle
import com.sun.net.httpserver.HttpServer
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Image
import java.awt.PopupMenu
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseListener
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.SwingUtilities

/** Waits until [condition] holds; the tests never sleep for a fixed time to let something happen. */
fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "Timed out waiting for: $what" }
        Thread.sleep(20)
    }
}

object TestEnv {
    /**
     * The Gradle test task points the user's home, configuration and cache at build/test-home. Tests that use the
     * default locations call this first, so that a run without that setup stops before it touches real files.
     */
    fun requireIsolated() {
        check("test-home" in Platform.configDir.toString() && "test-home" in Platform.cacheDir.toString()) {
            "Run the tests through Gradle: they would use ${Platform.configDir} otherwise."
        }
    }

    fun tempDir(name: String): Path = Files.createTempDirectory("awsdbc-$name")
}

// ---------------------------------------------------------------------------------------------- programs

/**
 * A program that is not really started.
 *
 * @param exitCode null for a program that runs until it is destroyed
 * @param timesOut waiting for it with a time limit gives up at once, as if the limit had passed
 * @param interrupted waiting for it without a time limit is interrupted
 */
class FakeProcess(
    output: String = "",
    exitCode: Int? = 0,
    private val timesOut: Boolean = false,
    private val interrupted: Boolean = false,
) : Process() {
    private val ended = CountDownLatch(if (exitCode == null) 1 else 0)

    @Volatile
    private var code = exitCode ?: 143

    @Volatile
    var destroyed = false
        private set

    private val stdout = ByteArrayInputStream(output.toByteArray())

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        if (interrupted) throw InterruptedException("interrupted while waiting")
        ended.await()
        return code
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = if (timesOut) ended.count == 0L else ended.await(timeout, unit)

    override fun exitValue(): Int {
        if (ended.count > 0) throw IllegalThreadStateException("still running")
        return code
    }

    override fun destroy() {
        destroyed = true
        ended.countDown()
    }

    override fun descendants(): Stream<ProcessHandle> = Stream.empty()

    /** Ends the program as if it had stopped on its own. */
    fun exit(code: Int) {
        this.code = code
        ended.countDown()
    }
}

/** Stands in for every program the application starts, for as long as it is open. */
class FakePrograms(var respond: (List<String>) -> Process = { FakeProcess() }) : AutoCloseable {
    val started = CopyOnWriteArrayList<ProcessBuilder>()
    val commands: List<List<String>> get() = started.map { it.command() }

    init {
        Platform.launcher = { builder ->
            started += builder
            respond(builder.command())
        }
    }

    override fun close() {
        Platform.launcher = ProcessBuilder::start
    }
}

/** Records what the application wants to open instead of opening it. */
class FakeDesktop(private val broken: Boolean = false) : DesktopShell, AutoCloseable {
    val opened = CopyOnWriteArrayList<String>()
    val copied = CopyOnWriteArrayList<String>()

    init {
        Platform.desktop = this
    }

    override fun copy(text: String) {
        copied += text
    }

    override fun browse(url: String) {
        if (broken) throw UnsupportedOperationException("no browser")
        opened += url
    }

    override fun reveal(file: Path) {
        if (broken) throw UnsupportedOperationException("no file manager")
        opened += file.toString()
    }

    override fun close() {
        Platform.desktop = SystemDesktop
    }
}

class FakeTray(private val broken: Boolean = false) : TrayHandle {
    @Volatile
    override var image: Image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    @Volatile
    override var toolTip: String? = null

    @Volatile
    override var popupMenu: PopupMenu? = null
    val mouseListeners = CopyOnWriteArrayList<MouseListener>()
    val messages = CopyOnWriteArrayList<String>()

    @Volatile
    var removed = false

    override fun addMouseListener(listener: MouseListener) {
        mouseListeners += listener
    }

    override fun displayMessage(title: String, text: String, type: TrayIcon.MessageType) {
        if (broken) throw IllegalStateException("the tray is gone")
        messages += "$type $title: $text"
    }

    override fun remove() {
        if (broken) throw IllegalStateException("the tray is gone")
        removed = true
    }
}

// ---------------------------------------------------------------------------------------------- tunnels

fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

/** Stands in for the database at the far end of a tunnel: sends back whatever it receives. */
class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        Thread.ofVirtual().start {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread.ofVirtual().start { socket.use { runCatching { it.getInputStream().transferTo(it.getOutputStream()) } } }
            }
        }
    }

    override fun close() = server.close()
}

/** A Session Manager session that exists in the test only. */
class FakeSession(override val id: String, override val localPort: Int) : BackendSession {
    @Volatile
    var alive = true

    /** What AWS says about the session: there, gone, or no answer. */
    @Volatile
    var remote: Boolean? = true
    val remoteChecks = AtomicInteger()
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    override fun isAlive() = alive

    override fun onExit(callback: () -> Unit) {
        callbacks += callback
    }

    override fun checkRemote(): Boolean? {
        remoteChecks.incrementAndGet()
        return remote
    }

    override fun close() = kill()

    /** The session ends, as it does when it times out or the network goes away. */
    fun kill() {
        if (alive) {
            alive = false
            callbacks.forEach { it() }
        }
    }
}

/**
 * Opens [FakeSession]s that lead to [port].
 * @param failure what opening a session fails with, by connection and by number of the attempt (1 is the first)
 */
class FakeBackend(@Volatile var port: Int, var failure: (ConnectionProfile, Int) -> Exception? = { _, _ -> null }) : TunnelBackend {
    val opens = AtomicInteger()
    val sessions = CopyOnWriteArrayList<FakeSession>()

    override fun open(profile: ConnectionProfile, log: (String) -> Unit): BackendSession {
        val n = opens.incrementAndGet()
        failure(profile, n)?.let { throw it }
        return FakeSession("fake-$n", port).also { sessions += it }
    }
}

// ---------------------------------------------------------------------------------------------- AWS

/**
 * Answers the requests of the AWS SDK. The SDK is pointed at it with the system property aws.endpointUrl, and the
 * AWS profiles are read from the file the Gradle test task names in aws.configFile.
 */
object FakeAws {
    data class Reply(val status: Int, val body: String, val contentType: String = "text/xml")

    /** Replies by action (DescribeInstances, StartSession, …). An action without a reply is answered with an error. */
    val replies = ConcurrentHashMap<String, Reply>()

    /** The actions that were called, with the body of the request. */
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            val action = exchange.requestHeaders.getFirst("X-Amz-Target")?.substringAfter('.')
                ?: Regex("Action=([A-Za-z]+)").find(body)?.groupValues?.get(1)
                ?: "unknown"
            requests += action to body
            val reply = replies[action] ?: Reply(400, """{"__type":"NoReplyConfigured","message":"The test has no reply for $action"}""", "application/x-amz-json-1.1")
            val bytes = reply.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", reply.contentType)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        System.setProperty("aws.endpointUrl", endpoint)
    }

    fun reset() {
        replies.clear()
        requests.clear()
        profiles(PROFILES)
    }

    fun called(action: String): Int = requests.count { it.first == action }

    /** Writes the AWS config file the application reads. */
    fun profiles(text: String) {
        val file = Paths.get(checkNotNull(System.getProperty("aws.configFile")) { "Run the tests through Gradle: aws.configFile is not set." })
        check("test-home" in file.toString()) { "Refusing to write $file" }
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    fun json(body: String, status: Int = 200) = Reply(status, body, "application/x-amz-json-1.1")

    /** An error of a JSON service (Systems Manager). */
    fun jsonError(code: String, message: String) = json("""{"__type":"$code","message":"$message"}""", 400)

    /** An error of EC2. */
    fun ec2Error(code: String, message: String) =
        Reply(400, "<Response><Errors><Error><Code>$code</Code><Message>$message</Message></Error></Errors><RequestID>r-1</RequestID></Response>")

    /** An error of a query service (RDS). */
    fun queryError(code: String, message: String) = Reply(
        400,
        "<ErrorResponse><Error><Type>Sender</Type><Code>$code</Code><Message>$message</Message></Error><RequestId>r-1</RequestId></ErrorResponse>",
    )

    /** @param instances instance id to name; a blank name is an instance without a Name tag */
    fun instances(vararg instances: Pair<String, String>) = Reply(
        200,
        """<DescribeInstancesResponse xmlns="http://ec2.amazonaws.com/doc/2016-11-15/"><requestId>r-1</requestId><reservationSet><item><instancesSet>""" +
            instances.joinToString("") { (id, name) ->
                "<item><instanceId>$id</instanceId><tagSet>" +
                    (if (name.isBlank()) "" else "<item><key>Name</key><value>$name</value></item>") +
                    "<item><key>Team</key><value>data</value></item></tagSet></item>"
            } +
            "</instancesSet></item></reservationSet></DescribeInstancesResponse>",
    )

    /** @param databases identifier to endpoint address; a blank address is an instance that is still being created */
    fun dbInstances(vararg databases: Pair<String, String>) = Reply(
        200,
        """<DescribeDBInstancesResponse xmlns="http://rds.amazonaws.com/doc/2014-10-31/"><DescribeDBInstancesResult><DBInstances>""" +
            databases.joinToString("") { (id, address) ->
                "<DBInstance><DBInstanceIdentifier>$id</DBInstanceIdentifier><Engine>postgres</Engine>" +
                    (if (address.isBlank()) "" else "<Endpoint><Address>$address</Address><Port>5432</Port></Endpoint>") +
                    "</DBInstance>"
            } +
            "</DBInstances></DescribeDBInstancesResult><ResponseMetadata><RequestId>r-1</RequestId></ResponseMetadata></DescribeDBInstancesResponse>",
    )

    /** @param clusters identifier to (writer endpoint, reader endpoint); blank endpoints are left out */
    fun dbClusters(vararg clusters: Pair<String, Pair<String, String>>) = Reply(
        200,
        """<DescribeDBClustersResponse xmlns="http://rds.amazonaws.com/doc/2014-10-31/"><DescribeDBClustersResult><DBClusters>""" +
            clusters.joinToString("") { (id, endpoints) ->
                "<DBCluster><DBClusterIdentifier>$id</DBClusterIdentifier><Engine>aurora-mysql</Engine><Port>3306</Port>" +
                    (if (endpoints.first.isBlank()) "" else "<Endpoint>${endpoints.first}</Endpoint>") +
                    (if (endpoints.second.isBlank()) "" else "<ReaderEndpoint>${endpoints.second}</ReaderEndpoint>") +
                    "</DBCluster>"
            } +
            "</DBClusters></DescribeDBClustersResult><ResponseMetadata><RequestId>r-1</RequestId></ResponseMetadata></DescribeDBClustersResponse>",
    )

    fun session(id: String) = json("""{"SessionId":"$id","TokenValue":"token-of-$id","StreamUrl":"wss://ssmmessages.example/$id"}""")

    fun sessions(vararg ids: String) = json("""{"Sessions":[${ids.joinToString(",") { """{"SessionId":"$it","Status":"Connected"}""" }}]}""")

    /**
     * keys: access keys, nothing to sign in to. sso: IAM Identity Center with an sso-session section.
     * legacy: the older form with the start URL in the profile. role: a role on top of the sso profile.
     * nowhere: no region. loop: a source_profile that points at itself.
     */
    const val PROFILES = """
        [profile keys]
        region = eu-central-1
        aws_access_key_id = AKIAIOSFODNN7EXAMPLE
        aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

        [profile sso]
        sso_session = corp
        sso_account_id = 111122223333
        sso_role_name = Developer
        region = eu-west-1

        [sso-session corp]
        sso_start_url = https://corp.awsapps.com/start
        sso_region = eu-west-1

        [profile legacy]
        sso_start_url = https://legacy.awsapps.com/start
        sso_region = eu-west-1
        sso_account_id = 111122223333
        sso_role_name = Developer

        [profile role]
        source_profile = sso
        role_arn = arn:aws:iam::111122223333:role/Admin

        [profile nowhere]
        aws_access_key_id = AKIAIOSFODNN7EXAMPLE
        aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

        [profile loop]
        source_profile = loop
        """
}

// ---------------------------------------------------------------------------------------------- Swing

/** Runs [block] on the event dispatch thread and returns its result; failures come back as they were thrown. */
fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val result = AtomicReference<Result<T>>()
    try {
        SwingUtilities.invokeAndWait { result.set(runCatching(block)) }
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }
    return result.get().getOrThrow()
}

/** Returns once everything that was queued on the event dispatch thread so far has run. */
fun flushEdt() = repeat(3) { onEdt { } }

/**
 * Deals with the next modal dialog that opens. A modal dialog blocks whoever opens it until it is closed, so somebody
 * else has to answer it: [answer] runs on the event dispatch thread once the dialog is on screen and has to close it.
 */
class DialogAnswer(private val answer: (Dialog) -> Unit) {
    private val before = Window.getWindows().filter { it.isShowing }.toSet()
    private val failure = AtomicReference<Throwable>()
    private val answered = CountDownLatch(1)

    init {
        Thread.ofPlatform().daemon().name("dialog-answer").start {
            try {
                val deadline = System.currentTimeMillis() + 30_000
                var dialog: Dialog? = null
                while (dialog == null) {
                    check(System.currentTimeMillis() < deadline) { "No dialog was opened" }
                    Thread.sleep(20)
                    dialog = onEdt { Window.getWindows().filterIsInstance<Dialog>().firstOrNull { it.isShowing && it.isModal && it !in before } }
                }
                onEdt { answer(dialog) }
            } catch (e: Throwable) {
                failure.set(e)
                // Do not leave the test hanging behind a dialog nobody answers.
                runCatching { onEdt { Window.getWindows().filterIsInstance<Dialog>().filter { it.isShowing && it !in before }.forEach { it.dispose() } } }
            } finally {
                answered.countDown()
            }
        }
    }

    /** Returns once the dialog has been answered; fails if it was not, or if answering it failed. */
    fun await() {
        check(answered.await(40, TimeUnit.SECONDS)) { "The dialog was not answered" }
        failure.get()?.let { throw AssertionError("Answering the dialog failed: $it", it) }
    }
}

/** Runs [trigger], which opens a modal dialog and does not return before that is closed, and lets [answer] close it. */
fun <T> answering(answer: (Dialog) -> Unit, trigger: () -> T): T {
    val pending = DialogAnswer(answer)
    val result = onEdt(trigger)
    pending.await()
    return result
}

/** The component that follows the label with this text in a form. */
fun Container.labelled(text: String): Component {
    val label = first<JLabel> { it.text == text }
    val siblings = label.parent.components
    return siblings[siblings.indexOf(label) + 1]
}

/** All components below this one, depth first. */
fun Container.descendants(): List<Component> = components.flatMap { c -> listOf(c) + ((c as? Container)?.descendants() ?: emptyList()) }

inline fun <reified T : Component> Container.all(): List<T> = descendants().filterIsInstance<T>()

inline fun <reified T : Component> Container.first(matches: (T) -> Boolean = { true }): T =
    all<T>().firstOrNull(matches) ?: throw AssertionError("No ${T::class.simpleName} like that in ${this::class.simpleName}")

/** The button, check box or menu item with this text. */
fun Container.button(text: String): AbstractButton = first<AbstractButton> { it.text == text }

fun Container.click(text: String) = button(text).doClick(0)
