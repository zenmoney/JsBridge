package app.zenmoney.jsbridge

expect sealed interface JsValue : AutoCloseable {
    val context: JsContext
}

fun JsValue.toJson(): String =
    context.evaluateScript("JSON.stringify").use { stringify ->
        (stringify as JsFunction).apply(context.globalObject, listOf(this)).use {
            it.toString()
        }
    }

fun JsValue.toPlainValue(): Any? {
    return when (this) {
        context.NULL,
        context.UNDEFINED,
        -> null
        is JsBoolean -> return toBoolean()
        is JsNumber -> return toNumber()
        is JsString -> return toString()
        is JsDate -> return toInstant()
        is JsUint8Array -> return toByteArray()
        is JsArray -> return toPlainList()
        is JsObject -> return toPlainMap()
        else -> toString()
    }
}
