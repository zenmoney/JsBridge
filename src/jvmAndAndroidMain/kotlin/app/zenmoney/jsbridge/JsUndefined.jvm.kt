package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueUndefined

actual sealed interface JsUndefined : JsValue

internal open class JsUndefinedImpl(
    context: JsContext,
    v8Value: V8ValueUndefined,
) : JsValueImpl(context, v8Value),
    JsUndefined {
    override fun equals(other: Any?): Boolean = other is JsUndefined && context === other.context

    override fun hashCode(): Int = 1
}
