package app.zenmoney.jsbridge

internal interface JsWebView : AutoCloseable {
    var onMessage: (String) -> Unit

    fun evaluateJavaScript(script: String)
}

internal expect fun createJsWebView(): JsWebView

internal expect class JsWebViewBlockingRequest<T>() {
    fun complete(result: Result<T>)

    fun await(debug: String): T
}
