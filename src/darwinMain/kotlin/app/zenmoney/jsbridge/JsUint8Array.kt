package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.valueAtIndex

actual sealed interface JsUint8Array : JsValue {
    actual val size: Int

    actual fun toByteArray(): ByteArray
}

actual fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array =
    JsUint8ArrayImpl(
        context,
        context.jsUint8Array.constructWithArguments(
            listOf(
                JSValue.valueWithObject(value.asList(), context.jsContext)!!,
            ),
        )!!,
    )

internal class JsUint8ArrayImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsUint8Array {
    override val size: Int
        get() = jsValue.objectForKeyedSubscript("byteLength")?.toInt32() ?: 0

    override fun toByteArray(): ByteArray =
        ByteArray(size) {
            jsValue.valueAtIndex(it.toULong())!!.toInt32().toByte()
        }
}
