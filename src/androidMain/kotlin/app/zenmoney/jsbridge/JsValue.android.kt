package app.zenmoney.jsbridge

actual sealed interface JsValue : AutoCloseable {
    actual val context: JsContext
}

internal actual val JsValue.core: JsValueCore
    get() = (this as JsValueImpl)._core

internal open class JsValueImpl(
    context: JsContext,
    val v8Value: Any?,
) : JsValue {
    @Suppress("PropertyName")
    internal val _core = JsValueCore(context)
    override val context: JsContext
        get() = _core.context

    override fun close() {
        _core.close(this)
    }

    override fun toString(): String = v8Value.toString()

    override fun hashCode(): Int = v8Value.hashCode()

    override fun equals(other: Any?): Boolean = other is JsValueImpl && context == other.context && v8Value == other.v8Value
}
