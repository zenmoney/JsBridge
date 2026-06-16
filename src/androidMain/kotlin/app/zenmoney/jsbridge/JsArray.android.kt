package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Array

actual sealed interface JsArray : JsObject {
    actual val size: Int
}

internal class JsArrayImpl(
    context: JsContext,
    v8Value: V8Array,
) : JsObjectImpl(context, v8Value),
    JsArray {
    val v8Array: V8Array
        get() = v8Value as V8Array

    override val size: Int
        get() = v8Array.length()
}
