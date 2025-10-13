package app.zenmoney.jsbridge

import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.values.reference.V8ValueFunction
import kotlin.Throws

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
    val name = "f$value${value.hashCode()}".filter { it.isLetterOrDigit() }
    val callbackContext =
        JavetCallbackContext(
            name,
            JavetCallbackType.DirectCallThisAndResult,
            IJavetDirectCallable.ThisAndResult<Exception> { thiz, args ->
                (
                    try {
                        value(
                            JsValue(context, thiz.toClone()),
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

    @Throws(JsException::class)
    override fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue = context.callFunction(this, thiz, args)

    @Throws(JsException::class)
    override fun applyAsConstructor(args: List<JsValue>): JsValue =
        context.callFunctionAsConstructor(
            ArrayList<JsValue>(args.size + 1).apply {
                add(this@JsFunctionImpl)
                addAll(args)
            },
        )
}
