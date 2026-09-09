package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class JsAppleWebViewLifecycleTest {
    @Test
    fun closingExternalWebViewContextOnMainThreadPreservesEvaluationOrdering() {
        onMainThread {
            assertExternalWebViewCanBeReused()
        }
    }

    @Test
    fun closingExternalWebViewContextOnBackgroundThreadPreservesEvaluationOrdering() {
        val request = JsWebViewBlockingRequest<Unit>()
        val queue = dispatch_queue_create("app.zenmoney.jsbridge.lifecycle.test", null)
        dispatch_async(queue) {
            request.complete(runCatching { assertExternalWebViewCanBeReused() })
        }

        request.await("background WebView disposal test")
    }

    private fun assertExternalWebViewCanBeReused() {
        lateinit var webView: WKWebView
        onMainThread {
            val configuration = WKWebViewConfiguration()
            configuration.userContentController = WKUserContentController()
            webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
        }
        val context = JsWebViewContext(webView, disposeWebView = {})
        try {
            webView.evaluateString(
                """
                globalThis.__bridgeMaps = [];
                globalThis.Map = class extends Map {
                    constructor(...args) { super(...args); __bridgeMaps.push(this); }
                };
                'ready';
                """.trimIndent(),
            )
            context
                .evaluateScript(
                    """
                    globalThis.__lifecycleEvents = [];
                    globalThis.__oldBridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                    __oldBridge.dispose = (() => {
                        const dispose = __oldBridge.dispose;
                        return function () {
                            __lifecycleEvents.push('dispose');
                            dispose.call(__oldBridge);
                        };
                    })();
                    new Uint8Array(1024 * 1024);
                    """.trimIndent(),
                ).close()
            onMainThread {
                // Deliberately submit without waiting, then close while this evaluation is queued in WebKit.
                webView.evaluateJavaScript(
                    "__lifecycleEvents.push(typeof $JS_WEB_VIEW_BRIDGE_OBJECT === 'object' ? 'queued-before-close' : 'already-disposed');",
                    null,
                )
            }

            context.close()

            // Native evaluateJavaScript completion provides a barrier after the queued disposal evaluation.
            assertEquals("undefined", webView.evaluateString("typeof $JS_WEB_VIEW_BRIDGE_OBJECT"))
            assertEquals("queued-before-close,dispose", webView.evaluateString("__lifecycleEvents.join(',')"))
            assertEquals("true", webView.evaluateString("String(__bridgeMaps.length > 0 && __bridgeMaps.every(map => map.size === 0))"))
            JsWebViewContext(webView, disposeWebView = {}).use { replacement ->
                assertEquals(
                    42,
                    replacement.evaluateScript("globalThis.__replacementBridge = $JS_WEB_VIEW_BRIDGE_OBJECT; 6 * 7").use { it.int },
                )
                assertEquals(
                    "true",
                    webView.evaluateString(
                        """
                        __oldBridge.dispose();
                        __oldBridge.dispatch(['e', 'globalThis.__lateOldDispatchRan = true'], 999);
                        String($JS_WEB_VIEW_BRIDGE_OBJECT === __replacementBridge);
                        """.trimIndent(),
                    ),
                )
                assertEquals("undefined", replacement.evaluateScript("typeof __lateOldDispatchRan").use { it.string })
                assertEquals(43, replacement.evaluateScript("43").use { it.int })
            }
            assertEquals("undefined", webView.evaluateString("typeof $JS_WEB_VIEW_BRIDGE_OBJECT"))
        } finally {
            context.close()
            onMainThread { webView.stopLoading() }
        }
    }

    private fun WKWebView.evaluateString(script: String): String {
        val request = JsWebViewBlockingRequest<String>()
        onMainThread {
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
        return request.await("native WebView lifecycle inspection")
    }

    private fun onMainThread(block: () -> Unit) {
        if (NSThread.isMainThread()) {
            block()
        } else {
            dispatch_sync(dispatch_get_main_queue()) { block() }
        }
    }
}
