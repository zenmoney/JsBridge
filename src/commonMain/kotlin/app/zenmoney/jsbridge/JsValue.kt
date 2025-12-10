package app.zenmoney.jsbridge

expect sealed interface JsValue : AutoCloseable {
    val context: JsContext
}

internal expect val JsValue.core: JsValueCore

internal class JsValueCore(
    context: JsContext,
) : JsScopeItem() {
    @Suppress("PropertyName")
    internal var _context: JsContext? = context
    val context: JsContext
        get() = checkNotNull(_context) { "JsValue is already closed" }

    fun close(value: JsValue) {
        _context?.closeValue(value)
        _context = null
    }
}

@Suppress("FunctionName")
fun <T : JsValue> JsScope.JsValueAlias(value: T): T = context.createValueAlias(value).autoClose()

fun JsValue.toJson(): String =
    jsScoped(context) {
        val stringify = eval("JSON.stringify") as JsFunction
        stringify(this@toJson).toString()
    }

fun JsValue.toPlainValue(): Any? = context.getPlainValueOf(this)

val JsValue.isClosed: Boolean
    get() = core._context == null

val JsValue.isScoped: Boolean
    get() =
        core._context
            ?.core
            ?.scope
            ?.let { !it.contains(this) } ?: false

internal fun JsValue.toBasicPlainValue(): Any? {
    return when (this) {
        context.NULL,
        context.UNDEFINED,
        -> null

        is JsBoolean -> return toBoolean()

        is JsNumber -> return toNumber()

        is JsString -> return toString()

        is JsDate -> return toMillis()

        is JsUint8Array -> return toByteArray()

        is JsArray -> return toPlainList()

        is JsObject -> return toPlainMap()

        else -> toString()
    }
}

fun JsValue.isNullOrUndefined(): Boolean = this == context.NULL || this == context.UNDEFINED
