package app.zenmoney.jsbridge

import com.caoccao.javet.values.primitive.V8ValueZonedDateTime
import java.time.ZonedDateTime
import java.util.Date

actual sealed interface JsDate : JsObject {
    actual fun toMillis(): Long
}

internal actual fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate = JsValue(context, context.v8Runtime.createV8ValueZonedDateTime(millis)) as JsDate

fun JsScope.JsDate(date: Date): JsDate = JsDate(context, date.time).autoClose()

fun JsScope.JsDate(date: ZonedDateTime): JsDate = JsValue(context, context.v8Runtime.createV8ValueZonedDateTime(date)).autoClose() as JsDate

internal class JsDateImpl(
    context: JsContext,
    v8Value: V8ValueZonedDateTime,
) : JsValueImpl(context, v8Value),
    JsDate {
    private val millis = v8Value.toPrimitive()

    override fun hashCode(): Int = millis.toInt()

    override fun equals(other: Any?): Boolean = (other is JsDateImpl) && millis == other.millis

    override fun toMillis(): Long = millis

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        TODO("Not yet implemented")
    }
}
