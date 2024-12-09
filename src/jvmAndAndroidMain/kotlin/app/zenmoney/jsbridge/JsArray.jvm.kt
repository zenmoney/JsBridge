package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueArray

actual sealed interface JsArray : JsObject {
    actual val size: Int

    actual operator fun get(index: Int): JsValue
}

internal class JsArrayImpl(
    context: JsContext,
    v8Value: V8ValueArray,
) : JsObjectImpl(context, v8Value),
    JsArray {
    val v8Array: V8ValueArray
        get() = v8Value as V8ValueArray

    override val size: Int
        get() = v8Array.length

    override fun get(index: Int): JsValue = JsValue(context, v8Array.get(index))
}

actual fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray =
    JsArrayImpl(
        context,
        context.v8Runtime.createV8ValueArray().apply {
            if (value is List) {
                push(*Array(value.size) { (value[it] as JsValueImpl).v8Value })
            } else {
                value.forEach {
                    push((it as JsValueImpl).v8Value)
                }
            }
        },
    ).also { context.registerValue(it) }
