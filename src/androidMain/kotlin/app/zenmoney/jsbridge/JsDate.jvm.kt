package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Object
import java.util.Date

actual sealed interface JsDate : JsObject {
    actual fun toMillis(): Long
}

actual fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate = context.createDate.apply(context.globalObject, listOf(JsNumber(context, millis))) as JsDate

fun JsDate(
    context: JsContext,
    date: Date,
): JsDate = JsDate(context, date.time)

internal class JsDateImpl(
    context: JsContext,
    v8Value: V8Object,
) : JsObjectImpl(context, v8Value),
    JsDate {
    private val millis: Long = (context.jsGetTime.call(v8Value, null) as Number).toLong()

    override fun hashCode(): Int = millis.toInt()

    override fun equals(other: Any?): Boolean = (other is JsDateImpl) && millis == other.millis

    override fun toMillis(): Long = millis
}
