package app.zenmoney.jsbridge

import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.values.reference.V8ValueFunction

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
    val name = "f$value${value.hashCode()}".filter { it.isLetterOrDigit() }
    val callbackContext =
        JavetCallbackContext(
            name,
            JavetCallbackType.DirectCallThisAndResult,
            IJavetDirectCallable.ThisAndResult<Exception> { thiz, args ->
                (
                    try {
                        value(
                            JsValue(context, thiz.toClone()) as JsObject,
                            args?.map { arg -> JsValue(context, arg.toClone()) } ?: emptyList(),
                        )
                    } catch (e: Exception) {
                        context.throwExceptionToJs(e)
                    } as JsValueImpl
                ).v8Value.toClone()
            },
        )
    return JsFunctionImpl(
        context,
        context.v8Runtime.createV8ValueFunction(callbackContext),
    ).also {
        context.registerValue(it)
        context.registerCallbackContextHandle(callbackContext.handle)
    }
}

internal class JsFunctionImpl(
    context: JsContext,
    v8Function: V8ValueFunction,
) : JsObjectImpl(context, v8Function),
    JsFunction {
    val v8Function: V8ValueFunction
        get() = v8Value as V8ValueFunction

    override fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue = context.callFunction(this, thiz, args)
}
