package app.zenmoney.jsbridge

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsWebViewTimerObservationProtocolTest {
    @Test
    fun notificationFromFailedAttachmentDoesNotRegisterWorkAfterReattachment() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                webView.delayTimerNotifications = true
                webView.failTickExtraction = true
                val error =
                    assertFailsWith<IllegalStateException> {
                        eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                    }
                assertEquals("Attachment tick extraction failed", error.message)
                assertEquals(1, webView.delayedNotifications.size)

                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                webView.onMessage(webView.delayedNotifications.removeLast())
                testScheduler.runCurrent()

                // No run() snapshot can erase a phantom waiter and hide a stale attachment event.
                assertTrue(eventLoop.coroutineContext[Job]!!.children.none(), "A failed attachment must permanently disable its listener")
            }
        }

    @Test
    fun snapshotTracksDelayedRegistrationAndIgnoresNotificationsOlderThanCompletion() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                webView.delayTimerNotifications = true
                context.evaluateScript("globalThis.timer = setTimeout(() => {}, 100)").close()
                assertEquals(1, webView.delayedNotifications.size)

                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                // Only observer messages are delayed; microtask checkpoints can complete normally.
                // Without the synchronous snapshot, run() would already return here.
                assertFalse(running.isCompleted)
                assertEquals(1, webView.delayedNotifications.size)

                context.evaluateScript("__fireBrowserTimer(timer)").close()
                assertEquals(2, webView.delayedNotifications.size)
                webView.onMessage(webView.delayedNotifications.removeLast())
                withTimeout(1_000) { running.await() }

                // Deliver registration after callback-completion; callback-start sends no notification.
                // No active run() can repair stale state with another snapshot while this message is processed.
                webView.delayedNotifications.asReversed().forEach(webView.onMessage)
                webView.delayedNotifications.clear()
                testScheduler.runCurrent()
                assertTrue(eventLoop.coroutineContext[Job]!!.children.none(), "Stale notifications must not recreate a timer waiter")
            }
        }

    @Test
    fun intervalTicksAndOverlappingTimersNotifyOnlyWhenWorkBecomesPendingAndIdle() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                webView.delayTimerNotifications = true
                context
                    .evaluateScript(
                        """
                        globalThis.intervalCalls = 0;
                        globalThis.timeoutCalls = 0;
                        globalThis.interval = setInterval(() => {
                            intervalCalls++;
                            if (intervalCalls === 100) {
                                clearInterval(interval);
                                globalThis.last = setTimeout(() => timeoutCalls++, 100);
                            }
                        }, 100);
                        globalThis.overlap = setTimeout(() => {
                            timeoutCalls++;
                            globalThis.nested = setTimeout(() => timeoutCalls++, 100);
                        }, 100);
                        """.trimIndent(),
                    ).close()
                assertEquals(1, webView.delayedNotifications.size)
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                context
                    .evaluateScript(
                        """
                        for (let index = 0; index < 99; index++) __fireBrowserTimer(interval);
                        __fireBrowserTimer(overlap);
                        __fireBrowserTimer(nested);
                        """.trimIndent(),
                    ).close()
                assertEquals(
                    1,
                    webView.delayedNotifications.size,
                    "Repeated ticks and overlapping timers must not repeat the pending notification",
                )

                // The last interval tick clears itself and schedules a timeout. Its running callback
                // keeps the observer busy throughout, including between clearInterval and setTimeout.
                context.evaluateScript("__fireBrowserTimer(interval)").close()
                testScheduler.runCurrent()
                assertEquals(100, context.evaluateScript("intervalCalls").use { it.int })
                assertEquals(1, webView.delayedNotifications.size)
                assertFalse(running.isCompleted)

                context.evaluateScript("__fireBrowserTimer(last)").close()
                assertEquals(3, context.evaluateScript("timeoutCalls").use { it.int })
                assertEquals(2, webView.delayedNotifications.size, "Only the final callback should notify that work is idle")
                webView.delayedNotifications.forEach(webView.onMessage)
                webView.delayedNotifications.clear()
                withTimeout(1_000) { running.await() }
            }
        }

    @Test
    fun failedObserverDisposalDoesNotStrandTheLoopCompletionWaiter() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                context.evaluateScript("setTimeout(() => {}, 100)").close()
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)

                var completionCalled = false
                eventLoop.onCompletion = { _, _ -> completionCalled = true }
                webView.failNextEvaluation = true
                eventLoop.cancel()

                withTimeout(1_000) { running.await() }
                assertEquals(1, webView.failedEvaluations)
                assertTrue(completionCalled, "A failed disposal RPC must still publish loop completion")
            }
        }

    private suspend fun TestScope.withTimerWebView(
        attachOnEntry: Boolean = true,
        block: suspend (JsWebViewContext, JsEventLoop, TimerProtocolWebView) -> Unit,
    ) {
        JsEngineContext().use { backing ->
            val webView = TimerProtocolWebView(backing)
            jsScoped(backing) {
                eval(TIMER_PROTOCOL_BROWSER_SCRIPT)
                eval("globalThis.window = globalThis; globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {}")
                val native = eval(JS_WEB_VIEW_ANDROID_INTERFACE) as JsObject
                native["postMessage"] =
                    JsFunction { args ->
                        webView.receiveMessage(args[0].string)
                        JsUndefined()
                    }
            }
            val context = JsWebViewContext(webView)
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                if (attachOnEntry) eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                block(context, eventLoop, webView)
            } finally {
                webView.failNextEvaluation = false
                context.close()
                eventLoop.cancel()
                withTimeout(1_000) { eventLoop.run() }
            }
        }
    }
}

private class TimerProtocolWebView(
    private val backing: JsEngineContext,
) : JsWebView {
    override var onMessage: (String) -> Unit = {}
    var delayTimerNotifications = false
    val delayedNotifications = mutableListOf<String>()
    private var timerListenerId: String? = null
    var failTickExtraction = false
    var failNextEvaluation = false
    var failedEvaluations = 0
        private set

    fun receiveMessage(message: String) {
        val callbackId = CALLBACK_ID.find(message)?.groupValues?.get(1)
        if (delayTimerNotifications && callbackId != null) {
            if (timerListenerId == null) timerListenerId = callbackId
            if (callbackId == timerListenerId) {
                delayedNotifications += message
                return
            }
        }
        onMessage(message)
    }

    override fun evaluateJavaScript(script: String) {
        if (failTickExtraction && TICK_EXTRACTION.containsMatchIn(script)) {
            failTickExtraction = false
            // The attachment script has installed its wrappers, but native code has not
            // finished reading the returned functions. Queue a notification before failing that RPC.
            backing.evaluateScript("globalThis.failedAttachmentTimer = setTimeout(() => {}, 100)").close()
            error("Attachment tick extraction failed")
        }
        if (failNextEvaluation) {
            failNextEvaluation = false
            failedEvaluations++
            error("WebView no longer accepts the queued disposal command")
        }
        backing.evaluateScript(script).close()
    }

    override fun close() {}

    private companion object {
        val CALLBACK_ID = Regex("""^\["f",\d+,(\d+),""")
        val TICK_EXTRACTION = Regex("""dispatch\(\["g",\d+,"tick"\]""")
    }
}

private val TIMER_PROTOCOL_BROWSER_SCRIPT =
    """
    (() => {
        let nextId = 1;
        const pending = new Map();
        function schedule(callback, args, repeat) {
            const id = nextId++;
            pending.set(id, { callback, args, repeat });
            return id;
        }
        globalThis.setTimeout = function (callback, delay, ...args) { return schedule(callback, args, false); };
        globalThis.setInterval = function (callback, delay, ...args) { return schedule(callback, args, true); };
        globalThis.clearTimeout = globalThis.clearInterval = function (id) { pending.delete(id); };
        globalThis.__fireBrowserTimer = function (id) {
            const timer = pending.get(id);
            if (!timer) throw new Error('Unknown browser timer');
            if (!timer.repeat) pending.delete(id);
            timer.callback.apply(globalThis, timer.args);
        };
    })();
    """.trimIndent()
