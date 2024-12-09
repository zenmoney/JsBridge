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
    get() {
        val keysFunc = context.evaluateScript("Object.keys") as JsFunction
        val keys = keysFunc.apply(context.globalObject, listOf(this)) as JsArray
        return keys.mapTo(linkedSetOf()) { it.toString() }.also {
            keysFunc.close()
            keys.close()
        }
    }

fun JsObject.toPlainMap(): Map<String, Any?> =
    keys.associateWith {
        val value = get(it)
        value.toPlainValue().also { value.close() }
    }
