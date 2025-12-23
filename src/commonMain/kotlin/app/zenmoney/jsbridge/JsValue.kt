package app.zenmoney.jsbridge

import kotlin.contracts.contract

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

    fun close(value: JsValue): Boolean {
        if (value.isSingleton() && _context?.isClosed == false) {
            return false
        }
        _context?.closeValue(value)
        _context = null
        return true
    }
}

internal fun JsValue.isSingleton(): Boolean {
    val context = core._context ?: return false
    return this === context.globalThis || this === context.NULL || this === context.UNDEFINED
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

fun JsValue?.isNullOrUndefined(): Boolean {
    contract {
        returns(false) implies (this@isNullOrUndefined != null)
    }
    return this == null || this == context.NULL || this == context.UNDEFINED
}

val JsValue.boolean: Boolean
    get() =
        booleanOrNull
            ?: throw IllegalStateException("$this does not represent a Boolean")

val JsValue.booleanOrNull: Boolean?
    get() = (this as? JsBoolean)?.toBoolean()

val JsValue.double: Double
    get() =
        doubleOrNull
            ?: throw NumberFormatException("$this is not a Double")

val JsValue.doubleOrNull: Double?
    get() = (this as? JsNumber)?.toNumber()?.toDouble()

val JsValue.float: Float
    get() =
        floatOrNull
            ?: throw NumberFormatException("$this is not a Float")

val JsValue.floatOrNull: Float?
    get() = (this as? JsNumber)?.toNumber()?.toFloat()

val JsValue.int: Int
    get() =
        intOrNull
            ?: throw NumberFormatException("$this is not an Int")

val JsValue.intOrNull: Int?
    get() =
        when (val n = (this as? JsNumber)?.toNumber()) {
            null -> {
                null
            }

            is Int -> {
                n
            }

            is Byte,
            is Short,
            -> {
                n.toInt()
            }

            else -> {
                when (val l = longOrNull) {
                    null -> null
                    in Int.MIN_VALUE..Int.MAX_VALUE -> l.toInt()
                    else -> null
                }
            }
        }

val JsValue.long: Long
    get() =
        longOrNull
            ?: throw NumberFormatException("$this is not a Long")

val JsValue.longOrNull: Long?
    get() =
        when (val n = (this as? JsNumber)?.toNumber()) {
            null -> {
                null
            }

            is Long -> {
                n
            }

            is Byte,
            is Int,
            is Short,
            -> {
                n.toLong()
            }

            is Float -> {
                val l = n.toLong()
                if (l.toFloat() == n) l else null
            }

            else -> {
                val d = (n as? Double) ?: n.toDouble()
                val l = n.toLong()
                if (l.toDouble() == d) l else null
            }
        }

val JsValue.string: String
    get() =
        stringOrNull
            ?: throw IllegalStateException("$this is not a String")

val JsValue.stringOrNull: String?
    get() = (this as? JsString)?.toString()
