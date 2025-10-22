package app.zenmoney.jsbridge

expect sealed interface JsUint8Array : JsObject {
    val size: Int

    fun toByteArray(): ByteArray
}

internal fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array = context.createUint8Array(value)

fun JsScope.JsUint8Array(value: ByteArray): JsUint8Array = JsUint8Array(context, value).autoClose()
