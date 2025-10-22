package app.zenmoney.jsbridge

actual sealed interface JsNull : JsValue

internal open class JsNullImpl(
    context: JsContext,
    v8Value: Any?,
) : JsValueImpl(context, v8Value),
    JsNull
