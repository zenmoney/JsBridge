package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

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
): JsStringObject = context.evaluateScript("new String(\"${value.replace("\"", "\\\"")}\")") as JsStringObject

internal class JsStringImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsString

internal class JsStringObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsStringObject {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean = other is JsStringObjectImpl && toString() == other.toString()
}
