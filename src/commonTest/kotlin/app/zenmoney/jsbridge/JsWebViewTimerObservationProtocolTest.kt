package app.zenmoney.jsbridge

import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.KEEP
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.OBSERVE
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.REPLACE
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.INSTALL
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.SKIP
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
    fun aLostWebViewDuringTickDoesNotCancelOtherContextsOrTheirTimers() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                JsEngineContext().use { plugin ->
                    eventLoop.attachTo(plugin)
                    plugin.evaluateScript("globalThis.timerFinished = false; setTimeout(() => { timerFinished = true; }, 1)").close()
                    webView.failNextRequestAsDetached = true

                    withTimeout(1_000) { eventLoop.run() }

                    assertTrue(context.isClosed)
                    assertFalse(plugin.isClosed)
                    assertTrue(plugin.evaluateScript("timerFinished").use { it.boolean })
                    assertTrue(eventLoop.coroutineContext[Job]!!.isActive)
                }
            }
        }

    @Test
    fun notificationFromFailedAttachmentDoesNotRegisterWorkAfterReattachment() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                webView.delayTimerNotifications = true
                webView.failTickExtraction = true
                val error =
                    assertFailsWith<IllegalStateException> {
                        eventLoop.attachTo(
                            context,
                            policies =
                                JsEventLoopPolicies(
                                    timers = JsEventLoopPolicy(OBSERVE, SKIP),
                                    immediate = JsEventLoopPolicy(KEEP, SKIP),
                                    nextTick = JsEventLoopPolicy(KEEP, SKIP),
                                ),
                        )
                    }
                assertEquals("Attachment tick extraction failed", error.message)
                assertEquals(1, webView.delayedNotifications.size)

                eventLoop.attachTo(
                    context,
                    policies =
                        JsEventLoopPolicies(
                            timers = JsEventLoopPolicy(OBSERVE, SKIP),
                            immediate = JsEventLoopPolicy(KEEP, SKIP),
                            nextTick = JsEventLoopPolicy(KEEP, SKIP),
                        ),
                )
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

    @Test
    fun unobservedAttachmentsNeedOnlyTwoTicksAndOneCheckpointWithoutExportedPromises() =
        runTest {
            for (policy in listOf(JsEventLoopPolicy(KEEP, SKIP), JsEventLoopPolicy(REPLACE, INSTALL))) {
                withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                    eventLoop.attachTo(context, policies = JsEventLoopPolicies(timers = policy, immediate = policy, nextTick = policy))
                    webView.resetTraffic()

                    withTimeout(1_000) { eventLoop.runAndComplete() }

                    assertEquals(3, webView.functionCalls, "Idle run needs two ticks and a checkpoint, with no disposal RPC: $policy")
                    assertEquals(
                        0,
                        webView.functionResults.count { it.startsWith("[\"h\",") },
                        "Checkpoints must not export a Promise handle",
                    )
                    assertEquals(emptyList(), webView.nativeEvents)
                }
            }
        }

    @Test
    fun unavailableObserversDoNotAddDisposalRpc() =
        runTest {
            for (removeTimers in listOf(false, true)) {
                for (ifMissing in listOf(SKIP, INSTALL)) {
                    withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                        context
                            .evaluateScript(
                                """
                                delete globalThis.setImmediate;
                                delete globalThis.clearImmediate;
                                delete globalThis.process;
                                if ($removeTimers) {
                                    for (const name of ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval']) {
                                        delete globalThis[name];
                                    }
                                }
                                """.trimIndent(),
                            ).close()
                        val policy = JsEventLoopPolicy(OBSERVE, ifMissing)
                        webView.resetTraffic()
                        eventLoop.attachTo(
                            context,
                            policies =
                                JsEventLoopPolicies(
                                    timers = if (removeTimers) policy else JsEventLoopPolicy(KEEP, SKIP),
                                    immediate = policy,
                                    nextTick = policy,
                                ),
                        )
                        assertEquals(
                            listOf("disposeAttachment", "tick", "runMicrotaskCheckpoint"),
                            webView.propertyReads,
                            "Resolved policies must not require a separate availability RPC",
                        )
                        webView.resetTraffic()

                        withTimeout(1_000) { eventLoop.runAndComplete() }

                        assertEquals(3, webView.functionCalls, "No observer was installed: $policy, removeTimers=$removeTimers")
                        assertEquals(emptyList(), webView.nativeEvents)
                        assertEquals(0, webView.functionResults.count { it.startsWith("[\"h\",") })
                    }
                }
            }
        }

    @Test
    fun initialTickConsumesTheAttachmentWakeupWithoutPollingObservedWorkAgain() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                webView.delayTimerNotifications = true
                context.evaluateScript("globalThis.timer = setTimeout(() => {}, 100)").close()
                webView.resetTraffic()
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()

                assertFalse(running.isCompleted)
                assertEquals(1, webView.functionCalls, "The first tick already captured all work; await the observer without another RPC")

                context.evaluateScript("__fireBrowserTimer(timer)").close()
                webView.delayedNotifications.forEach(webView.onMessage)
                webView.delayedNotifications.clear()
                withTimeout(1_000) { running.await() }
            }
        }

    @Test
    fun queuedWakeupsAreCoalescedAndDoNotAwaitNativeReplies() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                eventLoop.attachTo(context)
                webView.delayCallbackReplies = true
                webView.resetTraffic()
                repeat(2) { iteration ->
                    context
                        .evaluateScript(
                            """
                            globalThis.calls = 0;
                            for (let i = 0; i < 100; i++) {
                                process.nextTick(() => {
                                    calls++;
                                    setImmediate(() => { calls++; process.nextTick(() => calls++); });
                                });
                            }
                            """.trimIndent(),
                        ).close()
                    assertEquals(iteration + 1, webView.nativeEvents.count { it == "queued" })
                    withTimeout(1_000) { eventLoop.run() }
                    assertEquals(300, context.evaluateScript("calls").use { it.int })
                    assertEquals(
                        iteration + 1,
                        webView.nativeEvents.count { it == "queued" },
                        "Callbacks queued inside tick need no notification",
                    )
                    assertTrue(
                        webView.functionCalls <= 105 * (iteration + 1),
                        "RPCs are bounded by callback ticks, the checkpoint and settling the one native wakeup job",
                    )
                    assertTrue(webView.delayedCallbackReplies.isNotEmpty(), "Native callback Promises are still unresolved")
                }
                webView.delayCallbackReplies = false
                webView.flushCallbackReplies()
            }
        }

    @Test
    fun microtaskChainsDoNotWakeAnAlreadyDrainingQueue() =
        runTest {
            for (scheduler in listOf("setImmediate", "process.nextTick")) {
                withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                    eventLoop.attachTo(context)
                    webView.delayCallbackReplies = true
                    repeat(2) {
                        webView.resetTraffic()
                        context
                            .evaluateScript(
                                """
                                globalThis.calls = 0;
                                function work() {
                                    if (++calls < 100) Promise.resolve().then(() => $scheduler(work));
                                }
                                $scheduler(work);
                                """.trimIndent(),
                            ).close()

                        withTimeout(1_000) { eventLoop.run() }

                        assertEquals(100, context.evaluateScript("calls").use { it.int }, scheduler)
                        assertEquals(listOf("queued"), webView.nativeEvents, "Only the initial registration needs a wakeup: $scheduler")
                        assertTrue(
                            webView.functionCalls <= 104,
                            "100 callbacks need at most 104 RPCs, including the initial wakeup job and checkpoint: $scheduler",
                        )
                        assertTrue(webView.delayedCallbackReplies.isNotEmpty(), "Queue progress must not await native replies")
                    }
                    webView.delayCallbackReplies = false
                    webView.flushCallbackReplies()
                }
            }
        }

    @Test
    fun nativeCallbackRepliesDoNotRequestRendererAcknowledgements() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                eventLoop.attachTo(context)
                context.globalThis["nativeCall"] =
                    JsFunction(context) { args ->
                        if (args[0].boolean) error("native failure")
                        JsNumber(42)
                    }
                webView.resetTraffic()
                context
                    .evaluateScript(
                        """
                        nativeCall(false).then(value => { globalThis.nativeResult = value; });
                        nativeCall(true).catch(error => { globalThis.nativeError = error.message; });
                        """.trimIndent(),
                    ).close()

                withTimeout(1_000) { eventLoop.run() }

                assertEquals(42, context.evaluateScript("nativeResult").use { it.int })
                assertEquals("native failure", context.evaluateScript("nativeError").use { it.string })
                assertTrue(webView.callbackReplies >= 2, "Both success and failure must reach JavaScript")
                assertEquals(
                    0,
                    webView.callbackReplyAcknowledgements,
                    "Native callback replies have no request awaiting an acknowledgement",
                )
            }
        }

    @Test
    fun nativeTimerIdsAndCancellationDoNotDependOnAcknowledgements() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                eventLoop.attachTo(context)
                webView.delayCallbackReplies = true
                webView.resetTraffic()
                context
                    .evaluateScript(
                        """
                        globalThis.calls = 0;
                        globalThis.timer = setTimeout(() => calls++, 1);
                        globalThis.cancelled = setInterval(() => calls++, 1);
                        globalThis.idsAreNumbers = typeof timer === 'number' && typeof cancelled === 'number';
                        clearTimeout(cancelled);
                        for (let i = 0; i < 100; i++) { clearInterval(cancelled); clearTimeout(-1); }
                        """.trimIndent(),
                    ).close()
                assertTrue(context.evaluateScript("idsAreNumbers").use { it.boolean })
                withTimeout(1_000) { eventLoop.run() }
                assertEquals(1, context.evaluateScript("calls").use { it.int })
                context.evaluateScript("clearTimeout(timer)").close()
                assertEquals(2, webView.nativeEvents.count { it == "schedule" })
                assertEquals(1, webView.nativeEvents.count { it == "cancel" }, "Only cancelling a live native timer needs a native call")
                assertTrue(webView.delayedCallbackReplies.isNotEmpty())
                webView.delayCallbackReplies = false
                webView.flushCallbackReplies()
            }
        }

    @Test
    fun rejectedNativeTimerPromisesReleaseRegistrationsAndAreHandled() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                context.evaluateScript(jsCaptureTimerMaps).close()
                eventLoop.attachTo(context)
                context
                    .evaluateScript(
                        """
                        globalThis.Map = __originalMap;
                        globalThis.handledRejections = 0;
                        const originalCatch = Promise.prototype.catch;
                        Promise.prototype.catch = function (handler) {
                            return originalCatch.call(this, function (error) {
                                if (error === 'test rejection') handledRejections++;
                                return handler(error);
                            });
                        };
                        """.trimIndent(),
                    ).close()
                webView.delayTimerNotifications = true
                context.evaluateScript("globalThis.timer = setTimeout(() => {}, 100)").close()
                context
                    .evaluateScript(
                        "globalThis.registry = __timerMaps.find(map => { const item = map.get(timer); return item && typeof item.callback === 'function'; })",
                    ).close()
                assertEquals(1, context.evaluateScript("registry.size").use { it.int })
                webView.rejectNotification(webView.delayedNotifications.removeLast())
                // The backing engine's rejection tracker also schedules host turns, even for handled
                // rejections. Deliver those registrations; they are distinct from the rejected timer.
                webView.flushTimerNotifications()
                withTimeout(1_000) { eventLoop.run() }
                assertEquals(0, context.evaluateScript("registry.size").use { it.int })
                assertEquals(1, context.evaluateScript("handledRejections").use { it.int })

                webView.delayTimerNotifications = true
                context.evaluateScript("clearTimeout(setTimeout(() => {}, 100))").close()
                assertEquals(2, webView.delayedNotifications.size)
                webView.delayedNotifications
                    .toList()
                    .also { webView.delayedNotifications.clear() }
                    .forEach(webView::rejectNotification)
                webView.flushTimerNotifications()
                withTimeout(1_000) { eventLoop.run() }
                assertEquals(3, context.evaluateScript("handledRejections").use { it.int })
            }
        }

    @Test
    fun mixedObserversShareOnePendingBoundaryAndKeepIntervalTicksLocal() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                context
                    .evaluateScript(
                        """
                        (() => {
                            const schedule = setTimeout;
                            globalThis.setImmediate = (callback, ...args) => schedule(callback, 0, ...args);
                            globalThis.clearImmediate = clearTimeout;
                            globalThis.process = { nextTick(callback, ...args) {
                                globalThis.nextTickId = schedule(callback, 0, ...args);
                            } };
                        })();
                        """.trimIndent(),
                    ).close()
                eventLoop.attachTo(
                    context,
                    policies =
                        JsEventLoopPolicies(
                            timers = JsEventLoopPolicy(OBSERVE, SKIP),
                            immediate = JsEventLoopPolicy(OBSERVE, SKIP),
                            nextTick = JsEventLoopPolicy(OBSERVE, SKIP),
                        ),
                )
                webView.delayCallbackReplies = true
                webView.resetTraffic()
                context
                    .evaluateScript(
                        """
                        globalThis.calls = 0;
                        globalThis.interval = setInterval(() => calls++, 100);
                        globalThis.immediate = setImmediate(() => calls++);
                        process.nextTick(() => calls++);
                        """.trimIndent(),
                    ).close()
                assertEquals(listOf("observe"), webView.nativeEvents)
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                context
                    .evaluateScript(
                        """
                        for (let i = 0; i < 100; i++) __fireBrowserTimer(interval);
                        clearTimeout(interval);
                        __fireBrowserTimer(immediate);
                        """.trimIndent(),
                    ).close()
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                assertEquals(
                    listOf("observe"),
                    webView.nativeEvents,
                    "Repeating callbacks and other groups finishing do not cross the idle boundary",
                )
                context.evaluateScript("__fireBrowserTimer(nextTickId)").close()
                withTimeout(1_000) { running.await() }
                assertEquals(102, context.evaluateScript("calls").use { it.int })
                assertEquals(listOf("observe", "observe"), webView.nativeEvents)
                webView.delayCallbackReplies = false
                webView.flushCallbackReplies()
            }
        }

    @Test
    fun tickSnapshotAccountsForObservedRegistrationsWithoutNotificationRoundTrips() =
        runTest {
            withTimerWebView(attachOnEntry = false) { context, eventLoop, webView ->
                eventLoop.attachTo(context, policies = JsEventLoopPolicies(timers = JsEventLoopPolicy(OBSERVE, SKIP)))
                context
                    .evaluateScript(
                        """
                        globalThis.calls = 0;
                        setImmediate(() => {
                            for (let i = 0; i < 100; i++) clearTimeout(setTimeout(() => {}, 100));
                            globalThis.timer = setTimeout(() => calls++, 100);
                        });
                        """.trimIndent(),
                    ).close()
                webView.resetTraffic()
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted, "The tick snapshot must register the remaining observed work")
                assertEquals(emptyList(), webView.nativeEvents, "The snapshot replaces all observe notifications inside tick")

                context.evaluateScript("__fireBrowserTimer(timer)").close()
                withTimeout(1_000) { running.await() }
                assertEquals(1, context.evaluateScript("calls").use { it.int })
                assertEquals(listOf("observe"), webView.nativeEvents, "External completion still needs a notification")
            }
        }

    @Test
    fun disposingAnObserverDoesNotNotifyAnAlreadyCancelledNativeLoop() =
        runTest {
            withTimerWebView { context, eventLoop, webView ->
                context.evaluateScript("setTimeout(() => {}, 100)").close()
                val running = async { eventLoop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                webView.resetTraffic()

                eventLoop.cancel()
                withTimeout(1_000) { running.await() }

                assertEquals(emptyList(), webView.nativeEvents)
                assertEquals(1, webView.functionCalls, "One disposal RPC restores all observed functions")
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
                if (attachOnEntry) {
                    eventLoop.attachTo(
                        context,
                        policies =
                            JsEventLoopPolicies(
                                timers = JsEventLoopPolicy(OBSERVE, SKIP),
                                immediate = JsEventLoopPolicy(KEEP, SKIP),
                                nextTick = JsEventLoopPolicy(KEEP, SKIP),
                            ),
                    )
                }
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
    var delayCallbackReplies = false
    val delayedCallbackReplies = mutableListOf<String>()
    val nativeEvents = mutableListOf<String>()
    val functionResults = mutableListOf<String>()
    val propertyReads = mutableListOf<String>()
    var functionCalls = 0
        private set
    var callbackReplies = 0
        private set
    var callbackReplyAcknowledgements = 0
        private set
    private val callRequests = mutableSetOf<String>()

    fun resetTraffic() {
        nativeEvents.clear()
        functionResults.clear()
        propertyReads.clear()
        functionCalls = 0
        callbackReplies = 0
        callbackReplyAcknowledgements = 0
    }

    fun rejectNotification(message: String) {
        val id = message.substringAfter(',').substringBefore(',').toInt()
        backing
            .evaluateScript(
                JsWebViewMessage.FailNativeCallback(id, JsWebViewProtocolValue.String("test rejection")).toScript(),
            ).close()
    }

    fun flushCallbackReplies() {
        delayedCallbackReplies.toList().also { delayedCallbackReplies.clear() }.forEach {
            backing.evaluateScript(it).close()
        }
    }

    fun flushTimerNotifications() {
        delayTimerNotifications = false
        delayedNotifications.toList().also { delayedNotifications.clear() }.forEach(onMessage)
    }

    val delayedNotifications = mutableListOf<String>()
    private var timerListenerId: String? = null
    var failTickExtraction = false
    var failNextEvaluation = false
    var failNextRequestAsDetached = false
    var failedEvaluations = 0
        private set

    fun receiveMessage(message: String) {
        RESULT.find(message)?.let {
            if (callRequests.remove(it.groupValues[1])) functionResults += it.groupValues[2]
        }
        val callbackId = CALLBACK_ID.find(message)?.groupValues?.get(1)
        if (callbackId != null) EVENT.find(message)?.let { nativeEvents += it.groupValues[1] }
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
        PROPERTY_READ
            .find(script)
            ?.groupValues
            ?.get(1)
            ?.let { propertyReads += it }
        if (CALLBACK_REPLY.containsMatchIn(script)) callbackReplies++
        CALL.find(script)?.let {
            functionCalls++
            callRequests += it.groupValues[1]
        }
        if (delayCallbackReplies && script.contains(".dispatch([\"+\",")) {
            delayedCallbackReplies += script
            return
        }
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

    override fun evaluateJavaScript(
        script: String,
        onFailure: (Throwable) -> Unit,
    ) {
        if (CALLBACK_REPLY.containsMatchIn(script)) callbackReplyAcknowledgements++
        if (failNextRequestAsDetached) {
            failNextRequestAsDetached = false
            onFailure(JsWebViewContextDetachedException())
        } else {
            evaluateJavaScript(script)
        }
    }

    override fun close() {}

    private companion object {
        val CALLBACK_REPLY = Regex("""\.dispatch\(\["[+-]",""")
        val CALL = Regex("""\.dispatch\(\["c",\d+,.*],(\d+)\);$""")
        val RESULT = Regex("""^\["r",(\d+),(.+)]$""")
        val EVENT = Regex(""",\["(schedule|cancel|queued|observe)"(?:,|\])""")
        val CALLBACK_ID = Regex("""^\["f",\d+,(\d+),""")
        val TICK_EXTRACTION = Regex("""dispatch\(\["g",\d+,"tick"\]""")
        val PROPERTY_READ = Regex("""dispatch\(\["g",\d+,"([^"]+)"\]""")
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
