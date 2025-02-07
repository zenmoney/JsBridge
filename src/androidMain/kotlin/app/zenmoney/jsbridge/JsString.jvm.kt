package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsString : JsValue

actual sealed interface JsStringObject :
    JsObject,
    JsString

actual fun JsString(
    context: JsContext,
    value: String,
): JsString = JsValue(context, value) as JsString

actual fun JsStringObject(
    context: JsContext,
    value: String,
): JsStringObject = context.createStringObject.apply(context.globalObject, listOf(JsString(context, value))) as JsStringObject

internal class JsStringImpl(
    context: JsContext,
    v8Value: String,
) : JsValueImpl(context, v8Value),
    JsString {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is JsStringImpl && toString() == other.toString()

    override fun toString(): String = v8Value.toString()
}

internal class JsStringObjectImpl(
    context: JsContext,
    v8Value: V8Object,
) : JsObjectImpl(context, v8Value),
    JsStringObject {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is JsStringObjectImpl && toString() == other.toString()

    override fun toString(): String = v8Value.toString()
}
