package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8ArrayBuffer
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8TypedArray
import com.eclipsesource.v8.V8Value
import java.nio.ByteBuffer

actual sealed interface JsValue : AutoCloseable {
    actual val context: JsContext
}

private fun areV8ValuesStrictEqual(
    a: V8Value,
    b: V8Value,
): Boolean = a.isUndefined == b.isUndefined && (a.isUndefined || a.strictEquals(b))

internal open class JsValueImpl(
    final override val context: JsContext,
    val v8Value: Any?,
) : JsValue {
    init {
        if (v8Value is V8Value && !v8Value.isUndefined) {
            v8Value.setWeak()
        }
    }

    override fun close() {
        if (v8Value is V8Value) {
            v8Value.close()
        }
    }

    override fun toString(): String = v8Value.toString()

    override fun hashCode(): Int = v8Value.hashCode()

    override fun equals(other: Any?): Boolean =
        other is JsValueImpl &&
            context == other.context &&
            (
                if (other.v8Value is V8Value && v8Value is V8Value) {
                    areV8ValuesStrictEqual(v8Value, other.v8Value)
                } else {
                    v8Value == other.v8Value
                }
            )
}

internal fun JsValue(
    context: JsContext,
    value: Any?,
): JsValue {
    if (value is V8Value) {
        if (value.isUndefined) {
            return context.UNDEFINED
        }
        if (value.runtime != context.v8Runtime) {
            throw IllegalArgumentException("value runtime must match the JsContext runtime")
        }
        if (value === (context.globalObject as JsValueImpl).v8Value) {
            return context.globalObject
        }
        if (areV8ValuesStrictEqual(value, (context.globalObject as JsObjectImpl).v8Object)) {
            value.close()
            return context.globalObject
        }
        return when (value) {
            is V8TypedArray ->
                if (value.type == V8TypedArray.UNSIGNED_INT_8_ARRAY) {
                    JsUint8ArrayImpl(context, value)
                } else {
                    JsObjectImpl(context, value)
                }
            is V8Array -> JsArrayImpl(context, value)
            is V8Function -> JsFunctionImpl(context, value)
            is V8Object -> {
                val type =
                    context.jsTypeOf.call(
                        context.globalObject.v8Object,
                        V8Array(context.v8Runtime).apply { push(value) },
                    ) as String
                when (type) {
                    "boolean" -> JsBooleanObjectImpl(context, value)
                    "date" -> JsDateImpl(context, value)
                    "number" -> JsNumberObjectImpl(context, value)
                    "string" -> JsStringObjectImpl(context, value)
                    else -> JsObjectImpl(context, value)
                }
            }
            else -> TODO()
        }
    }
    return when (value) {
        null -> context.NULL
        is JsValue -> value
        is Boolean -> JsBooleanImpl(context, value)
        is Number -> JsNumberImpl(context, value.toDouble())
        is String -> JsStringImpl(context, value)
        is ByteArray -> {
            val buffer = V8ArrayBuffer(context.v8Runtime, ByteBuffer.allocateDirect(value.size).apply { put(value) })
            JsUint8ArrayImpl(
                context,
                V8TypedArray(
                    context.v8Runtime,
                    buffer,
                    V8Value.UNSIGNED_INT_8_ARRAY,
                    0,
                    value.size,
                ),
            ).also { buffer.close() }
        }
        else -> throw IllegalArgumentException("unexpected value ${value::class} $value")
    }
}
