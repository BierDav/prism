package at.quickme.prism.shared

import at.quickme.prism.rocky.ApiRequest
import at.quickme.prism.rocky.Replica
import at.quickme.prism.rocky.openProvisionedReplica
import at.quickme.prism.rocky.openReplica
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * This device's replica of Prism's Rocky database (rocky-prism, through
 * the UniFFI bindings in `at.quickme.prism.rocky`), with the placement calls
 * [PrismClient] makes to the server and the same objects (`Api.kt`): the
 * bindings carry the HTTP API's JSON bodies, decoded here.
 *
 * Every edit returns the placement view after it, as the server does.
 * A failure is a [ReplicaException] (`NotFound`, `InvalidInput`, `Conflict`,
 * `Storage`, `Closed`, `Json`).
 *
 * [clusterMachine] is the T3 host whose models count as the cluster's in
 * the view; empty: the one the workers name (their `cluster_machine`, in
 * the replica's settings).
 */
class PrismReplica private constructor(private val replica: Replica, private val clusterMachine: String) {
    companion object {
        /**
         * Opens (or creates, seeded with the default profiles) the replica in
         * [directory]. With [dial] it syncs by dialing the peers Rocky knows
         * (the workers' `wss://prism.quickme.at/rocky/<pod>`); otherwise it
         * stays on this device.
         */
        @Throws(Exception::class)
        suspend fun open(directory: String, dial: Boolean = false, clusterMachine: String = ""): PrismReplica =
            PrismReplica(openReplica(directory, dial), clusterMachine)

        /**
         * Opens this device's enrolled replica in [directory]: installed from
         * [provisioning] (`prism-device-v1:…`, the `rocky_provisioning`
         * setting) on the first open, reused afterwards. [peers] are the
         * workers' contacts (`rocky_peers`, one per line); it syncs with them
         * on its own.
         */
        @Throws(Exception::class)
        suspend fun openProvisioned(
            directory: String,
            provisioning: String,
            peers: List<String>,
            clusterMachine: String = "",
        ): PrismReplica = PrismReplica(openProvisionedReplica(directory, provisioning, peers), clusterMachine)

        /** The workers' contacts in a `rocky_peers` value: one per line or comma. */
        fun peers(setting: String): List<String> =
            setting.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The Prism API (docs/prism-api.md) answered by this replica: the
     * [PrismClient] the apps used for the server, for [device] (the name the
     * workers know this device by, whose key checks its approval
     * signatures). Reads come from the replica; each action is written for
     * the leading worker, and answers once its outcome synced back, or with
     * `202` after [waitSeconds] while no worker is reachable (the action
     * stays queued and runs when one is).
     *
     * With [prismUrl] (the workers' public URL), an action this device can't
     * hand to the workers, because that URL doesn't answer, runs on this
     * device instead: in T3 over NetBird, with the T3 sessions each T3 host
     * registered for it (worker/src/direct.rs). Empty: always the workers.
     */
    fun client(device: String, waitSeconds: Int = 20, prismUrl: String = ""): PrismClient =
        PrismClient(ORIGIN, { device }, transport(device, waitSeconds, prismUrl))

    /** The [PrismTransport] behind [client]. */
    fun transport(device: String, waitSeconds: Int = 20, prismUrl: String = ""): PrismTransport = PrismTransport { request ->
        val answer = replica.call(
            ApiRequest(
                method = request.method,
                path = request.path,
                body = request.body,
                device = device,
                clusterMachine = clusterMachine,
                waitSeconds = waitSeconds.coerceAtLeast(0).toUInt(),
                prismUrl = prismUrl,
            )
        )
        PrismResponse(answer.status, answer.body)
    }

    private val json get() = PrismClient.json
    private val withDefaults = Json(PrismClient.json) { encodeDefaults = true }

    /** Closes the database and its network; later calls fail with `Closed`. */
    @Throws(Exception::class)
    suspend fun close() {
        try {
            replica.shutdown()
        } finally {
            replica.destroy()
        }
    }

    @Throws(Exception::class)
    suspend fun placement(): Placement = json.decodeFromString(Placement.serializer(), replica.placement(clusterMachine))

    /** Creates or replaces a profile; entries are stored in order. */
    @Throws(Exception::class)
    suspend fun savePlacementProfile(id: String, label: String, entries: List<PlacementEntry>): Placement {
        val body = buildJsonObject {
            put("label", label)
            put("entries", JsonArray(entries.map { e ->
                buildJsonObject {
                    put("instanceId", e.instanceId)
                    put("model", e.model)
                    put("reasoning", e.reasoning)
                    put("enabled", e.enabled)
                    e.sessionShareOfWeekly?.let { put("sessionShareOfWeekly", it) }
                }
            }))
        }
        replica.putPlacementProfile(id, body.toString())
        return placement()
    }

    /** Removes a profile; fails with `Conflict` for the alert-fix profile. */
    @Throws(Exception::class)
    suspend fun deletePlacementProfile(id: String): Placement {
        replica.deletePlacementProfile(id)
        return placement()
    }

    @Throws(Exception::class)
    suspend fun setPlacementOrder(ids: List<String>): Placement {
        replica.orderPlacementProfiles(ids)
        return placement()
    }

    @Throws(Exception::class)
    suspend fun setAlertFixProfile(profile: String): Placement {
        replica.setAlertFixProfile(profile)
        return placement()
    }

    /** As [PrismClient.setPlacementActions]: null leaves a setting alone, `""` clears it. */
    @Throws(Exception::class)
    suspend fun setPlacementActions(
        alertFix: String? = null,
        textGeneration: String? = null,
        roleProfiles: Map<String, String>? = null,
    ): Placement {
        val body = buildJsonObject {
            if (alertFix != null) put("alertFix", alertFix)
            if (textGeneration != null) put("textGeneration", textGeneration)
            if (roleProfiles != null) {
                put("roleProfiles", buildJsonObject {
                    for ((role, profile) in roleProfiles) put(role, profile)
                })
            }
        }
        replica.setPlacementActions(body.toString())
        return placement()
    }

    @Throws(Exception::class)
    suspend fun setPlacementPolicy(policy: PlacementPolicy): Placement {
        // Every field: rocky-prism's PlacementPolicy has no defaults.
        replica.setPlacementPolicy(withDefaults.encodeToString(PlacementPolicy.serializer(), policy))
        return placement()
    }

    /** As [PrismClient.setAccountLimit]: [account]'s temporary limit. Fails with [PrismError]. */
    @Throws(Exception::class)
    suspend fun setAccountLimit(account: String, limit: PlacementAccountLimit): Placement =
        placementCall("PUT", account, json.encodeToString(PlacementAccountLimit.serializer(), limit))

    /** Removes [account]'s temporary limit. Fails with [PrismError]. */
    @Throws(Exception::class)
    suspend fun clearAccountLimit(account: String): Placement = placementCall("DELETE", account, null)

    /** An account-limit route of the replica's API (it has no binding of its own). */
    private suspend fun placementCall(method: String, account: String, body: String?): Placement {
        val answer = replica.call(
            ApiRequest(
                method = method,
                path = "/v1/placement/account-limits/${T3Links.encode(account)}",
                body = body,
                device = "",
                clusterMachine = clusterMachine,
                waitSeconds = 0u,
                prismUrl = "",
            )
        )
        if (answer.status / 100 != 2) {
            val error = try {
                json.parseToJsonElement(answer.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            } catch (e: Exception) {
                null
            }
            throw PrismError(answer.status, "", error ?: "The replica answered HTTP ${answer.status}.")
        }
        return json.decodeFromString(Placement.serializer(), answer.body)
    }

    /**
     * The tables each committed change touched, local writes and synced ones
     * alike, for as long as it is collected ([tables] null: every table).
     */
    fun changes(tables: List<String>? = null): Flow<List<String>> = flow {
        val changes = replica.changes(tables)
        try {
            while (true) emit(changes.next())
        } finally {
            changes.destroy()
        }
    }

    /** [changes] for Swift: each change's tables, comma-separated. */
    fun updates(tables: List<String>?): Watchable<String> = Watchable(changes(tables).map { it.joinToString(",") })

    /** The placement view now and after every change to it; for Swift, a [Watchable]. */
    fun placementUpdates(): Watchable<Placement> = Watchable(
        flow {
            changes(PLACEMENT_TABLES).onStart { emit(emptyList()) }.collect { emit(placement()) }
        }
    )
}

/** What [PrismReplica.client]'s requests name as their origin: no server. */
private const val ORIGIN = "rocky://replica"

/** The tables the placement view reads (rocky-prism's schema). */
private val PLACEMENT_TABLES = listOf("profiles", "profile_entries", "actions", "policy", "hosts", "threads", "instructions", "move_acks", "samples")
