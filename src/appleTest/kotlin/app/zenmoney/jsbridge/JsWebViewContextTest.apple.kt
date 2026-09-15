package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

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

    override fun runAsyncRuntimeTest(block: suspend CoroutineScope.() -> Unit) {
        // WebKit callbacks need an active main run loop and real time. Advancing runTest's
        // virtual checkpoint timeout while callbacks are in transit can keep run() retrying.
        runBrowserTimerTest { withContext(Dispatchers.Default.limitedParallelism(1), block) }
    }

    override val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf(
            "globalThis.onunhandledrejection:callback probe",
            "globalThis.addEventListener:callback probe",
        )
    override val expectPromiseOverride: Boolean = false

    @Test
    @OptIn(ExperimentalForeignApi::class)
    fun executesOnPageWithStrictCsp() {
        val webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
        val loaded = JsWebViewBlockingRequest<Unit>()
        val delegate =
            object : NSObject(), WKNavigationDelegateProtocol {
                override fun webView(
                    webView: WKWebView,
                    didFinishNavigation: WKNavigation?,
                ) {
                    loaded.complete(Result.success(Unit))
                }
            }
        webView.navigationDelegate = delegate
        try {
            webView.loadHTMLString(
                """<meta http-equiv="Content-Security-Policy" content="script-src 'none'"><body>CSP test</body>""",
                NSURL(string = "https://example.test"),
            )
            loaded.await("CSP test page load")
            // Keep the weakly held navigation delegate alive throughout loading.
            assertSame(delegate, webView.navigationDelegate)
            JsWebViewContext(webView, disposeWebView = {}).use { context ->
                assertWebViewWorksWithStrictCsp(context)
                runBrowserTimerTest { assertWebViewAsyncWorksWithStrictCsp(context) }
            }
        } finally {
            webView.navigationDelegate = null
        }
    }

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
                JsWebViewMessage.WrapScript("globalThis.sessionValue = 'stale'").toScript(),
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
