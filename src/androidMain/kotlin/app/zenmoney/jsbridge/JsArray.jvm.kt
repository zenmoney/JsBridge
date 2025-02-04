package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Value

actual sealed interface JsArray : JsObject {
    actual val size: Int

    actual operator fun get(index: Int): JsValue
}

internal class JsArrayImpl(
    context: JsContext,
    v8Value: V8Array,
) : JsObjectImpl(context, v8Value),
    JsArray {
    val v8Array: V8Array
        get() = v8Value as V8Array

    override val size: Int
        get() = v8Array.length()

    override fun get(index: Int): JsValue = JsValue(context, v8Array.get(index))
}

actual fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray =
    JsArrayImpl(
        context,
        V8Array(context.v8Runtime).apply {
            value.forEach { value ->
                if (value == context.NULL) {
                    pushNull()
                    return@forEach
                }
                if (value == context.UNDEFINED) {
                    pushUndefined()
                    return@forEach
                }
                val valueV8Value = (value as? JsValueImpl)?.v8Value as? V8Value
                if (valueV8Value != null) {
                    push(valueV8Value)
                    return@forEach
                }
                when (value) {
                    is JsBoolean -> push(value.toBoolean())
                    is JsNumber -> push(value.toNumber().toDouble())
                    is JsString -> push(value.toString())
                    else -> TODO()
                }
            }
        },
    )
