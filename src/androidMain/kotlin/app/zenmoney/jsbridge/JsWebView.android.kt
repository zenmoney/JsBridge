package app.zenmoney.jsbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private var defaultAndroidContext: Context? = null

fun JsWebViewContext.Companion.setDefaultAndroidContext(context: Context) {
    defaultAndroidContext = context.applicationContext
}

@Suppress("FunctionName")
fun JsWebViewContext(context: Context): JsWebViewContext {
    val applicationContext = context.applicationContext
    return JsWebViewContext {
        AndroidJsWebView(createWebView(applicationContext))
    }
}

@Suppress("FunctionName")
fun JsWebViewContext(webView: WebView): JsWebViewContext =
    JsWebViewContext {
        AndroidJsWebView(webView)
    }

internal actual fun createJsWebView(): JsWebView =
    AndroidJsWebView(
        createWebView(
            checkNotNull(defaultAndroidContext) {
                "Call JsWebViewContext.setDefaultAndroidContext(context) before using JsWebViewContext() on Android"
            },
        ),
    )

private fun createWebView(context: Context): WebView {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return WebView(context)
    }
    val latch = CountDownLatch(1)
    var result: Result<WebView>? = null
    Handler(Looper.getMainLooper()).post {
        result = runCatching { WebView(context) }
        latch.countDown()
    }
    check(latch.await(10, TimeUnit.SECONDS)) { "Timed out creating Android WebView" }
    return result!!.getOrThrow()
}

internal actual class JsWebViewBlockingRequest<T> {
    private val latch = CountDownLatch(1)
    private var result: Result<T>? = null

    actual fun complete(result: Result<T>) {
        this.result = result
        latch.countDown()
    }

    actual fun await(debug: String): T {
        check(latch.await(10, TimeUnit.SECONDS)) {
            "Timed out executing JsWebViewContext.$debug"
        }
        return checkNotNull(result).getOrThrow()
    }
}

private class AndroidJsWebView(
    private val webView: WebView,
) : JsWebView {
    private val mainHandler = Handler(Looper.getMainLooper())

    override var onMessage: (String) -> Unit = {}

    init {
        runOnWebViewThreadBlocking {
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(NativeBridge(), JS_WEB_VIEW_ANDROID_INTERFACE)
        }
    }

    private val isOnWebViewThread: Boolean
        get() = Looper.myLooper() == Looper.getMainLooper()

    override fun close() {
        runOnWebViewThread {
            webView.removeJavascriptInterface(JS_WEB_VIEW_ANDROID_INTERFACE)
            webView.destroy()
        }
    }

    override fun evaluateJavaScript(script: String) {
        runOnWebViewThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun runOnWebViewThread(block: () -> Unit) {
        if (isOnWebViewThread) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun runOnWebViewThreadBlocking(block: () -> Unit) {
        if (isOnWebViewThread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var result: Result<Unit>? = null
        mainHandler.post {
            result = runCatching { block() }
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Timed out running WebView initialization on main thread" }
        result!!.getOrThrow()
    }

    private inner class NativeBridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            onMessage(message)
        }
    }
}
