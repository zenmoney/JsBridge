package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestScope
import platform.CoreGraphics.CGRectMake
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsWebViewContextTest : JsWebViewContextBaseTest() {
    override fun createContext(): JsContext = JsWebViewContext()

    override fun runBrowserTimerTest(block: suspend TestScope.() -> Unit) {
        val request = JsWebViewBlockingRequest<Unit>()
        val queue = dispatch_queue_create("app.zenmoney.jsbridge.browser.timers.test", null)
        dispatch_async(queue) {
            request.complete(runCatching { super.runBrowserTimerTest(block) })
        }
        request.await("browser timer test with active main run loop")
    }

    override val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf(
            "globalThis.onunhandledrejection:callback probe",
            "globalThis.addEventListener:callback probe",
        )
    override val expectPromiseOverride: Boolean = false

    @Test
    @OptIn(ExperimentalForeignApi::class)
    fun staleCommandDoesNotMutateAReplacementContext() {
        val webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
        lateinit var oldAdapter: AppleJsWebView
        JsWebViewContext { contextId ->
            AppleJsWebView(webView, contextId, disposeWebView = {}).also { oldAdapter = it }
        }.use { first ->
            first.evaluateScript("1").close()
        }
        JsWebViewContext(webView, disposeWebView = {}).use { replacement ->
            replacement.evaluateScript("globalThis.sessionValue = 'replacement'").close()
            oldAdapter.evaluateJavaScript(
                JsWebViewMessage.Evaluate("globalThis.sessionValue = 'stale'").toScript(),
            )
            replacement.evaluateScript("sessionValue").use { value ->
                assertEquals("replacement", value.toString())
            }
        }
    }

    @Test
    fun executesFromBackgroundThread() {
        val request = JsWebViewBlockingRequest<Double>()
        val queue = dispatch_queue_create("app.zenmoney.jsbridge.test", null)
        dispatch_async(queue) {
            request.complete(
                runCatching {
                    JsWebViewContext().use { context ->
                        val result = context.evaluateScript("40 + 2")

                        assertIs<JsNumber>(result).toNumber().toDouble()
                    }
                },
            )
        }

        assertEquals(42.0, request.await("background test"))
    }
}
