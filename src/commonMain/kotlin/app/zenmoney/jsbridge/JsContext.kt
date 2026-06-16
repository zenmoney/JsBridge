package app.zenmoney.jsbridge

expect sealed class JsContext(
    unit: Unit,
) : AutoCloseable {
    internal abstract val core: JsContextCore

    abstract var getPlainValueOf: (JsValue) -> Any?

    abstract val globalThis: JsObject

    internal abstract val NULL: JsNull
    internal abstract val UNDEFINED: JsUndefined

    @Throws(JsException::class)
    internal abstract fun evaluateScript(script: String): JsValue

    @Throws(JsException::class)
    internal abstract fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    @Throws(JsException::class)
    internal abstract fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    internal abstract fun createArray(value: Iterable<JsValue>): JsArray

    internal abstract fun createBoolean(value: Boolean): JsBoolean

    internal abstract fun createBooleanObject(value: Boolean): JsBooleanObject

    internal abstract fun createDate(millis: Long): JsDate

    internal abstract fun createError(exception: Throwable): JsObject

    internal abstract fun createException(error: JsValue): JsException

    internal abstract fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction

    internal abstract fun createNumber(value: Number): JsNumber

    internal abstract fun createNumberObject(value: Number): JsNumberObject

    internal abstract fun createObject(): JsObject

    internal abstract fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    internal abstract fun createString(value: String): JsString

    internal abstract fun createStringObject(value: String): JsStringObject

    internal abstract fun createUint8Array(value: ByteArray): JsUint8Array

    internal abstract fun <T : JsValue> createValueAlias(value: T): T

    internal abstract fun closeValue(value: JsValue)

    abstract override fun close()

    internal abstract fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue

    internal abstract fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue
}

expect class JsEngineContext : JsContext {
    constructor()

    override val core: JsContextCore
    override var getPlainValueOf: (JsValue) -> Any?
    override val globalThis: JsObject
    override val NULL: JsNull
    override val UNDEFINED: JsUndefined

    override fun evaluateScript(script: String): JsValue

    override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    override fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    override fun createArray(value: Iterable<JsValue>): JsArray

    override fun createBoolean(value: Boolean): JsBoolean

    override fun createBooleanObject(value: Boolean): JsBooleanObject

    override fun createDate(millis: Long): JsDate

    override fun createError(exception: Throwable): JsObject

    override fun createException(error: JsValue): JsException

    override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction

    override fun createNumber(value: Number): JsNumber

    override fun createNumberObject(value: Number): JsNumberObject

    override fun createObject(): JsObject

    override fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    override fun createString(value: String): JsString

    override fun createStringObject(value: String): JsStringObject

    override fun createUint8Array(value: ByteArray): JsUint8Array

    override fun <T : JsValue> createValueAlias(value: T): T

    override fun closeValue(value: JsValue)

    override fun close()

    override fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue

    override fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue
}

@Suppress("FunctionName")
fun JsContext(): JsEngineContext = JsEngineContext()

internal class JsContextCore(
    context: JsContext,
) : AutoCloseable {
    @Suppress("PropertyName")
    internal var _scope: JsScope? = JsScope().also { it._context = context }
    var scopeValuesPool: MutableList<ArrayList<AutoCloseable>>? = arrayListOf()
    var eventLoop: JsEventLoop? = null

    private var tag: Any? = null
    private var tagReader: JsFunction? = null
    private var tagSetter: JsFunction? = null

    val scope: JsScope
        get() = checkNotNull(_scope) { "JsContext is already closed" }

    fun addValue(value: JsValue) {
        scope.tryAutoClose(value)
    }

    fun removeValue(value: JsValue) {
        _scope?.tryEscape(value)
    }

    fun getTag(
        jsObject: JsObject,
        key: String,
    ): Any? =
        jsScoped(jsObject.context) {
            initTagReaderAndSetter(context)
            tagReader!!(jsObject, JsString(key))
            tag?.also { tag = null }
        }

    fun setTag(
        jsObject: JsObject,
        key: String,
        value: Any,
    ) = jsScoped(jsObject.context) {
        val read =
            JsFunction {
                context.core.tag = value
                context.UNDEFINED
            }
        initTagReaderAndSetter(context)
        tagSetter!!(jsObject, JsString(key), read)
    }

    fun removeTag(
        jsObject: JsObject,
        key: String,
    ) {
        jsScoped(jsObject.context) {
            initTagReaderAndSetter(context)
            tagSetter!!(jsObject, JsString(key), context.UNDEFINED)
        }
    }

    private fun initTagReaderAndSetter(context: JsContext) {
        if (tagReader == null) {
            tagReader =
                context.evaluateScript(
                    """
                    (function (object, key) {
                        const getter = object[Symbol.for('appZenmoneyTag' + key)];
                        if (getter) {
                            getter();
                        }
                    })
                    """.trimIndent(),
                ) as JsFunction
            tagSetter =
                context.evaluateScript(
                    """
                    (function (object, key, getter) {
                        object[Symbol.for('appZenmoneyTag' + key)] = getter;
                    })
                    """.trimIndent(),
                ) as JsFunction
        }
    }

    override fun close() {
        scopeValuesPool = null
        _scope.also { _scope = null }?.close()
        eventLoop = null
        tag = null
        tagReader = null
        tagSetter = null
    }
}

val JsContext.isClosed: Boolean
    get() = core._scope == null
