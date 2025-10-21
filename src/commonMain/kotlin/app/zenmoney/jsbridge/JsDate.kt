package app.zenmoney.jsbridge

expect sealed interface JsDate : JsObject {
    fun toMillis(): Long
}

internal expect fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate

fun JsScope.JsDate(millis: Long): JsDate = JsDate(context, millis).autoClose()
