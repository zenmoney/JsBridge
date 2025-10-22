package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsValue : AutoCloseable {
    actual val context: JsContext
}

internal actual val JsValue.core: JsValueCore
    get() = (this as JsValueImpl)._core

internal open class JsValueImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValue {
    private var _jsValue: JSValue? = jsValue
    val jsValue: JSValue
        get() = checkNotNull(_jsValue) { "JsValue is already closed" }

    @Suppress("PropertyName")
    internal val _core = JsValueCore(context)
    override val context: JsContext
        get() = _core.context

    override fun close() {
        _core.close(this)
        _jsValue = null
    }

    override fun toString(): String = jsValue.toString_() ?: jsValue.toString()

    override fun hashCode(): Int = jsValue.hashCode()

    override fun equals(other: Any?): Boolean = other is JsValueImpl && context == other.context && jsValue.isEqualToObject(other.jsValue)
}
