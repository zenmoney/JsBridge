package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import platform.JavaScriptCore.valueAtIndex

actual sealed interface JsArray : JsObject {
    actual val size: Int
}

internal class JsArrayImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsArray {
    override val size: Int
        get() = jsValue.objectForKeyedSubscript("length")?.toInt32() ?: 0
}
