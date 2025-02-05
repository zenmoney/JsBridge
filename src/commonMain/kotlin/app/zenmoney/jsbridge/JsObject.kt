package app.zenmoney.jsbridge

expect sealed interface JsObject : JsValue {
    operator fun get(key: String): JsValue

    operator fun set(
        key: String,
        value: JsValue?,
    )
}

expect fun JsObject(context: JsContext): JsObject

val JsObject.keys: Set<String>
    get() =
        context.evaluateScript("Object.keys").use { keysFunc ->
            (keysFunc as JsFunction).apply(context.globalObject, listOf(this)).use { keys ->
                (keys as JsArray).mapTo(linkedSetOf()) {
                    val key = it.toString()
                    it.close()
                    key
                }
            }
        }

fun JsObject.toPlainMap(): Map<String, Any?> =
    keys.associateWith { key ->
        get(key).use { it.toPlainValue() }
    }
