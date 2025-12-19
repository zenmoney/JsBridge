package app.zenmoney.jsbridge

expect sealed interface JsObject : JsValue {
    operator fun set(
        key: String,
        value: JsValue?,
    )
}

internal expect fun JsObject.getValue(key: String): JsValue

internal fun JsObject(context: JsContext): JsObject = context.createObject()

fun JsScope.JsObject(): JsObject = JsObject(context).autoClose()

val JsObject.keys: Set<String>
    get() =
        jsScoped(context) {
            val keysFunc = eval("Object.keys") as JsFunction
            val keys = keysFunc(this@keys) as JsArray
            keys.mapTo(linkedSetOf()) { it.toString() }
        }

fun JsObject.toPlainMap(): Map<String, Any?> =
    keys.associateWith { key ->
        getValue(key).use { it.toPlainValue() }
    }

fun JsObject.defineProperty(
    key: String,
    value: JsValue? = null,
    get: JsFunction? = null,
    set: JsFunction? = null,
    configurable: Boolean? = null,
    enumerable: Boolean? = null,
    writable: Boolean? = null,
) {
    JsString(context, key).use { key ->
        defineProperty(
            key,
            value,
            get,
            set,
            configurable,
            enumerable,
            writable,
        )
    }
}

fun JsObject.defineProperty(
    key: JsValue,
    value: JsValue? = null,
    get: JsFunction? = null,
    set: JsFunction? = null,
    configurable: Boolean? = null,
    enumerable: Boolean? = null,
    writable: Boolean? = null,
) {
    jsScoped(context) {
        val defineProperty = eval("Object.defineProperty") as JsFunction
        defineProperty(
            this@defineProperty,
            key,
            JsObject().apply {
                value?.let { this["value"] = value }
                get?.let { this["get"] = get }
                set?.let { this["set"] = set }
                configurable?.let { this["configurable"] = JsBoolean(configurable) }
                enumerable?.let { this["enumerable"] = JsBoolean(enumerable) }
                writable?.let { this["writable"] = JsBoolean(writable) }
            },
        )
    }
}
