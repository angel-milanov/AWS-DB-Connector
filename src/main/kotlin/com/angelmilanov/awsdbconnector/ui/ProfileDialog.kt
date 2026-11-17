package com.angelmilanov.awsdbconnector.ui

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.AwsDiscovery
import com.angelmilanov.awsdbconnector.aws.AwsProfileInfo
import com.angelmilanov.awsdbconnector.aws.AwsProfiles
import com.angelmilanov.awsdbconnector.aws.AwsResources
import com.angelmilanov.awsdbconnector.aws.BastionInfo
import com.angelmilanov.awsdbconnector.aws.DatabaseInfo
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import net.miginfocom.swing.MigLayout
import java.awt.Window
import java.util.concurrent.TimeUnit
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Editor for a single connection profile. [result] is non-null after the user pressed Save. */
class ProfileDialog(
    owner: Window?,
    private val original: ConnectionProfile?,
    private val others: List<ConnectionProfile>,
    private val loginGate: LoginGate,
) : JDialog(owner, if (original == null) "New connection" else "Edit connection", ModalityType.APPLICATION_MODAL) {

    var result: ConnectionProfile? = null
        private set

    private val awsProfiles: List<AwsProfileInfo> = AwsProfiles.load()

    private val nameField = JTextField(28)
    private val profileCombo = JComboBox(awsProfiles.map { it.name }.toTypedArray())
    private val regionField = JTextField()
    private val loadButton = JButton("Load from AWS")
    private val loadStatus = JLabel(" ")
    private val bastionCombo = JComboBox<Any>().apply { isEditable = true }
    private val dbCombo = JComboBox<Any>().apply { isEditable = true }
    private val hostField = JTextField()
    private val dbPortSpinner = portSpinner(5432)
    private val localPortSpinner = portSpinner(5432)
    private val autoConnect = JCheckBox("Connect automatically when the application starts", true)
    private val keepAlive = JCheckBox("Keep the session alive (reconnect immediately when it drops)", true)
    private val errorLabel = JLabel(" ")
    private var localPortTouched = original != null

    init {
        val form = JPanel(MigLayout("insets 22 24 18 24, fillx, wrap 2, hidemode 3", "[right]14[grow,fill,320::]", "[]10[]"))

        form.add(section("Connection"), "span, growx, gapbottom 2")
        form.add(JLabel("Name"))
        form.add(nameField)

        form.add(section("AWS"), "span, growx, gaptop 10, gapbottom 2")
        form.add(JLabel("AWS profile"))
        form.add(profileCombo)
        form.add(JLabel("Region"))
        form.add(regionField)
        form.add(JLabel(""))
        form.add(JPanel(MigLayout("insets 0, fillx", "[][grow,fill]")).apply {
            add(loadButton)
            add(loadStatus)
        })
        form.add(JLabel("Bastion host"))
        form.add(bastionCombo)

        form.add(section("Database"), "span, growx, gaptop 10, gapbottom 2")
        form.add(JLabel("RDS instance"))
        form.add(dbCombo)
        form.add(JLabel("Host name"))
        form.add(hostField)
        form.add(JLabel("Database port"))
        form.add(dbPortSpinner, "growx 0, width 110!")

        form.add(section("Local"), "span, growx, gaptop 10, gapbottom 2")
        form.add(JLabel("Local port"))
        form.add(localPortSpinner, "growx 0, width 110!")
        form.add(JLabel(""))
        form.add(autoConnect)
        form.add(JLabel(""))
        form.add(keepAlive)

        errorLabel.foreground = UIManager.getColor("Actions.Red") ?: java.awt.Color(0xF8, 0x51, 0x49)
        val save = JButton("Save").apply { addActionListener { onSave() } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        form.add(errorLabel, "span, growx, gaptop 8")
        form.add(JPanel(MigLayout("insets 0, fillx", "[grow][][]")).apply {
            add(JLabel(""))
            add(cancel, "width 90!")
            add(save, "width 90!")
        }, "span, growx, gaptop 4")

        contentPane = form
        rootPane.defaultButton = save
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)

        regionField.putClientProperty("JTextField.placeholderText", "from the AWS profile")
        hostField.putClientProperty("JTextField.placeholderText", "mydb.xxxxxxxx.eu-central-1.rds.amazonaws.com")
        nameField.putClientProperty("JTextField.placeholderText", "e.g. Invoice PROD")
        bastionCombo.toolTipText = "EC2 instance id of the bastion host (i-…). Pick one after \"Load from AWS\" or type it."
        dbCombo.toolTipText = "RDS instance or cluster. Picking one fills in host name and port."
        keepAlive.toolTipText = "If unchecked, a dropped session is only re-created when an application connects to the local port."

        profileCombo.addActionListener { updateRegionPlaceholder() }
        loadButton.addActionListener { loadResources() }
        dbCombo.addActionListener { (dbCombo.selectedItem as? DatabaseInfo)?.let(::applyDatabase) }
        localPortSpinner.addChangeListener { if (localPortSpinner.hasFocus() || localPortSpinner.editor.isFocusOwner) localPortTouched = true }

        fill()
        updateRegionPlaceholder()
        pack()
        setLocationRelativeTo(owner)
    }

    private fun section(text: String) = JPanel(MigLayout("insets 0, fillx", "[][grow,fill]")).apply {
        add(JLabel(text).apply { putClientProperty("FlatLaf.styleClass", "h4") })
        add(javax.swing.JSeparator(), "growx, gapleft 8")
    }

    private fun portSpinner(value: Int) = JSpinner(SpinnerNumberModel(value, 1, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }

    private fun fill() {
        val p = original
        if (p == null) {
            localPortSpinner.value = suggestLocalPort(5432)
            if (awsProfiles.isEmpty()) error("No AWS profiles found in your AWS config file.")
            return
        }
        nameField.text = p.name
        if (awsProfiles.none { it.name == p.awsProfile } && p.awsProfile.isNotBlank()) profileCombo.addItem(p.awsProfile)
        profileCombo.selectedItem = p.awsProfile
        regionField.text = p.region
        bastionCombo.selectedItem = p.bastionInstanceId
        dbCombo.selectedItem = p.rdsInstanceId
        hostField.text = p.dbHost
        dbPortSpinner.value = p.dbPort
        localPortSpinner.value = p.localPort
        autoConnect.isSelected = p.autoConnect
        keepAlive.isSelected = p.keepAlive
    }

    private fun updateRegionPlaceholder() {
        val region = awsProfiles.firstOrNull { it.name == profileCombo.selectedItem }?.region
        regionField.putClientProperty(
            "JTextField.placeholderText",
            if (region != null) "$region (from the AWS profile)" else "e.g. eu-central-1",
        )
        regionField.repaint()
    }

    private fun suggestLocalPort(preferred: Int): Int {
        val used = others.map { it.localPort }.toSet()
        var port = preferred
        while (port in used && port < 65535) port++
        return port
    }

    private fun applyDatabase(db: DatabaseInfo) {
        hostField.text = db.host
        dbPortSpinner.value = db.port
        if (nameField.text.isBlank()) nameField.text = db.id
        if (!localPortTouched) localPortSpinner.value = suggestLocalPort(db.port)
    }

    private fun comboText(combo: JComboBox<Any>): String =
        ((combo.editor.editorComponent as? JTextField)?.text ?: combo.selectedItem?.toString().orEmpty()).trim()

    private fun bastionId(): String {
        (bastionCombo.selectedItem as? BastionInfo)?.let { if (comboText(bastionCombo) == it.toString()) return it.id }
        val text = comboText(bastionCombo)
        return INSTANCE_ID.find(text)?.value ?: text
    }

    private fun rdsId(): String {
        (dbCombo.selectedItem as? DatabaseInfo)?.let { if (comboText(dbCombo) == it.toString()) return it.id }
        return comboText(dbCombo)
    }

    private fun loadResources() {
        val profile = profileCombo.selectedItem as? String
        if (profile.isNullOrBlank()) return error("Select an AWS profile first.")
        val region = regionField.text.trim()
        val currentBastion = bastionId()
        val currentDb = rdsId()
        loadButton.isEnabled = false
        status("Loading EC2 and RDS resources…")
        Thread.ofVirtual().name("aws-discovery").start {
            val outcome = runCatching {
                try {
                    AwsDiscovery.load(profile, region)
                } catch (e: AuthRequiredException) {
                    status("Sign-in required – complete it in your browser…")
                    val ok = loginGate.login(profile, true).get(6, TimeUnit.MINUTES)
                    if (!ok) throw e
                    status("Loading EC2 and RDS resources…")
                    AwsDiscovery.load(profile, region)
                }
            }
            SwingUtilities.invokeLater {
                loadButton.isEnabled = true
                outcome.onSuccess { showResources(it, currentBastion, currentDb) }
                    .onFailure { loadStatus.text = "Failed: ${it.message}"; loadStatus.toolTipText = it.message }
            }
        }
    }

    private fun showResources(res: AwsResources, currentBastion: String, currentDb: String) {
        bastionCombo.model = DefaultComboBoxModel<Any>(res.bastions.toTypedArray())
        bastionCombo.selectedItem = res.bastions.firstOrNull { it.id == currentBastion }
            ?: currentBastion.ifBlank { res.bastions.firstOrNull { it.name.contains("bastion", true) } ?: "" }
        dbCombo.model = DefaultComboBoxModel<Any>(res.databases.toTypedArray())
        dbCombo.selectedItem = res.databases.firstOrNull { it.id == currentDb } ?: currentDb
        val summary = "${res.bastions.size} running instances, ${res.databases.size} database endpoints"
        loadStatus.text = if (res.warnings.isEmpty()) summary else "$summary – ${res.warnings.size} warning(s)"
        loadStatus.toolTipText = res.warnings.joinToString("<br>", "<html>", "</html>").takeIf { res.warnings.isNotEmpty() }
    }

    private fun status(text: String) = SwingUtilities.invokeLater {
        loadStatus.text = text
        loadStatus.toolTipText = null
    }

    private fun error(text: String) {
        errorLabel.text = text
    }

    private fun onSave() {
        runCatching { dbPortSpinner.commitEdit(); localPortSpinner.commitEdit() }
        val name = nameField.text.trim()
        val awsProfile = (profileCombo.selectedItem as? String).orEmpty()
        val bastion = bastionId()
        val host = hostField.text.trim()
        val localPort = localPortSpinner.value as Int
        val region = regionField.text.trim()
        when {
            name.isEmpty() -> return error("Please enter a name.")
            awsProfile.isEmpty() -> return error("Please select an AWS profile.")
            region.isNotEmpty() && !REGION.matches(region) -> return error("The region does not look valid (e.g. eu-central-1).")
            !INSTANCE_ID.matches(bastion) -> return error("The bastion host must be an instance id such as i-0123456789abcdef0.")
            host.isEmpty() || host.any { it.isWhitespace() } || !HOST.matches(host) ->
                return error("Please enter the database host name (no spaces).")
            others.any { it.localPort == localPort } ->
                return error("Local port $localPort is already used by \"${others.first { it.localPort == localPort }.name}\".")
        }
        val base = original ?: ConnectionProfile()
        result = base.copy(
            name = name,
            awsProfile = awsProfile,
            region = region,
            rdsInstanceId = rdsId(),
            dbHost = host,
            dbPort = dbPortSpinner.value as Int,
            bastionInstanceId = bastion,
            localPort = localPort,
            autoConnect = autoConnect.isSelected,
            keepAlive = keepAlive.isSelected,
        )
        dispose()
    }

    companion object {
        private val INSTANCE_ID = Regex("m?i-[0-9a-f]{8,17}")
        private val REGION = Regex("[a-z]{2}(-[a-z]+)+-\\d")
        private val HOST = Regex("[A-Za-z0-9._:-]+")
    }
}
