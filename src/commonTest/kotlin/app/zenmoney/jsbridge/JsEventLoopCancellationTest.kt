package app.zenmoney.jsbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsEventLoopCancellationTest {
    @Test
    fun nonPositiveIntervalsYieldToIndependentCancellation() =
        runTest {
            for (delay in listOf(0, -1)) {
                withEngineEventLoop { context, eventLoop ->
                    val firstTick = CompletableDeferred<Unit>()
                    var callCount = 0
                    var observedCallCount = 0
                    val canceller =
                        launch {
                            firstTick.await()
                            observedCallCount = callCount
                            eventLoop.cancel()
                        }
                    jsScoped(context) {
                        context.globalThis["onInterval"] =
                            JsFunction {
                                callCount++
                                firstTick.complete(Unit)
                                // Bound the regression: without yielding the independent canceller cannot run.
                                if (callCount == 10) {
                                    eval("clearInterval(intervalId)")
                                }
                                JsUndefined()
                            }
                        eval("globalThis.intervalId = setInterval(onInterval, $delay)")
                    }

                    eventLoop.runAndComplete()
                    canceller.join()

                    assertEquals(1, observedCallCount, "Interval delay: $delay")
                    assertEquals(1, callCount, "Interval delay: $delay")
                    assertFalse(eventLoop.coroutineContext[Job]!!.isActive)
                }
            }
        }

    @Test
    fun nonPositiveIntervalsHonorCancellationInsideTheirCallback() =
        runTest {
            for (delay in listOf(0, -1)) {
                withEngineEventLoop { context, eventLoop ->
                    var callCount = 0
                    lateinit var timerJob: Job
                    jsScoped(context) {
                        context.globalThis["onInterval"] =
                            JsFunction {
                                callCount++
                                if (callCount == 1) {
                                    timerJob.cancel()
                                }
                                // A missing cancellation checkpoint must fail without hanging the test runner.
                                if (callCount == 3) {
                                    eval("clearInterval(intervalId)")
                                }
                                JsUndefined()
                            }
                        eval("globalThis.intervalId = setInterval(onInterval, $delay)")
                    }
                    timerJob = eventLoop.coroutineContext[Job]!!.children.single()

                    eventLoop.runAndComplete()

                    assertTrue(timerJob.isCancelled)
                    assertEquals(1, callCount, "Interval delay: $delay")
                }
            }
        }

    @Test
    fun cancellingNeverSettlingAwaitRemovesItsEventLoopChild() =
        runTest {
            withEngineEventLoop { context, eventLoop ->
                val promise = jsScoped(context) { eval("new Promise(() => {})").escape() }
                val waiting =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        promise.awaitEscaped()
                    }
                assertTrue(eventLoop.coroutineContext[Job]!!.children.any())

                waiting.cancelAndJoin()
                testScheduler.runCurrent()

                assertTrue(eventLoop.coroutineContext[Job]!!.children.none())
                withTimeout(1_000) {
                    eventLoop.runAndComplete()
                }
            }
        }

    @Test
    fun cancellingOneAwaitPreservesTheOtherAwaitAndPromiseProducer() =
        runTest {
            withEngineEventLoop { context, eventLoop ->
                val finishProducer = CompletableDeferred<Unit>()
                var producerCompleted = false
                var producerCancelled = false
                val promise =
                    jsScoped(context) {
                        JsPromise {
                            try {
                                finishProducer.await()
                            } catch (e: CancellationException) {
                                producerCancelled = true
                                throw e
                            }
                            producerCompleted = true
                            JsNumber(42)
                        }.escape()
                    }
                val first = async(start = CoroutineStart.UNDISPATCHED) { promise.awaitEscaped() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { promise.awaitEscaped() }
                testScheduler.runCurrent()

                first.cancelAndJoin()
                testScheduler.runCurrent()

                assertFalse(producerCancelled)
                assertFalse(producerCompleted)
                assertTrue(second.isActive)
                finishProducer.complete(Unit)
                eventLoop.runAndComplete()

                assertEquals(42, second.await().use { it.int })
                assertTrue(producerCompleted)
                assertFalse(producerCancelled)
            }
        }

    private suspend fun TestScope.withEngineEventLoop(block: suspend (JsEngineContext, JsEventLoop) -> Unit) {
        JsEngineContext().use { context ->
            val eventLoop = JsEventLoop(coroutineContext).apply { attachTo(context) }
            try {
                block(context, eventLoop)
            } finally {
                eventLoop.cancel()
                eventLoop.run()
            }
        }
    }
}
