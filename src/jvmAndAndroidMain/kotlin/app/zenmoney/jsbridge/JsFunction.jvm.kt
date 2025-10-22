package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueFunction

actual sealed interface JsFunction : JsObject

internal class JsFunctionImpl(
    context: JsContext,
    v8Function: V8ValueFunction,
) : JsObjectImpl(context, v8Function),
    JsFunction {
    val v8Function: V8ValueFunction
        get() = v8Value as V8ValueFunction
}
