package app.zenmoney.jsbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JsWebViewCallbackDisposalTest {
    @Test
    fun disposingHandledCallbackDoesNotKeepSchedulingRejectionNotifications() =
        assertDisposalStopsScheduling("__pendingCall({}).catch(error => { __rejection = error.message; })", pending = true)

    @Test
    fun disposingUnhandledCallbackDoesNotKeepSchedulingRejectionNotifications() =
        assertDisposalStopsScheduling("__pendingCall({})", pending = true)

    @Test
    fun callingDisposedCallbackDoesNotScheduleRejectionNotifications() =
        assertDisposalStopsScheduling("try { __pendingCall({}); } catch (error) { __rejection = error.message; }", pending = false)

    private fun assertDisposalStopsScheduling(
        script: String,
        pending: Boolean,
    ) = runTest {
        JsEngineContext().use { backing ->
            val webView =
                object : JsWebView {
                    override var onMessage: (String) -> Unit = {}

                    override fun evaluateJavaScript(script: String) {
                        backing.evaluateScript(script).close()
                    }

                    override fun close() {}
                }
            jsScoped(backing) {
                eval("globalThis.window = globalThis; globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {}")
                val native = eval(JS_WEB_VIEW_ANDROID_INTERFACE) as JsObject
                native["postMessage"] =
                    JsFunction { args ->
                        webView.onMessage(args[0].string)
                        JsUndefined()
                    }
            }
            val context = JsWebViewContext(webView)
            val eventLoop = JsEventLoop(coroutineContext)
            var nativeCalls = 0
            try {
                context.evaluateScript("0").close()
                // The runtime has captured Map already. Capture only the timer scheduler's map during attachment.
                backing.evaluateScript(jsCaptureTimerMaps).close()
                eventLoop.attachTo(context)
                backing.evaluateScript(jsBoundTimerScheduling).close()
                assertEquals(1, backing.evaluateScript("__timerMaps.length").use { it.int })
                context.globalThis["__pendingCall"] =
                    JsFunction(context) {
                        nativeCalls++
                        context.UNDEFINED
                    }
                // Do not advance the Kotlin dispatcher: any native invocation stays pending until disposal.
                if (pending) backing.evaluateScript("$script; undefined").close()
                context.close()
                if (!pending) backing.evaluateScript(script).close()
                assertEquals(0, backing.evaluateScript("__timerMaps[0].size").use { it.int })
                assertEquals(if (pending) 2 else 0, backing.evaluateScript("__timerScheduleCount").use { it.int })
                if (!pending || script.contains("catch")) {
                    assertEquals("JsContext is closed", backing.evaluateScript("__rejection").use { it.string })
                }
                assertEquals(
                    if (pending && !script.contains("catch")) "JsContext is closed" else "",
                    backing.evaluateScript("__unhandledReasons.join(';')").use { it.string },
                )
                assertEquals(0, nativeCalls)
            } finally {
                context.close()
                eventLoop.cancel()
                eventLoop.run()
            }
        }
    }
}

internal val jsCaptureTimerMaps =
    """
    globalThis.__originalMap = globalThis.Map;
    globalThis.__timerMaps = [];
    globalThis.Map = class extends Map {
        constructor(...args) { super(...args); __timerMaps.push(this); }
    };
    """.trimIndent()

internal val jsBoundTimerScheduling =
    """
    globalThis.Map = __originalMap;
    globalThis.__timerScheduleCount = 0;
    globalThis.__rejection = '';
    globalThis.__unhandledReasons = [];
    globalThis.onunhandledrejection = function (event) {
        __unhandledReasons.push(event.reason.message);
        event.preventDefault();
    };
    (() => {
        const schedule = globalThis.setTimeout;
        globalThis.setTimeout = function (...args) {
            // Bound a recursive microtask regression so it fails instead of hanging the suite.
            if (++__timerScheduleCount > 20) return -1;
            return schedule(...args);
        };
    })();
    """.trimIndent()
