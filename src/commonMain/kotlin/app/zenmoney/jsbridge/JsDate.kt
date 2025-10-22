package app.zenmoney.jsbridge

expect sealed interface JsDate : JsObject {
    fun toMillis(): Long
}

internal fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate = context.createDate(millis)

fun JsScope.JsDate(millis: Long): JsDate = JsDate(context, millis).autoClose()
