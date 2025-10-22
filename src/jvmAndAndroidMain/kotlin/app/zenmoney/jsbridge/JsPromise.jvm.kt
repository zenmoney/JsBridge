package app.zenmoney.jsbridge

import com.caoccao.javet.values.reference.V8ValueObject

actual sealed interface JsPromise : JsObject

internal open class JsPromiseImpl(
    context: JsContext,
    v8Object: V8ValueObject,
) : JsObjectImpl(context, v8Object),
    JsPromise
