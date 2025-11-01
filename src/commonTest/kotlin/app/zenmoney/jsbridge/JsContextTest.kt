package app.zenmoney.jsbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(context.globalThis, thiz)
        assertEquals(context.globalThis, globalThis)
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
            JsFunction(context) {
                exception = RuntimeException("my error message")
                throw exception
            }
        context.globalThis["f"] = f
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
            f.call(
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
            JsFunction(context) {
                exception = NullPointerException()
                throw exception
            }

        context.globalThis["f"] = f
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
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsBooleanObject() {
        val value = context.evaluateScript("new Boolean(true)")
        assertIs<JsBoolean>(value)
        assertIs<JsBooleanObject>(value)
        assertEquals(true, value.toBoolean())
        assertEquals(JsBooleanObject(context, true), value)
        assertNotEquals(JsBoolean(context, true), value)
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsNumber() {
        val value = context.evaluateScript("1 + 2")
        assertIs<JsNumber>(value)
        assertEquals(3.0, value.toNumber().toDouble())
        assertEquals(JsNumber(context, 3), value)
        assertEquals(JsNumber(context, 3).hashCode(), value.hashCode())
        assertEquals(value, context.createValueAlias(value))
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
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsString() {
        val value = context.evaluateScript("\"abc\"")
        assertIs<JsString>(value)
        assertEquals("abc", value.toString())
        assertEquals(JsString(context, "abc"), value)
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsStringObject() {
        val value = context.evaluateScript("new String(\"abc\")")
        assertIs<JsString>(value)
        assertIs<JsStringObject>(value)
        assertEquals("abc", value.toString())
        assertEquals(JsStringObject(context, "abc"), value)
        assertNotEquals(JsString(context, "abc"), value)
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsArray() {
        val value = context.evaluateScript("[\"abc\", 3.5, true, {a: 2.4}]")
        assertIs<JsArray>(value)
        assertEquals(listOf("abc", 3.5, true, mapOf("a" to 2.4)), value.toPlainList())
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun returnsUint8Array() {
        val value = context.evaluateScript("Uint8Array.from([1, 2, 3, 127, 128, 255])")
        assertIs<JsUint8Array>(value)
        assertEquals(6, value.size)
        assertContentEquals(byteArrayOf(1, 2, 3, 127, 128.toByte(), 255.toByte()), value.toByteArray())
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun passesUint8ArrayToJs() {
        val arr1 = JsUint8Array(context, byteArrayOf(1, 2, 3, 127, 128.toByte(), 255.toByte()))
        context.globalThis["arr"] = arr1
        val arr2 = context.globalThis.getValue("arr")
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
        assertEquals(arr1, context.createValueAlias(arr1))
        assertEquals(arr1, context.createValueAlias(arr2))
    }

    @Test
    fun returnsDate() {
        val value = context.evaluateScript("new Date(45678)")
        assertIs<JsDate>(value)
        assertEquals(45678, value.toMillis())
        val date = JsDate(context, 45678)
        assertEquals(date, value)
        assertEquals(date.hashCode(), value.hashCode())
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun objectEqualsTheSameObject() {
        val a1 = context.evaluateScript("var a = {a: 1}; a")
        assertIs<JsObject>(a1)
        assertEquals(JsNumber(context, 1), a1.getValue("a"))
        val a2 = context.evaluateScript("a")
        assertIs<JsObject>(a2)
        assertEquals(JsNumber(context, 1), a2.getValue("a"))
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        val b1 = context.evaluateScript("var b = {a: 1}; b")
        assertNotEquals(a1, b1)
        assertNotEquals(a2, b1)
        assertEquals(a1, context.createValueAlias(a1))
    }

    @Test
    fun arrayEqualsTheSameArray() {
        val a1 = context.evaluateScript("var a = [1]; a")
        assertIs<JsArray>(a1)
        assertEquals(JsNumber(context, 1), a1.getValue(0))
        val a2 = context.evaluateScript("a")
        assertIs<JsArray>(a2)
        assertEquals(JsNumber(context, 1), a2.getValue(0))
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
        val b1 = context.evaluateScript("var b = [1]; b")
        assertNotEquals(a1, b1)
        assertNotEquals(a2, b1)
    }

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
            f.call(
                listOf(
                    JsNumber(context, 2),
                    JsNumber(context, 3),
                ),
            ),
        )
        assertEquals(JsNumber(context, 1), context.globalThis.getValue("callCount"))
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
            f.call(
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
        f.call(
            listOf(
                JsNumber(context, 1),
                JsNumber(context, 2),
            ),
            foo,
        )
        assertEquals(JsNumber(context, 2), context.evaluateScript("callCount"))
    }

    @Test
    fun changesAreVisibleToBothJsAndNativeCode() {
        val a = JsObject(context)
        context.globalThis["a"] = a
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
                        setTimeout(() => resolve(Promise.resolve(5)), 50);
                    })
                    """.trimIndent(),
                )
            assertIs<JsObject>(result)
            assertEquals(JsNumber(context, 5), result.await())
            eventLoop.runAndWaitForCompletion()
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
            eventLoop.runAndWaitForCompletion()
        }

    @Test
    fun callsNativeAsyncFunction() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            context.globalThis["f"] =
                JsFunction(context) {
                    JsPromise {
                        JsNumber(context, 5)
                    }
                }
            val a = context.evaluateScript("var a = [1]; a;")
            context.evaluateScript("f().then((result) => { a.push(result); });")
            assertIs<JsArray>(a)
            assertEquals(1, a.size)
            assertEquals(JsNumber(context, 1), a.getValue(0))
            eventLoop.runAndWaitForCompletion()
            assertEquals(2, a.size)
            assertEquals(JsNumber(context, 5), a.getValue(1))
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
    fun callsFunctionAsConstructor() {
        val errorFunction = context.evaluateScript("Error")
        assertIs<JsFunction>(errorFunction)
        val error = errorFunction.callAsConstructor(listOf(JsString(context, "my message")))
        assertIs<JsObject>(error)
        context.globalThis["error"] = error
        assertEquals(JsBoolean(context, true), context.evaluateScript("error instanceof Error"))
        assertEquals(JsString(context, "my message"), context.evaluateScript("error.message"))
    }

    @Test
    fun doesNotThrowOnRepeatingValueClose() {
        val a = JsObject(context)
        assertFalse(a.isScoped)
        assertFalse(a.isClosed)
        a.close()
        assertTrue(a.isClosed)
        a.close()
    }

    @Test
    fun scopeClosesOwnedObjects() {
        lateinit var a: JsObject
        jsScoped(context) {
            a = JsObject()
            assertFalse(a.isClosed)
            assertTrue(a.isScoped)
        }
        assertTrue(a.isClosed)
        assertFails { a.getValue("a") }
    }

    @Test
    fun scopeDoesNotCloseEscapedObject() {
        lateinit var a: JsObject
        jsScoped(context) {
            a = JsObject().escape()
            assertFalse(a.isClosed)
            assertFalse(a.isScoped)
        }
        assertFalse(a.isClosed)
        a.getValue("a")
    }
}
