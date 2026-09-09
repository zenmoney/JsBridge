package app.zenmoney.jsbridge

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsWebViewNavigationTest {
    @Test
    fun reconnectsRepeatedlyWithoutRebindingOrReloadingTheNativeInterface() =
        runBlocking {
            lateinit var webView: CountingWebView
            onMain { webView = CountingWebView(ApplicationProvider.getApplicationContext<Context>()) }
            val eventLoop = JsEventLoop(coroutineContext)
            var calls = 0
            try {
                repeat(5) { generation ->
                    val context = JsWebViewContext(webView, disposeWebView = {})
                    try {
                        eventLoop.attachTo(context)
                        jsScoped(context) {
                            if (generation == 0) {
                                eval("globalThis.firstNativeBridge = $JS_WEB_VIEW_ANDROID_INTERFACE; globalThis.previousGeneration = -1")
                            }
                            assertTrue(eval("firstNativeBridge === $JS_WEB_VIEW_ANDROID_INTERFACE").boolean)
                            assertEquals(generation - 1, eval("previousGeneration").int)
                            assertEquals(context.id, eval("$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId").int)
                            context.globalThis["nativeGeneration"] =
                                JsFunction {
                                    calls++
                                    JsNumber(generation)
                                }
                            withTimeout(5_000) {
                                assertEquals(generation, eval("nativeGeneration()").await().int)
                            }
                            eval("globalThis.previousGeneration = $generation")
                        }
                    } finally {
                        if (generation % 2 == 0) context.close() else context.closeAsync().join()
                    }
                    onMain {
                        assertEquals(1, webView.bridgeInstallations)
                        assertEquals(0, webView.bridgeRemovals)
                    }
                }
                assertEquals(5, calls)
            } finally {
                eventLoop.cancel()
                eventLoop.run()
                onMain { webView.destroy() }
            }
        }

    @Test
    fun initializesWebViewOnlyOnFirstUse() {
        val webView = createWebView()
        var disposed = false
        try {
            JsWebViewContext(webView, disposeWebView = { disposed = true }).use {
                onMain { assertFalse(webView.settings.javaScriptEnabled) }
            }
            assertFalse(disposed)
            onMain { assertFalse(webView.settings.javaScriptEnabled) }

            JsWebViewContext(webView, disposeWebView = {}).use { context ->
                onMain { assertFalse(webView.settings.javaScriptEnabled) }
                assertEquals(context.id, context.evaluateScript("$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId").use { it.int })
                onMain { assertTrue(webView.settings.javaScriptEnabled) }
            }
        } finally {
            onMain { webView.destroy() }
        }
    }

    @Test
    fun reconnectsAfterRepeatedNavigation() =
        runBlocking {
            val webView = createWebView()
            var context = JsWebViewContext(webView, disposeWebView = {})
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                assertEquals(3, context.evaluateScript("1 + 2").use { it.int })
                repeat(3) { page ->
                    loadPage(webView, page)
                    context.closeAsync().join()
                    context = JsWebViewContext(webView, disposeWebView = {})
                    eventLoop.attachTo(context)
                    assertEquals(page, context.evaluateScript("document.body.textContent * 1").use { it.int })
                    context.globalThis["nativeAnswer"] = JsFunction(context) { JsNumber(context, 42) }
                    context.evaluateScript("nativeAnswer()").use { promise ->
                        withTimeout(5_000) {
                            assertEquals(42, promise.awaitEscaped().use { it.int })
                        }
                    }
                }
            } finally {
                context.closeAsync().join()
                eventLoop.cancel()
                eventLoop.run()
                onMain { webView.destroy() }
            }
        }

    @Test
    fun ignoresPreviousSessionResponseWithReusedRequestId() {
        val webView = createWebView()
        try {
            JsWebViewContext(webView, disposeWebView = {}).use { context ->
                context.evaluateScript("globalThis.previousBridge = $JS_WEB_VIEW_BRIDGE_OBJECT; 1").close()
            }
            JsWebViewContext(webView, disposeWebView = {}).use { context ->
                // Both contexts start at request 1. Deliver the old response before the real one.
                assertEquals(
                    42,
                    context.evaluateScript("previousBridge.dispatch(['e', '-1'], 1); 42").use { it.int },
                )
            }
        } finally {
            onMain { webView.destroy() }
        }
    }

    @Test
    fun ignoresPreviousSessionCallbackWithReusedCallbackId() =
        runBlocking {
            val webView = createWebView()
            val eventLoop = JsEventLoop(coroutineContext)
            var oldCalls = 0
            var newCalls = 0
            try {
                JsWebViewContext(webView, disposeWebView = {}).use { context ->
                    eventLoop.attachTo(context)
                    context.globalThis["previousCallback"] =
                        JsFunction(context) {
                            oldCalls++
                            context.UNDEFINED
                        }
                }
                JsWebViewContext(webView, disposeWebView = {}).use { context ->
                    eventLoop.attachTo(context)
                    context.globalThis["currentCallback"] =
                        JsFunction(context) {
                            newCalls++
                            JsNumber(context, 42)
                        }
                    context
                        .evaluateScript(
                            """
                            (() => {
                                let message;
                                try { previousCallback(); } catch (error) { message = error.message; }
                                if (message !== 'JsContext is closed') throw new Error('Unexpected closed callback result');
                                return currentCallback();
                            })()
                            """.trimIndent(),
                        ).use { promise ->
                            withTimeout(5_000) {
                                assertEquals(42, promise.awaitEscaped().use { it.int })
                            }
                        }
                }
                assertEquals(0, oldCalls)
                assertEquals(1, newCalls)
            } finally {
                eventLoop.cancel()
                eventLoop.run()
                onMain { webView.destroy() }
            }
        }

    @Test
    fun rejectingConcurrentContextDoesNotDetachActiveSession() {
        val webView = createWebView()
        try {
            JsWebViewContext(webView, disposeWebView = {}).use { active ->
                assertEquals(1, active.evaluateScript("1").use { it.int })
                JsWebViewContext(webView, disposeWebView = {}).use { other ->
                    assertFailsWith<IllegalStateException> { other.evaluateScript("2") }
                }
                assertEquals(3, active.evaluateScript("3").use { it.int })
            }
        } finally {
            onMain { webView.destroy() }
        }
    }

    @Test
    fun closesEachSessionOnceAndAllowsFinalDisposal() {
        val webView = createWebView()
        var disposals = 0
        val keepWebView: (WebView) -> Unit = { disposals++ }
        val first = JsWebViewContext(webView, disposeWebView = keepWebView)
        try {
            first.evaluateScript("1").close()
            first.close()
            assertEquals(1, disposals)
            JsWebViewContext(webView, disposeWebView = keepWebView).use { second ->
                second.evaluateScript("2").close()
                first.close()
                assertEquals(3, second.evaluateScript("3").use { it.int })
                assertEquals(1, disposals)
            }
            assertEquals(2, disposals)
        } finally {
            first.close()
            onMain { webView.destroy() }
        }
    }

    private fun createWebView(): WebView {
        lateinit var webView: WebView
        onMain { webView = WebView(ApplicationProvider.getApplicationContext<Context>()) }
        return webView
    }

    private suspend fun loadPage(
        webView: WebView,
        page: Int,
    ) {
        val loaded = CompletableDeferred<Unit>()
        onMain {
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        loaded.complete(Unit)
                    }
                }
            webView.loadDataWithBaseURL("https://jsbridge.test/$page", "<html><body>$page</body></html>", "text/html", "UTF-8", null)
        }
        withTimeout(10_000) { loaded.await() }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private class CountingWebView(
        context: Context,
    ) : WebView(context) {
        var bridgeInstallations = 0
            private set
        var bridgeRemovals = 0
            private set

        override fun addJavascriptInterface(
            obj: Any,
            interfaceName: String,
        ) {
            if (interfaceName == JS_WEB_VIEW_ANDROID_INTERFACE) bridgeInstallations++
            super.addJavascriptInterface(obj, interfaceName)
        }

        override fun removeJavascriptInterface(interfaceName: String) {
            if (interfaceName == JS_WEB_VIEW_ANDROID_INTERFACE) bridgeRemovals++
            super.removeJavascriptInterface(interfaceName)
        }
    }
}
