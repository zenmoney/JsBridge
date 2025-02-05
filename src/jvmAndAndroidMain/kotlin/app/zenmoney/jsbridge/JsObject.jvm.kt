package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueObject

actual sealed interface JsObject : JsValue {
    actual operator fun get(key: String): JsValue

    actual operator fun set(
        key: String,
        value: JsValue?,
    )
}

actual fun JsObject(context: JsContext): JsObject =
    JsObjectImpl(context, context.v8Runtime.createV8ValueObject()).also {
        context.registerValue(it)
    }

internal open class JsObjectImpl(
    context: JsContext,
    v8Object: V8ValueObject,
) : JsValueImpl(context, v8Object),
    JsObject {
    private val hashCode = v8Object.identityHash

    val v8Object: V8ValueObject
        get() = v8Value as V8ValueObject

    override fun hashCode(): Int = hashCode

    override fun get(key: String): JsValue = JsValue(context, v8Object.get(key))

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        v8Object.set(key, ((value ?: context.NULL) as JsValueImpl).v8Value)
    }
}
