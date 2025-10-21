package app.zenmoney.jsbridge

expect sealed interface JsUint8Array : JsObject {
    val size: Int

    fun toByteArray(): ByteArray
}

internal expect fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array

fun JsScope.JsUint8Array(value: ByteArray): JsUint8Array = JsUint8Array(context, value).autoClose()
