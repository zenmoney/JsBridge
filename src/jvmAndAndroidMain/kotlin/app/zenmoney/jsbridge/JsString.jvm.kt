package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueStringObject

actual sealed interface JsString : JsValue

actual sealed interface JsStringObject :
    JsObject,
    JsString

internal actual fun JsString(
    context: JsContext,
    value: String,
): JsString = JsValue(context, value) as JsString

internal actual fun JsStringObject(
    context: JsContext,
    value: String,
): JsStringObject =
    JsStringObjectImpl(
        context,
        context.v8Runtime.createV8ValueStringObject(value),
    ).also { context.registerValue(it) }

internal class JsStringImpl(
    context: JsContext,
    v8Value: V8ValueString,
) : JsValueImpl(context, v8Value),
    JsString {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is JsStringImpl && toString() == other.toString()

    override fun toString(): String = v8Value.toString()
}

internal class JsStringObjectImpl(
    context: JsContext,
    v8Value: V8ValueStringObject,
) : JsObjectImpl(context, v8Value),
    JsStringObject {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is JsStringObjectImpl && toString() == other.toString()

    override fun toString(): String = v8Value.toString()
}
