package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.valueAtIndex
import platform.JavaScriptCore.valueForProperty

actual sealed interface JsUint8Array : JsValue {
    actual val size: Int

    actual fun toByteArray(): ByteArray
}

actual fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array = JsValue(context, value) as JsUint8Array

internal class JsUint8ArrayImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsUint8Array {
    override val size: Int
        get() = jsValue.valueForProperty("byteLength")?.toInt32() ?: 0

    override fun toByteArray(): ByteArray =
        ByteArray(size) {
            jsValue.valueAtIndex(it.toULong())!!.toInt32().toByte()
        }
}
