package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8ArrayBuffer
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8TypedArray
import com.eclipsesource.v8.V8Value
import java.nio.ByteBuffer
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
    private val v8Runtime: V8 = V8.createV8Runtime()

    actual override var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any? = { value, state ->
        toBasicPlainValue(value, state)
    }

    actual override val globalThis: JsObject =
        JsObjectImpl(this, v8Runtime.executeObjectScript("this"))
            .also { registerValue(it) }

    actual override val NULL: JsNull =
        JsNullImpl(this, null)
            .also { registerValue(it) }
    actual override val UNDEFINED: JsUndefined =
        JsUndefinedImpl(this, v8Runtime.executeScript("undefined"))
            .also { registerValue(it) }

    init {
        v8Runtime.executeScript(jsPromiseRejectionTrackingScript)
    }

    private var lastException: Throwable? = null

    private val cachedValues =
        listOf(
            NULL,
            UNDEFINED,
            globalThis,
        )

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

    private val booleanClass: JsFunction = evaluateScript("Boolean") as JsFunction
    private val dateClass: JsFunction = evaluateScript("Date") as JsFunction
    private val errorClass: JsFunction = evaluateScript("Error") as JsFunction
    private val numberClass: JsFunction = evaluateScript("Number") as JsFunction
    private val promiseClass: JsFunction = evaluateScript("Promise") as JsFunction
    private val stringClass: JsFunction = evaluateScript("String") as JsFunction

    internal val jsGetTime: V8Function =
        v8Runtime.executeScript(
            """
            (function () {
                return this.getTime();
            })
            """.trimIndent(),
        ) as V8Function
    private val jsTypeOf: V8Function =
        v8Runtime.executeScript(
            """
            (function () {
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
            })
            """.trimIndent(),
        ) as V8Function

    @Throws(JsException::class)
    actual override fun evaluateScript(script: String): JsValue {
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
        return createValue(v8Value)
    }

    @Throws(JsException::class)
    actual override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue {
        val fullArgs =
            createArray(
                listOf(
                    f,
                    thiz,
                    *args.toTypedArray(),
                ),
            )
        val v8Value: Any? =
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
                    v8Runtime.executeScript("[appZenmoneyError, appZenmoneyErrorOccurred]"),
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

    actual override fun createArray(value: Iterable<JsValue>): JsArray {
        return JsArrayImpl(
            this,
            V8Array(v8Runtime).apply {
                value.forEach { value ->
                    if (value == NULL) {
                        pushNull()
                        return@forEach
                    }
                    if (value == UNDEFINED) {
                        pushUndefined()
                        return@forEach
                    }
                    val valueV8Value = (value as? JsValueImpl)?.v8Value as? V8Value
                    if (valueV8Value != null) {
                        push(valueV8Value)
                        return@forEach
                    }
                    when (value) {
                        is JsBoolean -> push(value.toBoolean())
                        is JsNumber -> push(value.toNumber().toDouble())
                        is JsString -> push(value.toString())
                        else -> TODO()
                    }
                }
            },
        ).also { registerValue(it) }
    }

    actual override fun createBoolean(value: Boolean): JsBoolean = createValue(value) as JsBoolean

    actual override fun createBooleanObject(value: Boolean): JsBooleanObject =
        jsScoped(this) {
            (booleanClass.invokeAsConstructor(JsBoolean(value)) as JsBooleanObject).escape()
        }

    actual override fun createDate(millis: Long): JsDate =
        jsScoped(this) {
            (dateClass.invokeAsConstructor(JsNumber(millis)) as JsDate).escape()
        }

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
                    "${it.message}" == message || message == "Unhandled Java Exception"
                }?.also { lastException = null },
            (if (error is JsObject) error.toPlainMap() else null) ?: emptyMap(),
            (error as? JsObject)
                ?.getValue("name")
                ?.use { (it as? JsString)?.toString() }
                ?: if (error is JsString) "Error" else "",
        )
    }

    actual override fun createError(exception: Throwable): JsObject {
        lastException = exception
        return jsScoped(this) {
            (errorClass.invokeAsConstructor(JsString(exception.message?.ifBlank { null } ?: exception.toString())) as JsObject).escape()
        }
    }

    actual override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction =
        JsFunctionImpl(
            this,
            V8Function(v8Runtime) { thiz, args ->
                jsFunctionScoped(this) {
                    val context = context as JsEngineContext
                    (
                        try {
                            _thiz = context.createValue(thiz.twin()).autoClose()
                            value(
                                this,
                                args?.map { context.createValue(it).autoClose() } ?: emptyList(),
                            )
                        } catch (e: Exception) {
                            context.lastException = e
                            throw e
                        } as JsValueImpl
                    ).v8Value.let {
                        if (it is V8Value) {
                            it.twin()
                        } else {
                            it
                        }
                    }
                }
            },
        ).also { registerValue(it) }

    actual override fun createNumber(value: Number): JsNumber = createValue(value) as JsNumber

    actual override fun createNumberObject(value: Number): JsNumberObject =
        jsScoped(this) {
            (numberClass.invokeAsConstructor(JsNumber(value)) as JsNumberObject).escape()
        }

    actual override fun createObject(): JsObject =
        JsObjectImpl(this, V8Object(v8Runtime)).also {
            registerValue(it)
        }

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
        jsScoped(this) {
            (stringClass.invokeAsConstructor(JsString(value)) as JsStringObject).escape()
        }

    actual override fun createUint8Array(value: ByteArray): JsUint8Array = createValue(value) as JsUint8Array

    private fun createValue(value: Any?): JsValue {
        if (value == null) {
            return NULL
        }
        if (value is V8Value) {
            if (!value.isUndefined && value.runtime != v8Runtime) {
                throw IllegalArgumentException("value runtime must match the JsContext runtime")
            }
            cachedValues.forEach {
                val cachedV8Value = (it as JsValueImpl).v8Value
                if (cachedV8Value == value) {
                    if (value !== cachedV8Value) {
                        value.closeQuietly()
                    }
                    return it
                }
            }
        }
        return when (value) {
            is Boolean -> {
                JsBooleanImpl(this, value)
            }

            is Number -> {
                JsNumberImpl(this, value.toDouble())
            }

            is String -> {
                JsStringImpl(this, value)
            }

            is ByteArray -> {
                val buffer = V8ArrayBuffer(v8Runtime, ByteBuffer.allocateDirect(value.size).apply { put(value) })
                JsUint8ArrayImpl(
                    this,
                    V8TypedArray(
                        v8Runtime,
                        buffer,
                        V8Value.UNSIGNED_INT_8_ARRAY,
                        0,
                        value.size,
                    ),
                ).also { buffer.closeQuietly() }
            }

            is V8TypedArray -> {
                if (value.type == V8TypedArray.UNSIGNED_INT_8_ARRAY) {
                    JsUint8ArrayImpl(this, value)
                } else {
                    JsObjectImpl(this, value)
                }
            }

            is V8Array -> {
                JsArrayImpl(this, value)
            }

            is V8Function -> {
                JsFunctionImpl(this, value)
            }

            is V8Object -> {
                val type = jsTypeOf.call(value, null) as String
                when (type) {
                    "boolean" -> JsBooleanObjectImpl(this, value)
                    "date" -> JsDateImpl(this, value)
                    "number" -> JsNumberObjectImpl(this, value)
                    "string" -> JsStringObjectImpl(this, value)
                    "Promise" -> JsPromiseImpl(this, value)
                    else -> JsObjectImpl(this, value)
                }
            }

            else -> {
                throw IllegalArgumentException("unexpected value ${value::class} $value")
            }
        }.also { registerValue(it) }
    }

    actual override fun <T : JsValue> createValueAlias(value: T): T {
        @Suppress("UNCHECKED_CAST")
        return createValue((value as JsValueImpl).let { (it.v8Value as? V8Value)?.twin() ?: it.v8Value }) as T
    }

    internal fun registerValue(value: JsValue) {
        core.addValue(value)
    }

    actual override fun closeValue(value: JsValue) {
        ((value as JsValueImpl).v8Value as? V8Value)?.closeQuietly()
        core.removeValue(value)
    }

    actual override fun close() {
        core.close()
        jsGetTime.closeQuietly()
        jsTypeOf.closeQuietly()
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

private fun V8Value.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

private fun <T> V8Array.map(action: (Any?) -> T): List<T> {
    val result = arrayListOf<T>()
    var i = 0
    while (i < length()) {
        result.add(action(get(i++)))
    }
    return result
}
