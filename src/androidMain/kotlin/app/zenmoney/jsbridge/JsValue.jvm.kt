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

internal open class JsValueImpl(
    final override val context: JsContext,
    val v8Value: Any?,
) : JsValue {
    override fun close() {
        context.closeValue(this)
    }

    override fun toString(): String = v8Value.toString()

    override fun hashCode(): Int = v8Value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsValueImpl && context == other.context && v8Value == other.v8Value
}

internal fun JsValue(
    context: JsContext,
    value: Any?,
): JsValue {
    if (value == null) {
        return context.NULL
    }
    if (value is V8Value) {
        if (!value.isUndefined && value.runtime != context.v8Runtime) {
            throw IllegalArgumentException("value runtime must match the JsContext runtime")
        }
        context.cachedValues.forEach {
            val cachedV8Value = (it as JsValueImpl).v8Value
            if (cachedV8Value == value) {
                if (value !== cachedV8Value) {
                    value.close()
                }
                return it
            }
        }
    }
    return when (value) {
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
        is V8TypedArray ->
            if (value.type == V8TypedArray.UNSIGNED_INT_8_ARRAY) {
                JsUint8ArrayImpl(context, value)
            } else {
                JsObjectImpl(context, value)
            }
        is V8Array -> JsArrayImpl(context, value)
        is V8Function -> JsFunctionImpl(context, value)
        is V8Object -> {
            val type = context.jsTypeOf.call(value, null) as String
            when (type) {
                "boolean" -> JsBooleanObjectImpl(context, value)
                "date" -> JsDateImpl(context, value)
                "number" -> JsNumberObjectImpl(context, value)
                "string" -> JsStringObjectImpl(context, value)
                "Promise" -> JsPromiseImpl(context, value)
                else -> JsObjectImpl(context, value)
            }
        }
        else -> throw IllegalArgumentException("unexpected value ${value::class} $value")
    }.also { context.registerValue(it) }
}
