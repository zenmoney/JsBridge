package app.zenmoney.jsbridge

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsContextRunBlockingTest {
    @Test
    fun processesAlreadyQueuedBridgeCallsInsideAMainThreadCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = mutableListOf<Int>()
        val completed = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
            val submitted = CountDownLatch(1)
            Thread {
                AndroidMainThread.dispatch { calls += 1 }
                AndroidMainThread.dispatch {
                    calls += 2
                    completed.complete(Unit)
                }
                submitted.countDown()
            }.start()
            assertTrue(submitted.await(1, TimeUnit.SECONDS))
            JsContext.runBlocking {
                withTimeout(1000) { completed.await() }
            }
        }
        // The original Handler messages must not execute the transferred calls a second time.
        instrumentation.runOnMainSync {}
        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun cancellationKeepsQueuedBridgeCallsAvailableToTheMainHandler() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            assertFailsWith<IllegalStateException> {
                JsContext.runBlocking {
                    val submitted = CountDownLatch(1)
                    Thread {
                        AndroidMainThread.dispatch { calls += 1 }
                        submitted.countDown()
                    }.start()
                    assertTrue(submitted.await(1, TimeUnit.SECONDS))
                    throw IllegalStateException("Abort the callback before its queued calls run")
                }
            }
        }
        instrumentation.runOnMainSync {}
        assertEquals(listOf(1), calls)
    }

    @Test
    fun cancellingAnInnerCallbackReturnsQueuedCallsToTheOuterCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = mutableListOf<Int>()
        val completed = CompletableDeferred<Unit>()
        instrumentation.runOnMainSync {
            JsContext.runBlocking {
                assertFailsWith<IllegalStateException> {
                    JsContext.runBlocking {
                        val submitted = CountDownLatch(1)
                        Thread {
                            AndroidMainThread.dispatch {
                                calls += 1
                                completed.complete(Unit)
                            }
                            submitted.countDown()
                        }.start()
                        assertTrue(submitted.await(1, TimeUnit.SECONDS))
                        throw IllegalStateException("Abort only the inner callback")
                    }
                }
                withTimeout(1000) { completed.await() }
            }
        }
        instrumentation.runOnMainSync {}
        assertEquals(listOf(1), calls)
    }

    @Test
    fun mainDispatcherResumesSuspendingCallsWhileNativeCallbackBlocksMain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val calls = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            JsContext.runBlocking {
                withTimeout(2_000) {
                    withContext(Dispatchers.Default) {
                        withContext(JsContext.mainDispatcher) {
                            assertTrue(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                            calls += 1
                            delay(10)
                            calls += 2
                        }
                    }
                }
            }
        }
        instrumentation.runOnMainSync {}
        assertEquals(listOf(1, 2), calls)
    }

    @Test
    fun supportsJsEngineContextFromMainThread() {
        var result: Int? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            JsContext().use { context ->
                result =
                    JsContext.runBlocking {
                        withContext(Dispatchers.Default) {}
                        context.evaluateScript("40 + 2").use { it.int }
                    }
            }
        }

        assertEquals(42, result)
    }
}
