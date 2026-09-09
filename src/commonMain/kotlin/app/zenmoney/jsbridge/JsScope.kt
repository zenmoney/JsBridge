package app.zenmoney.jsbridge

/**
 * Owns JavaScript wrappers until closed. Operations may borrow values from other scopes in the same [context].
 * [autoClose] adopts a wrapper from the context's lifetime; [escape] returns it to that lifetime.
 * Context singletons (`null`, `undefined`, and `globalThis`) always remain owned by the context.
 */
open class JsScope internal constructor(
    values: ArrayList<AutoCloseable>? = null,
) : JsScopeItem(),
    AutoCloseable {
    constructor(context: JsContext) : this(context.core.scopeValuesPool?.removeLastOrNull()) {
        _context = context
        context.core.scope.also { it.tryAutoClose(this) }
    }

    private var values: ArrayList<AutoCloseable>? = values ?: arrayListOf()

    @Suppress("PropertyName")
    internal var _context: JsContext? = null
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

    operator fun contains(value: JsValue): Boolean = values?.getOrNull(value.core.indexInScope) === value

    override fun close() {
        values
            ?.also { values = null }
            ?.apply {
                forEach { it.close() }
                clear()
            }?.let { _context?.core?.scopeValuesPool?.add(it) }
        scope?.also { scope = null }?.tryEscape(this)
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
        value.asScopeItem().let {
            it.indexInScope = values.lastIndex
            it.scope = this
        }
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

internal fun JsScope.requireSameContext(value: JsValue) {
    require(context === value.context) { "JsValue belongs to another JsContext" }
}

private fun Any.asScopeItem(): JsScopeItem =
    when (this) {
        is JsValue -> core
        is JsScope -> this
        else -> throw IllegalArgumentException("Invalid scoped value type ${this::class}")
    }

sealed class JsScopeItem {
    internal var scope: JsScope? = null
    internal var indexInScope: Int = -1
}

@Throws(JsException::class)
context(scope: JsScope)
fun eval(script: String): JsValue = scope.context.evaluateScript(script).autoClose()

context(scope: JsScope)
fun evalBlockScoped(
    script: String,
    vararg bindings: Pair<String, JsValue>,
): JsValue {
    val s = StringBuilder()
    s.append("{\n")
    for (placeholder in bindings) {
        val globalVarName = "__appZenmoneyEval${placeholder.first}"
        scope.context.globalThis[globalVarName] = placeholder.second
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
