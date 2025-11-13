package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsBoolean : JsValue {
    actual fun toBoolean(): Boolean
}

actual sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

internal class JsBooleanImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsBoolean {
    override fun toBoolean(): Boolean = jsValue.toBool()
}

internal class JsBooleanObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsBooleanObject {
    override fun toBoolean(): Boolean = jsValue.toBool()
}
