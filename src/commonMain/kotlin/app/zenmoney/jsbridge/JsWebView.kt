package app.zenmoney.jsbridge

internal interface JsWebView : AutoCloseable {
    var onMessage: (String) -> Unit

    fun initializeRuntime() {
        evaluateJavaScript(jsWebViewRuntimeScript)
    }

    fun disposeRuntime() {
        evaluateJavaScript(jsWebViewDisposeRuntimeScript)
    }

    fun evaluateJavaScript(script: String)

    /** Reports submission or native execution failures that cannot arrive through the page's message bridge. */
    fun evaluateJavaScript(
        script: String,
        onFailure: (Throwable) -> Unit,
    ) {
        evaluateJavaScript(script)
    }
}

/**
 * The expected JavaScript bridge session is unexpectedly unavailable while the WebView adapter is open.
 * This does not imply that the page or its JavaScript execution context was destroyed.
 * The detached [JsWebViewContext] is closed before its blocking request throws this exception.
 */
class JsWebViewContextDetachedException internal constructor() :
    IllegalStateException("JsWebView context is detached from its JavaScript bridge session")

internal expect fun createJsWebView(contextId: Int): JsWebView

internal expect class JsWebViewBlockingRequest<T>() {
    fun complete(result: Result<T>)

    fun await(debug: String): T
}
