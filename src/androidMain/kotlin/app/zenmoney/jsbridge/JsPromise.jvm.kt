package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object

actual sealed interface JsPromise : JsObject

actual fun JsPromise(
    context: JsContext,
    executor: (JsFunction, JsFunction) -> Unit,
): JsPromise =
    context.createPromise(
        listOf(
            JsFunction(context) { args ->
                this.close()
                args.forEachIndexed { index, it -> if (index > 1) it.close() }
                executor(args[0] as JsFunction, args[1] as JsFunction)
                context.UNDEFINED
            },
        ),
    ) as JsPromise

internal open class JsPromiseImpl(
    context: JsContext,
    v8Object: V8Object,
) : JsObjectImpl(context, v8Object),
    JsPromise
