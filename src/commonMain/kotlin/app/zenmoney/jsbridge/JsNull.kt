package app.zenmoney.jsbridge

expect sealed interface JsNull : JsValue

fun JsScope.JsNull(): JsNull = context.NULL
