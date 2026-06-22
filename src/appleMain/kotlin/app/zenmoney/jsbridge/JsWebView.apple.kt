package app.zenmoney.jsbridge

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
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
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

@OptIn(ExperimentalForeignApi::class)
@Suppress("FunctionName")
fun JsWebViewContext(webView: WKWebView): JsWebViewContext =
    JsWebViewContext {
        AppleJsWebView(webView)
    }

@OptIn(ExperimentalForeignApi::class)
@Suppress("FunctionName")
fun JsWebViewContext(configuration: WKWebViewConfiguration): JsWebViewContext =
    JsWebViewContext {
        AppleJsWebView(createWebView(configuration))
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun createJsWebView(): JsWebView = AppleJsWebView(createWebView())

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

private class AppleJsWebView(
    private val webView: WKWebView,
) : JsWebView {
    private val messageQueue = dispatch_queue_create("app.zenmoney.jsbridge.messages", null)
    private val messageHandler = AppleMessageHandler(this)

    override var onMessage: (String) -> Unit = {}

    init {
        runOnWebViewThreadBlocking {
            webView.configuration.userContentController.addScriptMessageHandler(messageHandler, JS_WEB_VIEW_IOS_HANDLER)
        }
    }

    private val isOnWebViewThread: Boolean
        get() = NSThread.isMainThread()

    override fun close() {
        runOnWebViewThread {
            webView.configuration.userContentController.removeScriptMessageHandlerForName(JS_WEB_VIEW_IOS_HANDLER)
            webView.stopLoading()
        }
    }

    override fun evaluateJavaScript(script: String) {
        runOnWebViewThread {
            webView.evaluateJavaScript(script, null)
        }
    }

    fun receiveMessage(message: NSString) {
        dispatch_async(messageQueue) {
            onMessage(message.toString())
        }
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
