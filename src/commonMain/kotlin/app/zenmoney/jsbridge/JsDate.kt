package app.zenmoney.jsbridge

expect sealed interface JsDate : JsObject {
    fun toMillis(): Long
}

expect fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate
