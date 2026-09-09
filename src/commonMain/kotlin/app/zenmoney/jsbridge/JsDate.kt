package app.zenmoney.jsbridge

expect sealed interface JsDate : JsObject {
    fun toMillis(): Long
}

internal fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate = context.createDate(millis)

context(scope: JsScope)
fun JsDate(millis: Long): JsDate = JsDate(scope.context, millis).autoClose()
