package app.zenmoney.jsbridge

import kotlinx.datetime.Instant

expect sealed interface JsDate : JsObject {
    fun toMillis(): Long
}

expect fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate

fun JsDate.toInstant(): Instant = Instant.fromEpochMilliseconds(toMillis())
