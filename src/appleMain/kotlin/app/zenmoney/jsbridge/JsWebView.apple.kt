package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
@Suppress("FunctionName")
fun JsWebViewContext(
    webView: WKWebView,
    disposeWebView: (WKWebView) -> Unit = WKWebView::stopLoading,
): JsWebViewContext =
    JsWebViewContext(
        createWebView = { contextId -> AppleJsWebView(webView, contextId, disposeWebView) },
    )

@OptIn(ExperimentalForeignApi::class)
@Suppress("FunctionName")
fun JsWebViewContext(configuration: WKWebViewConfiguration): JsWebViewContext =
    JsWebViewContext { contextId ->
        AppleJsWebView(createWebView(configuration), contextId)
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJsWebView(contextId: Int): JsWebView = AppleJsWebView(createWebView(), contextId)

@OptIn(ExperimentalForeignApi::class)
private fun createWebView(): WKWebView {
    if (NSThread.isMainThread()) {
        return createWebViewOnMainThread()
    }
    var webView: WKWebView? = null
    dispatch_sync(dispatch_get_main_queue()) {
        webView = createWebViewOnMainThread()
    }
    return checkNotNull(webView)
}

@OptIn(ExperimentalForeignApi::class)
private fun createWebViewOnMainThread(): WKWebView {
    val configuration = WKWebViewConfiguration()
    configuration.userContentController = WKUserContentController()
    return WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
}

@OptIn(ExperimentalForeignApi::class)
private fun createWebView(configuration: WKWebViewConfiguration): WKWebView {
    if (NSThread.isMainThread()) {
        return WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
    }
    var webView: WKWebView? = null
    dispatch_sync(dispatch_get_main_queue()) {
        webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration)
    }
    return checkNotNull(webView)
}

@OptIn(ExperimentalForeignApi::class)
internal actual class JsWebViewBlockingRequest<T> {
    private val semaphore = dispatch_semaphore_create(0)

    @Volatile
    private var result: Result<T>? = null

    actual fun complete(result: Result<T>) {
        this.result = result
        dispatch_semaphore_signal(semaphore)
    }

    actual fun await(debug: String): T {
        if (!NSThread.isMainThread()) {
            check(dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 10_000_000_000L)) == 0L) {
                "Timed out executing JsWebViewContext.$debug"
            }
            return checkNotNull(result).getOrThrow()
        }

        val deadline = NSDate().timeIntervalSinceReferenceDate + 10.0
        while (true) {
            result?.let { return it.getOrThrow() }
            check(NSDate().timeIntervalSinceReferenceDate < deadline) {
                "Timed out executing JsWebViewContext.$debug"
            }
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.001, true)
        }
    }
}

internal class AppleJsWebView(
    private val webView: WKWebView,
    private val contextId: Int,
    private val disposeWebView: (WKWebView) -> Unit = WKWebView::stopLoading,
) : JsWebView {
    private val messageHandler = AppleMessageHandler(this)

    @Volatile
    private var isClosed = false

    override var onMessage: (String) -> Unit = {}

    init {
        runOnWebViewThreadBlocking {
            webView.configuration.userContentController.addScriptMessageHandler(messageHandler, JS_WEB_VIEW_IOS_HANDLER)
        }
    }

    private val isOnWebViewThread: Boolean
        get() = NSThread.isMainThread()

    override fun close() {
        runOnWebViewThreadBlocking {
            if (isClosed) return@runOnWebViewThreadBlocking
            isClosed = true
            onMessage = {}
            webView.configuration.userContentController.removeScriptMessageHandlerForName(JS_WEB_VIEW_IOS_HANDLER)
            disposeWebView(webView)
        }
    }

    override fun initializeRuntime() {
        evaluateInSession(createJsWebViewRuntimeScript(contextId))
    }

    override fun evaluateJavaScript(script: String) {
        // A queued command can reach WebKit after a replacement context installs its runtime.
        evaluateInSession(
            "if (window.$JS_WEB_VIEW_BRIDGE_OBJECT && $JS_WEB_VIEW_BRIDGE_OBJECT.sessionId === $contextId) { $script }",
        )
    }

    override fun evaluateJavaScript(
        script: String,
        onFailure: (Throwable) -> Unit,
    ) {
        runOnWebViewThread {
            if (isClosed) {
                onFailure(IllegalStateException("JsWebView is closed"))
                return@runOnWebViewThread
            }
            val guardedScript =
                // Keep Script scope: a function wrapper would make plugin var declarations local.
                "if (window.$JS_WEB_VIEW_BRIDGE_OBJECT && " +
                    "$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId === $contextId) { $script\n; true; } else { false; }"
            webView.evaluateJavaScript(guardedScript) { result, error ->
                when {
                    error != null -> {
                        onFailure(IllegalStateException(error.localizedDescription))
                    }

                    (result as? NSNumber)?.boolValue == false -> {
                        onFailure(JsWebViewContextDetachedException())
                    }

                    (result as? NSNumber)?.boolValue != true -> {
                        onFailure(IllegalStateException("Unexpected JsWebView native execution result"))
                    }
                }
            }
        }
    }

    private fun evaluateInSession(script: String) {
        runOnWebViewThread {
            if (!isClosed) webView.evaluateJavaScript(script, null)
        }
    }

    fun receiveMessage(message: NSString) {
        // Capture completed results before a later WK navigation callback closes the context.
        // Native function and Promise callbacks dispatch onto their event loop in the protocol handler.
        if (!isClosed) onMessage(message.toString())
    }

    private fun runOnWebViewThread(block: () -> Unit) {
        if (isOnWebViewThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                block()
            }
        }
    }

    private fun runOnWebViewThreadBlocking(block: () -> Unit) {
        if (isOnWebViewThread) {
            block()
        } else {
            dispatch_sync(dispatch_get_main_queue()) {
                block()
            }
        }
    }
}

private class AppleMessageHandler(
    private val webView: AppleJsWebView,
) : NSObject(),
    WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        (didReceiveScriptMessage.body as? NSString)?.let { message ->
            webView.receiveMessage(message)
        }
    }
}
