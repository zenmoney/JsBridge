package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsNull : JsValue

internal open class JsNullImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsValueImpl(context, jsValue),
    JsNull
