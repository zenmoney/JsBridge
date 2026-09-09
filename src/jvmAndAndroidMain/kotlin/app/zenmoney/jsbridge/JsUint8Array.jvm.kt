package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueTypedArray

actual sealed interface JsUint8Array : JsObject {
    actual val size: Int

    actual fun toByteArray(): ByteArray
}

internal class JsUint8ArrayImpl(
    context: JsContext,
    v8Value: V8ValueTypedArray,
) : JsObjectImpl(context, v8Value),
    JsUint8Array {
    override val size: Int
        get() = (v8Value as V8ValueTypedArray).byteLength

    override fun toByteArray(): ByteArray {
        val array = v8Value as V8ValueTypedArray
        val result = ByteArray(array.byteLength)
        if (result.isEmpty()) return result
        // Javet reads these properties through JavaScript. Finish those reads before obtaining
        // the direct buffer: a user-defined getter can change the backing store.
        val byteOffset = array.byteOffset
        array.buffer.use { buffer ->
            val bytes = buffer.byteBuffer.duplicate()
            bytes.position(byteOffset)
            bytes.get(result)
        }
        return result
    }
}
