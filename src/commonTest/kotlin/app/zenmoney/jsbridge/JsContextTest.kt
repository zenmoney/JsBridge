package app.zenmoney.jsbridge

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JsContextTest {
    private lateinit var context: JsContext

    @BeforeTest
    fun createContext() {
        context = JsContext()
    }

    @AfterTest
    fun closeContext() {
        context.close()
    }

    @Test
    fun globalObjectEqualsGlobalThis() {
        val thiz = context.evaluateScript("this")
        val globalThis = context.evaluateScript("globalThis")
        assertEquals(context.globalObject, thiz)
        assertEquals(context.globalObject, globalThis)
    }

    @Test
    fun throwsJsException() {
        assertFailsWith(JsException::class) {
            context.evaluateScript("f()")
        }
    }

    @Test
    fun throwsJsExceptionWithData() {
        try {
            context.evaluateScript(
                """
                const e = new Error("my error message");
                e.a = 1.4;
                e.b = "2";
                e.c = {c: ["ccc"]};
                throw e;
                """.trimIndent(),
            )
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error: my error message", e.message)
            assertEquals(
                mapOf("a" to 1.4, "b" to "2", "c" to mapOf("c" to listOf("ccc"))),
                e.data,
            )
        }
    }

    @Test
    fun throwsJsExceptionWithNativeExceptionCause() {
        var exception: Exception? = null
        val f =
            JsFunction(context) { args ->
                exception = RuntimeException("my error message")
                throw exception!!
            }
        context.globalObject["f"] = f
        try {
            context.evaluateScript("f(1, 2)")
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error: my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(
                emptyMap(),
                e.data,
            )
        }
        try {
            f.apply(
                context.globalObject,
                listOf(
                    JsNumber(context, 1),
                    JsNumber(context, 2),
                ),
            )
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals("Error: my error message", e.message)
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
            JsFunction(context) { args ->
                exception = NullPointerException()
                throw exception!!
            }

        context.globalObject["f"] = f
        try {
            context.evaluateScript("f(1, 2)")
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals(exception, e.cause)
            assertEquals(
                emptyMap(),
                e.data,
            )
        }
    }

    @Test
    fun returnsBoolean() {
        val value = context.evaluateScript("true")
        assertIs<JsBoolean>(value)
        assertEquals(true, value.toBoolean())
        assertEquals(JsBoolean(context, true), value)
    }

    @Test
    fun returnsBooleanObject() {
        val value = context.evaluateScript("new Boolean(true)")
        assertIs<JsBoolean>(value)
        assertIs<JsBooleanObject>(value)
        assertEquals(true, value.toBoolean())
        assertEquals(JsBooleanObject(context, true), value)
        assertNotEquals(JsBoolean(context, true), value)
    }

    @Test
    fun returnsNumber() {
        val value = context.evaluateScript("1 + 2")
        assertIs<JsNumber>(value)
        assertEquals(3.0, value.toNumber().toDouble())
        assertEquals(JsNumber(context, 3), value)
        assertEquals(JsNumber(context, 3).hashCode(), value.hashCode())
    }

    @Test
    fun returnsNumberObject() {
        val value = context.evaluateScript("new Number(1 + 2)")
        assertIs<JsNumber>(value)
        assertIs<JsNumberObject>(value)
        assertEquals(3.0, value.toNumber().toDouble())
        assertEquals(JsNumberObject(context, 3), value)
        assertEquals(JsNumberObject(context, 3).hashCode(), value.hashCode())
        assertNotEquals(JsNumber(context, 3), value)
    }

    @Test
    fun returnsString() {
        val value = context.evaluateScript("\"abc\"")
        assertIs<JsString>(value)
        assertEquals("abc", value.toString())
        assertEquals(JsString(context, "abc"), value)
    }

    @Test
    fun returnsStringObject() {
        val value = context.evaluateScript("new String(\"abc\")")
        assertIs<JsString>(value)
        assertIs<JsStringObject>(value)
        assertEquals("abc", value.toString())
        assertEquals(JsStringObject(context, "abc"), value)
        assertNotEquals(JsString(context, "abc"), value)
    }

    @Test
    fun returnsArray() {
        val value = context.evaluateScript("[\"abc\", 3.5, true, {a: 2.4}]")
        assertIs<JsArray>(value)
        assertEquals(listOf("abc", 3.5, true, mapOf("a" to 2.4)), value.toPlainList())
    }

    @Test
    fun returnsUint8Array() {
        val value = context.evaluateScript("Uint8Array.from([1, 2, 3, 127, 128, 255])")
        assertIs<JsUint8Array>(value)
        assertEquals(6, value.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 127, 128.toByte(), 255.toByte()), value.toByteArray())
    }

    @Test
    fun passesUint8ArrayToJs() {
        val arr1 = JsUint8Array(context, byteArrayOf(1, 2, 3, 127, 128.toByte(), 255.toByte()))
        context.globalObject["arr"] = arr1
        val arr2 = context.globalObject["arr"]
        assertIs<JsUint8Array>(arr2)
        val isEqual =
            context.evaluateScript(
                """
                (function (arr1, arr2) {
                    if (arr1 instanceof Uint8Array !== arr2 instanceof Uint8Array) {
                        return false;
                    }
                    if (arr1.length !== arr2.length) {
                        return false;
                    }
                    return arr1.every((value, index) => value === arr2[index]);
                })(arr, Uint8Array.from([1, 2, 3, 127, 128, 255]))
                """.trimIndent(),
            )
        assertIs<JsBoolean>(isEqual)
        assertTrue(isEqual.toBoolean())
    }

    @Test
    fun returnsDate() {
        val value = context.evaluateScript("new Date(45678)")
        assertIs<JsDate>(value)
        assertEquals(45678, value.toMillis())
        val date = JsDate(context, 45678)
        assertEquals(date, value)
        assertEquals(date.hashCode(), value.hashCode())
    }

    @Test
    fun objectEqualsTheSameObject() {
        val a1 = context.evaluateScript("var a = {a: 1}; a")
        assertIs<JsObject>(a1)
        assertEquals(JsNumber(context, 1), a1["a"])
        val a2 = context.evaluateScript("a")
        assertIs<JsObject>(a2)
        assertEquals(JsNumber(context, 1), a2["a"])
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        val b1 = context.evaluateScript("var b = {a: 1}; b")
        assertNotEquals(a1, b1)
        assertNotEquals(a2, b1)
    }

    @Test
    fun arrayEqualsTheSameArray() {
        val a1 = context.evaluateScript("var a = [1]; a")
        assertIs<JsArray>(a1)
        assertEquals(JsNumber(context, 1), a1[0])
        val a2 = context.evaluateScript("a")
        assertIs<JsArray>(a2)
        assertEquals(JsNumber(context, 1), a2[0])
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        val b1 = context.evaluateScript("var b = [1]; b")
        assertNotEquals(a1, b1)
        assertNotEquals(a2, b1)
    }

    @Test
    fun callsNativeFunctionWithoutArguments() {
        var callCount = 0
        context.globalObject["f"] =
            JsFunction(context) { args ->
                callCount++
                assertEquals(emptyList(), args)
                JsNumber(context, 3)
            }
        val result = context.evaluateScript("f()")
        assertEquals(1, callCount)
        assertEquals(JsNumber(context, 3), result)
    }

    @Test
    fun callsNativeFunction() {
        var callCount = 0
        context.globalObject["f"] =
            JsFunction(context) { args ->
                callCount++
                assertEquals(4, args.size)
                assertEquals(
                    listOf(
                        JsNumber(context, 1),
                        context.NULL,
                        context.UNDEFINED,
                        JsNumber(context, 2),
                    ),
                    args,
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
        context.globalObject["obj"] = obj
        val a = context.evaluateScript("var a = {}; a")
        val b = JsArray(context, listOf(a, JsNumber(context, 7.1)))
        context.globalObject["b"] = b
        obj["f"] =
            JsFunction(context) {
                callCount++
                thiz = this
                args = it
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
        context.globalObject["f"] =
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
    fun callsJsFunction() {
        context.evaluateScript("var callCount = 0;")
        val f =
            context.evaluateScript(
                """
                function f(a, b) {
                    callCount++;
                    return a + b;
                };
                f
                """.trimIndent(),
            )
        assertIs<JsFunction>(f)
        assertEquals(
            JsNumber(context, 5),
            f.apply(
                context.globalObject,
                listOf(
                    JsNumber(context, 2),
                    JsNumber(context, 3),
                ),
            ),
        )
        assertEquals(JsNumber(context, 1), context.globalObject["callCount"])
    }

    @Test
    fun callsJsFunctionWithGivenThis() {
        val foo =
            context.evaluateScript(
                """
                var callCount = 0;
                var foo = {};
                foo;
                """.trimIndent(),
            )
        val f =
            context.evaluateScript(
                """
                function f(a, b) {
                    callCount++;
                    if (a !== 1 || b !== 2) {
                        throw new Error("Wrong arguments");
                    }
                    if (this !== foo) {
                        throw new Error("Wrong this");
                    }
                };
                f
                """.trimIndent(),
            ) as JsFunction
        try {
            f.apply(
                context.globalObject,
                listOf(
                    JsNumber(context, 1),
                    JsNumber(context, 2),
                ),
            )
            assertTrue(false)
        } catch (e: JsException) {
            assertEquals(JsNumber(context, 1), context.evaluateScript("callCount"))
            assertEquals("Error: Wrong this", e.message)
        }
        f.apply(
            foo,
            listOf(
                JsNumber(context, 1),
                JsNumber(context, 2),
            ),
        )
        assertEquals(JsNumber(context, 2), context.evaluateScript("callCount"))
    }

    @Test
    fun changesAreVisibleToBothJsAndNativeCode() {
        val a = JsObject(context)
        context.globalObject["a"] = a
        val b = JsObject(context)
        a["b"] = b
        b["c"] = JsNumber(context, 5)
        assertEquals(JsNumber(context, 5), context.evaluateScript("a.b.c"))
        context.evaluateScript("a.b.c = '6';")
        assertEquals(JsString(context, "6"), context.evaluateScript("a.b.c"))
        b["c"] = JsNumber(context, 8)
        assertEquals(JsBoolean(context, true), context.evaluateScript("a.b.c == 8"))
    }

    @Test
    fun objectReturnedFromNativeFunctionIsNotClosedAutomatically() {
        var callCount = 0
        val a = JsObject(context)
        val b = JsObject(context)
        a["b"] = b
        context.globalObject["f"] =
            JsFunction(context) { args ->
                callCount++
                a
            }
        val result = context.evaluateScript("var obj = f()")
        assertEquals(context.UNDEFINED, result)
        assertEquals(1, callCount)
        a["c"] = JsNumber(context, 1)
        a["d"] = JsNumber(context, 2)
        b.close()
        a.close()
    }

    @Test
    fun returnsJsErrorObjectAndConvertsItToJsExceptionWithNativeExceptionCause() {
        var exception: Exception? = null
        val f =
            JsFunction(context) { args ->
                exception = RuntimeException("my error message")
                exception.toJsObject(context)
            }
        context.globalObject["f"] = f
        val error = context.evaluateScript("var error = f(1, 2); error")
        assertIs<JsObject>(error)
        assertEquals(JsBoolean(context, true), context.evaluateScript("error instanceof Error"))
        val e = error.toJsException()
        assertEquals("Error: my error message", e.message)
        assertEquals(exception, e.cause)
        assertEquals(
            emptyMap(),
            e.data,
        )
    }

    @Test
    fun resolvesPromiseValue() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            val result =
                context.evaluateScript(
                    """
                    new Promise((resolve) => {
                        setTimeout(() => resolve(5), 50);
                    })
                    """.trimIndent(),
                )
            assertIs<JsObject>(result)
            assertEquals(JsNumber(context, 5), result.await())
            eventLoop.runUntilIdle()
        }

    @Test
    fun throwsJsExceptionOnPromiseError() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            val result =
                context.evaluateScript(
                    """
                    new Promise((resolve, reject) => {
                        setTimeout(() => {
                            const e = new Error("my error message");
                            e.a = 1.4;
                            e.b = "2";
                            e.c = {c: ["ccc"]};
                            reject(e);
                        }, 50);
                    })
                    """.trimIndent(),
                )
            assertIs<JsObject>(result)
            try {
                result.await()
                assertTrue(false)
            } catch (e: JsException) {
                assertEquals("Error: my error message", e.message)
                assertEquals(
                    mapOf("a" to 1.4, "b" to "2", "c" to mapOf("c" to listOf("ccc"))),
                    e.data,
                )
            }
            eventLoop.runUntilIdle()
        }

    @Test
    fun callsNativeAsyncFunction() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            context.globalObject["f"] =
                JsFunction(context) {
                    with(eventLoop) {
                        async { JsNumber(context, 5) }.toJsPromise(context)
                    }
                }
            val a = context.evaluateScript("var a = [1]; a;")
            context.evaluateScript("f().then((result) => { a.push(result); });")
            assertIs<JsArray>(a)
            assertEquals(1, a.size)
            assertEquals(JsNumber(context, 1), a[0])
            eventLoop.runUntilIdle()
            assertEquals(2, a.size)
            assertEquals(JsNumber(context, 5), a[1])
        }
}
