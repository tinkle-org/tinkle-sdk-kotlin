package com.tinkle.sdk.smoke

import com.tinkle.sdk.ClientConfigFfi
import com.tinkle.sdk.FfiCursorStore
import com.tinkle.sdk.InMemoryFfiStore
import com.tinkle.sdk.RoomSub
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

            val loads = ConcurrentLinkedQueue<Pair<String, String>>()
            val foreignStore = object : FfiCursorStore {
                override suspend fun load(principal: String, stream: String): ULong? {
                    delay(10) // Force the async foreign callback to suspend and resume.
                    loads.add(principal to stream)
                    return 37uL
                }
                override suspend fun save(principal: String, stream: String, cursor: ULong) = Unit
                override suspend fun removeStream(principal: String, stream: String) = Unit
                override suspend fun clearPrincipal(principal: String) = Unit
            }
            LoopbackGateway().use { gateway ->
                // syntra-ws pins the Syntra endpoint path; a bare host:port URL is rejected.
                val client = TinkleClient(
                    ClientConfigFfi("ws://127.0.0.1:${gateway.port}/syntra/v1/ws", true, null),
                    "local-smoke-fixture",
                    foreignStore,
                )
                try {
                    client.connect()
                    val response = client.subscribe(listOf(RoomSub(7L, 0uL)), emptyList())
                    check(response.stream == "user:42")
                    // The Rust client reads Kotlin's cursor then sends it back through
                    // the gateway; this cannot pass by invoking the callback in Kotlin.
                    check(response.afterCursor == 37uL)
                    check(loads.contains("user:42" to "user:42"))
                    check(loads.contains("user:42" to "room:7"))
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
