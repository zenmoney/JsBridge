package app.zenmoney.jsbridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

abstract class JsWebViewContextBaseTest : JsContextTest() {
    protected open fun runBrowserTimerTest(block: suspend TestScope.() -> Unit) = runTest { block() }

    @Test
    fun reportsOriginalErrorWhenItsPropertiesThrow() {
        val exception =
            assertFailsWith<JsException> {
                context.evaluateScript(
                    """
                    throw Object.defineProperties(new Error('original failure'), {
                        name: { get() { throw new Error('name getter failed'); } },
                        detail: { enumerable: true, get() { throw new Error('detail getter failed'); } }
                    });
                    """.trimIndent(),
                )
            }

        assertEquals("original failure", exception.message)
        assertEquals("", exception.name)
        assertTrue(exception.data.isEmpty())
        assertEquals(42, context.evaluateScript("42").use { it.int })
    }

    @Test
    fun rejectsDetachedUint8ArrayDuringByteCopy() {
        val value =
            assertIs<JsUint8Array>(
                context.evaluateScript(
                    """
                    (() => {
                        const memory = new WebAssembly.Memory({ initial: 1, maximum: 2 });
                        const bytes = new Uint8Array(memory.buffer);
                        memory.grow(1);
                        return bytes;
                    })()
                    """.trimIndent(),
                ),
            )

        val exception = assertFailsWith<JsException> { value.toByteArray() }

        assertEquals("Cannot encode a detached Uint8Array", exception.message)
    }

    @Test
    fun runWaitsForChildLaunchedDuringMicrotaskCheckpoint() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            val runCall =
                async(start = CoroutineStart.UNDISPATCHED) {
                    eventLoop.run()
                }
            assertFalse(runCall.isCompleted)
            val childCanComplete = CompletableDeferred<Unit>()
            val child =
                eventLoop.launch {
                    childCanComplete.await()
                }

            testScheduler.runCurrent()

            assertFalse(runCall.isCompleted)
            childCanComplete.complete(Unit)
            child.join()
            runCall.await()
            eventLoop.runAndComplete()
        }

    @Test
    fun observedTimersPreserveExistingIntervalsAndTheirCancellation() =
        runBrowserTimerTest {
            context
                .evaluateScript(
                    """
                    globalThis.existingTimerCanFinish = false;
                    globalThis.existingTimerFinished = false;
                    globalThis.existingTimerTicksAfterAttachment = 0;
                    globalThis.cancelledTimerTicks = 0;
                    globalThis.existingTimerDone = new Promise(resolve => {
                        globalThis.existingTimer = setInterval(() => {
                            if (!existingTimerCanFinish) return;
                            if (++existingTimerTicksAfterAttachment < 3) return;
                            clearInterval(existingTimer);
                            existingTimerFinished = true;
                            resolve();
                        }, 10);
                    });
                    globalThis.cancelledTimer = setInterval(() => cancelledTimerTicks++, 10);
                    """.trimIndent(),
                ).close()
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        clearTimeout(cancelledTimer);
                        globalThis.ticksAtCancellation = cancelledTimerTicks;
                        existingTimerCanFinish = true;
                        """.trimIndent(),
                    ).close()
                val existingTimer =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        jsScoped(context) { eval("existingTimerDone").await() }
                    }

                async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }.awaitBrowserResult()
                existingTimer.awaitBrowserResult()

                jsScoped(context) {
                    assertTrue(eval("existingTimerFinished").boolean)
                    assertEquals(eval("ticksAtCancellation").int, eval("cancelledTimerTicks").int)
                }
            } finally {
                eventLoop.cancel()
                context.evaluateScript("clearInterval(existingTimer); clearInterval(cancelledTimer)").close()
            }
        }

    @Test
    fun runWaitsForObservedTimeoutAndTimeoutScheduledByItsCallback() =
        runBrowserTimerTest {
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        globalThis.timerEvents = [];
                        setTimeout(function (value) {
                            timerEvents.push(value + ':' + (this === globalThis));
                            setTimeout(() => timerEvents.push('second'), 10);
                        }, 10, 'first');
                        """.trimIndent(),
                    ).close()

                async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }.awaitBrowserResult()

                assertEquals("first:true,second", context.evaluateScript("timerEvents.join(',')").use { it.string })
            } finally {
                eventLoop.cancel()
            }
        }

    @Test
    fun runWaitsForObservedIntervalUntilItIsCleared() =
        runBrowserTimerTest {
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        globalThis.observedIntervalTicks = 0;
                        globalThis.observedInterval = setInterval(() => {
                            if (++observedIntervalTicks === 3) clearTimeout(observedInterval);
                        }, 10);
                        """.trimIndent(),
                    ).close()

                async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }.awaitBrowserResult()

                assertEquals(3, context.evaluateScript("observedIntervalTicks").use { it.int })
            } finally {
                eventLoop.cancel()
                context.evaluateScript("clearInterval(globalThis.observedInterval)").close()
            }
        }

    @Test
    fun runSynchronizesObservedTimerRegistrationsQueuedByBrowserMicrotasks() =
        runBrowserTimerTest {
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        globalThis.microtaskTimerFinished = false;
                        Promise.resolve().then(() => {
                            setTimeout(() => { microtaskTimerFinished = true; }, 20);
                        });
                        """.trimIndent(),
                    ).close()

                // Enter run before the test dispatcher delivers any browser registration callbacks.
                async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }.awaitBrowserResult()

                assertTrue(context.evaluateScript("microtaskTimerFinished").use { it.boolean })
            } finally {
                eventLoop.cancel()
            }
        }

    @Test
    fun cancellingObserverRestoresBrowserFunctionsAndPreservesPendingCallbacks() =
        runBrowserTimerTest {
            context
                .evaluateScript(
                    """
                    globalThis.originalTimerFunctions = [setTimeout, clearTimeout, setInterval, clearInterval];
                    """.trimIndent(),
                ).close()
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        globalThis.detachedTimerCanFinish = false;
                        globalThis.detachedTimerFinished = false;
                        globalThis.detachedTimer = setInterval(() => {
                            if (!detachedTimerCanFinish) return;
                            clearInterval(detachedTimer);
                            detachedTimerFinished = true;
                        }, 10);
                        """.trimIndent(),
                    ).close()

                eventLoop.cancel()
                async(start = CoroutineStart.UNDISPATCHED) { eventLoop.run() }.awaitBrowserResult()

                jsScoped(context) {
                    assertTrue(
                        eval(
                            "[setTimeout, clearTimeout, setInterval, clearInterval].every((fn, i) => fn === originalTimerFunctions[i])",
                        ).boolean,
                    )
                    eval("detachedTimerCanFinish = true")
                }
                async(start = CoroutineStart.UNDISPATCHED) {
                    while (!context.evaluateScript("detachedTimerFinished").use { it.boolean }) {
                        withContext(Dispatchers.Default) { delay(10) }
                    }
                }.awaitBrowserResult()
            } finally {
                eventLoop.cancel()
                context.evaluateScript("clearInterval(globalThis.detachedTimer)").close()
            }
        }

    @Test
    fun closesWebViewHandleAliasesIndependently() {
        jsScoped(context) {
            val arr = eval("[{value: 1}]") as JsArray
            val a1 = arr[0] as JsObject
            val a2 = arr[0] as JsObject

            assertEquals((a1 as JsWebViewObject).handle, (a2 as JsWebViewObject).handle)

            a1.close()

            assertTrue(a1.isClosed)
            assertFalse(a2.isClosed)
            assertEquals(JsNumber(context, 1), a2.getValue("value"))
        }
    }

    @Test
    fun closesCreatedWebViewValueAliasesIndependently() {
        jsScoped(context) {
            val a1 = eval("({value: 1})") as JsObject
            val a2 = JsValueAlias(a1)

            assertEquals((a1 as JsWebViewObject).handle, (a2 as JsWebViewObject).handle)

            a1.close()

            assertTrue(a1.isClosed)
            assertFalse(a2.isClosed)
            assertEquals(JsNumber(context, 1), a2.getValue("value"))
        }
    }

    @Test
    fun keepsTagsAfterNativeWrapperIsClosedWhileJsRetainsObject() {
        val nativeObject = Any()
        val a1 =
            assertIs<JsObject>(
                context.evaluateScript("globalThis.__taggedObject = {value: 1}; __taggedObject"),
            )
        val handle = (a1 as JsWebViewObject).handle
        a1.setTag("nativeObject", nativeObject)

        a1.close()
        val a2 = assertIs<JsObject>(context.evaluateScript("__taggedObject"))

        assertEquals(handle, (a2 as JsWebViewObject).handle)
        assertEquals(nativeObject, a2.getTag("nativeObject"))
    }
}

// Browser timers and bridge delivery use real host queues, independent of runTest's virtual clock.
private suspend fun <T> Deferred<T>.awaitBrowserResult(): T =
    withContext(Dispatchers.Default) {
        withTimeout(5_000) { await() }
    }
