package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.valueAtIndex

actual sealed class JsContext actual constructor(
    unit: Unit,
) : AutoCloseable {
    internal actual abstract val core: JsContextCore
    actual abstract var getPlainValueOf: (JsValue) -> Any?
    actual abstract val globalThis: JsObject
    internal actual abstract val NULL: JsNull
    internal actual abstract val UNDEFINED: JsUndefined

    @Throws(JsException::class)
    internal actual abstract fun evaluateScript(script: String): JsValue

    @Throws(JsException::class)
    internal actual abstract fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    @Throws(JsException::class)
    internal actual abstract fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    internal actual abstract fun createArray(value: Iterable<JsValue>): JsArray

    internal actual abstract fun createBoolean(value: Boolean): JsBoolean

    internal actual abstract fun createBooleanObject(value: Boolean): JsBooleanObject

    internal actual abstract fun createDate(millis: Long): JsDate

    internal actual abstract fun createError(exception: Throwable): JsObject

    internal actual abstract fun createException(error: JsValue): JsException

    internal actual abstract fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction

    internal actual abstract fun createNumber(value: Number): JsNumber

    internal actual abstract fun createNumberObject(value: Number): JsNumberObject

    internal actual abstract fun createObject(): JsObject

    internal actual abstract fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    internal actual abstract fun createString(value: String): JsString

    internal actual abstract fun createStringObject(value: String): JsStringObject

    internal actual abstract fun createUint8Array(value: ByteArray): JsUint8Array

    internal actual abstract fun <T : JsValue> createValueAlias(value: T): T

    internal actual abstract fun closeValue(value: JsValue)

    actual abstract override fun close()

    internal actual abstract fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue

    internal actual abstract fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue
}

actual class JsEngineContext :
    JsContext(Unit),
    AutoCloseable {
    actual override val core = JsContextCore(this)

    @Suppress("PropertyName")
    private var _jsContext: JSContext? = JSContext()
    internal val jsContext: JSContext
        get() = checkNotNull(_jsContext) { "JsContext is already closed" }

    actual override var getPlainValueOf: (JsValue) -> Any? = { it.toBasicPlainValue() }

    actual override val globalThis: JsObject =
        JsObjectImpl(this, jsContext.globalObject!!)
            .also { registerValue(it) }

    actual override val NULL: JsNull =
        JsNullImpl(this, JSValue.valueWithNullInContext(jsContext)!!)
            .also { registerValue(it) }
    actual override val UNDEFINED: JsUndefined =
        JsUndefinedImpl(this, JSValue.valueWithUndefinedInContext(jsContext)!!)
            .also { registerValue(it) }

    private var lastException: Throwable? = null

    private var jsCallFunction: JSValue? =
        jsContext.evaluateScript(
            """
            (function () {
                var f = arguments[0];
                var thiz = arguments[1];
                var args = Array.prototype.slice.call(arguments, 2);
                return f.apply(thiz, args);
            })
            """.trimIndent(),
        )
    private var jsBoolean: JSValue? = jsContext.evaluateScript("Boolean")!!
    private var jsDate: JSValue? = jsContext.evaluateScript("Date")!!
    private var jsDefineProperty: JSValue? = jsContext.evaluateScript("Object.defineProperty")!!
    private var jsError: JSValue? = jsContext.evaluateScript("Error")!!
    private var jsNumber: JSValue? = jsContext.evaluateScript("Number")!!
    private var jsPromise: JSValue? = jsContext.evaluateScript("Promise")!!
    private var jsString: JSValue? = jsContext.evaluateScript("String")!!
    private var jsTypeOf: JSValue? =
        jsContext.evaluateScript(
            """
            (function (value) {
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
            })
            """.trimIndent(),
        )!!
    private var jsUint8Array: JSValue? = jsContext.evaluateScript("Uint8Array")!!
    private var jsWrapFunction: JSValue? =
        jsContext.evaluateScript(
            """
            (function (f) {
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
            })
            """.trimIndent(),
        )!!

    @Throws(JsException::class)
    actual override fun evaluateScript(script: String): JsValue {
        val jsValue = jsContext.evaluateScript(script)
        throwExceptionIfNeeded()
        return createValue(jsValue)
    }

    @Throws(JsException::class)
    actual override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue {
        val jsValue =
            jsCallFunction.checkNotNull().callWithArguments(
                ArrayList<JSValue>(args.size + 2).apply {
                    add((f as JsFunctionImpl).jsValue)
                    add((thiz as JsValueImpl).jsValue)
                    args.forEach { add((it as JsValueImpl).jsValue) }
                },
            )
        throwExceptionIfNeeded()
        return createValue(jsValue)
    }

    @Throws(JsException::class)
    actual override fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue {
        val jsValue =
            (f as JsFunctionImpl).jsValue.constructWithArguments(args.map { (it as JsValueImpl).jsValue })
        throwExceptionIfNeeded()
        return createValue(jsValue)
    }

    private fun throwExceptionIfNeeded() {
        val e = jsContext.exception
        if (e != null) {
            jsContext.exception = null
            throw createException(e)
        }
    }

    actual override fun createArray(value: Iterable<JsValue>): JsArray =
        JsArrayImpl(
            this,
            JSValue.valueWithObject(
                value.map { (it as JsValueImpl).jsValue },
                jsContext,
            )!!,
        ).also { registerValue(it) }

    actual override fun createBoolean(value: Boolean): JsBoolean = createValue(value) as JsBoolean

    actual override fun createBooleanObject(value: Boolean): JsBooleanObject =
        JsBooleanObjectImpl(
            this,
            jsBoolean.checkNotNull().constructWithArguments(
                listOf(
                    JSValue.valueWithBool(value, jsContext)!!,
                ),
            )!!,
        ).also { registerValue(it) }

    actual override fun createDate(millis: Long): JsDate =
        createValue(jsDate.checkNotNull().constructWithArguments(listOf(millis))) as JsDate

    actual override fun createError(exception: Throwable): JsObject =
        JsObjectImpl(this, createJsError(exception))
            .also { registerValue(it) }

    actual override fun createException(error: JsValue): JsException = createException((error as JsValueImpl).jsValue)

    private fun createException(error: JSValue): JsException =
        JsException(
            if (error.isString) {
                error.toString_() ?: error.toString()
            } else {
                (
                    error
                        .takeIf { it.isObject }
                        ?.objectForKeyedSubscript("message")
                        ?.takeIf { it.isString }
                        ?: error
                ).let {
                    it.toString_() ?: it.toString()
                }
            },
            lastException
                ?.takeIf { error.isObject && error.objectForKeyedSubscript("appZenmoneyException")?.toInt32() == it.hashCode() }
                ?.also { lastException = null },
            (if (error.isObject) error.toDictionary()?.mapKeys { it.key.toString() } else null) ?: emptyMap(),
            error
                .takeIf { it.isObject }
                ?.objectForKeyedSubscript("name")
                ?.takeIf { it.isString }
                ?.let {
                    it.toString_() ?: it.toString()
                } ?: "",
        )

    private fun createJsError(exception: Throwable): JSValue {
        lastException = exception
        val message = exception.message?.ifBlank { null } ?: exception.toString()
        val jsError =
            jsError.checkNotNull().constructWithArguments(
                listOf(
                    JSValue.valueWithObject(message, jsContext)!!,
                ),
            )!!
        jsDefineProperty.checkNotNull().callWithArguments(
            listOf(
                jsError,
                "appZenmoneyException",
                mapOf(
                    "enumerable" to false,
                    "value" to exception.hashCode(),
                ),
            ),
        )
        return jsError
    }

    actual override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction {
        val f: () -> JSValue = {
            jsFunctionScoped(this) {
                val context = context as JsEngineContext
                (
                    try {
                        _thiz = context.createValue(JSContext.currentThis()).autoClose()
                        value(
                            this,
                            JSContext.currentArguments()?.map { context.createValue(it).autoClose() } ?: emptyList(),
                        )
                    } catch (e: Exception) {
                        context.jsContext.exception = context.createJsError(e)
                        context.UNDEFINED
                    } as JsValueImpl
                ).jsValue
            }
        }
        return JsFunctionImpl(
            this,
            jsWrapFunction.checkNotNull().callWithArguments(listOf(JSValue.valueWithObject(f, jsContext)!!)) as JSValue,
        ).also { registerValue(it) }
    }

    actual override fun createNumber(value: Number): JsNumber = createValue(value) as JsNumber

    actual override fun createNumberObject(value: Number): JsNumberObject =
        JsNumberObjectImpl(
            this,
            jsNumber.checkNotNull().constructWithArguments(
                listOf(
                    JSValue.valueWithDouble(value.toDouble(), jsContext)!!,
                ),
            )!!,
        ).also { registerValue(it) }

    actual override fun createObject(): JsObject =
        JsObjectImpl(this, JSValue.valueWithNewObjectInContext(jsContext)!!)
            .also { registerValue(it) }

    actual override fun createPromise(executor: JsScope.(JsFunction, JsFunction) -> Unit): JsPromise =
        createFunction {
            executor(
                this,
                it[0] as JsFunction,
                it[1] as JsFunction,
            )
            context.UNDEFINED
        }.use {
            JsPromiseImpl(
                this,
                jsPromise.checkNotNull().constructWithArguments(listOf((it as JsFunctionImpl).jsValue))!!,
            ).also { registerValue(it) }
        }

    actual override fun createString(value: String): JsString = createValue(value) as JsString

    actual override fun createStringObject(value: String): JsStringObject =
        JsStringObjectImpl(
            this,
            jsString.checkNotNull().constructWithArguments(
                listOf(
                    JSValue.valueWithObject(value, jsContext)!!,
                ),
            )!!,
        ).also { registerValue(it) }

    actual override fun createUint8Array(value: ByteArray): JsUint8Array =
        JsUint8ArrayImpl(
            this,
            jsUint8Array.checkNotNull().constructWithArguments(
                listOf(
                    JSValue.valueWithObject(value.asList(), jsContext)!!,
                ),
            )!!,
        ).also { registerValue(it) }

    private fun createValue(value: Any?): JsValue =
        when (value) {
            null -> {
                NULL
            }

            is JsValue -> {
                value
            }

            is Boolean -> {
                JsBooleanImpl(this, JSValue.valueWithBool(value, jsContext)!!)
            }

            is Int -> {
                JsNumberImpl(this, JSValue.valueWithInt32(value, jsContext)!!)
            }

            is Number -> {
                JsNumberImpl(this, JSValue.valueWithDouble(value.toDouble(), jsContext)!!)
            }

            is String -> {
                JsStringImpl(this, JSValue.valueWithObject(value, jsContext)!!)
            }

            is ByteArray -> {
                createUint8Array(value)
            }

            is JSValue -> {
                when {
                    value.context != jsContext -> {
                        throw IllegalArgumentException("value runtime must match the JsContext runtime")
                    }

                    value.isNull -> {
                        NULL
                    }

                    value.isUndefined -> {
                        UNDEFINED
                    }

                    value.isBoolean -> {
                        JsBooleanImpl(this, value)
                    }

                    value.isNumber -> {
                        JsNumberImpl(this, value)
                    }

                    value.isString -> {
                        JsStringImpl(this, value)
                    }

                    value.isEqualToObject((globalThis as JsValueImpl).jsValue) -> {
                        globalThis
                    }

                    value.isDate -> {
                        JsDateImpl(this, value)
                    }

                    value.isArray -> {
                        JsArrayImpl(this, value)
                    }

                    else -> {
                        val type = jsTypeOf.checkNotNull().callWithArguments(listOf(value))!!.toString()
                        when {
                            type == "boolean" -> JsBooleanObjectImpl(this, value)
                            type == "number" -> JsNumberObjectImpl(this, value)
                            type == "string" -> JsStringObjectImpl(this, value)
                            type == "function" -> JsFunctionImpl(this, value)
                            type == "Uint8Array" -> JsUint8ArrayImpl(this, value)
                            type == "Promise" -> JsPromiseImpl(this, value)
                            value.isObject -> JsObjectImpl(this, value)
                            else -> TODO()
                        }
                    }
                }
            }

            else -> {
                TODO()
            }
        }.also { registerValue(it) }

    actual override fun <T : JsValue> createValueAlias(value: T): T {
        @Suppress("UNCHECKED_CAST")
        return createValue((value as JsValueImpl).jsValue) as T
    }

    internal fun registerValue(value: JsValue) {
        core.addValue(value)
    }

    actual override fun closeValue(value: JsValue) {
        core.removeValue(value)
    }

    actual override fun close() {
        core.close()
        _jsContext = null
        jsCallFunction = null
        jsBoolean = null
        jsDate = null
        jsDefineProperty = null
        jsError = null
        jsNumber = null
        jsPromise = null
        jsString = null
        jsTypeOf = null
        jsUint8Array = null
        jsWrapFunction = null
    }

    actual override fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue = createValue((obj as JsArrayImpl).jsValue.valueAtIndex(index.toULong()))

    actual override fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue = createValue((obj as JsObjectImpl).jsValue.objectForKeyedSubscript(key))
}

private fun JSValue?.checkNotNull(): JSValue = checkNotNull(this) { "JsContext is already closed" }
