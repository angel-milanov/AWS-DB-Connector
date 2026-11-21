package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.BastionInfo
import com.angelmilanov.awsdbconnector.aws.DatabaseInfo
import com.angelmilanov.awsdbconnector.aws.LoginGate
import com.angelmilanov.awsdbconnector.model.ConnectionProfile
import com.angelmilanov.awsdbconnector.model.ThemeMode
import com.angelmilanov.awsdbconnector.ui.ProfileDialog
import com.angelmilanov.awsdbconnector.ui.Theme
import java.awt.event.ActionEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.KeyStroke
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileDialogTest {
    private val logins = CopyOnWriteArrayList<String>()
    private var signInWorks: () -> Boolean = { false }
    private val gate = LoginGate { profile, _ ->
        logins += profile
        CompletableFuture.completedFuture(signInWorks())
    }
    private val orders = ConnectionProfile(
        name = "Orders", awsProfile = "keys", region = "eu-west-1", rdsInstanceId = "orders", dbHost = "orders.c1x2y3.eu-west-1.rds.amazonaws.com",
        dbPort = 5432, bastionInstanceId = "i-0123456789abcdef0", localPort = 5432, autoConnect = false, keepAlive = false,
    )
    private val billing = ConnectionProfile(name = "Billing", awsProfile = "keys", dbHost = "billing.example", bastionInstanceId = "i-0123456789abcdef0", localPort = 5433)

    /** The dialog as the user sees it: fields are found by their labels. */
    private class Form(val dialog: ProfileDialog) {
        val name get() = dialog.labelled("Name") as JTextField
        val profile get() = dialog.labelled("AWS profile") as JComboBox<*>
        val region get() = dialog.labelled("Region") as JTextField
        val bastion get() = dialog.labelled("Bastion host") as JComboBox<*>
        val database get() = dialog.labelled("RDS instance") as JComboBox<*>
        val host get() = dialog.labelled("Host name") as JTextField
        val databasePort get() = dialog.labelled("Database port") as JSpinner
        val localPort get() = dialog.labelled("Local port") as JSpinner
        val status get() = dialog.button("Load from AWS").parent.components[1] as JLabel

        fun shows(text: String) = dialog.all<JLabel>().any { it.text == text }
        fun type(combo: JComboBox<*>, text: String) {
            (combo.editor.editorComponent as JTextField).text = text
        }

        fun save(): ConnectionProfile? {
            dialog.click("Save")
            return dialog.result
        }
    }

    private fun open(original: ConnectionProfile?, vararg others: ConnectionProfile) = onEdt { Form(ProfileDialog(null, original, others.toList(), gate)) }

    private fun load(form: Form): String {
        onEdt { form.dialog.click("Load from AWS") }
        await("the resources to be loaded") { onEdt { form.dialog.button("Load from AWS").isEnabled } }
        return onEdt { form.status.text }
    }

    @BeforeTest
    fun setup() {
        FakeAws.reset()
        FakeAws.replies["DescribeInstances"] = FakeAws.instances("i-0aaaaaaaaaaaaaaaa" to "web", "i-0bbbbbbbbbbbbbbbb" to "prod-bastion")
        FakeAws.replies["DescribeDBInstances"] = FakeAws.dbInstances("orders" to "orders.c1x2y3.eu-central-1.rds.amazonaws.com")
        FakeAws.replies["DescribeDBClusters"] = FakeAws.dbClusters("shop" to ("shop.cluster-c1x2y3.eu-central-1.rds.amazonaws.com" to ""))
        onEdt { Theme.apply(ThemeMode.LIGHT) }
    }

    @Test
    fun `a new connection starts with the AWS profiles and a free local port`() {
        val form = open(null, orders, billing)
        onEdt {
            assertEquals("New connection", form.dialog.title)
            assertEquals(listOf("keys", "legacy", "loop", "nowhere", "role", "sso"), (0 until form.profile.itemCount).map { form.profile.getItemAt(it) })
            assertEquals(5434, form.localPort.value, "5432 and 5433 are taken by other connections")
            assertEquals(5432, form.databasePort.value)
            assertTrue(form.dialog.first<JCheckBox> { it.text.startsWith("Connect automatically") }.isSelected)

            assertEquals("eu-central-1 (from the AWS profile)", form.region.getClientProperty("JTextField.placeholderText"))
            form.profile.selectedItem = "legacy"
            assertEquals("e.g. eu-central-1", form.region.getClientProperty("JTextField.placeholderText"), "the profile names no region")
        }
    }

    @Test
    fun `an existing connection is shown as it was saved`() {
        val form = open(orders.copy(awsProfile = "removed-from-the-config"), billing)
        onEdt {
            assertEquals("Edit connection", form.dialog.title)
            assertEquals("Orders", form.name.text)
            assertEquals("removed-from-the-config", form.profile.selectedItem, "a profile that is gone is still shown")
            assertEquals("eu-west-1", form.region.text)
            assertEquals("i-0123456789abcdef0", form.bastion.selectedItem)
            assertEquals("orders", form.database.selectedItem)
            assertEquals("orders.c1x2y3.eu-west-1.rds.amazonaws.com", form.host.text)
            assertEquals(5432 to 5432, form.databasePort.value to form.localPort.value)
            assertFalse(form.dialog.first<JCheckBox> { it.text.startsWith("Keep the session alive") }.isSelected)

            assertEquals(orders.copy(awsProfile = "removed-from-the-config"), form.save(), "saving without a change changes nothing")
            assertFalse(form.dialog.isDisplayable)
        }
    }

    @Test
    fun `resources are loaded from AWS to pick from`() {
        val form = open(null, billing)

        assertEquals("2 running instances, 2 database endpoints", load(form))
        onEdt {
            assertEquals("prod-bastion  —  i-0bbbbbbbbbbbbbbbb", form.bastion.selectedItem.toString(), "what is named like a bastion host is preselected")
            assertNull(form.status.toolTipText)

            // Picking a database fills in the rest.
            form.database.selectedItem = (0 until form.database.itemCount).map { form.database.getItemAt(it) as DatabaseInfo }.first { it.id == "shop" }
            assertEquals("shop.cluster-c1x2y3.eu-central-1.rds.amazonaws.com", form.host.text)
            assertEquals(3306, form.databasePort.value)
            assertEquals(3306, form.localPort.value, "the port of the database, as it is free")
            assertEquals("shop", form.name.text)

            val saved = form.save()!!
            assertEquals(
                listOf("shop", "keys", "", "shop", "shop.cluster-c1x2y3.eu-central-1.rds.amazonaws.com", 3306, "i-0bbbbbbbbbbbbbbbb", 3306, true, true),
                listOf(saved.name, saved.awsProfile, saved.region, saved.rdsInstanceId, saved.dbHost, saved.dbPort, saved.bastionInstanceId, saved.localPort, saved.autoConnect, saved.keepAlive),
            )
        }
    }

    @Test
    fun `loading keeps what was chosen before and does not rename or move an existing connection`() {
        val form = open(orders.copy(bastionInstanceId = "i-0aaaaaaaaaaaaaaaa", region = ""), billing)

        load(form)
        onEdt {
            assertEquals(BastionInfo("i-0aaaaaaaaaaaaaaaa", "web"), form.bastion.selectedItem)
            assertEquals("orders", (form.database.selectedItem as DatabaseInfo).id)
            assertEquals("orders.c1x2y3.eu-central-1.rds.amazonaws.com", form.host.text, "the host name is brought up to date")
            assertEquals("Orders" to 5432, form.name.text to form.localPort.value)

            form.type(form.database, "something-else")
            form.type(form.bastion, "i-0cccccccccccccccc")
            val saved = form.save()!!
            assertEquals("something-else" to "i-0cccccccccccccccc", saved.rdsInstanceId to saved.bastionInstanceId)
            assertEquals(orders.id, saved.id)
        }
    }

    @Test
    fun `what cannot be listed is shown as a warning, and a failure as it is`() {
        FakeAws.replies["DescribeInstances"] = FakeAws.ec2Error("UnauthorizedOperation", "You are not authorized to perform this operation.")
        val form = open(null)

        assertEquals("0 running instances, 2 database endpoints – 1 warning(s)", load(form))
        assertEquals("<html>EC2: UnauthorizedOperation: You are not authorized to perform this operation.</html>", onEdt { form.status.toolTipText })

        onEdt { form.profile.selectedItem = "nowhere" }
        val failure = load(form)
        assertTrue(failure.startsWith("Failed: No region configured for AWS profile 'nowhere'"), failure)
        assertEquals(failure.removePrefix("Failed: "), onEdt { form.status.toolTipText })
    }

    @Test
    fun `expired credentials are renewed before the resources are loaded`() {
        val working = FakeAws.replies.getValue("DescribeInstances")
        FakeAws.replies["DescribeInstances"] = FakeAws.ec2Error("AuthFailure", "AWS was not able to validate the provided access credentials")
        val form = open(null)

        assertEquals("Failed: AuthFailure: AWS was not able to validate the provided access credentials", load(form), "the sign-in did not work")
        assertEquals(listOf("keys"), logins)

        signInWorks = {
            FakeAws.replies["DescribeInstances"] = working
            true
        }
        assertEquals("2 running instances, 2 database endpoints", load(form))
    }

    @Test
    fun `a connection is only saved when it is complete`() {
        val form = open(null, orders)
        onEdt {
            assertNull(form.save())
            assertTrue(form.shows("Please enter a name."))
            form.name.text = " Reports "

            form.region.text = "frankfurt"
            assertNull(form.save())
            assertTrue(form.shows("The region does not look valid (e.g. eu-central-1)."))
            form.region.text = " eu-central-1 "

            assertNull(form.save())
            assertTrue(form.shows("The bastion host must be an instance id such as i-0123456789abcdef0."))
            form.type(form.bastion, "prod-bastion  —  i-0bbbbbbbbbbbbbbbb")

            for (host in listOf("", "reports example", "reports/example")) {
                form.host.text = host
                assertNull(form.save())
                assertTrue(form.shows("Please enter the database host name (no spaces)."), host)
            }
            form.host.text = " reports.example "

            form.localPort.value = 5432
            assertNull(form.save())
            assertTrue(form.shows("Local port 5432 is already used by \"Orders\"."))
            form.localPort.value = 6000
            form.databasePort.value = 3306
            form.dialog.first<JCheckBox> { it.text.startsWith("Connect automatically") }.isSelected = false

            val saved = form.save()!!
            assertEquals(
                ConnectionProfile(
                    id = saved.id, name = "Reports", awsProfile = "keys", region = "eu-central-1", rdsInstanceId = "", dbHost = "reports.example",
                    dbPort = 3306, bastionInstanceId = "i-0bbbbbbbbbbbbbbbb", localPort = 6000, autoConnect = false, keepAlive = true,
                ),
                saved,
            )
            assertFalse(form.dialog.isDisplayable)
        }
    }

    @Test
    fun `without AWS profiles there is nothing to connect with`() {
        FakeAws.profiles("")
        val form = open(null)
        onEdt {
            assertTrue(form.shows("No AWS profiles found in your AWS config file."))
            form.dialog.click("Load from AWS")
            assertTrue(form.shows("Select an AWS profile first."))
            form.name.text = "Reports"
            assertNull(form.save())
            assertTrue(form.shows("Please select an AWS profile."))
        }
    }

    @Test
    fun `cancel and escape close the dialog without saving`() {
        val cancelled = open(orders)
        val escaped = open(orders)
        onEdt {
            cancelled.name.text = "Changed"
            cancelled.dialog.click("Cancel")
            assertNull(cancelled.dialog.result)
            assertFalse(cancelled.dialog.isDisplayable)

            val escape = escaped.dialog.rootPane.getActionForKeyStroke(KeyStroke.getKeyStroke("ESCAPE"))
            escape.actionPerformed(ActionEvent(escaped.dialog, ActionEvent.ACTION_PERFORMED, "escape"))
            assertNull(escaped.dialog.result)
            assertFalse(escaped.dialog.isDisplayable)
        }
    }
}
