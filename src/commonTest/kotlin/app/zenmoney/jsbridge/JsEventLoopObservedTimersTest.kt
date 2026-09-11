package app.zenmoney.jsbridge

import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsEventLoopObservedTimersTest {
    @Test
    fun failedAttachmentRestoresFunctionsAndKeepsTimersCreatedDuringAttachment() =
        runTest {
            JsEngineContext().use { context ->
                context.evalBrowser(FAKE_BROWSER_TIMERS)
                context.evalBrowser(
                    """
                    globalThis.calls = 0;
                    Object.defineProperty(globalThis, 'process', {
                        configurable: true,
                        get() {
                            globalThis.duringAttachment = setTimeout(() => calls++, 10);
                            throw new Error('attachment failed');
                        }
                    });
                    """.trimIndent(),
                )
                val eventLoop = JsEventLoop(coroutineContext)
                try {
                    assertFailsWith<JsException> { eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE) }
                    assertNull(context.core.eventLoop)
                    assertTrue(context.browserBoolean("__browser.originalsWereRestored()"))
                    context.evalBrowser("__browser.fire(duringAttachment)")
                    assertEquals(1, context.browserInt("calls"))
                } finally {
                    eventLoop.cancel()
                    eventLoop.run()
                }
            }
        }

    @Test
    fun functionsFrozenAfterAttachmentStillDelegateWhenObservationStops() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    Object.defineProperty(globalThis, 'setTimeout', { writable: false, configurable: false });
                    globalThis.calls = 0;
                    globalThis.timeout = setTimeout(() => calls++, 10);
                    """.trimIndent(),
                )
                eventLoop.cancel()
                withTimeout(1_000) { eventLoop.run() }
                context.evalBrowser(
                    "__browser.fire(timeout); globalThis.next = setTimeout(() => calls++, 10); __browser.fire(next)",
                )
                assertEquals(2, context.browserInt("calls"))
            }
        }

    @Test
    fun runWaitsForBrowserTimeoutAndItsNestedTimeout() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.calls = [];
                    globalThis.first = setTimeout(() => {
                        calls.push('first');
                        globalThis.second = setTimeout(() => calls.push('second'), 50);
                    }, 100);
                    """.trimIndent(),
                )
                assertEquals(101, context.browserInt("first"))
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                assertEquals("", context.browserString("calls.join(',')"))

                context.evalBrowser("__browser.fire(first)")
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                assertEquals("first", context.browserString("calls.join(',')"))

                context.evalBrowser("__browser.fire(second)")
                withTimeout(1_000) { running.await() }
                assertEquals("first,second", context.browserString("calls.join(',')"))
            }
        }

    @Test
    fun intervalKeepsRunPendingBetweenTicksUntilCrossCancellation() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser("globalThis.calls = 0; globalThis.interval = setInterval(() => calls++, 100)")
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                repeat(2) {
                    context.evalBrowser("__browser.fire(interval)")
                    testScheduler.runCurrent()
                    assertFalse(running.isCompleted)
                }

                context.evalBrowser("clearTimeout(interval)")
                withTimeout(1_000) { running.await() }
                assertEquals(2, context.browserInt("calls"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun clearFunctionsCoerceIdsOnceAndCanCancelEitherTimerKind() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.conversions = 0;
                    globalThis.timeout = setTimeout(() => {}, 100);
                    globalThis.interval = setInterval(() => {}, 100);
                    clearInterval({ valueOf() { conversions++; return timeout + 4294967296; } });
                    clearTimeout(String(interval));
                    globalThis.bigIntRejected = false;
                    try { clearTimeout(1n); } catch (error) { bigIntRejected = error instanceof TypeError; }
                    """.trimIndent(),
                )

                withTimeout(1_000) { eventLoop.run() }
                assertEquals(1, context.browserInt("conversions"))
                assertTrue(context.browserBoolean("bigIntRejected"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun browserCallbackReceivesOriginalThisAndArgumentsAndKeepsThrownError() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.expectedError = new Error('callback failed');
                    globalThis.argument = {};
                    globalThis.timeout = setTimeout(function (first, second) {
                        globalThis.callbackThisWasWindow = this === globalThis;
                        globalThis.callbackArgumentsWerePreserved = first === argument && second === 42;
                        throw expectedError;
                    }, 100, argument, 42);
                    """.trimIndent(),
                )
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                context.evalBrowser(
                    """
                    globalThis.sameErrorWasThrown = false;
                    try { __browser.fire(timeout); } catch (error) { sameErrorWasThrown = error === expectedError; }
                    """.trimIndent(),
                )
                withTimeout(1_000) { running.await() }
                assertTrue(context.browserBoolean("callbackThisWasWindow"))
                assertTrue(context.browserBoolean("callbackArgumentsWerePreserved"))
                assertTrue(context.browserBoolean("sameErrorWasThrown"))
            }
        }

    @Test
    fun timeoutCleanupDoesNotRemoveNewTimerWithReusedBrowserId() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.calls = 0;
                    globalThis.first = setTimeout(() => {
                        calls++;
                        clearTimeout(first);
                        __browser.reuseNextId(first);
                        globalThis.second = setTimeout(() => calls++, 100);
                    }, 100);
                    """.trimIndent(),
                )
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                context.evalBrowser("__browser.fire(first)")
                testScheduler.runCurrent()

                assertEquals(context.browserInt("first"), context.browserInt("second"))
                assertFalse(running.isCompleted)
                assertEquals(1, context.browserInt("calls"))
                context.evalBrowser("__browser.fire(second)")
                withTimeout(1_000) { running.await() }
                assertEquals(2, context.browserInt("calls"))
            }
        }

    @Test
    fun cancellingLoopPreservesBrowserTimersAndDisablesCapturedWrappers() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.calls = 0;
                    globalThis.capturedSetTimeout = setTimeout;
                    globalThis.pending = setTimeout(() => calls++, 100);
                    """.trimIndent(),
                )
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                eventLoop.cancel()
                withTimeout(1_000) { running.await() }
                assertTrue(context.browserBoolean("__browser.originalsWereRestored()"))
                assertEquals(1, context.browserInt("__browser.pendingCount"))

                context.evalBrowser(
                    """
                    __browser.fire(pending);
                    globalThis.afterCancel = capturedSetTimeout(() => calls++, 100);
                    __browser.fire(afterCancel);
                    """.trimIndent(),
                )
                assertEquals(2, context.browserInt("calls"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun cancellingLoopPreservesTimerWrapperInstalledLaterByPage() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.calls = 0;
                    globalThis.capturedSetTimeout = setTimeout;
                    globalThis.pageSetTimeout = function (...args) { return capturedSetTimeout(...args); };
                    globalThis.setTimeout = pageSetTimeout;
                    """.trimIndent(),
                )

                eventLoop.cancel()
                withTimeout(1_000) { eventLoop.run() }
                assertTrue(context.browserBoolean("setTimeout === pageSetTimeout"))
                context.evalBrowser("globalThis.timeout = setTimeout(() => calls++, 100); __browser.fire(timeout)")
                assertEquals(1, context.browserInt("calls"))
            }
        }

    @Test
    fun rejectedBrowserSchedulingDoesNotLeaveRunPending() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser(
                    """
                    globalThis.expectedError = new Error('invalid delay');
                    globalThis.sameErrorWasThrown = false;
                    try {
                        setTimeout(() => {}, { valueOf() { throw expectedError; } });
                    } catch (error) {
                        sameErrorWasThrown = error === expectedError;
                    }
                    """.trimIndent(),
                )

                withTimeout(1_000) { eventLoop.run() }
                assertTrue(context.browserBoolean("sameErrorWasThrown"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun disposalDuringDelayConversionDoesNotRetainTheScheduledCallback() =
        runTest {
            withObservedBrowserTimers(
                beforeAttachment =
                    """
                    $jsCaptureTimerMaps
                    globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT = {
                        addDisposeCallback(callback) {
                            globalThis.disposeObservedTimers = callback;
                            return () => {};
                        },
                    };
                    """.trimIndent(),
            ) { context, eventLoop ->
                context.evalBrowser("globalThis.Map = __originalMap")
                assertTrue(context.browserBoolean("__timerMaps.length > 0"))
                context.evalBrowser(
                    """
                    globalThis.calls = 0;
                    globalThis.capturedSetTimeout = setTimeout;
                    globalThis.timeout = capturedSetTimeout(() => calls++, {
                        valueOf() {
                            disposeObservedTimers();
                            return 100;
                        },
                    });
                    """.trimIndent(),
                )

                // Keep the captured wrapper reachable: its disposed registry must not retain
                // the callback created by the browser after delay conversion returned.
                assertEquals(0, context.browserInt("__timerMaps.reduce((total, timers) => total + timers.size, 0)"))
                assertTrue(context.browserBoolean("__browser.originalsWereRestored()"))
                assertEquals(1, context.browserInt("__browser.pendingCount"))
                withTimeout(1_000) { eventLoop.run() }

                context.evalBrowser("__browser.fire(timeout)")
                assertEquals(1, context.browserInt("calls"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun browserCallbackReturnedPromiseDoesNotKeepRunPending() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser("globalThis.timeout = setTimeout(() => new Promise(() => {}), 100)")
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                context.evalBrowser("__browser.fire(timeout)")
                withTimeout(1_000) { running.await() }
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun timersCreatedBeforeAttachmentCanStillFireAndBeCancelled() =
        runTest {
            withObservedBrowserTimers(
                beforeAttachment =
                    """
                    globalThis.calls = 0;
                    globalThis.oldTimeout = setTimeout(() => calls++, 100);
                    globalThis.oldInterval = setInterval(() => calls++, 100);
                    """.trimIndent(),
            ) { context, eventLoop ->
                // Existing browser callbacks cannot be discovered or wrapped retroactively.
                withTimeout(1_000) { eventLoop.run() }
                assertEquals(0, context.browserInt("calls"))
                assertEquals(2, context.browserInt("__browser.pendingCount"))

                context.evalBrowser("__browser.fire(oldTimeout); __browser.fire(oldInterval); clearTimeout(oldInterval)")
                withTimeout(1_000) { eventLoop.run() }
                assertEquals(2, context.browserInt("calls"))
                assertEquals(0, context.browserInt("__browser.pendingCount"))
            }
        }

    @Test
    fun closingContextReleasesRunWaitingForBrowserTimer() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser("setTimeout(() => {}, 100)")
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                context.close()
                withTimeout(1_000) { running.await() }
                assertTrue(context.isClosed)
            }
        }

    @Test
    fun stringTimerHandlersAreDelegatedWithoutKeepingRunPending() =
        runTest {
            withObservedBrowserTimers { context, eventLoop ->
                context.evalBrowser("globalThis.calls = 0; globalThis.timeout = setTimeout('calls++', 100)")

                withTimeout(1_000) { eventLoop.run() }
                assertEquals(0, context.browserInt("calls"))
                assertEquals(1, context.browserInt("__browser.pendingCount"))
                context.evalBrowser("__browser.fire(timeout)")
                assertEquals(1, context.browserInt("calls"))
            }
        }

    private suspend fun TestScope.withObservedBrowserTimers(
        beforeAttachment: String = "",
        block: suspend (JsEngineContext, JsEventLoop) -> Unit,
    ) {
        JsEngineContext().use { context ->
            context.evalBrowser(FAKE_BROWSER_TIMERS)
            if (beforeAttachment.isNotEmpty()) context.evalBrowser(beforeAttachment)
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                block(context, eventLoop)
            } finally {
                eventLoop.cancel()
                eventLoop.run()
            }
        }
    }
}

private fun JsContext.evalBrowser(script: String) {
    evaluateScript(script).close()
}

private fun JsContext.browserInt(script: String): Int = evaluateScript(script).use { it.int }

private fun JsContext.browserString(script: String): String = evaluateScript(script).use { it.string }

private fun JsContext.browserBoolean(script: String): Boolean = evaluateScript(script).use { it.boolean }

// The browser owns this queue. Tests choose when each browser callback runs, independently of
// the Kotlin dispatcher, so a pending timer never depends on wall-clock timing or virtual delays.
private val FAKE_BROWSER_TIMERS =
    """
    (() => {
        let nextId = 101;
        const pending = new Map();
        function schedule(callback, delay, args, repeat) {
            const handler = typeof callback === 'function' ? callback : String(callback);
            const normalizedDelay = (+delay) | 0;
            const id = nextId++;
            if (pending.has(id)) throw new Error('Browser timer ID is already active');
            pending.set(id, { handler, args, repeat, delay: normalizedDelay });
            return id;
        }
        globalThis.setTimeout = function (callback, delay, ...args) {
            return schedule(callback, delay, args, false);
        };
        globalThis.setInterval = function (callback, delay, ...args) {
            return schedule(callback, delay, args, true);
        };
        globalThis.clearTimeout = function (id) { pending.delete((+id) | 0); };
        globalThis.clearInterval = function (id) { pending.delete((+id) | 0); };
        const names = ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'];
        const originals = names.map(name => globalThis[name]);
        globalThis.__browser = {
            get pendingCount() { return pending.size; },
            reuseNextId(id) { nextId = id; },
            originalsWereRestored() { return names.every((name, index) => globalThis[name] === originals[index]); },
            fire(id) {
                const item = pending.get(id);
                if (item === undefined) return;
                try {
                    if (typeof item.handler === 'function') return item.handler.apply(globalThis, item.args);
                    return (0, eval)(item.handler);
                } finally {
                    if (!item.repeat && pending.get(id) === item) pending.delete(id);
                }
            },
        };
    })();
    """.trimIndent()
