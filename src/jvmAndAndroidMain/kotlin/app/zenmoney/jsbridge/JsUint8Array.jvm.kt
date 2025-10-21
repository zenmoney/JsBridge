package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueTypedArray

actual sealed interface JsUint8Array : JsObject {
    actual val size: Int

    actual fun toByteArray(): ByteArray
}

internal actual fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array = JsValue(context, value) as JsUint8Array

internal class JsUint8ArrayImpl(
    context: JsContext,
    v8Value: V8ValueTypedArray,
) : JsObjectImpl(context, v8Value),
    JsUint8Array {
    override val size: Int
        get() = (v8Value as V8ValueTypedArray).byteLength

    override fun toByteArray(): ByteArray = (v8Value as V8ValueTypedArray).toBytes()
}
