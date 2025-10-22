package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsPromise : JsObject

internal open class JsPromiseImpl(
    context: JsContext,
    v8Object: V8Object,
) : JsObjectImpl(context, v8Object),
    JsPromise
