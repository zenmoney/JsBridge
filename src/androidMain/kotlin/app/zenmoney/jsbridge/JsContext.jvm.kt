package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Value
import java.util.Collections
import java.util.IdentityHashMap

actual class JsContext : AutoCloseable {
    private var lastException: Throwable? = null
    private var callFunction: JsFunction? = null

    internal val v8Runtime: V8 = V8.createV8Runtime()
    private val v8Values: MutableSet<V8Value> = Collections.newSetFromMap(IdentityHashMap())

    actual val globalObject: JsObject = JsObjectImpl(this, v8Runtime.executeObjectScript("this")).also { registerValue(it) }
    actual val NULL: JsValue = JsValueImpl(this, null)
    actual val UNDEFINED: JsValue = JsValueImpl(this, v8Runtime.executeScript("undefined")).also { registerValue(it) }

    internal val cachedValues =
        listOf(
            NULL,
            UNDEFINED,
            globalObject,
        )

    actual var getPlainValueOf: (JsValue) -> Any? = { it.toBasicPlainValue() }

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
    actual fun evaluateScript(script: String): JsValue {
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
                v8Value.close()
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
                v8Value.close()
            }
        }
        return JsValue(this, v8Value)
    }

    private inline fun throwExceptionIfNeeded(ifException: () -> Unit) {
        val arr =
            JsValue(
                this,
                v8Runtime.executeScript("[appZenmoneyError, appZenmoneyErrorOccurred]"),
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

    internal fun registerValue(value: JsValueImpl) {
        if (value.v8Value is V8Value) {
            v8Values.add(value.v8Value)
        }
    }

    internal fun closeValue(value: JsValueImpl) {
        if (value.v8Value is V8Value && value !in cachedValues) {
            value.v8Value.close()
        }
    }

    actual override fun close() {
        v8Values.forEach { it.close() }
        v8Values.clear()
        jsGetTime.close()
        jsTypeOf.close()
        v8Runtime.close()
    }
}
