package app.zenmoney.jsbridge

expect sealed interface JsUndefined : JsValue

context(scope: JsScope)
fun JsUndefined(): JsUndefined = scope.context.UNDEFINED
