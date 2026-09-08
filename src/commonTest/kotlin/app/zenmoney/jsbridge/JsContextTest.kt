package app.zenmoney.jsbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
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

suspend fun JsValue.awaitEscaped(checkType: Boolean = true): JsValue {
    if (checkType) {
        assertIs<JsPromise>(this)
    }
    return jsScoped(context) { await().escape() }
}

abstract class JsContextTest {
    protected lateinit var context: JsContext

    protected open val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf("globalThis.onunhandledrejection:callback probe")
    protected open val expectPromiseOverride: Boolean = true

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

    @Test
    fun invokesCloseHandlersOnceAndSupportsDisposal() {
        var invoked = 0
        var disposedInvoked = 0
        val handler: () -> Unit = { invoked++ }
        context.invokeOnClose(handler)
        context.invokeOnClose(handler).dispose()
        context.invokeOnClose { disposedInvoked++ }.dispose()

        context.close()
        context.close()

        assertEquals(1, invoked)
        assertEquals(0, disposedInvoked)

        var lateInvoked = 0
        context.invokeOnClose { lateInvoked++ }.dispose()
        assertEquals(1, lateInvoked)
    }

    @Test
    fun closeAsyncNotifiesImmediatelyAndCompletesJobAfterCleanup() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            var closeNotified = false
            context.invokeOnClose { closeNotified = true }

            val closeJob = context.closeAsync()

            assertTrue(closeNotified)
            assertTrue(context.isClosed)
            assertFalse(closeJob.isCompleted)
            closeJob.join()
            assertTrue(closeJob.isCompleted)

            eventLoop.cancel()
            eventLoop.run()
        }

    @Test
    fun valuesAreClosedWhileAsyncCleanupIsQueued() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext).apply { attachTo(context) }
            val value = JsObject(context)
            val scopedValue = JsScope(context).run { JsObject() }
            val escapedValue = jsScoped(context) { JsObject().escape() }
            val explicitlyClosed = JsObject(context).also { it.close() }
            val values = listOf(value, scopedValue, escapedValue, context.globalThis, context.NULL, context.UNDEFINED)
            values.forEach { assertFalse(it.isClosed) }
            assertTrue(explicitlyClosed.isClosed)

            val closeJob = context.closeAsync()

            assertFalse(closeJob.isCompleted)
            values.forEach {
                assertTrue(it.isClosed)
                assertTrue(it.core.scope != null)
            }
            closeJob.join()
            values.forEach { assertNull(it.core.scope) }
            eventLoop.runAndComplete()
        }

    @Test
    fun eventLoopSkipsContextWhileAsyncCleanupIsQueued() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val eventLoop = JsEventLoop(coroutineContext + dispatcher).apply { attachTo(context) }
            // Queue the first tick before closeAsync() queues cleanup on the event-loop dispatcher.
            val running = async(start = CoroutineStart.UNDISPATCHED) { eventLoop.runAndComplete() }

            val closeJob = context.closeAsync()

            assertTrue(context.isClosed)
            assertFalse(closeJob.isCompleted)
            withTimeout(5_000) {
                running.await()
                closeJob.join()
            }
        }

    @Test
    fun cancellingCloseJobDoesNotCancelContextCleanup() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }

            context.closeAsync().cancel()
            val closeJob = context.closeAsync()

            assertFalse(closeJob.isCompleted)
            closeJob.join()
            assertTrue(context.isClosed)

            eventLoop.cancel()
            eventLoop.run()
        }

    @Test
    fun closingContextFailsPendingPromiseAwait() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }
            val promise = context.evaluateScript("new Promise(() => {})")
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { promise.awaitEscaped() }
                }

            val closeJob = context.closeAsync()

            assertEquals("JsContext is closed", result.await().exceptionOrNull()?.message)
            closeJob.join()
            eventLoop.cancel()
            eventLoop.run()
        }

    private fun isBigIntSupported(): Boolean =
        assertIs<JsBoolean>(
            context.evaluateScript("typeof BigInt === 'function'"),
        ).toBoolean()

    private fun unhandledRejectionReasonMessage(reason: JsValue?): String {
        val message =
            (reason as? JsObject)
                ?.getValue("message")
                ?.use { it.takeIf { it !is JsUndefined }?.toString() }
        return message ?: reason?.toString() ?: "undefined"
    }

    private fun setNativePromiseRejectionHandler(
        eventName: String,
        onEvent: (JsValue?) -> Unit,
    ) {
        context.globalThis["on$eventName"] =
            JsFunction(context) { args ->
                val event = args.firstOrNull() as? JsObject
                onEvent(event?.let { it["reason"] })
                context.UNDEFINED
            }
    }

    private fun setNativeUnhandledRejectionHandler(onUnhandledRejection: (JsValue?) -> Unit) =
        setNativePromiseRejectionHandler("unhandledrejection", onUnhandledRejection)

    private fun setNativeRejectionHandledHandler(onRejectionHandled: (JsValue?) -> Unit) =
        setNativePromiseRejectionHandler("rejectionhandled", onRejectionHandled)

    fun runTestWithEventLoop(testBody: suspend TestScope.(eventLoop: JsEventLoop) -> Unit): TestResult =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(this@JsContextTest.context)
                }
            val testJob =
                launch {
                    testBody(eventLoop)
                }
            val eventLoopJob =
                launch {
                    eventLoop.runAndComplete()
                }
            testJob.join()
            eventLoopJob.join()
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
        val expected = JsBoolean(context, true)
        assertIs<JsBoolean>(value)
        assertEquals(true, value.toBoolean())
        assertEquals(expected, value)
        assertEquals(expected.hashCode(), value.hashCode())
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
    fun returnsBigIntAsNumber() {
        if (!isBigIntSupported()) return

        val value = context.evaluateScript("9007199254740993n")

        assertEquals(JsNumber(context, 9007199254740992.0), value)
    }

    @Test
    fun returnsBigIntFromArrayAsNumber() {
        if (!isBigIntSupported()) return

        val value = assertIs<JsArray>(context.evaluateScript("[9007199254740993n]")).getValue(0)

        assertEquals(JsNumber(context, 9007199254740992.0), value)
    }

    @Test
    fun reportsThrownBigIntAsJsException() {
        if (!isBigIntSupported()) return

        assertFailsWith<JsException> {
            context.evaluateScript("throw 1n")
        }
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
        val expected = JsString(context, "abc")
        assertIs<JsString>(value)
        assertEquals("abc", value.toString())
        assertEquals(expected, value)
        assertEquals(expected.hashCode(), value.hashCode())
        assertEquals(value, context.createValueAlias(value))
    }

    @Test
    fun valueHashCodesAreConsistentAcrossImplementations() {
        val millis = 0x1_0000_0001L

        assertEquals(0, context.NULL.hashCode())
        assertEquals(1, context.UNDEFINED.hashCode())
        assertEquals(true.hashCode(), JsBoolean(context, true).hashCode())
        assertEquals(42.5.hashCode(), JsNumber(context, 42.5).hashCode())
        assertEquals("value".hashCode(), JsString(context, "value").hashCode())
        assertEquals(millis.toInt(), JsDate(context, millis).hashCode())
    }

    @Test
    fun numberEqualityHandlesNaNAndSignedZeroConsistently() {
        val nan = JsNumber(context, Double.NaN)
        val evaluatedNan = context.evaluateScript("NaN")
        assertEquals(nan, evaluatedNan)
        assertEquals(evaluatedNan, nan)
        assertEquals(nan.hashCode(), evaluatedNan.hashCode())

        val zero = JsNumber(context, 0.0)
        val negativeZero = context.evaluateScript("-0")
        assertNotEquals(zero, negativeZero)
        assertNotEquals(negativeZero, zero)
    }

    @Test
    fun aliasesAreEqualAndHaveSameHashCode() {
        val values =
            listOf(
                context.NULL,
                context.UNDEFINED,
                JsBoolean(context, true),
                JsNumber(context, 42.5),
                JsString(context, "value"),
                JsBooleanObject(context, true),
                JsNumberObject(context, 42.5),
                JsStringObject(context, "value"),
                JsDate(context, 0x1_0000_0001L),
                context.createObject(),
                context.evaluateScript("[1]"),
                context.evaluateScript("(function () { return 1; })"),
                context.evaluateScript("Promise.resolve(1)"),
                JsUint8Array(context, byteArrayOf(1, 2, 3)),
            )

        values.forEach { value ->
            val alias = context.createValueAlias(value)
            assertEquals(value, alias)
            assertEquals(alias, value)
            assertEquals(value.hashCode(), alias.hashCode())
        }
    }

    @Test
    fun valuesFromDifferentContextsAreNotEqual() {
        createContext().use { otherContext ->
            val values =
                listOf(
                    context.NULL to otherContext.NULL,
                    context.UNDEFINED to otherContext.UNDEFINED,
                    JsBoolean(context, true) to JsBoolean(otherContext, true),
                    JsNumber(context, 42.5) to JsNumber(otherContext, 42.5),
                    JsString(context, "value") to JsString(otherContext, "value"),
                    JsDate(context, 123) to JsDate(otherContext, 123),
                    context.createObject() to otherContext.createObject(),
                )

            values.forEach { (value, otherValue) ->
                assertNotEquals(value, otherValue)
                assertNotEquals(otherValue, value)
            }
        }
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
    fun uint8ArraySubclassUsesTheSameValueMethods() {
        val value =
            context.evaluateScript(
                """
                (() => {
                    class Bytes extends Uint8Array {}
                    const value = Bytes.from([1, 2, 255]);
                    value.extra = "ignored";
                    Object.preventExtensions(value);
                    return value;
                })()
                """.trimIndent(),
            )

        assertIs<JsUint8Array>(value)
        assertEquals(3, value.size)
        assertContentEquals(byteArrayOf(1, 2, 255.toByte()), value.toByteArray())
    }

    @Test
    fun passesUint8ArrayToJs() {
        val arr1 = JsUint8Array(context, byteArrayOf(1, 2, 3, 127, 128.toByte(), 255.toByte()))
        context.globalThis["arr"] = arr1
        val arr2 = context.globalThis.getValue("arr")
        assertIs<JsUint8Array>(arr2)
        assertEquals(arr1, arr2)
        assertEquals(arr1.hashCode(), arr2.hashCode())
        val arr3 = context.evaluateScript("Uint8Array.from([1, 2, 3, 127, 128, 255])")
        assertIs<JsUint8Array>(arr3)
        assertNotEquals(arr1, arr3)
        assertNotEquals(arr2, arr3)
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
    fun dateEqualityAndHashCodeRemainStableAfterFirstAccess() {
        val first = assertIs<JsDate>(context.evaluateScript("globalThis.date = new Date(123); date"))
        val initialMillis = first.toMillis()
        val initialHashCode = first.hashCode()

        context.evaluateScript("date.setTime(456)")
        val second = assertIs<JsDate>(context.evaluateScript("date"))

        assertEquals(initialMillis, first.toMillis())
        assertEquals(initialHashCode, first.hashCode())
        assertEquals(456, second.toMillis())
        assertNotEquals(first, second)
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
        runTestWithEventLoop {
            val result =
                context.evaluateScript(
                    """
                    new Promise((resolve) => {
                        setTimeout(() => resolve(Promise.resolve(5)), 50);
                    })
                    """.trimIndent(),
                )
            assertIs<JsObject>(result)
            assertEquals(JsNumber(context, 5), result.awaitEscaped())
        }

    @Test
    fun ignoresSubsequentThenableCompletions() =
        runTestWithEventLoop {
            val thenable =
                context.evaluateScript(
                    """
                    ({
                        then(resolve, reject) {
                            resolve(5);
                            reject(new Error("late rejection"));
                            resolve(6);
                        },
                    })
                    """.trimIndent(),
                )

            assertEquals(JsNumber(context, 5), thenable.awaitEscaped(checkType = false))
        }

    @Test
    fun throwsJsExceptionOnPromiseError() =
        runTestWithEventLoop {
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
                result.awaitEscaped()
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
    fun configuresPromiseOverrideWhenRuntimeRequiresIt() {
        if (!expectPromiseOverride) {
            assertEquals(
                JsBoolean(context, true),
                context.evaluateScript("""typeof globalThis.$JS_NATIVE_PROMISE_GLOBAL_PROPERTY === "undefined""""),
            )
            return
        }

        val failures =
            context.evaluateScript(
                """
                (() => {
                    const NativePromise = globalThis.$JS_NATIVE_PROMISE_GLOBAL_PROPERTY;
                    const promise = new Promise((resolve) => resolve(1));
                    const resolvedPromise = Promise.resolve(1);
                    return [
                        ["native Promise is stored", typeof NativePromise === "function"],
                        ["global Promise is overridden", Promise !== NativePromise],
                        ["global Promise inherits native constructor", Object.getPrototypeOf(Promise) === NativePromise],
                        ["global Promise keeps native prototype", Promise.prototype === NativePromise.prototype],
                        ["constructed promise constructor points to wrapper", promise.constructor === Promise],
                        ["constructed promise is instanceof global Promise", promise instanceof Promise],
                        ["constructed promise is instanceof native Promise", promise instanceof NativePromise],
                        ["resolved promise is instanceof global Promise", resolvedPromise instanceof Promise],
                        ["resolved promise is instanceof native Promise", resolvedPromise instanceof NativePromise],
                    ].filter((check) => !check[1]).map((check) => check[0]);
                })()
                """.trimIndent(),
            ) as JsArray

        assertEquals(emptyList(), failures.toPlainList())
    }

    @Test
    fun reportsUnhandledPromiseRejectionToWebCallbacksWhenHostSupportsThem() =
        runTestWithEventLoop { eventLoop ->
            val callbackEvents = mutableListOf<String>()
            setNativeUnhandledRejectionHandler { reason ->
                callbackEvents += "globalThis.onunhandledrejection:${unhandledRejectionReasonMessage(reason)}"
            }
            context.evaluateScript(
                """
                globalThis.__unhandledRejectionCallbackEvents = [];
                function recordUnhandledRejectionCallback(name, reason) {
                    const message = reason && reason.message ? reason.message : String(reason);
                    globalThis.__unhandledRejectionCallbackEvents.push(name + ":" + message);
                }
                function recordBrowserUnhandledRejection(name, event) {
                    recordUnhandledRejectionCallback(
                        name,
                        event && "reason" in event ? event.reason : undefined
                    );
                }
                if (typeof globalThis.addEventListener === "function") {
                    globalThis.addEventListener("unhandledrejection", (event) => {
                        recordBrowserUnhandledRejection("globalThis.addEventListener", event);
                    });
                }

                Promise.reject(new Error("callback probe"));
                setTimeout(() => setTimeout(() => {
                    globalThis.__unhandledRejectionCallbackProbeDone = true;
                }, 0), 0);
                """.trimIndent(),
            )

            eventLoop.run()

            assertEquals(
                JsBoolean(context, true),
                context.evaluateScript("Boolean(globalThis.__unhandledRejectionCallbackProbeDone)"),
            )
            val observedCallbackEvents =
                callbackEvents +
                    (context.evaluateScript("globalThis.__unhandledRejectionCallbackEvents") as JsArray)
                        .toPlainList()
                        .map { it as String }

            assertEquals(
                expectedUnhandledRejectionCallbackEvents,
                observedCallbackEvents,
            )
        }

    @Test
    fun reportsUnhandledPromiseRejectionsBeforeEventLoopCompletion() =
        runTestWithEventLoop { eventLoop ->
            val unhandledRejections = mutableListOf<String>()
            setNativeUnhandledRejectionHandler { reason ->
                unhandledRejections += unhandledRejectionReasonMessage(reason)
            }
            context.evaluateScript(
                """
                Promise.reject(new Error("immediate"));
                setTimeout(() => {
                    Promise.reject(new Error("timer"));
                }, 0);
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                listOf("immediate", "timer"),
                unhandledRejections,
            )
        }

    @Test
    fun doesNotReportUnhandledPromiseRejectionWhenRejectedPromiseIsAwaitedFromNativeCode() =
        runTestWithEventLoop { eventLoop ->
            val unhandledRejections = mutableListOf<String>()
            setNativeUnhandledRejectionHandler { reason ->
                unhandledRejections += unhandledRejectionReasonMessage(reason)
            }
            val rejectedPromise =
                context.evaluateScript(
                    """
                    new Promise((resolve, reject) => {
                        setTimeout(() => reject(new Error("awaited from native")), 0);
                    })
                    """.trimIndent(),
                )
            val awaitException: Throwable? = runCatching { rejectedPromise.awaitEscaped() }.exceptionOrNull()

            eventLoop.runAndComplete()

            val exception = assertIs<JsException>(awaitException)
            assertEquals("awaited from native", exception.message)
            assertEquals(emptyList(), unhandledRejections)
        }

    @Test
    fun reportsRejectionHandledWhenRejectedPromiseGetsLateHandler() =
        runTestWithEventLoop { eventLoop ->
            val rejectionEvents = mutableListOf<String>()
            setNativeUnhandledRejectionHandler { reason ->
                rejectionEvents += "unhandledrejection:${unhandledRejectionReasonMessage(reason)}"
            }
            setNativeRejectionHandledHandler { reason ->
                rejectionEvents += "rejectionhandled:${unhandledRejectionReasonMessage(reason)}"
            }
            context.evaluateScript(
                """
                globalThis.__lateHandledPromise = Promise.reject(new Error("late handled"));
                """.trimIndent(),
            )

            eventLoop.run()

            assertEquals(
                listOf("unhandledrejection:late handled"),
                rejectionEvents,
            )

            context.evaluateScript(
                """
                globalThis.__lateHandledPromise.catch(() => {});
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                listOf(
                    "unhandledrejection:late handled",
                    "rejectionhandled:late handled",
                ),
                rejectionEvents,
            )
        }

    @Test
    fun reportsUnhandledRejectionFromNativePromiseBeforeEventLoopCompletion() =
        runTestWithEventLoop { eventLoop ->
            val unhandledRejections = mutableListOf<JsException>()
            setNativeUnhandledRejectionHandler { reason ->
                unhandledRejections += JsException(checkNotNull(reason))
            }
            val exception = RuntimeException("native promise failed")

            JsPromise(context) { _, _ ->
                throw exception
            }

            eventLoop.runAndComplete()

            assertEquals(1, unhandledRejections.size)
            val unhandledRejection = unhandledRejections.single()
            assertEquals("Error", unhandledRejection.name)
            assertEquals("native promise failed", unhandledRejection.message)
            assertSame(exception, unhandledRejection.cause)
        }

    @Test
    fun clearsTimeoutByReturnedId() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                var timeoutCallCount = 0;
                const cancelledTimeoutId = setTimeout(() => {
                    timeoutCallCount += 1;
                }, 50);
                if (typeof cancelledTimeoutId !== "number") {
                    throw new Error("setTimeout must return a number");
                }
                clearTimeout(cancelledTimeoutId);
                setTimeout(() => {
                    timeoutCallCount += 2;
                }, 50);
                """.trimIndent(),
            )
            eventLoop.run()
            assertEquals(JsNumber(context, 2), context.evaluateScript("timeoutCallCount"))
        }

    @Test
    fun clearsTimeoutsAndIntervalsWithEitherClearFunction() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                var timerCallOrder = [];
                const cancelledIntervalId = setInterval(() => {
                    timerCallOrder.push("interval");
                }, 50);
                clearTimeout(cancelledIntervalId);
                const cancelledTimeoutId = setTimeout(() => {
                    timerCallOrder.push("timeout");
                }, 50);
                clearInterval(cancelledTimeoutId);
                setTimeout(() => {
                    timerCallOrder.push("kept");
                }, 50);
                """.trimIndent(),
            )
            eventLoop.run()
            assertEquals(JsString(context, "kept"), context.evaluateScript("timerCallOrder.join(',')"))
        }

    @Test
    fun callsNativeAsyncFunction() =
        runTestWithEventLoop { eventLoop ->
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
            eventLoop.run()
            assertEquals(2, a.size)
            assertEquals(JsNumber(context, 5), a.getValue(1))
        }

    @Test
    fun runsPromiseAllThenChainBeforeEventLoopCompletion() =
        runTestWithEventLoop { eventLoop ->
            val callArgs = arrayListOf<Int>()
            var isPromiseAllCompleted = false
            var isEventLoopCompleted = false
            eventLoop.onCompletion = { isCancelled, exception ->
                assertFalse(isCancelled)
                assertNull(exception)
                assertEquals(listOf(1, 2, 3), callArgs)
                assertTrue(isPromiseAllCompleted)
                isEventLoopCompleted = true
            }
            context.globalThis["f"] =
                JsFunction(context) { args ->
                    callArgs.add(assertIs<JsNumber>(args.single()).toNumber().toInt())
                    JsUndefined()
                }
            context.globalThis["complete"] =
                JsFunction(context) {
                    isPromiseAllCompleted = true
                    JsUndefined()
                }
            context.evaluateScript(
                """
                Promise.all([f(1), f(2), f(3)])
                    .then(() => {})
                    .then(() => { globalThis.complete() });
                """.trimIndent(),
            )
            eventLoop.runAndComplete()
            assertTrue(isEventLoopCompleted)
        }

    @Test
    fun runsNextTickCallbacksBeforeImmediateCallbacks() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                globalThis.order = [];
                setImmediate(() => order.push("immediate"));
                process.nextTick(() => {
                    order.push("tick1");
                    process.nextTick(() => order.push("tick2"));
                });
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                JsString(context, "tick1,tick2,immediate"),
                context.evaluateScript("order.join(',')"),
            )
        }

    @Test
    fun runsPromiseMicrotasksQueuedByNextTickBeforeImmediateCallbacks() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                globalThis.order = [];
                setImmediate(() => order.push("immediate"));
                process.nextTick(() => {
                    order.push("tick");
                    Promise.resolve().then(() => order.push("promise-after-tick"));
                });
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                JsString(context, "tick,promise-after-tick,immediate"),
                context.evaluateScript("order.join(',')"),
            )
        }

    @Test
    fun runsPromiseMicrotasksBetweenImmediateCallbacks() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                globalThis.order = [];
                setImmediate(() => {
                    order.push("immediate1");
                    process.nextTick(() => order.push("tick-after-immediate1"));
                    Promise.resolve().then(() => order.push("promise-after-immediate1"));
                    setImmediate(() => order.push("immediate3"));
                });
                setImmediate(() => order.push("immediate2"));
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                JsString(context, "immediate1,tick-after-immediate1,promise-after-immediate1,immediate2,immediate3"),
                context.evaluateScript("order.join(',')"),
            )
        }

    @Test
    fun runsNextTickAndPromiseMicrotasksBeforeCallbacksQueuedByTimeoutCallback() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                globalThis.order = [];
                setTimeout(() => {
                    order.push("timeout");
                    process.nextTick(() => order.push("tick-after-timeout"));
                    Promise.resolve().then(() => order.push("promise-after-timeout"));
                    setImmediate(() => order.push("immediate-after-timeout"));
                    setTimeout(() => order.push("timeout-after-timeout"), 0);
                }, 0);
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                JsString(context, "timeout,tick-after-timeout,promise-after-timeout,immediate-after-timeout,timeout-after-timeout"),
                context.evaluateScript("order.join(',')"),
            )
        }

    @Test
    fun runsCallbacksQueuedByPromiseMicrotasksAfterTickCallback() =
        runTestWithEventLoop { eventLoop ->
            context.evaluateScript(
                """
                globalThis.order = [];
                process.nextTick(() => {
                    order.push("tick");
                    Promise.resolve().then(() => {
                        order.push("promise");
                        setImmediate(() => order.push("immediate"));
                    });
                });
                """.trimIndent(),
            )

            eventLoop.runAndComplete()

            assertEquals(
                JsString(context, "tick,promise,immediate"),
                context.evaluateScript("order.join(',')"),
            )
        }

    @Test
    fun callsNativeFunctionWithoutArgumentsAsynchronously() =
        runTestWithEventLoop { eventLoop ->
            var callCount = 0
            val value =
                JsFunction(context) {
                    callCount++
                    assertEquals(emptyList(), it)
                    JsNumber(context, 3)
                }
            context.globalThis["f"] = value

            val result = context.evaluateScript("(async () => await f())()")
            eventLoop.run()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 3), result.awaitEscaped())
            assertEquals(value, context.createValueAlias(value))
        }

    @Test
    fun callsNativeFunctionAsynchronously() =
        runTestWithEventLoop { eventLoop ->
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

            val result = context.evaluateScript("(async () => await f(1, null, undefined, 2))()")
            eventLoop.run()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 5), result.awaitEscaped())
        }

    @Test
    fun callsNativeFunctionWithGivenThisAsynchronously() =
        runTestWithEventLoop { eventLoop ->
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

            val result = context.evaluateScript("(async () => await obj.f(1, 2, a, b))()")
            eventLoop.run()

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
            assertEquals(JsNumber(context, 5), result.awaitEscaped())
        }

    @Test
    fun nativeFunctionCanReadMethodReceiverAfterNativeWrapperIsClosedAsynchronously() =
        runTestWithEventLoop {
            val result =
                jsScoped(context) {
                    val obj = eval("globalThis.ZenMoney = {}; globalThis.ZenMoney") as JsObject
                    obj["callback"] =
                        JsFunction {
                            (thiz as JsObject)["data"]
                        }
                    eval(
                        """
                        (async () => {
                            ZenMoney.data = 42;
                            return await ZenMoney.callback();
                        })()
                        """.trimIndent(),
                    ).escape()
                }
            assertEquals(JsNumber(context, 42), result.awaitEscaped())
        }

    @Test
    fun callsNativeFunctionReturningArrayOfObjectsAsynchronously() =
        runTestWithEventLoop { eventLoop ->
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

            val result = context.evaluateScript("(async () => await f())()")
            eventLoop.run()
            val arrayResult = result.awaitEscaped()

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
        runTestWithEventLoop { eventLoop ->
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
                context.evaluateScript(
                    """
                    (async () => {
                        const result = new f(1, null, undefined, 2);
                        if (result instanceof Promise) await result;
                    })()
                    """.trimIndent(),
                )
            eventLoop.run()

            assertEquals(1, callCount)
            assertIs<JsObject>(thiz)
            assertNotEquals(context.globalThis, thiz)
            assertEquals(context.UNDEFINED, result.awaitEscaped())
        }

    @Test
    fun throwsJsExceptionWithNativeExceptionCauseAsynchronously() =
        runTestWithEventLoop { eventLoop ->
            val exception = RuntimeException("my error message")
            val f =
                JsFunction(context) {
                    throw exception
                }
            context.globalThis["f"] = f

            var result = context.evaluateScript("(async () => await f(1, 2))()")
            var e = assertIs<JsException>(runCatching { result.awaitEscaped() }.exceptionOrNull())
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)

            val asyncF = context.evaluateScript("(async (...args) => await f(...args))") as JsFunction
            result = asyncF.call(listOf(JsNumber(context, 1), JsNumber(context, 2)))
            e = assertIs<JsException>(runCatching { result.awaitEscaped() }.exceptionOrNull())
            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
        }

    @Test
    fun throwsJsExceptionWithNativeExceptionCauseWithEmptyMessageAsynchronously() =
        runTestWithEventLoop {
            val exception = NullPointerException()
            context.globalThis["f"] =
                JsFunction(context) {
                    throw exception
                }

            val result = context.evaluateScript("(async () => await f(1, 2))()")
            val e = assertIs<JsException>(runCatching { result.awaitEscaped() }.exceptionOrNull())

            assertEquals("Error", e.name)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
        }

    @Test
    fun catchesNativeExceptionAsynchronously() =
        runTestWithEventLoop {
            val exception = RuntimeException("my error message")
            context.globalThis["f"] =
                JsFunction(context) {
                    throw exception
                }

            val error =
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
                )
            val e = JsException(error.awaitEscaped())

            assertEquals("Error", e.name)
            assertEquals("my error message", e.message)
            assertEquals(exception, e.cause)
            assertEquals(emptyMap(), e.data)
        }

    @Test
    fun rejectsPromiseWhenNativeExecutorThrows() =
        runTestWithEventLoop {
            val exception = RuntimeException("executor failed")
            val result =
                JsPromise(context) { _, _ ->
                    throw exception
                }

            val e = assertIs<JsException>(runCatching { result.awaitEscaped() }.exceptionOrNull())
            assertEquals("Error", e.name)
            assertEquals("executor failed", e.message)
            assertEquals(exception, e.cause)
        }

    @Test
    fun objectReturnedFromNativeFunctionIsNotClosedAsynchronously() =
        runTestWithEventLoop { eventLoop ->
            var callCount = 0
            val a = JsObject(context)
            val b = JsObject(context)
            a["b"] = b
            context.globalThis["f"] =
                JsFunction(context) {
                    callCount++
                    a
                }

            val result = context.evaluateScript("(async () => await f())()")
            eventLoop.run()

            assertEquals(1, callCount)
            assertEquals(a, result.awaitEscaped())
            a["c"] = JsNumber(context, 1)
            a["d"] = JsNumber(context, 2)
        }

    @Test
    fun returnsJsErrorObjectAndConvertsItToJsExceptionAsynchronously() =
        runTestWithEventLoop {
            val exception = RuntimeException("my error message")
            context.globalThis["f"] =
                JsFunction(context) {
                    JsObject(exception)
                }

            val error = context.evaluateScript("(async () => await f(1, 2))()")
            val errorValue = error.awaitEscaped()
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
        runTestWithEventLoop { eventLoop ->
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
        runTestWithEventLoop { eventLoop ->
            var isCancelled: Boolean? = null
            var e: Throwable? = null
            eventLoop.onCompletion = { cancelled, exception ->
                isCancelled = cancelled
                e = exception
            }
            eventLoop.cancel()
            eventLoop.run()
            assertEquals(true, isCancelled)
            assertNull(e)
        }

    @Test
    fun requiresDispatcher() {
        assertFailsWith<IllegalArgumentException> {
            JsEventLoop(EmptyCoroutineContext)
        }
        assertFailsWith<IllegalArgumentException> {
            JsEventLoop(Dispatchers.Unconfined)
        }
    }

    @Test
    fun runCompletesNormallyWhenEventLoopIsCancelledWithoutCause() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            eventLoop.cancel()

            eventLoop.run()
        }

    @Test
    fun runThrowsEventLoopCancellationCause() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            val exception = RuntimeException("cancelled because of an error")
            var completionException: Throwable? = null
            eventLoop.onCompletion = { _, cause ->
                completionException = cause
            }

            eventLoop.cancel(exception)

            assertSame(exception, assertFailsWith<RuntimeException> { eventLoop.run() })
            assertSame(exception, completionException)
        }

    @Test
    fun runThrowsExplicitCancellationException() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            val exception = CancellationException("explicit cancellation cause")
            var completionException: Throwable? = null
            eventLoop.onCompletion = { _, cause ->
                completionException = cause
            }

            eventLoop.cancel(exception)

            assertSame(exception, assertFailsWith<CancellationException> { eventLoop.run() })
            assertSame(exception, completionException)
        }

    @Test
    fun runningRunThrowsEventLoopCancellationCause() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            eventLoop.launch {
                awaitCancellation()
            }
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        eventLoop.run()
                    }
                }
            val exception = RuntimeException("cancelled while running")

            eventLoop.cancel(exception)

            assertSame(exception, result.await().exceptionOrNull())
        }

    @Test
    fun concurrentRunCallsThrowTheSameEventLoopCancellationCause() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            eventLoop.launch {
                awaitCancellation()
            }
            val firstResult =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        eventLoop.run()
                    }
                }
            val secondResult =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        eventLoop.runAndComplete()
                    }
                }
            val exception = RuntimeException("cancelled while concurrently running")

            eventLoop.cancel(exception)

            assertSame(exception, firstResult.await().exceptionOrNull())
            assertSame(exception, secondResult.await().exceptionOrNull())
        }

    @Test
    fun serializesConcurrentRunAndCompleteCalls() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            val childCanComplete = CompletableDeferred<Unit>()
            eventLoop.launch {
                childCanComplete.await()
            }
            val runCall =
                async(start = CoroutineStart.UNDISPATCHED) {
                    eventLoop.run()
                }
            val runAndCompleteCall =
                async(start = CoroutineStart.UNDISPATCHED) {
                    eventLoop.runAndComplete()
                }

            childCanComplete.complete(Unit)

            runCall.await()
            runAndCompleteCall.await()
        }

    @Test
    fun rejectsRunFromEventLoopCoroutine() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            val result =
                eventLoop.async {
                    runCatching {
                        eventLoop.run()
                    }
                }

            assertIs<IllegalStateException>(result.await().exceptionOrNull())
            eventLoop.cancel()
            eventLoop.run()
        }

    @Test
    fun runAndCompleteThrowsEventLoopCancellationCause() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            val exception = RuntimeException("cancelled because of an error")
            eventLoop.cancel(exception)

            assertSame(exception, assertFailsWith<RuntimeException> { eventLoop.runAndComplete() })
        }

    @Test
    fun callsOnCompletionListenerWhenEventLoopIsCancelledBecauseOfException() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
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
            assertSame(exception, assertFailsWith<RuntimeException> { eventLoop.run() })
            assertEquals(true, isCancelled)
            assertEquals(exception, e)
            assertTrue(job.isCancelled)
            assertTrue(siblingJob.isCancelled)
        }

    @Test
    fun completedEventLoopRemainsAttached() =
        runTest {
            val eventLoop =
                JsEventLoop(coroutineContext).apply {
                    attachTo(context)
                }

            eventLoop.runAndComplete()

            assertSame(eventLoop, context.eventLoop)

            val nextEventLoop =
                JsEventLoop(coroutineContext)
            assertFailsWith<IllegalArgumentException> {
                nextEventLoop.attachTo(context)
            }
            nextEventLoop.cancel()
            nextEventLoop.run()
        }

    @Test
    fun attachesContextWhileEventLoopIsClosing() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            eventLoop.launch {
                awaitCancellation()
            }
            val runCall =
                async(start = CoroutineStart.UNDISPATCHED) {
                    eventLoop.run()
                }

            eventLoop.cancel()
            eventLoop.attachTo(context)
            runCall.await()

            assertSame(eventLoop, context.eventLoop)
        }

    @Test
    fun attachesContextWhileEventLoopIsRunning() =
        runTest {
            val attachedContext = createContext()
            try {
                val eventLoop =
                    JsEventLoop(coroutineContext).apply {
                        attachTo(context)
                    }
                val childStarted = CompletableDeferred<Unit>()
                val childCanComplete = CompletableDeferred<Unit>()
                eventLoop.launch {
                    childStarted.complete(Unit)
                    childCanComplete.await()
                }
                val runCall =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        eventLoop.run()
                    }
                childStarted.await()

                eventLoop.attachTo(attachedContext)

                assertSame(eventLoop, attachedContext.eventLoop)
                childCanComplete.complete(Unit)
                runCall.await()
                eventLoop.runAndComplete()
            } finally {
                attachedContext.close()
            }
        }

    @Test
    fun attachesContextToClosedEventLoop() =
        runTest {
            val eventLoop = JsEventLoop(coroutineContext)
            eventLoop.runAndComplete()

            eventLoop.attachTo(context)

            assertSame(eventLoop, context.eventLoop)
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
        runTestWithEventLoop { eventLoop ->
            var callCount = 0
            val f =
                JsFunction(context) {
                    callCount++
                    JsNumber(context, 7)
                }
            context.globalThis["f"] = f
            f.close()

            val result = context.evaluateScript("f()")
            eventLoop.run()

            assertEquals(1, callCount)
            assertEquals(JsNumber(context, 7), result.awaitEscaped(checkType = false))
        }
}
