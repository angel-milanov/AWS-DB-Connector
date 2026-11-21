package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.aws.AuthRequiredException
import com.angelmilanov.awsdbconnector.aws.AwsContext
import com.angelmilanov.awsdbconnector.aws.AwsDiscovery
import com.angelmilanov.awsdbconnector.aws.AwsErrors
import com.angelmilanov.awsdbconnector.aws.AwsProfileInfo
import com.angelmilanov.awsdbconnector.aws.AwsProfiles
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.awscore.exception.AwsServiceException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwsTest {
    @BeforeTest
    fun setup() = FakeAws.reset()

    // ------------------------------------------------------------------ profiles

    @Test
    fun `profiles are read from the AWS config file with what is needed to sign in`() {
        val profiles = AwsProfiles.load().associateBy { it.name }
        assertEquals(listOf("keys", "legacy", "loop", "nowhere", "role", "sso"), AwsProfiles.load().map { it.name })

        assertEquals(AwsProfileInfo("keys", "eu-central-1", null, null, null), profiles["keys"], "access keys: nothing to sign in to")
        assertEquals(AwsProfileInfo("sso", "eu-west-1", "session:corp", "corp", "sso"), profiles["sso"])
        assertEquals(
            AwsProfileInfo("legacy", null, "url:https://legacy.awsapps.com/start", "https://legacy.awsapps.com/start", "legacy"),
            profiles["legacy"],
        )
        assertEquals(
            AwsProfileInfo("role", "eu-west-1", "session:corp", "corp", "sso"), profiles["role"],
            "a role is signed in to through the profile it is based on, and takes its region from there",
        )
        assertEquals(AwsProfileInfo("loop", null, null, null, null), profiles["loop"])

        assertEquals("sso", AwsProfiles.find("role")?.loginProfile)
        assertNull(AwsProfiles.find("no-such-profile"))
    }

    @Test
    fun `a config file that cannot be read means no profiles`() {
        FakeAws.profiles("[profile broken")
        assertEquals(emptyList(), AwsProfiles.load())
    }

    @Test
    fun `the region comes from the connection first and from the profile otherwise`() {
        AwsContext("keys", "").use { assertEquals("eu-central-1", it.region.id()) }
        AwsContext("keys", " eu-north-1 ").use { assertEquals("eu-north-1", it.region.id()) }
        AwsContext("role", "").use { assertEquals("eu-west-1", it.region.id()) }
        AwsContext("nowhere", "us-east-1").use { assertEquals("us-east-1", it.region.id()) }

        assertTrue("No region configured" in assertFailsWith<IllegalStateException> { AwsContext("nowhere", "") }.message!!)
        assertTrue("was not found" in assertFailsWith<IllegalStateException> { AwsContext("no-such-profile", "") }.message!!)
    }

    @Test
    fun `clients are created when they are first used`() {
        val context = AwsContext("keys", "")
        assertEquals("ssm", context.ssm.serviceName())
        assertEquals("ec2", context.ec2.serviceName())
        assertEquals("rds", context.rds.serviceName())
        assertEquals("keys", context.profileName)
        context.close()
    }

    // ------------------------------------------------------------------ errors

    /** @param code null for an error the service gave no details about */
    private fun serviceError(code: String?, message: String?) = AwsServiceException.builder()
        .message("the message of the exception")
        .awsErrorDetails(code?.let { AwsErrorDetails.builder().errorCode(it).errorMessage(message).build() })
        .build()

    private class ExpiredTokenException : RuntimeException("with a message that says nothing")

    @Test
    fun `expired credentials are told apart from other failures`() {
        assertTrue(AwsErrors.isAuthError(serviceError("ExpiredToken", "The security token is no longer valid")))
        assertTrue(AwsErrors.isAuthError(ExpiredTokenException()), "recognised by the name of the exception")
        assertTrue(AwsErrors.isAuthError(RuntimeException("Unable to load SSO token")), "recognised by what the SDK says")
        assertTrue(AwsErrors.isAuthError(RuntimeException("outer", IllegalStateException("Token has expired and refresh failed"))))

        assertFalse(AwsErrors.isAuthError(serviceError("TargetNotConnected", "i-0123456789abcdef0 is not connected.")))
        assertFalse(AwsErrors.isAuthError(serviceError(null, null)))
        assertFalse(AwsErrors.isAuthError(RuntimeException()))
        assertFalse(AwsErrors.isAuthError(UnknownHostException("ssm.eu-central-1.amazonaws.com")))
    }

    @Test
    fun `errors are described in a line a user can read`() {
        assertEquals("AccessDenied: Not allowed", AwsErrors.describe(RuntimeException("wrapper", serviceError("AccessDenied", "Not allowed"))))
        assertEquals("the message of the exception", AwsErrors.describe(serviceError(null, null)))

        assertEquals(
            "No network connection (cannot resolve ssm.eu-central-1.amazonaws.com)",
            AwsErrors.describe(RuntimeException("Unable to execute HTTP request", UnknownHostException("ssm.eu-central-1.amazonaws.com"))),
        )
        assertEquals("Network problem: Connection refused", AwsErrors.describe(ConnectException("Connection refused")))
        assertEquals("Network problem: Unable to execute HTTP request", AwsErrors.describe(RuntimeException("Unable to execute HTTP request", SocketTimeoutException("Read timed out"))))

        assertEquals("First line", AwsErrors.describe(IllegalStateException("  First line  \nsecond line")))
        assertEquals("the cause", AwsErrors.describe(RuntimeException(null, IllegalStateException("the cause"))))
        assertEquals("IllegalStateException", AwsErrors.describe(IllegalStateException()))
        assertEquals(300, AwsErrors.describe(IllegalStateException("x".repeat(1000))).length)
    }

    // ------------------------------------------------------------------ discovery

    @Test
    fun `bastion hosts and database endpoints of the account are offered to pick from`() {
        FakeAws.replies["DescribeInstances"] = FakeAws.instances("i-0aaaaaaaaaaaaaaaa" to "web", "i-0bbbbbbbbbbbbbbbb" to "prod-bastion", "i-0cccccccccccccccc" to "", "i-0dddddddddddddddd" to "Jump host")
        FakeAws.replies["DescribeDBInstances"] = FakeAws.dbInstances("orders" to "orders.c1x2y3.eu-central-1.rds.amazonaws.com", "being-created" to "")
        FakeAws.replies["DescribeDBClusters"] = FakeAws.dbClusters(
            "shop" to ("shop.cluster-c1x2y3.eu-central-1.rds.amazonaws.com" to "shop.cluster-ro-c1x2y3.eu-central-1.rds.amazonaws.com"),
            "Analytics" to ("analytics.cluster-c1x2y3.eu-central-1.rds.amazonaws.com" to ""),
        )

        val resources = AwsDiscovery.load("keys", "")

        assertEquals(
            listOf("Jump host  —  i-0dddddddddddddddd", "prod-bastion  —  i-0bbbbbbbbbbbbbbbb", "i-0cccccccccccccccc", "web  —  i-0aaaaaaaaaaaaaaaa"),
            resources.bastions.map { it.toString() }, "what looks like a bastion host comes first",
        )
        assertEquals(
            listOf("Analytics  (aurora-mysql, cluster writer)", "orders  (postgres, instance)", "shop  (aurora-mysql, cluster writer)", "shop-ro  (aurora-mysql, cluster reader)"),
            resources.databases.map { it.toString() },
        )
        val reader = resources.databases.last()
        assertEquals("shop.cluster-ro-c1x2y3.eu-central-1.rds.amazonaws.com" to 3306, reader.host to reader.port)
        assertEquals("orders.c1x2y3.eu-central-1.rds.amazonaws.com" to 5432, resources.databases[1].host to resources.databases[1].port)
        assertEquals(emptyList(), resources.warnings)
        assertTrue("instance-state-name" in FakeAws.requests.first { it.first == "DescribeInstances" }.second, "only running instances are asked for")
    }

    @Test
    fun `what the profile may not list is left out with a warning`() {
        FakeAws.replies["DescribeInstances"] = FakeAws.ec2Error("UnauthorizedOperation", "You are not authorized to perform this operation.")
        FakeAws.replies["DescribeDBInstances"] = FakeAws.dbInstances("orders" to "orders.example")
        FakeAws.replies["DescribeDBClusters"] = FakeAws.queryError("AccessDenied", "Not allowed to describe clusters")

        val resources = AwsDiscovery.load("keys", "eu-west-1")

        assertEquals(emptyList(), resources.bastions)
        assertEquals(listOf("orders"), resources.databases.map { it.id })
        assertEquals(
            listOf("EC2: UnauthorizedOperation: You are not authorized to perform this operation.", "RDS clusters: AccessDenied: Not allowed to describe clusters"),
            resources.warnings,
        )
    }

    @Test
    fun `expired credentials end the discovery so that the user can sign in`() {
        FakeAws.replies["DescribeInstances"] = FakeAws.ec2Error("AuthFailure", "AWS was not able to validate the provided access credentials")
        val failure = assertFailsWith<AuthRequiredException> { AwsDiscovery.load("keys", "") }
        assertEquals("AuthFailure: AWS was not able to validate the provided access credentials", failure.message)

        // A profile of IAM Identity Center that nobody has signed in to: the SDK finds no token.
        assertFailsWith<AuthRequiredException> { AwsDiscovery.load("sso", "") }
    }
}
