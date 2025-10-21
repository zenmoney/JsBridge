package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsPromise : JsObject

internal actual fun JsPromise(
    context: JsContext,
    executor: JsScope.(JsFunction, JsFunction) -> Unit,
): JsPromise =
    jsScope(context) {
        this.context
            .createPromise(
                JsFunction { args, _ ->
                    executor(
                        this,
                        args[0] as JsFunction,
                        args[1] as JsFunction,
                    )
                    this.context.UNDEFINED
                },
            ).escape() as JsPromise
    }

internal open class JsPromiseImpl(
    context: JsContext,
    v8Object: V8Object,
) : JsObjectImpl(context, v8Object),
    JsPromise
