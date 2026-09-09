package app.zenmoney.jsbridge

expect sealed interface JsNull : JsValue

context(scope: JsScope)
fun JsNull(): JsNull = scope.context.NULL
