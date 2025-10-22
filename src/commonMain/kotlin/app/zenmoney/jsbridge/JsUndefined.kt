package app.zenmoney.jsbridge

expect sealed interface JsUndefined : JsValue

fun JsScope.JsUndefined(): JsUndefined = context.UNDEFINED
