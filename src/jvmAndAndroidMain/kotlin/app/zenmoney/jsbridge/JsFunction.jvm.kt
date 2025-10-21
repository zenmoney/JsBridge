package app.zenmoney.jsbridge

import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.values.reference.V8ValueFunction

actual sealed interface JsFunction : JsObject

@Throws(JsException::class)
internal actual fun JsFunction.apply(
    thiz: JsValue,
    args: List<JsValue>,
): JsValue = context.callFunction(this as JsFunctionImpl, thiz, args)

@Throws(JsException::class)
internal actual fun JsFunction.applyAsConstructor(args: List<JsValue>): JsValue =
    context.callFunctionAsConstructor.apply(
        args =
            ArrayList<JsValue>(args.size + 1).apply {
                add(this@applyAsConstructor)
                addAll(args)
            },
        thiz = context.globalThis,
    )

internal actual fun JsFunction(
    context: JsContext,
    value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue,
): JsFunction {
    val name = "f$value${value.hashCode()}".filter { it.isLetterOrDigit() }
    val callbackContext =
        JavetCallbackContext(
            name,
            JavetCallbackType.DirectCallThisAndResult,
            IJavetDirectCallable.ThisAndResult<Exception> { thiz, args ->
                jsScope(context) {
                    (
                        try {
                            value(
                                this,
                                args?.map { arg -> JsValue(context, arg.toClone()).autoClose() } ?: emptyList(),
                                JsValue(context, thiz.toClone()).autoClose(),
                            )
                        } catch (e: Exception) {
                            context.throwExceptionToJs(e)
                        } as JsValueImpl
                    ).v8Value.toClone()
                }
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
}
