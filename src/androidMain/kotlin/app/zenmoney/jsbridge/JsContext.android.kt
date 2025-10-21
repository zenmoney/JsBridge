package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Value

actual class JsContext : AutoCloseable {
    internal actual val core = JsContextCore()

    private var lastException: Throwable? = null

    internal val v8Runtime: V8 = V8.createV8Runtime()

    actual val globalThis: JsObject = JsObjectImpl(this, v8Runtime.executeObjectScript("this")).also { registerValue(it) }
    actual val NULL: JsValue = JsValueImpl(this, null)
    actual val UNDEFINED: JsValue = JsValueImpl(this, v8Runtime.executeScript("undefined")).also { registerValue(it) }

    internal val cachedValues =
        listOf(
            NULL,
            UNDEFINED,
            globalThis,
        )

    actual var getPlainValueOf: (JsValue) -> Any? = { it.toBasicPlainValue() }

    private var callFunction: JsFunction? = null
    internal val callFunctionAsConstructor: JsFunction =
        evaluateScript(
            """
            function appZenmoneyCallFunctionAsConstructor(f, ...args) {
                return new f(...args);
            };
            appZenmoneyCallFunctionAsConstructor;
            """.trimIndent(),
        ) as JsFunction
    internal val createBooleanObject: JsFunction =
        evaluateScript(
            """
            function appZenmoneyBoolean(value) {
                return new Boolean(value);
            };
            appZenmoneyBoolean
            """.trimIndent(),
        ) as JsFunction
    internal val createDate: JsFunction =
        evaluateScript(
            """
            function appZenmoneyDate(value) {
                return new Date(value);
            };
            appZenmoneyDate
            """.trimIndent(),
        ) as JsFunction
    internal val createNumberObject: JsFunction =
        evaluateScript(
            """
            function appZenmoneyNumber(value) {
                return new Number(value);
            };
            appZenmoneyNumber
            """.trimIndent(),
        ) as JsFunction
    internal val createPromise: JsFunction =
        evaluateScript(
            """
            function appZenmoneyPromise(executor) {
                return new Promise(executor);
            };
            appZenmoneyPromise;
            """.trimIndent(),
        ) as JsFunction
    internal val createStringObject: JsFunction =
        evaluateScript(
            """
            function appZenmoneyString(value) {
                return new String(value);
            };
            appZenmoneyString
            """.trimIndent(),
        ) as JsFunction

    internal val jsGetTime: V8Function =
        v8Runtime.executeScript(
            """
            function appZenmoneyGetTime() {
                return this.getTime();
            };
            appZenmoneyGetTime
            """.trimIndent(),
        ) as V8Function
    internal val jsTypeOf: V8Function =
        v8Runtime.executeScript(
            """
            function appZenmoneyTypeOf() {
                const value = this;
                if (value instanceof Boolean) {
                    return 'boolean';
                } else if (value instanceof Date) {
                    return 'date';
                } else if (value instanceof Number) {
                    return 'number';
                } else if (value instanceof String) {
                    return 'string';
                } else if (value instanceof Promise || typeof value === 'object' && value && typeof value.then === 'function') {
                    return 'Promise';
                }
                return typeof value;
            };
            appZenmoneyTypeOf;
            """.trimIndent(),
        ) as V8Function

    @Throws(JsException::class)
    internal actual fun evaluateScript(script: String): JsValue {
        val v8Value =
            try {
                v8Runtime.executeScript(
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
                )
            } catch (e: Exception) {
                throw JsException(e.message ?: e.toString(), e, emptyMap())
            }
        throwExceptionIfNeeded {
            if (v8Value is V8Value) {
                v8Value.closeQuietly()
            }
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
        val fullArgs =
            JsArray(
                this,
                listOf(
                    f,
                    thiz,
                    *args.toTypedArray(),
                ),
            )
        val v8Value: Any =
            (callFunction as JsFunctionImpl).v8Function.call(
                null,
                (fullArgs as JsArrayImpl).v8Array,
            )
        fullArgs.close()
        throwExceptionIfNeeded {
            if (v8Value is V8Value) {
                v8Value.closeQuietly()
            }
        }
        return JsValue(this, v8Value)
    }

    private inline fun throwExceptionIfNeeded(ifException: () -> Unit) {
        jsScope(this) {
            val arr =
                JsValue(
                    context,
                    v8Runtime.executeScript("[appZenmoneyError, appZenmoneyErrorOccurred]"),
                ).autoClose() as JsArray
            val hasError = arr[1]
            if (hasError is JsBoolean && hasError.toBoolean()) {
                val error = arr[0]
                val e = createJsException(error)
                ifException()
                throw e
            }
        }
    }

    internal fun createJsException(e: JsValue): JsException {
        val message =
            if (e is JsString) {
                "Error: $e"
            } else {
                e.toString()
            }
        return JsException(
            message,
            lastException
                ?.takeIf {
                    "Error: ${it.message}" == message || message == "Error: Unhandled Java Exception"
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

    internal fun registerValue(value: JsValue) {
        core.addValue(value)
    }

    internal actual fun closeValue(value: JsValue) {
        ((value as JsValueImpl).v8Value as? V8Value)?.close()
        core.removeValue(value)
    }

    actual override fun close() {
        core.close()
        jsGetTime.closeQuietly()
        jsTypeOf.closeQuietly()
        v8Runtime.close()
    }
}

internal fun V8Value.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
