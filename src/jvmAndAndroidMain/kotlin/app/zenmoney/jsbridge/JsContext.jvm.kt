package app.zenmoney.jsbridge

import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.values.V8Value

actual class JsContext : AutoCloseable {
    private var lastException: Throwable? = null

    internal val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()
    private val v8Values = hashSetOf<V8Value>()

    private var callFunction: JsFunction? = null
    private var callbackContextIndex = 0
    private var callbackContextHandles = LongArray(10) { 0 }

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
            val e = createJsException(error)
            hasError.close()
            error.close()
            arr.close()
            ifException()
            throw e
        }
        hasError.close()
        arr.close()
    }

    internal fun createJsException(e: JsValue): JsException {
        val message = e.toString()
        return JsException(
            message,
            lastException
                ?.takeIf {
                    "Error: ${it.message}" == message || message == "Error: Uncaught JavaError in function callback"
                }?.also { lastException = null },
            (if (e is JsObject) e.toPlainMap() else null) ?: emptyMap(),
        )
    }

    internal fun createJsError(e: Throwable): JsObject {
        lastException = e
        val message = e.message?.ifBlank { null }?.replace("\"", "\\\"")
        return evaluateScript("new Error(${if (message == null) "" else "\"${message}\""})") as JsObject
    }

    internal fun throwExceptionToJs(e: Throwable): Nothing {
        lastException = e
        throw e
    }

    internal fun registerValue(value: JsValueImpl) {
        v8Values.add(value.v8Value)
    }

    internal fun closeValue(value: JsValueImpl) {
        if (value.v8Value !== (globalObject as JsValueImpl).v8Value) {
            value.v8Value.close()
            v8Values.remove(value.v8Value)
        }
    }

    internal fun registerCallbackContextHandle(handle: Long) {
        if (callbackContextHandles.size <= callbackContextIndex) {
            callbackContextHandles = callbackContextHandles.copyOf(callbackContextHandles.size * 3 / 2)
        }
        callbackContextHandles[callbackContextIndex++] = handle
    }

    actual override fun close() {
        v8Values.forEach { it.close() }
        v8Values.clear()
        callbackContextHandles.forEach { v8Runtime.removeCallbackContext(it) }
        callbackContextIndex = 0
        v8Runtime.close()
    }
}
