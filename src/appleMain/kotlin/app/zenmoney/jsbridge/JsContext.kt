package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript

actual class JsContext : AutoCloseable {
    private var lastException: Throwable? = null

    internal val jsContext = JSContext()
    internal val jsDate = jsContext.evaluateScript("Date")!!
    internal val jsDefineProperty = jsContext.evaluateScript("Object.defineProperty")!!
    internal val jsPromise = jsContext.evaluateScript("Promise")!!
    internal val jsTypeOf =
        jsContext.evaluateScript(
            """
            function appZenmoneyTypeOf(value) {
                if (value instanceof Boolean) {
                    return 'boolean';
                } else if (value instanceof Number) {
                    return 'number';
                } else if (value instanceof String) {
                    return 'string';
                } else if (value instanceof Uint8Array) {
                    return 'Uint8Array';
                } else if (value instanceof Promise || typeof value === 'object' && value && typeof value.then === 'function') {
                    return 'Promise';
                }
                return typeof value;
            };
            appZenmoneyTypeOf;
            """.trimIndent(),
        )!!
    internal val jsUint8Array = jsContext.evaluateScript("Uint8Array")!!

    private var jsCallFunction =
        jsContext.evaluateScript(
            """
            function appZenmoneyCallFunction() {
                var f = arguments[0];
                var thiz = arguments[1];
                var args = Array.prototype.slice.call(arguments, 2);
                return f.apply(thiz, args);
            };
            appZenmoneyCallFunction;
            """.trimIndent(),
        )!!
    internal val jsWrapFunction =
        jsContext.evaluateScript(
            """
            function appZenmoneyWrapFunction(f) {
                return new Proxy(f, {
                    apply(target, thisArg, args) {
                        return target.apply(thisArg, args);
                    },
                    construct(target, args, newTarget) {
                        const obj = Object.create(target.prototype);
                        const result = target.apply(obj, args);
                        return (result !== null && (typeof result === "object" || typeof result === "function"))
                            ? result
                            : obj;
                    }
                });
            };
            appZenmoneyWrapFunction;
            """.trimIndent(),
        )!!

    actual val globalObject: JsObject = JsObjectImpl(this, jsContext.globalObject!!)
    actual val NULL: JsValue = JsValueImpl(this, JSValue.valueWithNullInContext(jsContext)!!)
    actual val UNDEFINED: JsValue = JsValueImpl(this, JSValue.valueWithUndefinedInContext(jsContext)!!)

    actual var getPlainValueOf: (JsValue) -> Any? = { it.toBasicPlainValue() }

    @Throws(JsException::class)
    actual fun evaluateScript(script: String): JsValue {
        val jsValue = jsContext.evaluateScript(script)
        throwExceptionIfNeeded()
        return JsValue(this, jsValue)
    }

    private fun throwExceptionIfNeeded() {
        val e = jsContext.exception
        if (e != null) {
            jsContext.exception = null
            throw createJsException(e)
        }
    }

    internal fun createJsException(e: JSValue): JsException =
        JsException(
            e.toString_() ?: e.toString(),
            lastException
                ?.takeIf { e.isObject && e.objectForKeyedSubscript("appZenmoneyException")?.toInt32() == it.hashCode() }
                ?.also { lastException = null },
            (if (e.isObject) e.toDictionary()?.mapKeys { it.key.toString() } else null) ?: emptyMap(),
        )

    internal fun createJsError(e: Throwable): JSValue {
        lastException = e
        val message = e.message?.ifBlank { null }?.replace("\"", "\\\"")
        val jsError = jsContext.evaluateScript("new Error(${if (message == null) "" else "\"${message}\""})")!!
        jsDefineProperty.callWithArguments(
            listOf(
                jsError,
                "appZenmoneyException",
                mapOf(
                    "enumerable" to false,
                    "value" to e.hashCode(),
                ),
            ),
        )
        return jsError
    }

    internal fun throwExceptionToJs(e: Throwable) {
        jsContext.exception = createJsError(e)
    }

    internal fun callFunction(
        f: JsFunctionImpl,
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue {
        val jsValue =
            jsCallFunction.callWithArguments(
                ArrayList<JSValue>(args.size + 2).apply {
                    add(f.jsValue)
                    add((thiz as JsValueImpl).jsValue)
                    args.forEach { add((it as JsValueImpl).jsValue) }
                },
            )
        throwExceptionIfNeeded()
        return JsValue(this, jsValue)
    }

    internal fun callFunctionAsConstructor(
        f: JsFunctionImpl,
        args: List<JsValue>,
    ): JsValue {
        val jsValue =
            f.jsValue.constructWithArguments(args.map { (it as JsValueImpl).jsValue })
        throwExceptionIfNeeded()
        return JsValue(this, jsValue)
    }

    actual override fun close() {
    }
}
