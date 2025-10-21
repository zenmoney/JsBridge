package app.zenmoney.jsbridge

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.JavaScriptCore.JSValue
import kotlin.math.roundToLong

actual sealed interface JsDate : JsObject {
    actual fun toMillis(): Long
}

internal actual fun JsDate(
    context: JsContext,
    millis: Long,
): JsDate = JsValue(context, context.jsDate.constructWithArguments(listOf(millis))) as JsDate

fun JsScope.JsDate(date: NSDate): JsDate = JsValue(context, JSValue.valueWithObject(date, context.jsContext)).autoClose() as JsDate

internal class JsDateImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsDate {
    private val millis = (jsValue.toDate()!!.timeIntervalSince1970 * 1000.0).roundToLong()

    override fun hashCode(): Int = millis.toInt()

    override fun equals(other: Any?): Boolean = other is JsDateImpl && millis == other.millis

    override fun toMillis(): Long = millis
}
