package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsUndefined : JsValue

internal open class JsUndefinedImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsUndefined {
    override fun equals(other: Any?): Boolean = other is JsUndefined && context === other.context

    override fun hashCode(): Int = 1
}
