package app.zenmoney.jsbridge

open class JsScope internal constructor(
    values: ArrayList<AutoCloseable>? = null,
) : JsScopeItem(),
    AutoCloseable {
    constructor(context: JsContext) : this(context.core.scopeValuesPool?.removeLastOrNull()) {
        _context = context
        parent = context.core.scope.also { it.tryAutoClose(this) }
    }

    private var parent: JsScope? = null
    private var values: ArrayList<AutoCloseable>? = values ?: arrayListOf()
    private var _context: JsContext? = null
    val context: JsContext
        get() = checkNotNull(_context) { "JsScope is already closed" }

    fun <T : JsValue> autoClose(value: T) {
        require(context === value.context) { "Can't autoclose value from another context" }
        if (value.isSingleton()) return
        context.core.scope.tryEscape(value)
        require(tryAutoClose(value)) { "Can't autoclose value from another scope" }
    }

    fun <T : Collection<JsValue>> autoClose(values: T) = values.forEach { autoClose(it) }

    fun <T : JsValue> escape(value: T) {
        require(context === value.context) { "Can't escape value from another context" }
        if (value.isSingleton()) return
        tryEscape(value)
        require(context.core.scope.tryAutoClose(value)) { "Can't escape value from another scope" }
    }

    fun <T : Collection<JsValue>> escape(values: T) = values.forEach { escape(it) }

    fun <T : JsValue> T.autoClose(): T = this.also { autoClose(it) }

    fun <T : Collection<JsValue>> T.autoClose(): T = this.also { autoClose(it) }

    fun <T : JsValue> T.escape(): T = this.also { escape(it) }

    fun <T : Collection<JsValue>> T.escape(): T = this.also { escape(it) }

    @Throws(JsException::class)
    fun eval(script: String): JsValue = context.evaluateScript(script).autoClose()

    operator fun JsArray.get(index: Int): JsValue = getValue(index).autoClose()

    operator fun JsObject.get(key: String): JsValue = getValue(key).autoClose()

    @Throws(JsException::class)
    operator fun JsFunction.invoke(
        args: List<JsValue> = emptyList(),
        thiz: JsValue = context.globalThis,
    ): JsValue = call(args, thiz).autoClose()

    @Throws(JsException::class)
    operator fun JsFunction.invoke(
        vararg args: JsValue,
        thiz: JsValue = context.globalThis,
    ): JsValue = call(args.asList(), thiz).autoClose()

    @Throws(JsException::class)
    fun JsFunction.invokeAsConstructor(args: List<JsValue> = emptyList()): JsValue = callAsConstructor(args).autoClose()

    @Throws(JsException::class)
    fun JsFunction.invokeAsConstructor(vararg args: JsValue): JsValue = callAsConstructor(args.asList()).autoClose()

    suspend fun JsValue.await(): JsValue = awaitInScope(this@JsScope)

    operator fun contains(value: JsValue): Boolean = values?.getOrNull(value.core.indexInScope) === value

    override fun close() {
        values
            ?.also { values = null }
            ?.apply {
                forEach { it.close() }
                clear()
            }?.let { _context?.core?.scopeValuesPool?.add(it) }
        parent?.also { parent = null }?.tryEscape(this)
        _context = null
    }

    internal fun tryAutoClose(value: AutoCloseable): Boolean {
        val values = values ?: return false
        val index = value.asScopeItem().indexInScope
        if (value === values.getOrNull(index)) {
            return true
        }
        if (index >= 0) {
            return false
        }
        values.add(value)
        value.asScopeItem().indexInScope = values.lastIndex
        return true
    }

    internal fun tryEscape(value: AutoCloseable): Boolean {
        val index = value.asScopeItem().indexInScope
        if (index < 0) {
            return true
        }
        val values = values ?: return false
        if (value !== values.getOrNull(index)) {
            return false
        }
        val lastValue = values.removeAt(values.lastIndex)
        if (lastValue !== value) {
            values[index] = lastValue
            lastValue.asScopeItem().indexInScope = index
        }
        value.asScopeItem().indexInScope = -1
        return true
    }
}

inline fun <T> jsScoped(
    context: JsContext,
    block: JsScope.() -> T,
) = JsScope(context).use(block)

private fun Any.asScopeItem(): JsScopeItem =
    when (this) {
        is JsValue -> core
        is JsScope -> this
        else -> throw IllegalArgumentException("Invalid scoped value type ${this::class}")
    }

sealed class JsScopeItem {
    internal var indexInScope: Int = -1
}

fun JsScope.evalBlockScoped(
    script: String,
    vararg bindings: Pair<String, JsValue>,
): JsValue {
    val s = StringBuilder()
    s.append("{\n")
    for (placeholder in bindings) {
        val globalVarName = "__appZenmoneyEval${placeholder.first}"
        context.globalThis[globalVarName] = placeholder.second
        s.append(
            """
            const ${placeholder.first} = $globalVarName;
            delete globalThis.$globalVarName;
            """.trimIndent(),
        )
    }
    s.append(script)
    s.append("\n}")
    return eval(s.toString())
}
