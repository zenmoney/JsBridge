package app.zenmoney.jsbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private var defaultAndroidContext: Context? = null

fun JsWebViewContext.Companion.setDefaultAndroidContext(context: Context) {
    defaultAndroidContext = context.applicationContext
}

@Suppress("FunctionName")
fun JsWebViewContext(context: Context): JsWebViewContext {
    val applicationContext = context.applicationContext
    return JsWebViewContext { contextId ->
        AndroidJsWebView(createWebView(applicationContext), contextId)
    }
}

/**
 * Initializes the adapter, native bridge and runtime lazily on the first JavaScript operation.
 * Use the first context before loading a page so the native bridge is registered before navigation.
 * After navigation, close the old context before using a new context for the same WebView.
 * Use `disposeWebView = {}` when the caller owns the WebView and wants to reuse it across documents.
 */
@Suppress("FunctionName")
fun JsWebViewContext(
    webView: WebView,
    disposeWebView: (WebView) -> Unit = WebView::destroy,
): JsWebViewContext =
    JsWebViewContext(
        createWebView = { contextId -> AndroidJsWebView(webView, contextId, disposeWebView) },
    )

internal actual fun createJsWebView(contextId: Int): JsWebView =
    AndroidJsWebView(
        createWebView(
            checkNotNull(defaultAndroidContext) {
                "Call JsWebViewContext.setDefaultAndroidContext(context) before using JsWebViewContext() on Android"
            },
        ),
        contextId,
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
    contextId: Int,
    private val disposeWebView: (WebView) -> Unit = WebView::destroy,
) : JsWebView {
    private val session = AndroidBridgeSession(contextId)
    private lateinit var nativeBridge: NativeBridge

    override var onMessage: (String) -> Unit
        get() = session.onMessage
        set(value) {
            session.onMessage = value
        }

    init {
        runOnMainThreadBlocking {
            nativeBridge = getOrCreateNativeBridge(webView)
            check(nativeBridge.session == null) { "Close the previous JsWebViewContext before reusing its WebView" }
            webView.evaluateJavascript(jsPromiseRejectionTrackingScript, null)
            nativeBridge.session = session
        }
    }

    override fun close() {
        session.onMessage = {}
        runOnMainThreadBlocking {
            if (nativeBridge.session === session) {
                nativeBridge.session = null
                // The interface must survive when disposeWebView keeps the WebView alive.
                disposeWebView(webView)
            }
        }
    }

    override fun initializeRuntime() {
        evaluateInSession(createJsWebViewRuntimeScript(session.id))
    }

    override fun evaluateJavaScript(script: String) {
        // A command already queued in WebView must not operate on a replacement runtime.
        evaluateInSession(
            "if (window.$JS_WEB_VIEW_BRIDGE_OBJECT && $JS_WEB_VIEW_BRIDGE_OBJECT.sessionId === ${session.id}) { $script }",
        )
    }

    private fun evaluateInSession(script: String) {
        AndroidMainThread.dispatch {
            if (nativeBridge.session === session) {
                webView.evaluateJavascript(script, null)
            }
        }
    }
}

private fun runOnMainThreadBlocking(block: () -> Unit) {
    val latch = CountDownLatch(1)
    var result: Result<Unit>? = null
    AndroidMainThread.dispatch {
        result = runCatching { block() }
        latch.countDown()
    }
    check(latch.await(10, TimeUnit.SECONDS)) { "Timed out running WebView initialization on main thread" }
    result!!.getOrThrow()
}

// WebView retains its JavascriptInterface. Both registry references are weak so an active
// session's handler (which can refer back to the WebView) cannot keep the WebView alive here.
// Access is confined to the main thread.
private val nativeBridges = WeakHashMap<WebView, WeakReference<NativeBridge>>()

private fun getOrCreateNativeBridge(webView: WebView): NativeBridge {
    webView.settings.javaScriptEnabled = true
    return nativeBridges[webView]?.get() ?: NativeBridge().also {
        webView.addJavascriptInterface(it, JS_WEB_VIEW_ANDROID_INTERFACE)
        nativeBridges[webView] = WeakReference(it)
    }
}

private class AndroidBridgeSession(
    val id: Int,
) {
    @Volatile
    var onMessage: (String) -> Unit = {}
}

private class NativeBridge {
    @Volatile
    var session: AndroidBridgeSession? = null

    @JavascriptInterface
    fun postMessage(
        message: String,
        sessionId: Int,
    ) {
        val target = session
        if (target != null && target.id == sessionId) target.onMessage(message)
    }
}
