package app.zenmoney.jsbridge

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsWebViewDisposalAndroidTest {
    @Test
    fun closingContextDisposesRuntimeAndKeepsExternalWebViewReusable() {
        lateinit var webView: WebView
        onMain {
            webView = WebView(ApplicationProvider.getApplicationContext<Context>())
            webView.settings.javaScriptEnabled = true
        }
        val context = JsWebViewContext(webView, disposeWebView = {})
        try {
            // Let the adapter install its JavascriptInterface before the first document execution.
            // This scalar result leaves no retained handles when replacing the runtime below.
            context.evaluateScript("0").close()
            // Reinstall the same session with test-only access to its retained maps.
            val runtime =
                createJsWebViewRuntimeScript(context.id).replace(
                    "const unpublishedHandles = new Set();",
                    "const unpublishedHandles = new Set(); " +
                        "globalThis.__retainedBridgeState = { objectByHandle, refCountByHandle, pendingJsCallbacks };",
                )
            evaluate(webView, "$JS_WEB_VIEW_BRIDGE_OBJECT.dispose(); $runtime")
            val retained =
                context.evaluateScript(
                    "globalThis.__previousBridge = $JS_WEB_VIEW_BRIDGE_OBJECT; ({payload: new Array(1024).fill(1)})",
                )
            assertEquals("true", evaluate(webView, "__retainedBridgeState.objectByHandle.size > 1"))
            assertEquals("true", evaluate(webView, "__retainedBridgeState.refCountByHandle.size > 0"))

            context.close()

            assertTrue(retained.isClosed)
            assertEquals("true", evaluate(webView, "typeof $JS_WEB_VIEW_BRIDGE_OBJECT === 'undefined'"))
            assertEquals(
                "[0,0,0]",
                evaluate(
                    webView,
                    "[__retainedBridgeState.objectByHandle.size, " +
                        "__retainedBridgeState.refCountByHandle.size, __retainedBridgeState.pendingJsCallbacks.size]",
                ),
            )
            assertEquals("42", evaluate(webView, "6 * 7"))

            JsWebViewContext(webView, disposeWebView = {}).use { replacement ->
                assertEquals(replacement.id, replacement.evaluateScript("$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId").use { it.int })
                context.close()
                assertEquals(
                    replacement.id,
                    replacement.evaluateScript("__previousBridge.dispose(); $JS_WEB_VIEW_BRIDGE_OBJECT.sessionId").use { it.int },
                )
                assertEquals(42, replacement.evaluateScript("40 + 2").use { it.int })
            }
            assertEquals("true", evaluate(webView, "typeof $JS_WEB_VIEW_BRIDGE_OBJECT === 'undefined'"))
        } finally {
            context.close()
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
        check(completed.await(10, TimeUnit.SECONDS)) { "Timed out evaluating WebView disposal probe" }
        return checkNotNull(result)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
