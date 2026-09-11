package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class JsWebViewObservedTimerLifecycleTest {
    @Test
    fun closingObserverContextRestoresBrowserTimersAndPreservesCallbacksAcrossReuse() =
        runBrowserTimerTest {
            lateinit var webView: WKWebView
            onMainThread {
                webView = WKWebView(CGRectMake(0.0, 0.0, 0.0, 0.0), WKWebViewConfiguration())
            }
            val context = JsWebViewContext(webView, disposeWebView = {})
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                context
                    .evaluateScript(
                        """
                        globalThis.originalBrowserTimers = [setTimeout, clearTimeout, setInterval, clearInterval];
                        """.trimIndent(),
                    ).close()
                eventLoop.attachTo(context, timerMode = JsTimerMode.OBSERVE)
                context
                    .evaluateScript(
                        """
                        globalThis.oldTimerCanFinish = false;
                        globalThis.oldTimerFinished = false;
                        globalThis.oldTimerDone = new Promise(resolve => {
                            globalThis.oldTimer = setInterval(() => {
                                if (!oldTimerCanFinish) return;
                                clearInterval(oldTimer);
                                oldTimerFinished = true;
                                resolve('survived');
                            }, 10);
                        });
                        """.trimIndent(),
                    ).close()
                val running = async(start = CoroutineStart.UNDISPATCHED) { eventLoop.run() }
                assertFalse(running.isCompleted)

                context.close()

                // Native evaluation is a barrier after runtime disposal, when context RPCs are unavailable.
                assertEquals("undefined", webView.evaluateString("typeof $JS_WEB_VIEW_BRIDGE_OBJECT"))
                assertEquals(
                    "true",
                    webView.evaluateString(
                        "String([setTimeout, clearTimeout, setInterval, clearInterval].every((fn, i) => fn === originalBrowserTimers[i]))",
                    ),
                )
                running.awaitBrowserResult()

                JsWebViewContext(webView, disposeWebView = {}).use { replacement ->
                    eventLoop.attachTo(replacement, timerMode = JsTimerMode.OBSERVE)
                    replacement
                        .evaluateScript(
                            """
                            globalThis.newTimerFinished = false;
                            oldTimerCanFinish = true;
                            setTimeout(() => { newTimerFinished = true; }, 20);
                            """.trimIndent(),
                        ).close()
                    val oldTimer =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            jsScoped(replacement) { eval("oldTimerDone").await().string }
                        }

                    async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }.awaitBrowserResult()

                    assertEquals("survived", oldTimer.awaitBrowserResult())
                    assertTrue(replacement.evaluateScript("oldTimerFinished && newTimerFinished").use { it.boolean })
                }
            } finally {
                eventLoop.cancel()
                context.close()
                webView.evaluateString("clearInterval(globalThis.oldTimer); 'cleared'")
                onMainThread { webView.stopLoading() }
            }
        }

    private fun runBrowserTimerTest(block: suspend TestScope.() -> Unit) {
        val request = JsWebViewBlockingRequest<Unit>()
        val queue = dispatch_queue_create("app.zenmoney.jsbridge.browser.timer.lifecycle.test", null)
        dispatch_async(queue) {
            request.complete(runCatching { runTest { block() } })
        }
        request.await("browser timer lifecycle test with active main run loop")
    }

    private suspend fun <T> Deferred<T>.awaitBrowserResult(): T =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { await() }
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
        return request.await("native WebView timer lifecycle inspection")
    }

    private fun onMainThread(block: () -> Unit) {
        if (NSThread.isMainThread()) {
            block()
        } else {
            dispatch_sync(dispatch_get_main_queue()) { block() }
        }
    }
}
