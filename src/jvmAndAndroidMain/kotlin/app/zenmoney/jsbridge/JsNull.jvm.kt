package app.zenmoney.jsbridge

import com.caoccao.javet.values.V8Value

actual sealed interface JsNull : JsValue

internal open class JsNullImpl(
    context: JsContext,
    v8Value: V8Value,
) : JsValueImpl(context, v8Value),
    JsNull {
    override fun equals(other: Any?): Boolean = other is JsNull && context === other.context

    override fun hashCode(): Int = 0
}
