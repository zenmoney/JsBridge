package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsEngineNumericValuesTest {
    @Test
    fun longValuesUseJavaScriptNumbers() {
        JsEngineContext().use { context ->
            for (value in listOf(0L, 1L, -1L, 9_007_199_254_740_991L, Long.MAX_VALUE)) {
                jsScoped(context) {
                    context.globalThis["number"] = JsNumber(value)
                    context.globalThis["boxedNumber"] = JsNumberObject(value)

                    assertEquals("number", eval("typeof number").string)
                    assertEquals("number", eval("typeof boxedNumber.valueOf()").string)
                    assertEquals(value.toDouble() + 1, eval("number + 1").double)
                    assertEquals(value.toDouble() + 1, eval("boxedNumber + 1").double)
                    assertIs<JsString>(eval("JSON.stringify([number, boxedNumber])"))
                }
            }
        }
    }

    @Test
    fun byteArrayConversionRespectsTypedArrayViewBounds() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val scripts =
                    listOf(
                        "new Uint8Array([10,20,30,40]).subarray(1,3)" to byteArrayOf(20, 30),
                        "new Uint8Array(new Uint8Array([10,20,30,40]).buffer,1,2)" to byteArrayOf(20, 30),
                        "new Uint8Array([10,20,30,40]).subarray(4)" to byteArrayOf(),
                        "new Uint8Array([0,127,128,255])" to byteArrayOf(0, 127, -128, -1),
                        "new Uint8Array(1024 * 1024).subarray(1024,1027)" to byteArrayOf(0, 0, 0),
                    )
                for ((script, expected) in scripts) {
                    val value = assertIs<JsUint8Array>(eval(script))
                    assertEquals(expected.size, value.size)
                    assertContentEquals(expected, value.toByteArray())
                    assertContentEquals(expected, assertIs<ByteArray>(value.toPlainValue()))
                }
            }
        }
    }
}
