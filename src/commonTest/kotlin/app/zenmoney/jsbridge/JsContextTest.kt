package app.zenmoney.jsbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class JsContextTest {
    protected lateinit var context: JsContext

    @BeforeTest
    fun onBeforeTest() {
        context = createContext()
    }

    @AfterTest
    fun onAfterTest() {
        if (::context.isInitialized) {
            context.close()
        }
    }

    abstract fun createContext(): JsContext

    private fun attachEventLoop(coroutineScope: CoroutineScope): JsEventLoop =
        JsEventLoop(coroutineScope.coroutineContext).apply {
            attachTo(context)
        }

    private fun CoroutineScope.awaitPromise(
        value: JsValue,
        checkType: Boolean = true,
    ): Deferred<JsValue> {
        if (checkType) {
            assertIs<JsPromise>(value)
        }
        val scope = JsScope(context)
        return async {
            try {
                with(scope) {
                    value.await().escape()
                }
            } finally {
                scope.close()
            }
        }
    }

    private fun CoroutineScope.awaitPromiseResult(value: JsValue): Deferred<Result<JsValue>> {
        assertIs<JsPromise>(value)
        val scope = JsScope(context)
        return async {
            try {
                Result.success(
                    with(scope) {
                        value.await().escape()
                    },
                )
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                scope.close()
            }
        }
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
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(
                mapOf("a" to 1.4, "b" to "2", "c" to mapOf("c" to listOf("ccc"))),
                e.data,
            )
        }
    }

    @Test
    fun toPlainValueReusesRepeatedObjectReferences() {
        val value =
            context.evaluateScript(
                """
                const child = {x: 1};
                ({a: child, b: child});
                """.trimIndent(),
            )

        val map = assertIs<Map<*, *>>(value.toPlainValue())
        val a = map["a"]
        val b = map["b"]

        assertSame(a, b)
        assertEquals(mapOf("x" to 1.0), a)
    }

    @Test
    fun toPlainValueHandlesCircularObjectReferences() {
        val value =
            context.evaluateScript(
                """
                const value = {name: "root"};
                value.self = value;
                value;
                """.trimIndent(),
            )

        val map = assertIs<Map<*, *>>(value.toPlainValue())

        assertEquals("root", map["name"])
        assertSame(map, map["self"])
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
        assertNotEquals(JsBooleanObject(context, true), value)
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
        assertNotEquals(JsNumberObject(context, 3), value)
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
        assertNotEquals(JsStringObject(context, "abc"), value)
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
            assertEquals("Error", e.name)
            assertEquals("Wrong this", e.message)
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
            assertEquals(JsNumber(context, 5), jsScoped(context) { result.await().escape() })
            eventLoop.runAndComplete()
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
                jsScoped(context) { result.await() }
                assertTrue(false)
            } catch (e: JsException) {
                assertEquals("Error", e.name)
                assertEquals("my error message", e.message)
                assertEquals(
                    mapOf("a" to 1.4, "b" to "2", "c" to mapOf("c" to listOf("ccc"))),
                    e.data,
                )
            }
            eventLoop.runAndComplete()
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
            eventLoop.runAndComplete()
            assertEquals(2, a.size)
            assertEquals(JsNumber(context, 5), a.getValue(1))
        }

    @Test
    fun callsNativeFunctionWithoutArgumentsAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            val value =
                JsFunction(context) {
                    callCount++
                    assertEquals(emptyList(), it)
                    JsNumber(context, 3)
                }
            context.globalThis["f"] = value

            val result = awaitPromise(context.evaluateScript("(async () => await f())()"))
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 3), result.await())
            assertEquals(value, context.createValueAlias(value))
        }

    @Test
    fun callsNativeFunctionAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            context.globalThis["f"] =
                JsFunction(context) {
                    callCount++
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

            val result = awaitPromise(context.evaluateScript("(async () => await f(1, null, undefined, 2))()"))
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 5), result.await())
        }

    @Test
    fun callsNativeFunctionWithGivenThisAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
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

            val result = awaitPromise(context.evaluateScript("(async () => await obj.f(1, 2, a, b))()"))
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
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
            assertEquals(JsNumber(context, 5), result.await())
        }

    @Test
    fun callsNativeFunctionReturningArrayOfObjectsAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            val array =
                JsArray(
                    context,
                    ('a'..'z').map { context.evaluateScript("({$it: '$it'})") },
                )
            context.globalThis["f"] =
                JsFunction(context) {
                    callCount++
                    array
                }

            val result = awaitPromise(context.evaluateScript("(async () => await f())()"))
            eventLoop.runAndComplete()
            val arrayResult = result.await()

            assertEquals(1, callCount)
            assertIs<JsArray>(arrayResult)
            assertEquals(26, arrayResult.size)
            assertEquals(
                ('a'..'z').map {
                    mapOf(it.toString() to it.toString())
                },
                arrayResult.toPlainList(),
            )
        }

    @Test
    fun callsNativeFunctionAsConstructorAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            var thiz: JsValue? = null
            context.globalThis["f"] =
                JsFunction(context) {
                    callCount++
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

            val result =
                awaitPromise(
                    context.evaluateScript(
                        """
                        (async () => {
                            const result = new f(1, null, undefined, 2);
                            if (result instanceof Promise) await result;
                        })()
                        """.trimIndent(),
                    ),
                )
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
            assertIs<JsObject>(thiz)
            assertNotEquals(context.globalThis, thiz)
            assertEquals(context.UNDEFINED, result.await())
        }

    @Test
    fun throwsJsExceptionWithNativeExceptionCauseAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            val exception = RuntimeException("my error message")
            val f =
                JsFunction(context) {
                    throw exception
                }
            context.globalThis["f"] = f

            val result = awaitPromiseResult(context.evaluateScript("(async () => await f(1, 2))()"))
            eventLoop.run()
            val e = assertIs<JsException>(result.await().exceptionOrNull())
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)

            val asyncF = context.evaluateScript("(async (...args) => await f(...args))") as JsFunction
            val promise = asyncF.call(listOf(JsNumber(context, 1), JsNumber(context, 2)))
            val promiseResult = awaitPromiseResult(promise)
            eventLoop.runAndComplete()
            val promiseException = assertIs<JsException>(promiseResult.await().exceptionOrNull())
            assertEquals("Error", promiseException.name)
            assertEquals("my error message", promiseException.message)
            assertEquals(exception, promiseException.cause)
            assertEquals(emptyMap(), promiseException.data)
        }

    @Test
    fun throwsJsExceptionWithNativeExceptionCauseWithEmptyMessageAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            val exception = NullPointerException()
            context.globalThis["f"] =
                JsFunction(context) {
                    throw exception
                }

            val result = awaitPromiseResult(context.evaluateScript("(async () => await f(1, 2))()"))
            eventLoop.runAndComplete()
            val e = assertIs<JsException>(result.await().exceptionOrNull())

            assertEquals("Error", e.name)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
        }

    @Test
    fun catchesNativeExceptionAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            val exception = RuntimeException("my error message")
            context.globalThis["f"] =
                JsFunction(context) {
                    throw exception
                }

            val error =
                awaitPromise(
                    context.evaluateScript(
                        """
                        (async () => {
                            try {
                                await f(1, 2);
                            } catch (e) {
                                return e;
                            }
                        })()
                        """.trimIndent(),
                    ),
                )
            eventLoop.runAndComplete()
            val e = JsException(error.await())

            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
        }

    @Test
    fun rejectsPromiseWhenNativeExecutorThrows() =
        runTest {
            val eventLoop = attachEventLoop(this)
            val exception = RuntimeException("executor failed")
            val promise =
                JsPromise(context) { _, _ ->
                    throw exception
                }
            val result = awaitPromiseResult(promise)

            eventLoop.runAndComplete()

            val e = assertIs<JsException>(result.await().exceptionOrNull())
            assertEquals("Error", e.name)
            assertEquals("executor failed", e.message)
            assertEquals(exception, e.cause)
        }

    @Test
    fun objectReturnedFromNativeFunctionIsNotClosedAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            val a = JsObject(context)
            val b = JsObject(context)
            a["b"] = b
            context.globalThis["f"] =
                JsFunction(context) {
                    callCount++
                    a
                }

            val result = awaitPromise(context.evaluateScript("(async () => await f())()"))
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
            assertEquals(a, result.await())
            a["c"] = JsNumber(context, 1)
            a["d"] = JsNumber(context, 2)
        }

    @Test
    fun returnsJsErrorObjectAndConvertsItToJsExceptionAsynchronously() =
        runTest {
            val eventLoop = attachEventLoop(this)
            val exception = RuntimeException("my error message")
            context.globalThis["f"] =
                JsFunction(context) {
                    JsObject(exception)
                }

            val error = awaitPromise(context.evaluateScript("(async () => await f(1, 2))()"))
            eventLoop.runAndComplete()
            val errorValue = error.await()
            assertIs<JsObject>(errorValue)
            context.globalThis["error"] = errorValue
            assertEquals(JsBoolean(context, true), context.evaluateScript("error instanceof Error"))
            val e = JsException(errorValue)
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
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

    @Test
    fun callsOnCompletionListenerWhenEventLoopHasRunAndCompleted() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            var isCancelled: Boolean? = null
            var e: Throwable? = null
            eventLoop.onCompletion = { cancelled, exception ->
                isCancelled = cancelled
                e = exception
            }
            eventLoop.runAndComplete()
            assertEquals(false, isCancelled)
            assertNull(e)
        }

    @Test
    fun callsOnCompletionListenerWhenEventLoopIsCancelled() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            var isCancelled: Boolean? = null
            var e: Throwable? = null
            eventLoop.onCompletion = { cancelled, exception ->
                isCancelled = cancelled
                e = exception
            }
            eventLoop.cancel()
            assertEquals(true, isCancelled)
            assertNull(e)
        }

    @Test
    fun callsOnCompletionListenerWhenEventLoopIsCancelledBecauseOfException() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            var isCancelled: Boolean? = null
            var e: Throwable? = null
            eventLoop.onCompletion = { cancelled, exception ->
                isCancelled = cancelled
                e = exception
            }
            var exception: Throwable? = null
            val siblingJob =
                eventLoop.launch {
                    awaitCancellation()
                }
            assertTrue(siblingJob.isActive)
            val job =
                eventLoop.launch {
                    exception = RuntimeException()
                    throw exception
                }
            job.join()
            assertEquals(true, isCancelled)
            assertEquals(exception, e)
            assertTrue(job.isCancelled)
            assertTrue(siblingJob.isCancelled)
        }

    @Test
    fun evalBlockScopedCreatesValidBindings() {
        jsScoped(context) {
            val a = JsObject()
            val obj =
                JsObject().apply {
                    this["a"] = a
                }
            assertEquals(JsString("undefined"), eval("typeof obj"))
            val result =
                evalBlockScoped(
                    "obj['a']",
                    "obj" to obj,
                )
            assertEquals(a, result)
            assertEquals(JsString("undefined"), eval("typeof obj"))
        }
    }

    @Test
    fun doesNotCloseSingletonValuesUntilContextIsClosed() {
        listOf(
            context.NULL,
            context.UNDEFINED,
            context.globalThis,
        ).forEach {
            if (it.isSingleton()) {
                it.close()
                assertFalse(it.isClosed)
            }
        }
    }

    @Test
    fun setsNativeObjectAsTag() {
        jsScoped(context) {
            val a1 = JsObject()
            val a2 = JsValueAlias(a1)
            assertNull(a1.getTag("nativeObject"))
            assertNull(a2.getTag("nativeObject"))
            val nativeObject = Any()
            a1.setTag("nativeObject", nativeObject)
            assertEquals(nativeObject, a1.getTag("nativeObject"))
            assertEquals(nativeObject, a2.getTag("nativeObject"))
            a2.removeTag("nativeObject")
            assertNull(a1.getTag("nativeObject"))
            assertNull(a2.getTag("nativeObject"))
        }
    }

    @Test
    fun keepsNativeFunctionCallbackAfterNativeWrapperIsClosedWhileJsRetainsFunction() =
        runTest {
            val eventLoop = attachEventLoop(this)
            var callCount = 0
            val f =
                JsFunction(context) {
                    callCount++
                    JsNumber(context, 7)
                }
            context.globalThis["f"] = f
            f.close()

            val result = awaitPromise(context.evaluateScript("f()"), checkType = false)
            eventLoop.runAndComplete()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 7), result.await())
        }
}
