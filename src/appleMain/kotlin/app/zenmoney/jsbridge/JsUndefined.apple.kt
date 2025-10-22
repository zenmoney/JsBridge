package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsUndefined : JsValue

internal open class JsUndefinedImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsUndefined
