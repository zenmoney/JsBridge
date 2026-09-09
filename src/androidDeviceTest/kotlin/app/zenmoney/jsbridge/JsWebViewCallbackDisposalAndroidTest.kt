package app.zenmoney.jsbridge

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class JsWebViewCallbackDisposalAndroidTest {
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
    ) = runBlocking {
        lateinit var webView: WebView
        onMain { webView = WebView(ApplicationProvider.getApplicationContext<Context>()) }
        val context = JsWebViewContext(webView, disposeWebView = {})
        val eventLoop = JsEventLoop(coroutineContext)
        var nativeCalls = 0
        try {
            context.evaluateScript("0").close()
            evaluate(webView, jsCaptureTimerMaps)
            eventLoop.attachTo(context)
            evaluate(webView, jsBoundTimerScheduling)
            assertEquals("1", evaluate(webView, "__timerMaps.length"))
            context.globalThis["__pendingCall"] =
                JsFunction(context) {
                    nativeCalls++
                    context.UNDEFINED
                }
            // Keep the owner dispatcher blocked so native callbacks remain pending while JS is disposed.
            if (pending) evaluate(webView, "$script; undefined")
            context.close()
            if (!pending) evaluate(webView, script)
            assertEquals("0", evaluate(webView, "__timerMaps[0].size"))
            assertEquals(if (pending) "2" else "0", evaluate(webView, "__timerScheduleCount"))
            if (!pending || script.contains("catch")) {
                assertEquals("\"JsContext is closed\"", evaluate(webView, "__rejection"))
            }
            assertEquals(
                if (pending && !script.contains("catch")) "\"JsContext is closed\"" else "\"\"",
                evaluate(webView, "__unhandledReasons.join(';')"),
            )
            assertEquals(0, nativeCalls)
        } finally {
            context.close()
            eventLoop.cancel()
            eventLoop.run()
            onMain { webView.destroy() }
        }
    }

    private fun evaluate(
        webView: WebView,
        script: String,
    ): String {
        val completed = CountDownLatch(1)
        var result: String? = null
        onMain {
            webView.evaluateJavascript(script) {
                result = it
                completed.countDown()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "Timed out evaluating WebView callback disposal probe" }
        return checkNotNull(result)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
