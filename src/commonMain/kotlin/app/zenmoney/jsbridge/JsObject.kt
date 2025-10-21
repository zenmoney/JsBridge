package app.zenmoney.jsbridge

expect sealed interface JsObject : JsValue {
    operator fun set(
        key: String,
        value: JsValue?,
    )
}

internal expect fun JsObject.getValue(key: String): JsValue

internal expect fun JsObject(context: JsContext): JsObject

fun JsScope.JsObject(): JsObject = JsObject(context).autoClose()

val JsObject.keys: Set<String>
    get() =
        jsScope(context) {
            val keysFunc = eval("Object.keys") as JsFunction
            val keys = keysFunc(this@keys) as JsArray
            keys.mapTo(linkedSetOf()) { it.toString() }
        }

fun JsObject.toPlainMap(): Map<String, Any?> =
    keys.associateWith { key ->
        getValue(key).use { it.toPlainValue() }
    }
