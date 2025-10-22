package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8Value

actual sealed interface JsObject : JsValue {
    actual operator fun set(
        key: String,
        value: JsValue?,
    )
}

internal actual fun JsObject.getValue(key: String): JsValue = context.createValue((this as JsObjectImpl).v8Object.get(key))

internal open class JsObjectImpl(
    context: JsContext,
    v8Object: V8Object,
) : JsValueImpl(context, v8Object),
    JsObject {
    val v8Object: V8Object
        get() = v8Value as V8Object

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        if (value == null || value == context.NULL) {
            v8Object.addNull(key)
            return
        }
        if (value == context.UNDEFINED) {
            v8Object.addUndefined(key)
            return
        }
        val valueV8Value = (value as? JsValueImpl)?.v8Value as? V8Value
        if (valueV8Value != null) {
            v8Object.add(key, valueV8Value)
            return
        }
        when (value) {
            is JsBoolean -> v8Object.add(key, value.toBoolean())
            is JsNumber -> v8Object.add(key, value.toNumber().toDouble())
            is JsString -> v8Object.add(key, value.toString())
            else -> TODO()
        }
    }
}
