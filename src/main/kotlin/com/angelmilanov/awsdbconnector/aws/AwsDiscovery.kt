package com.angelmilanov.awsdbconnector.aws

import software.amazon.awssdk.services.ec2.model.Filter

data class BastionInfo(val id: String, val name: String) {
    override fun toString() = if (name.isBlank()) id else "$name  —  $id"
}

data class DatabaseInfo(val id: String, val host: String, val port: Int, val engine: String, val kind: String) {
    override fun toString() = "$id  ($engine, $kind)"
}

data class AwsResources(
    val bastions: List<BastionInfo>,
    val databases: List<DatabaseInfo>,
    val warnings: List<String>,
)

/** Looks up candidate bastion hosts and RDS endpoints so the user can pick them instead of typing. */
object AwsDiscovery {
    fun load(awsProfile: String, region: String): AwsResources {
        val warnings = mutableListOf<String>()
        AwsContext(awsProfile, region).use { ctx ->
            val bastions = attempt("EC2", warnings) {
                ctx.ec2.describeInstancesPaginator { r ->
                    r.filters(Filter.builder().name("instance-state-name").values("running").build())
                }.reservations().flatMap { it.instances() }.map { i ->
                    BastionInfo(i.instanceId(), i.tags().firstOrNull { it.key() == "Name" }?.value().orEmpty())
                }.sortedWith(
                    compareBy<BastionInfo> { !it.name.contains("bastion", true) && !it.name.contains("jump", true) }
                        .thenBy { it.name.lowercase() },
                )
            }
            val instances = attempt("RDS instances", warnings) {
                ctx.rds.describeDBInstancesPaginator().dbInstances().mapNotNull { db ->
                    val ep = db.endpoint() ?: return@mapNotNull null
                    DatabaseInfo(db.dbInstanceIdentifier(), ep.address(), ep.port(), db.engine().orEmpty(), "instance")
                }
            }
            val clusters = attempt("RDS clusters", warnings) {
                ctx.rds.describeDBClustersPaginator().dbClusters().flatMap { c ->
                    listOfNotNull(
                        c.endpoint()?.let { DatabaseInfo(c.dbClusterIdentifier(), it, c.port(), c.engine().orEmpty(), "cluster writer") },
                        c.readerEndpoint()?.let { DatabaseInfo(c.dbClusterIdentifier() + "-ro", it, c.port(), c.engine().orEmpty(), "cluster reader") },
                    )
                }
            }
            return AwsResources(bastions, (clusters + instances).sortedBy { it.id.lowercase() }, warnings)
        }
    }

    private fun <T> attempt(what: String, warnings: MutableList<String>, block: () -> List<T>): List<T> = try {
        block()
    } catch (e: Exception) {
        if (AwsErrors.isAuthError(e)) throw AuthRequiredException(AwsErrors.describe(e), e)
        warnings += "$what: ${AwsErrors.describe(e)}"
        emptyList()
    }
}
