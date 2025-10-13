package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

actual sealed interface JsFunction : JsObject {
    @Throws(JsException::class)
    actual fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue

    @Throws(JsException::class)
    actual fun applyAsConstructor(args: List<JsValue>): JsValue
}

actual fun JsFunction(
    context: JsContext,
    value: JsValue.(args: List<JsValue>) -> JsValue,
): JsFunction {
    val f: () -> JSValue = {
        (
            try {
                value(
                    JsValue(context, JSContext.currentThis()),
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
        context.jsWrapFunction.callWithArguments(listOf(JSValue.valueWithObject(f, context.jsContext)!!)) as JSValue,
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

    @Throws(JsException::class)
    override fun applyAsConstructor(args: List<JsValue>): JsValue = context.callFunctionAsConstructor(this, args)
}
