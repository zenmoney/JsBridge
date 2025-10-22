package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsFunction : JsObject

internal class JsFunctionImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsFunction
