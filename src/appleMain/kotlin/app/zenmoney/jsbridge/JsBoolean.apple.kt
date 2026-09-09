package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsBoolean : JsValue {
    actual fun toBoolean(): Boolean
}

actual sealed interface JsBooleanObject :
    JsObject,
    JsBoolean

internal class JsBooleanImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsBoolean {
    override fun hashCode(): Int = toBoolean().hashCode()

    override fun equals(other: Any?): Boolean =
        other is JsBoolean &&
            other !is JsObject &&
            context === other.context &&
            toBoolean() == other.toBoolean()

    override fun toBoolean(): Boolean = jsValue.toBool()
}

internal class JsBooleanObjectImpl(
    context: JsContext,
    jsValue: JSValue,
    private val value: Boolean,
) : JsObjectImpl(context, jsValue),
    JsBooleanObject {
    override fun toBoolean(): Boolean = value
}
