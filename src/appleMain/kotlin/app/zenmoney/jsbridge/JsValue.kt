package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsValue : AutoCloseable {
    actual val context: JsContext
}

internal open class JsValueImpl(
    final override val context: JsContext,
    val jsValue: JSValue,
) : JsValue {
    override fun close() {
    }

    override fun toString(): String = jsValue.toString_() ?: jsValue.toString()

    override fun hashCode(): Int = jsValue.hashCode()

    override fun equals(other: Any?): Boolean = other is JsValueImpl && context == other.context && jsValue.isEqualToObject(other.jsValue)
}

internal fun JsValue(
    context: JsContext,
    value: Any?,
): JsValue {
    if (value is JSValue) {
        if (value.context != context.jsContext) {
            throw IllegalArgumentException("value runtime must match the JsContext runtime")
        }
        if (value.isEqualToObject((context.globalObject as JsValueImpl).jsValue)) {
            return context.globalObject
        }
    }
    return when (value) {
        null -> context.NULL
        is JsValue -> value
        is Boolean -> JsBooleanImpl(context, JSValue.valueWithBool(value, context.jsContext)!!)
        is Int -> JsNumberImpl(context, JSValue.valueWithInt32(value, context.jsContext)!!)
        is Number -> JsNumberImpl(context, JSValue.valueWithDouble(value.toDouble(), context.jsContext)!!)
        is String -> JsStringImpl(context, JSValue.valueWithObject(value, context.jsContext)!!)
        is ByteArray -> JsUint8Array(context, value)
        is JSValue ->
            when {
                value.isNull -> context.NULL
                value.isUndefined -> context.UNDEFINED
                value.isBoolean -> JsBooleanImpl(context, value)
                value.isNumber -> JsNumberImpl(context, value)
                value.isString -> JsStringImpl(context, value)
                value.isDate -> JsDateImpl(context, value)
                value.isArray -> JsArrayImpl(context, value)
                else -> {
                    val type = context.jsTypeOf.callWithArguments(listOf(value))!!.toString()
                    when {
                        type == "boolean" -> JsBooleanObjectImpl(context, value)
                        type == "number" -> JsNumberObjectImpl(context, value)
                        type == "string" -> JsStringObjectImpl(context, value)
                        type == "function" -> JsFunctionImpl(context, value)
                        type == "Uint8Array" -> JsUint8ArrayImpl(context, value)
                        type == "Promise" -> JsPromiseImpl(context, value)
                        value.isObject -> JsObjectImpl(context, value)
                        else -> TODO()
                    }
                }
            }
        else -> TODO()
    }
}
