package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

actual sealed interface JsFunction : JsObject {
    @Throws(JsException::class)
    actual fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue
}

actual fun JsFunction(
    context: JsContext,
    value: JsObject.(args: List<JsValue>) -> JsValue,
): JsFunction {
    val f: () -> JSValue = {
        (
            try {
                value(
                    JsValue(context, JSContext.currentThis()) as JsObject,
                    JSContext.currentArguments()?.map { JsValue(context, it) } ?: emptyList(),
                )
            } catch (e: Exception) {
                context.throwExceptionToJs(e)
                context.UNDEFINED
            } as JsValueImpl
        ).jsValue
    }
    return JsFunctionImpl(
        context,
        JSValue.valueWithObject(f, context.jsContext)!!,
    )
}

internal class JsFunctionImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsFunction {
    @Throws(JsException::class)
    override fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue = context.callFunction(this, thiz, args)
}
