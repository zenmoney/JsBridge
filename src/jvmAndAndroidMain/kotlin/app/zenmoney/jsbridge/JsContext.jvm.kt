package app.zenmoney.jsbridge

import com.caoccao.javet.enums.V8ValueReferenceType
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
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
import kotlin.Throws

actual sealed class JsContext actual constructor(
    unit: Unit,
) : AutoCloseable {
    internal actual abstract val core: JsContextCore
    actual abstract var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any?
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
    internal val v8Runtime: V8Runtime = V8Host.getV8Instance().createV8Runtime()

    actual override var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any? = { value, state ->
        toBasicPlainValue(value, state)
    }

    actual override val globalThis: JsObject =
        JsObjectImpl(this, v8Runtime.getExecutor("this").execute())
            .also { registerValue(it) }

    actual override val NULL: JsNull =
        JsNullImpl(this, v8Runtime.createV8ValueNull())
            .also { registerValue(it) }
    actual override val UNDEFINED: JsUndefined =
        JsUndefinedImpl(this, v8Runtime.createV8ValueUndefined())
            .also { registerValue(it) }

    init {
        evaluateScript(jsPromiseRejectionTrackingScript).close()
    }

    private var lastException: Throwable? = null

    private var callbackContextIndex = 0
    private var callbackContextHandles = LongArray(10) { 0 }

    private val callFunction: JsFunction =
        evaluateScript(
            """
            (function () {
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
            })
            """.trimIndent(),
        ) as JsFunction
    private val callFunctionAsConstructor: JsFunction =
        evaluateScript(
            """
            (function (f, ...args) {
                return new f(...args);
            })
            """.trimIndent(),
        ) as JsFunction

    private val errorClass: JsFunction = evaluateScript("Error") as JsFunction
    private val promiseClass: JsFunction = evaluateScript("Promise") as JsFunction

    @Throws(JsException::class)
    actual override fun evaluateScript(script: String): JsValue {
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
            v8Value.closeQuietly()
        }
        return createValue(v8Value)
    }

    @Throws(JsException::class)
    actual override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue {
        val v8Value: V8Value =
            (callFunction as JsFunctionImpl).v8Function.call(
                v8Runtime.globalObject,
                *Array(args.size + 2) {
                    when (it) {
                        0 -> (f as JsFunctionImpl).v8Function
                        1 -> (thiz as JsValueImpl).v8Value
                        else -> (args[it - 2] as JsValueImpl).v8Value
                    }
                },
            )
        throwExceptionIfNeeded {
            v8Value.closeQuietly()
        }
        return createValue(v8Value)
    }

    @Throws(JsException::class)
    actual override fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue =
        callFunctionAsConstructor.call(
            ArrayList<JsValue>(args.size + 1).apply {
                add(f)
                addAll(args)
            },
        )

    private inline fun throwExceptionIfNeeded(ifException: () -> Unit) {
        jsScoped(this) {
            val arr =
                createValue(
                    v8Runtime.getExecutor("[appZenmoneyError, appZenmoneyErrorOccurred]").execute(),
                ).autoClose() as JsArray
            val hasError = arr[1]
            if (hasError is JsBoolean && hasError.toBoolean()) {
                val error = arr[0]
                val e = createException(error)
                ifException()
                throw e
            }
        }
    }

    actual override fun createArray(value: Iterable<JsValue>): JsArray =
        JsArrayImpl(
            this,
            v8Runtime.createV8ValueArray().apply {
                if (value is List) {
                    push(*Array(value.size) { (value[it] as JsValueImpl).v8Value })
                } else {
                    value.forEach {
                        push((it as JsValueImpl).v8Value)
                    }
                }
            },
        ).also { registerValue(it) }

    actual override fun createBoolean(value: Boolean): JsBoolean = createValue(value) as JsBoolean

    actual override fun createBooleanObject(value: Boolean): JsBooleanObject =
        JsBooleanObjectImpl(
            this,
            v8Runtime.createV8ValueBooleanObject(value),
        ).also { registerValue(it) }

    actual override fun createDate(millis: Long): JsDate = createValue(v8Runtime.createV8ValueZonedDateTime(millis)) as JsDate

    actual override fun createException(error: JsValue): JsException {
        val message =
            if (error is JsString) {
                error.toString()
            } else {
                (error as? JsObject)
                    ?.getValue("message")
                    ?.use { it.takeIf { it !is JsUndefined }?.toString() }
                    ?: error.toString()
            }
        return JsException(
            message,
            lastException
                ?.takeIf {
                    "${it.message}" == message || message == "Uncaught JavaError in function callback"
                }?.also { lastException = null },
            (if (error is JsObject) error.toPlainMap() else null) ?: emptyMap(),
            (error as? JsObject)
                ?.getValue("name")
                ?.use { (it as? JsString)?.toString() }
                ?: "",
        )
    }

    actual override fun createError(exception: Throwable): JsObject {
        lastException = exception
        return jsScoped(this) {
            (errorClass.invokeAsConstructor(JsString(exception.message?.ifBlank { null } ?: exception.toString())) as JsObject).escape()
        }
    }

    actual override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction {
        val name = "f$value${value.hashCode()}".filter { it.isLetterOrDigit() }
        val callbackContext =
            JavetCallbackContext(
                name,
                JavetCallbackType.DirectCallThisAndResult,
                IJavetDirectCallable.ThisAndResult<Exception> { thiz, args ->
                    jsFunctionScoped(this) {
                        val context = context as JsEngineContext
                        (
                            try {
                                _thiz = context.createValue(thiz.toClone()).autoClose()
                                value(
                                    this,
                                    args?.map { arg -> context.createValue(arg.toClone()).autoClose() } ?: emptyList(),
                                )
                            } catch (e: Exception) {
                                context.lastException = e
                                throw e
                            } as JsValueImpl
                        ).v8Value.toClone()
                    }
                },
            )
        return JsFunctionImpl(
            this,
            v8Runtime.createV8ValueFunction(callbackContext),
        ).also {
            registerValue(it)
            registerCallbackContextHandle(callbackContext.handle)
        }
    }

    actual override fun createNumber(value: Number): JsNumber = createValue(value) as JsNumber

    actual override fun createNumberObject(value: Number): JsNumberObject =
        when (value) {
            is Int -> JsNumberObjectImpl(this, v8Runtime.createV8ValueIntegerObject(value))
            is Long -> JsNumberObjectImpl(this, v8Runtime.createV8ValueLongObject(value))
            else -> JsNumberObjectImpl(this, v8Runtime.createV8ValueDoubleObject(value.toDouble()))
        }.also { registerValue(it) }

    actual override fun createObject(): JsObject =
        JsObjectImpl(this, v8Runtime.createV8ValueObject())
            .also { registerValue(it) }

    actual override fun createPromise(executor: JsScope.(JsFunction, JsFunction) -> Unit): JsPromise =
        jsScoped(this) {
            (
                promiseClass.invokeAsConstructor(
                    JsFunction {
                        executor(
                            this,
                            it[0] as JsFunction,
                            it[1] as JsFunction,
                        )
                        context.UNDEFINED
                    },
                ) as JsPromise
            ).escape()
        }

    actual override fun createString(value: String): JsString = createValue(value) as JsString

    actual override fun createStringObject(value: String): JsStringObject =
        JsStringObjectImpl(
            this,
            v8Runtime.createV8ValueStringObject(value),
        ).also { registerValue(it) }

    actual override fun createUint8Array(value: ByteArray): JsUint8Array = createValue(value) as JsUint8Array

    private fun createValue(value: Any?): JsValue {
        if (value == null) {
            return NULL
        }
        if (value is V8Value) {
            if (value.v8Runtime != v8Runtime) {
                throw IllegalArgumentException("value runtime must match the JsContext runtime")
            }
            if (value is V8ValueNull) {
                return NULL
            }
            if (value is V8ValueUndefined) {
                return UNDEFINED
            }
            if (value is V8ValueObject &&
                value !== (globalThis as JsValueImpl).v8Value &&
                value.strictEquals((globalThis as JsValueImpl).v8Value)
            ) {
                value.closeQuietly()
                return globalThis
            }
        }
        return when (value) {
            is Boolean -> {
                JsBooleanImpl(this, v8Runtime.createV8ValueBoolean(value))
            }

            is Int -> {
                JsNumberImpl(this, v8Runtime.createV8ValueInteger(value))
            }

            is Long -> {
                JsNumberImpl(this, v8Runtime.createV8ValueLong(value))
            }

            is Number -> {
                JsNumberImpl(this, v8Runtime.createV8ValueDouble(value.toDouble()))
            }

            is String -> {
                JsStringImpl(this, v8Runtime.createV8ValueString(value))
            }

            is ByteArray -> {
                JsUint8ArrayImpl(
                    this,
                    v8Runtime.createV8ValueTypedArray(V8ValueReferenceType.Uint8Array, value.size).apply { fromBytes(value) },
                )
            }

            is V8ValueBoolean -> {
                JsBooleanImpl(this, value)
            }

            is V8ValueBooleanObject -> {
                JsBooleanObjectImpl(this, value)
            }

            is V8ValueBigInteger -> {
                JsNumberImpl(this, value)
            }

            is V8ValueInteger -> {
                JsNumberImpl(this, value)
            }

            is V8ValueIntegerObject -> {
                JsNumberObjectImpl(this, value)
            }

            is V8ValueLong -> {
                JsNumberImpl(this, value)
            }

            is V8ValueLongObject -> {
                JsNumberObjectImpl(this, value)
            }

            is V8ValueDouble -> {
                JsNumberImpl(this, value)
            }

            is V8ValueDoubleObject -> {
                JsNumberObjectImpl(this, value)
            }

            is V8ValueString -> {
                JsStringImpl(this, value)
            }

            is V8ValueStringObject -> {
                JsStringObjectImpl(this, value)
            }

            is V8ValueZonedDateTime -> {
                JsDateImpl(this, value)
            }

            is V8ValueTypedArray -> {
                if (value.type == V8ValueReferenceType.Uint8Array) {
                    JsUint8ArrayImpl(this, value)
                } else {
                    JsObjectImpl(this, value)
                }
            }

            is V8ValueArray -> {
                JsArrayImpl(this, value)
            }

            is V8ValueFunction -> {
                JsFunctionImpl(this, value)
            }

            is V8ValuePromise -> {
                JsPromiseImpl(this, value)
            }

            is V8ValueObject -> {
                if (value.has("then") && value.get<V8Value>("then").use { it is V8ValueFunction }) {
                    JsPromiseImpl(this, value)
                } else {
                    JsObjectImpl(this, value)
                }
            }

            else -> {
                TODO()
            }
        }.also { registerValue(it) }
    }

    actual override fun <T : JsValue> createValueAlias(value: T): T {
        @Suppress("UNCHECKED_CAST")
        return createValue((value as JsValueImpl).v8Value.toClone()) as T
    }

    internal fun registerValue(value: JsValue) {
        core.addValue(value)
    }

    actual override fun closeValue(value: JsValue) {
        (value as JsValueImpl).v8Value.closeQuietly()
        core.removeValue(value)
    }

    private fun registerCallbackContextHandle(handle: Long) {
        if (callbackContextHandles.size <= callbackContextIndex) {
            callbackContextHandles = callbackContextHandles.copyOf(callbackContextHandles.size * 3 / 2)
        }
        callbackContextHandles[callbackContextIndex++] = handle
    }

    actual override fun close() {
        core.close()
        callbackContextHandles.forEach { v8Runtime.removeCallbackContext(it) }
        callbackContextIndex = 0
        v8Runtime.close()
    }

    actual override fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue = createValue((obj as JsArrayImpl).v8Array.get(index))

    actual override fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue = createValue((obj as JsObjectImpl).v8Object.get(key))
}

internal fun V8Value.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
