package app.zenmoney.jsbridge

import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.values.V8Value

actual class JsContext : AutoCloseable {
    private var lastException: Exception? = null

    internal val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()

    private var callFunction: JsFunction? = null
    private var callbackContextIndex = 0
    private var callbackContextHandles = LongArray(10) { 0 }

    actual val globalObject: JsObject = JsObjectImpl(this, v8Runtime.getExecutor("this").execute())
    actual val NULL: JsValue = JsValueImpl(this, v8Runtime.createV8ValueNull())
    actual val UNDEFINED: JsValue = JsValueImpl(this, v8Runtime.createV8ValueUndefined())

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

    internal fun registerCallbackContextHandle(handle: Long) {
        if (callbackContextHandles.size <= callbackContextIndex) {
            callbackContextHandles = callbackContextHandles.copyOf(callbackContextHandles.size * 3 / 2)
        }
        callbackContextHandles[callbackContextIndex++] = handle
    }

    actual override fun close() {
        callbackContextHandles.forEach { v8Runtime.removeCallbackContext(it) }
        callbackContextIndex = 0
        v8Runtime.close()
    }
}
