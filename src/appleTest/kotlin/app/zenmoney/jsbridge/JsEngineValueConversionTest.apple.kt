package app.zenmoney.jsbridge

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSDate
import platform.Foundation.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class JsEngineValueConversionTest {
    @Test
    fun boxedBooleansPreserveTheirPrimitiveValues() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                for (expected in listOf(false, true)) {
                    val created = JsBooleanObject(expected)
                    val evaluated = assertIs<JsBooleanObject>(eval("new Boolean($expected)"))

                    assertEquals(expected, created.toBoolean())
                    assertEquals(expected, evaluated.toBoolean())
                    assertEquals(expected, evaluated.boolean)
                    assertEquals(expected, evaluated.toPlainValue())
                    assertEquals(expected, JsValueAlias(evaluated).toBoolean())
                }
            }
        }
    }

    @Test
    fun boxedBooleansUseTheIntrinsicValueOf() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val value =
                    assertIs<JsBooleanObject>(
                        eval(
                            """
                            Boolean.prototype.valueOf = function () { throw new Error('overridden valueOf'); };
                            const value = new Boolean(false);
                            value.valueOf = function () { return true; };
                            value;
                            """.trimIndent(),
                        ),
                    )

                assertFalse(value.toBoolean())
                assertFalse(JsValueAlias(value).toBoolean())
            }
        }
    }

    @OptIn(BetaInteropApi::class)
    @Test
    fun nsDatesUseUnixEpochMilliseconds() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                for (expected in listOf(0L, 1_000L, -1_000L, 1_704_067_200_000L)) {
                    val date = NSDate.create(timeIntervalSince1970 = expected / 1000.0)

                    assertEquals(expected, JsDate(date).toMillis())
                }
            }
        }
    }

    @Test
    fun classificationErrorsAreThrownByTheFailingEvaluation() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                repeat(3) {
                    val exception =
                        assertFailsWith<JsException> {
                            eval("({ get then() { throw new Error('classification failed'); } })")
                        }

                    assertEquals("classification failed", exception.message)
                    assertEquals(42, eval("6 * 7").int)
                }
            }
        }
    }

    @Test
    fun classificationErrorsAreThrownByTheFailingFunctionCall() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val createValue =
                    assertIs<JsFunction>(
                        eval("(() => ({ get then() { throw new Error('result classification failed'); } }))"),
                    )

                val exception = assertFailsWith<JsException> { createValue() }

                assertEquals("result classification failed", exception.message)
                assertEquals(42, eval("6 * 7").int)
            }
        }
    }
}
