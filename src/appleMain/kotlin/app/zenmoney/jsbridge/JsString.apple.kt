package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsString : JsValue

actual sealed interface JsStringObject :
    JsObject,
    JsString

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
