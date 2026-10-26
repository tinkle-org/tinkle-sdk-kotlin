package com.tinkle.sdk.smoke

import com.tinkle.sdk.ClientConfigFfi
import com.tinkle.sdk.FfiCursorStore
import com.tinkle.sdk.FfiException
import com.tinkle.sdk.FfiSyncEvent
import com.tinkle.sdk.InMemoryFfiStore
import com.tinkle.sdk.TargetSub
import com.tinkle.sdk.TinkleClient
import com.tinkle.sdk.TinkleSdk
import com.tinkle.sdk.uniffiEnsureInitialized
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue

/** Lives in the target APK so R8 optimizes the actual SDK call sites. No UI. */
public object SmokeChecks {
    @JvmStatic
    public fun run(): Unit = runBlocking {
        withTimeout(20_000) {
            TinkleSdk.initialize()
            TinkleSdk.initialize()
            uniffiEnsureInitialized()
            // Exercises a real Rust constructor and release, not a JVM fake.
            InMemoryFfiStore().destroy()

            val journals = mapOf(
                ("user:42" to "target:room:7") to 37L,
                ("user:42" to "target:user:7") to 53L,
                ("user:43" to "target:room:7") to 73L,
                ("user:43" to "target:user:7") to 89L,
            )
            val loads = ConcurrentLinkedQueue<Pair<String, String>>()
            val foreignStore = object : FfiCursorStore {
                override suspend fun loadJournal(principal: String, targetKey: String): Long? {
                    delay(10) // Force the async foreign callback to suspend and resume.
                    loads.add(principal to targetKey)
                    if (targetKey == "target:room:8") throw FfiException.Storage("smoke load failure")
                    return journals[principal to targetKey]
                }
                override suspend fun saveJournal(principal: String, targetKey: String, acceptSeq: Long) = Unit
                override suspend fun removeTarget(principal: String, targetKey: String) = Unit
                override suspend fun clearPrincipal(principal: String) = Unit
            }
            LoopbackGateway().use { gateway ->
                // syntra-ws pins the Syntra endpoint path; a bare host:port URL is rejected.
                val client = TinkleClient(
                    ClientConfigFfi("ws://127.0.0.1:${gateway.port}/syntra/v1/ws", true, null, grpcEndpoint = null),
                    "local-smoke-fixture",
                    foreignStore,
                )
                try {
                    client.connect()
                    client.setPrincipalUserId(42)
                    val targets = listOf(TargetSub("room", 7L), TargetSub("user", 7L))
                    val response = client.subscribe(targets, emptyList())
                    check(response.targets.toSet() == setOf("target:room:7", "target:user:7"))
                    // Rust reads Kotlin's journal and sends it through the gateway;
                    // these checks cannot pass by invoking the callback in Kotlin.
                    check(gateway.requests.toList() == listOf("target:room:7" to 37L, "target:user:7" to 53L))
                    client.setPrincipalUserId(43)
                    check(client.subscribe(targets, emptyList()).targets.toSet() == response.targets.toSet())
                    check(loads.toList() == journals.keys.toList())
                    check(gateway.requests.toList() == listOf(
                        "target:room:7" to 37L, "target:user:7" to 53L,
                        "target:room:7" to 73L, "target:user:7" to 89L,
                    ))

                    // A foreign load error must survive Rust's callback adapter and
                    // surface as a per-target failure, before any request for that target.
                    client.subscribe(listOf(TargetSub("room", 8L)), emptyList())
                    var event: FfiSyncEvent
                    do {
                        event = checkNotNull(client.nextEvent())
                    } while (event !is FfiSyncEvent.ReconcileFailed)
                    check(event.targetKey == "target:room:8")
                    check(event.error.contains("smoke load failure"))
                    check(loads.contains("user:43" to "target:room:8"))
                    check(gateway.requests.none { it.first == "target:room:8" })
                } finally {
                    try {
                        client.shutdown()
                    } finally {
                        client.destroy()
                    }
                }
            }
        }
    }
}
