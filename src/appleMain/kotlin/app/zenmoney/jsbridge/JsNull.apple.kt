package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsNull : JsValue

internal open class JsNullImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsNull {
    override fun equals(other: Any?): Boolean = other is JsNull && context === other.context

    override fun hashCode(): Int = 0
}
