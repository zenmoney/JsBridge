package app.zenmoney.jsbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

internal fun assertWebViewWorksWithStrictCsp(context: JsWebViewContext) {
    assertEquals(42, context.evaluateScript("40 + 2").use { it.int })
    jsScoped(context) {
        val snapshot = eval("value => JSON.stringify(value)") as JsFunction
        val result =
            context.eval(
                "var captured = { answer: 42 }; queueMicrotask(() => { captured.answer = 99; }); captured;",
                snapshot,
            )
        assertEquals("{\"answer\":42}", result.string)
        assertEquals(99, eval("captured.answer").int)
    }
    assertEquals(42, context.evaluateScript("with (new Proxy({}, { has: () => true, get: () => 0 })) 42;").use { it.int })
    context.evaluateScript("let undefined = 42; if (false) 7;").use { assertIs<JsUndefined>(it) }
    // Native injection must work without weakening the policy for the page's own eval.
    assertEquals(
        "EvalError",
        context.evaluateScript("try { eval('42') } catch (error) { error.name }").use { it.string },
    )
    context.evaluateScript("var cspGlobal = 41; document.body.dataset.answer = cspGlobal + 1").close()
    assertEquals(42, context.evaluateScript("cspGlobal + 1").use { it.int })
    assertEquals("42", context.evaluateScript("document.body.dataset.answer").use { it.string })
    context.evaluateScript("document.body").use { body ->
        context.evaluateScript("document.body").use { assertEquals(body, it) }
    }
    context.evaluateScript("value => value + 1").use { function ->
        jsScoped(context) { assertEquals(42, assertIs<JsFunction>(function)(JsNumber(41)).int) }
    }
    context.evaluateScript("new Promise(() => {})").use { assertIs<JsPromise>(it) }
    val failure =
        assertFailsWith<JsException> {
            context.evaluateScript("throw Object.assign(new TypeError('CSP error'), { detail: 42 })")
        }
    assertEquals("TypeError", failure.name)
    assertEquals("CSP error", failure.message)
    assertEquals(mapOf("detail" to 42.0), failure.data)
    assertEquals("SyntaxError", assertFailsWith<JsException> { context.evaluateScript("let = ;") }.name)
    assertFalse(context.isClosed)
    assertEquals(43, context.evaluateScript("43").use { it.int })
}

internal suspend fun assertWebViewAsyncWorksWithStrictCsp(context: JsContext) =
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        val eventLoop = JsEventLoop(coroutineContext)
        try {
            eventLoop.attachTo(context)
            jsScoped(context) {
                context.globalThis["cspNativeAnswer"] = JsFunction { args -> JsNumber(args[0].int + 1) }
                withTimeout(5_000) {
                    val promise = eval("(async () => await cspNativeAnswer(41))()")
                    eventLoop.run()
                    assertEquals(42, promise.await().int)
                }
            }
        } finally {
            eventLoop.cancel()
            eventLoop.run()
        }
    }
