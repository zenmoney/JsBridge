package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.valueAtIndex
import platform.JavaScriptCore.valueForProperty

actual sealed interface JsArray : JsObject {
    actual val size: Int

    actual operator fun get(index: Int): JsValue
}

internal class JsArrayImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsArray {
    override val size: Int
        get() = jsValue.valueForProperty("length")?.toInt32() ?: 0

    override fun get(index: Int): JsValue = JsValue(context, jsValue.valueAtIndex(index.toULong()))
}

actual fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray =
    JsArrayImpl(
        context,
        JSValue.valueWithObject(
            value.map { (it as JsValueImpl).jsValue },
            context.jsContext,
        )!!,
    )
