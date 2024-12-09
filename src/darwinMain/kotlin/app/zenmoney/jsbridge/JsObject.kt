package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.setValue
import platform.JavaScriptCore.valueForProperty

actual sealed interface JsObject : JsValue {
    actual operator fun get(key: String): JsValue

    actual operator fun set(
        key: String,
        value: JsValue?,
    )
}

actual fun JsObject(context: JsContext): JsObject = JsObjectImpl(context, JSValue.valueWithNewObjectInContext(context.jsContext)!!)

internal open class JsObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsObject {
    override fun get(key: String): JsValue = JsValue(context, jsValue.valueForProperty(key))

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        jsValue.setValue(((value ?: context.NULL) as JsValueImpl).jsValue, key)
    }
}
