package app.zenmoney.jsbridge

import androidx.collection.MutableScatterSet
import androidx.collection.mutableScatterSetOf
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.values.V8Value

actual class JsContext : AutoCloseable {
    private var lastException: Exception? = null

    internal val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()
    internal val isClosed: Boolean
        get() = v8Values == null

    private var v8Values: MutableScatterSet<V8Value>? = mutableScatterSetOf()
    private var callFunction: JsFunction? = null

    actual val globalObject: JsObject = JsObjectImpl(this, v8Runtime.getExecutor("this").execute()).also { registerValue(it) }
    actual val NULL: JsValue = JsValueImpl(this, v8Runtime.createV8ValueNull()).also { registerValue(it) }
    actual val UNDEFINED: JsValue = JsValueImpl(this, v8Runtime.createV8ValueUndefined()).also { registerValue(it) }

    actual var getPlainValueOf: (JsValue) -> Any? = { it.toBasicPlainValue() }

    @Throws(JsException::class)
    actual fun evaluateScript(script: String): JsValue {
        val v8Value: V8Value =
            try {
                v8Runtime
                    .getExecutor(
                        """
                        var appZenmoneyError = undefined;
                        var appZenmoneyErrorOccurred = undefined;
                        try {
                            $script
                        } catch (e) {
                            appZenmoneyError = e;
                            appZenmoneyErrorOccurred = true;
                        }
                        """.trimIndent(),
                    ).execute()
            } catch (e: Exception) {
                throw JsException(e.message ?: e.toString(), e, emptyMap())
            }
        throwExceptionIfNeeded {
            v8Value.close()
        }
        return JsValue(this, v8Value)
    }

    internal fun callFunction(
        f: JsFunctionImpl,
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue {
        if (callFunction == null) {
            callFunction =
                evaluateScript(
                    """
                    function appZenmoneyCallFunction() {
                        var f = arguments[0];
                        var thiz = arguments[1];
                        var args = Array.prototype.slice.call(arguments, 2);
                        try {
                            appZenmoneyError = undefined;
                            appZenmoneyErrorOccurred = undefined;
                            return f.apply(thiz, args);
                        } catch (e) {
                            appZenmoneyError = e;
                            appZenmoneyErrorOccurred = true;
                        }
                    };
                    appZenmoneyCallFunction;
                    """.trimIndent(),
                ) as JsFunction
        }
        val v8Value: V8Value =
            (callFunction as JsFunctionImpl).v8Function.call(
                v8Runtime.globalObject,
                *Array(args.size + 2) {
                    when (it) {
                        0 -> f.v8Function
                        1 -> (thiz as JsValueImpl).v8Value
                        else -> (args[it - 2] as JsValueImpl).v8Value
                    }
                },
            )
        throwExceptionIfNeeded {
            v8Value.close()
        }
        return JsValue(this, v8Value)
    }

    private inline fun throwExceptionIfNeeded(ifException: () -> Unit) {
        val arr =
            JsValue(
                this,
                v8Runtime.getExecutor("[appZenmoneyError, appZenmoneyErrorOccurred]").execute(),
            ) as JsArray
        val hasError = arr[1]
        if (hasError is JsBoolean && hasError.toBoolean()) {
            val error = arr[0]
            val message = error.toString()
            val e =
                JsException(
                    message,
                    lastException?.takeIf { "Error: ${it.message}" == message },
                    (if (error is JsObject) error.toPlainMap() else null) ?: emptyMap(),
                )
            lastException = null
            hasError.close()
            error.close()
            arr.close()
            ifException()
            throw e
        }
        hasError.close()
        arr.close()
    }

    internal fun throwExceptionToJs(e: Exception): Nothing {
        lastException = e
        throw e
    }

    internal fun registerValue(value: JsValueImpl) {
        v8Values?.add(value.v8Value)
    }

    internal fun closeValue(value: JsValueImpl) {
        if (value != globalObject) {
            value.v8Value.close()
            v8Values?.remove(value.v8Value)
        }
    }

    override fun close() {
        if (isClosed) {
            return
        }
        val v = v8Values
        v8Values = null
        v?.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                throw Exception("Error while closing value: ${it::class}", e)
            }
        }
        v8Runtime.close()
    }
}
