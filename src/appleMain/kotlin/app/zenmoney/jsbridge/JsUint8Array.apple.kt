package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.valueAtIndex

actual sealed interface JsUint8Array : JsObject {
    actual val size: Int

    actual fun toByteArray(): ByteArray
}

internal class JsUint8ArrayImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsUint8Array {
    override val size: Int
        get() = jsValue.objectForKeyedSubscript("byteLength")?.toInt32() ?: 0

    override fun toByteArray(): ByteArray =
        ByteArray(size) {
            jsValue.valueAtIndex(it.toULong())!!.toInt32().toByte()
        }
}
