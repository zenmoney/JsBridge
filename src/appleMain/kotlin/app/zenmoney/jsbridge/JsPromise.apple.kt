package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsPromise : JsObject

internal open class JsPromiseImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsPromise
