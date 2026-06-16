package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JsEngineContextTest : JsContextTest() {
    override fun createContext(): JsContext = JsContext()

    @Test
    fun callsNativeFunctionWithoutArguments() {
        var callCount = 0
        val value =
            JsFunction(context) {
                callCount++
                assertEquals(emptyList(), it)
                JsNumber(context, 3)
            }
        context.globalThis["f"] = value
        val result = context.evaluateScript("f()")
        assertEquals(1, callCount)
        assertEquals(JsNumber(context, 3), result)
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun callsNativeFunction() {
        var callCount = 0
        context.globalThis["f"] =
            JsFunction(context) {
                callCount++
                assertEquals(4, it.size)
                assertEquals(
                    listOf(
                        JsNumber(context, 1),
                        context.NULL,
                        context.UNDEFINED,
                        JsNumber(context, 2),
                    ),
                    it,
                )
                JsNumber(context, 5)
            }
        val result = context.evaluateScript("f(1, null, undefined, 2)")
        assertEquals(1, callCount)
        assertEquals(JsNumber(context, 5), result)
    }

    @Test
    fun callsNativeFunctionWithGivenThis() {
        var callCount = 0
        var thiz: JsValue? = null
        var args: List<JsValue>? = null
        val obj = JsObject(context)
        context.globalThis["obj"] = obj
        val a = context.evaluateScript("var a = {}; a")
        val b = JsArray(context, listOf(a, JsNumber(context, 7.1)))
        context.globalThis["b"] = b
        obj["f"] =
            JsFunction(context) {
                callCount++
                thiz = this.thiz.escape()
                args = it.escape()
                JsNumber(context, 5)
            }
        val result = context.evaluateScript("obj.f(1, 2, a, b)")
        assertEquals(1, callCount)
        assertEquals(4, args?.size)
        assertEquals(
            listOf(
                JsNumber(context, 1),
                JsNumber(context, 2),
                a,
                b,
            ),
            args,
        )
        assertEquals(obj, thiz)
        assertEquals(JsNumber(context, 5), result)
    }

    @Test
    fun callsNativeFunctionReturningArrayOfObjects() {
        var callCount = 0
        context.globalThis["f"] =
            JsFunction(context) {
                callCount++
                JsArray(
                    context,
                    ('a'..'z').map { context.evaluateScript("var obj = {$it: '$it'}; obj") },
                )
            }
        val result = context.evaluateScript("f()")
        assertEquals(1, callCount)
        assertIs<JsArray>(result)
        assertEquals(26, result.size)
        assertEquals(
            ('a'..'z').map {
                mapOf(it.toString() to it.toString())
            },
            result.toPlainList(),
        )
    }

    @Test
    fun callsNativeFunctionAsConstructor() {
        var callCount = 0
        var thiz: JsValue? = null
        context.globalThis["f"] =
            JsFunction(context) {
                callCount++
                assertEquals(4, it.size)
                assertEquals(
                    listOf(
                        JsNumber(context, 1),
                        context.NULL,
                        context.UNDEFINED,
                        JsNumber(context, 2),
                    ),
                    it,
                )
                thiz = this.thiz.escape()
                context.UNDEFINED
            }
        val result = context.evaluateScript("new f(1, null, undefined, 2)")
        assertEquals(1, callCount)
        assertEquals(thiz, result)
        assertIs<JsObject>(result)
        assertNotEquals(context.globalThis, result)
    }

    @Test
    fun throwsJsExceptionWithNativeExceptionCause() {
        var exception: Exception? = null
        val f =
            JsFunction(context) {
                exception = RuntimeException("my error message")
                throw exception
            }
        context.globalThis["f"] = f
        try {
            context.evaluateScript("f(1, 2)")
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(
                emptyMap(),
                e.data,
            )
        }
        try {
            f.call(
                listOf(
                    JsNumber(context, 1),
                    JsNumber(context, 2),
                ),
            )
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(
                emptyMap(),
                e.data,
            )
        }
    }

    @Test
    fun throwsJsExceptionWithNativeExceptionCauseWithEmptyMessage() {
        var exception: Exception? = null
        val f =
            JsFunction(context) {
                exception = NullPointerException()
                throw exception
            }

        context.globalThis["f"] = f
        try {
            context.evaluateScript("f(1, 2)")
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error", e.name)
            assertEquals(exception, e.cause)
            assertEquals(
                emptyMap(),
                e.data,
            )
        }
    }

    @Test
    fun catchesNativeException() {
        var exception: Exception? = null
        val f =
            JsFunction(context) {
                exception = RuntimeException("my error message")
                throw exception
            }
        context.globalThis["f"] = f
        val error = context.evaluateScript("try { f(1, 2) } catch (e) { e }")
        val e = JsException(error)
        assertEquals("Error", e.name)
        assertEquals("my error message", e.message)
        assertEquals(exception, e.cause)
        assertEquals(
            emptyMap(),
            e.data,
        )
    }

    @Test
    fun objectReturnedFromNativeFunctionIsNotClosedAutomatically() {
        var callCount = 0
        val a = JsObject(context)
        val b = JsObject(context)
        a["b"] = b
        context.globalThis["f"] =
            JsFunction(context) {
                callCount++
                a
            }
        val result = context.evaluateScript("var obj = f()")
        assertEquals(context.UNDEFINED, result)
        assertEquals(1, callCount)
        a["c"] = JsNumber(context, 1)
        a["d"] = JsNumber(context, 2)
    }

    @Test
    fun returnsJsErrorObjectAndConvertsItToJsExceptionWithNativeExceptionCause() {
        var exception: Exception? = null
        val f =
            JsFunction(context) {
                exception = RuntimeException("my error message")
                JsObject(exception)
            }
        context.globalThis["f"] = f
        val error = context.evaluateScript("var error = f(1, 2); error")
        assertIs<JsObject>(error)
        assertEquals(JsBoolean(context, true), context.evaluateScript("error instanceof Error"))
        val e = JsException(error)
        assertEquals("Error", e.name)
        assertEquals("my error message", e.message)
        assertEquals(exception, e.cause)
        assertEquals(
            emptyMap(),
            e.data,
        )
    }
}
