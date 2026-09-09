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
}

internal expect fun createJsWebView(contextId: Int): JsWebView

internal expect class JsWebViewBlockingRequest<T>() {
    fun complete(result: Result<T>)

    fun await(debug: String): T
}
