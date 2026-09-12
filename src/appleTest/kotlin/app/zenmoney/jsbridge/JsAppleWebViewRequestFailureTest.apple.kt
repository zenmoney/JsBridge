package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSThread
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class JsAppleWebViewRequestFailureTest {
    @Test
    fun missingPageBridgeRejectsTheRequestImmediately() {
        assertLostSession("delete globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT")
    }

    @Test
    fun replacementSessionRejectsTheOldRequestImmediately() {
        assertLostSession("$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId = -1")
    }

    @Test
    fun nativeEvaluationExceptionRejectsTheRequestImmediatelyAndDisposesUncertainRefcounts() {
        withWebView { webView, context ->
            // Queue a release whose prefix cannot execute after dispatch starts throwing.
            context.evaluateScript("({ retained: true })").close()
            webView.evaluateString("$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch = () => { throw new Error('native failure'); }; 'ready'")
            val started = NSDate().timeIntervalSinceReferenceDate

            val failure = assertFailsWith<IllegalStateException> { context.evaluateScript("42") }

            assertTrue(NSDate().timeIntervalSinceReferenceDate - started < 2.0, "A native failure must not wait for the RPC timeout")
            assertFalse(failure is JsWebViewContextDetachedException)
            assertTrue(context.isClosed)
            assertTrue(failure.message.orEmpty().isNotEmpty())
            assertEquals("undefined", webView.evaluateString("typeof $JS_WEB_VIEW_BRIDGE_OBJECT"))
        }
    }

    @Test
    fun ordinaryJavaScriptExceptionPreservesTheContextAndItsNextEvaluation() {
        withWebView { _, context ->
            val failure = assertFailsWith<JsException> { context.evaluateScript("throw new Error('ordinary page failure')") }

            assertTrue(failure.message.orEmpty().contains("ordinary page failure"))
            assertFalse(context.isClosed)
            assertEquals(43, context.evaluateScript("43").use { it.int })
        }
    }

    private fun assertLostSession(invalidate: String) {
        withWebView { webView, context ->
            webView.evaluateString("$invalidate; 'ready'")
            val started = NSDate().timeIntervalSinceReferenceDate

            assertFailsWith<JsWebViewContextDetachedException> { context.evaluateScript("42") }

            assertTrue(NSDate().timeIntervalSinceReferenceDate - started < 2.0, "A lost session must not wait for the RPC timeout")
            assertTrue(context.isClosed)
        }
    }

    private fun withWebView(block: (WKWebView, JsWebViewContext) -> Unit) {
        lateinit var webView: WKWebView
        onMain {
            webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
        }
        val context = JsWebViewContext(webView, disposeWebView = {})
        try {
            assertEquals(42, context.evaluateScript("42").use { it.int })
            block(webView, context)
        } finally {
            context.close()
            onMain { webView.stopLoading() }
        }
    }

    private fun WKWebView.evaluateString(script: String): String {
        val request = JsWebViewBlockingRequest<String>()
        onMain {
            evaluateJavaScript(script) { result, error ->
                request.complete(
                    if (error == null) {
                        Result.success(result.toString())
                    } else {
                        Result.failure(IllegalStateException(error.localizedDescription))
                    },
                )
            }
        }
        return request.await("native failure test setup")
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread()) {
            block()
        } else {
            dispatch_sync(dispatch_get_main_queue()) { block() }
        }
    }
}
