package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8TypedArray

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
    v8Value: V8TypedArray,
) : JsValueImpl(context, v8Value),
    JsUint8Array {
    override val size: Int
        get() = (v8Value as V8TypedArray).length()

    override fun toByteArray(): ByteArray = (v8Value as V8TypedArray).getBytes(0, size)
}
