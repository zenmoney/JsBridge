package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueArray

actual sealed interface JsArray : JsObject {
    actual val size: Int
}

internal actual fun JsArray.getValue(index: Int): JsValue = context.createValue((this as JsArrayImpl).v8Array.get(index))

internal class JsArrayImpl(
    context: JsContext,
    v8Value: V8ValueArray,
) : JsObjectImpl(context, v8Value),
    JsArray {
    val v8Array: V8ValueArray
        get() = v8Value as V8ValueArray

    override val size: Int
        get() = v8Array.length
}
