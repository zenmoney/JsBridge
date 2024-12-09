package app.zenmoney.jsbridge

expect sealed interface JsValue : AutoCloseable {
    val context: JsContext
}

fun JsValue.toJson(): String {
    val stringify = context.evaluateScript("JSON.stringify") as JsFunction
    val jsonValue = stringify.apply(context.globalObject, listOf(this))
    val json = jsonValue.toString()
    stringify.close()
    jsonValue.close()
    return json
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
