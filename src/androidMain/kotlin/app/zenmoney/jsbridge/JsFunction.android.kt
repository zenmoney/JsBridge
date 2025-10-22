package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Function

actual sealed interface JsFunction : JsObject

internal class JsFunctionImpl(
    context: JsContext,
    v8Function: V8Function,
) : JsObjectImpl(context, v8Function),
    JsFunction {
    val v8Function: V8Function
        get() = v8Value as V8Function
}
