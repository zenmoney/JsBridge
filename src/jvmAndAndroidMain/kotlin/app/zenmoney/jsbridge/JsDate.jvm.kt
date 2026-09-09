package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueZonedDateTime
import java.time.ZonedDateTime
import java.util.Date

actual sealed interface JsDate : JsObject {
    actual fun toMillis(): Long
}

context(scope: JsScope)
fun JsDate(date: Date): JsDate = JsDate(scope.context, date.time).autoClose()

context(scope: JsScope)
fun JsDate(date: ZonedDateTime): JsDate = JsDate(scope.context, date.toInstant().toEpochMilli()).autoClose()

internal class JsDateImpl(
    context: JsContext,
    v8Value: V8ValueZonedDateTime,
) : JsValueImpl(context, v8Value),
    JsDate {
    private val millis = v8Value.toPrimitive()

    override fun hashCode(): Int = toMillis().toInt()

    override fun equals(other: Any?): Boolean = other is JsDate && context === other.context && toMillis() == other.toMillis()

    override fun toMillis(): Long = millis

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        TODO("Not yet implemented")
    }
}
