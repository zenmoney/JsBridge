package app.zenmoney.jsbridge

expect sealed interface JsPromise : JsObject

expect fun JsPromise(
    context: JsContext,
    executor: (
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise
