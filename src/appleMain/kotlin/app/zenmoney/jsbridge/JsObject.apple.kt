package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.setObject

actual sealed interface JsObject : JsValue {
    actual operator fun set(
        key: String,
        value: JsValue?,
    )
}

internal actual fun JsObject.getValue(key: String): JsValue =
    context.createValue((this as JsObjectImpl).jsValue.objectForKeyedSubscript(key))

internal open class JsObjectImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsObject {
    override fun set(
        key: String,
        value: JsValue?,
    ) {
        jsValue.setObject(((value ?: context.NULL) as JsValueImpl).jsValue, key)
    }
}
