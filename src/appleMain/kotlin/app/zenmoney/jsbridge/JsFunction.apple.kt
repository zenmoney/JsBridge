package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue

actual sealed interface JsFunction : JsObject

@Throws(JsException::class)
internal actual fun JsFunction.apply(
    thiz: JsValue,
    args: List<JsValue>,
): JsValue = context.callFunction(this as JsFunctionImpl, thiz, args)

@Throws(JsException::class)
internal actual fun JsFunction.applyAsConstructor(args: List<JsValue>): JsValue =
    context.callFunctionAsConstructor(this as JsFunctionImpl, args)

internal actual fun JsFunction(
    context: JsContext,
    value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue,
): JsFunction {
    val f: () -> JSValue = {
        jsScope(context) {
            (
                try {
                    value(
                        this,
                        JSContext.currentArguments()?.map { JsValue(context, it).autoClose() } ?: emptyList(),
                        JsValue(context, JSContext.currentThis()).autoClose(),
                    )
                } catch (e: Exception) {
                    context.throwExceptionToJs(e)
                    context.UNDEFINED
                } as JsValueImpl
            ).jsValue
        }
    }
    return JsFunctionImpl(
        context,
        context.jsWrapFunction.callWithArguments(listOf(JSValue.valueWithObject(f, context.jsContext)!!)) as JSValue,
    ).also { context.registerValue(it) }
}

internal class JsFunctionImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsFunction
