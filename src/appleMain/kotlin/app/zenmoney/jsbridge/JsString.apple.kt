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
    JsString {
    override fun hashCode(): Int = toString().hashCode()

    override fun equals(other: Any?): Boolean =
        other is JsString &&
            other !is JsObject &&
            context === other.context &&
            toString() == other.toString()
}

internal class JsStringObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsStringObject
