package app.zenmoney.jsbridge

expect sealed interface JsUint8Array : JsValue {
    val size: Int

    fun toByteArray(): ByteArray
}

expect fun JsUint8Array(
    context: JsContext,
    value: ByteArray,
): JsUint8Array
