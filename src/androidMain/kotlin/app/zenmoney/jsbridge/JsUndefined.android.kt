package app.zenmoney.jsbridge

actual sealed interface JsUndefined : JsValue

internal open class JsUndefinedImpl(
    context: JsContext,
    v8Value: Any?,
) : JsValueImpl(context, v8Value),
    JsUndefined {
    override fun equals(other: Any?): Boolean = other is JsUndefined && context === other.context

    override fun hashCode(): Int = 1
}
