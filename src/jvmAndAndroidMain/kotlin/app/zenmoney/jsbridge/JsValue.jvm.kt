package app.zenmoney.jsbridge

import com.caoccao.javet.enums.V8ValueReferenceType
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueBigInteger
import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.primitive.V8ValueDouble
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueLong
import com.caoccao.javet.values.primitive.V8ValueNull
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.primitive.V8ValueUndefined
import com.caoccao.javet.values.primitive.V8ValueZonedDateTime
import com.caoccao.javet.values.reference.V8ValueArray
import com.caoccao.javet.values.reference.V8ValueBooleanObject
import com.caoccao.javet.values.reference.V8ValueDoubleObject
import com.caoccao.javet.values.reference.V8ValueFunction
import com.caoccao.javet.values.reference.V8ValueIntegerObject
import com.caoccao.javet.values.reference.V8ValueLongObject
import com.caoccao.javet.values.reference.V8ValueObject
import com.caoccao.javet.values.reference.V8ValuePromise
import com.caoccao.javet.values.reference.V8ValueStringObject
import com.caoccao.javet.values.reference.V8ValueTypedArray

actual sealed interface JsValue : AutoCloseable {
    actual val context: JsContext
}

internal open class JsValueImpl(
    final override val context: JsContext,
    val v8Value: V8Value,
) : JsValue {
    override fun close() {
        context.closeValue(this)
    }

    override fun toString(): String = v8Value.toString()

    override fun hashCode(): Int = v8Value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsValueImpl && context == other.context && v8Value.strictEquals(other.v8Value)
}

internal fun JsValue(
    context: JsContext,
    value: Any?,
): JsValue {
    if (value == null) {
        return context.NULL
    }
    if (value is V8Value) {
        if (value.v8Runtime != context.v8Runtime) {
            throw IllegalArgumentException("value runtime must match the JsContext runtime")
        }
        if (value is V8ValueNull) {
            return context.NULL
        }
        if (value is V8ValueUndefined) {
            return context.UNDEFINED
        }
        if (value is V8ValueObject &&
            value !== (context.globalObject as JsValueImpl).v8Value &&
            value.strictEquals((context.globalObject as JsValueImpl).v8Value)
        ) {
            value.closeQuietly()
            return context.globalObject
        }
    }
    return when (value) {
        is Boolean -> JsBooleanImpl(context, context.v8Runtime.createV8ValueBoolean(value))
        is Int -> JsNumberImpl(context, context.v8Runtime.createV8ValueInteger(value))
        is Long -> JsNumberImpl(context, context.v8Runtime.createV8ValueLong(value))
        is Number -> JsNumberImpl(context, context.v8Runtime.createV8ValueDouble(value.toDouble()))
        is String -> JsStringImpl(context, context.v8Runtime.createV8ValueString(value))
        is ByteArray ->
            JsUint8ArrayImpl(
                context,
                context.v8Runtime.createV8ValueTypedArray(V8ValueReferenceType.Uint8Array, value.size).apply { fromBytes(value) },
            )
        is V8ValueBoolean -> JsBooleanImpl(context, value)
        is V8ValueBooleanObject -> JsBooleanObjectImpl(context, value)
        is V8ValueBigInteger -> JsNumberImpl(context, value)
        is V8ValueInteger -> JsNumberImpl(context, value)
        is V8ValueIntegerObject -> JsNumberObjectImpl(context, value)
        is V8ValueLong -> JsNumberImpl(context, value)
        is V8ValueLongObject -> JsNumberObjectImpl(context, value)
        is V8ValueDouble -> JsNumberImpl(context, value)
        is V8ValueDoubleObject -> JsNumberObjectImpl(context, value)
        is V8ValueString -> JsStringImpl(context, value)
        is V8ValueStringObject -> JsStringObjectImpl(context, value)
        is V8ValueZonedDateTime -> JsDateImpl(context, value)
        is V8ValueTypedArray ->
            if (value.type == V8ValueReferenceType.Uint8Array) {
                JsUint8ArrayImpl(context, value)
            } else {
                JsObjectImpl(context, value)
            }
        is V8ValueArray -> JsArrayImpl(context, value)
        is V8ValueFunction -> JsFunctionImpl(context, value)
        is V8ValuePromise -> JsPromiseImpl(context, value)
        is V8ValueObject ->
            if (value.has("then") && value.get<V8Value>("then").use { it is V8ValueFunction }) {
                JsPromiseImpl(context, value)
            } else {
                JsObjectImpl(context, value)
            }
        else -> TODO()
    }.also { context.registerValue(it) }
}
