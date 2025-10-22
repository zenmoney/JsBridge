package app.zenmoney.jsbridge

expect sealed interface JsPromise : JsObject

internal fun JsPromise(
    context: JsContext,
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = context.createPromise(executor)

fun JsScope.JsPromise(
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = JsPromise(context, executor).autoClose()
