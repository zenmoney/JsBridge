package app.zenmoney.jsbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsWebViewLifecycleTest {
    @Test
    fun accessorsReleaseTemporaryWrappers() =
        withEngineBackedWebView { _, webView ->
            JsWebViewContext(webView).use { context ->
                jsScoped(context) {
                    val array = eval("[1, 2, 3]") as JsArray
                    val bytes = eval("new Uint8Array(4)") as JsUint8Array
                    val boolean = eval("new Boolean(false)") as JsBoolean
                    val number = eval("new Number(42)") as JsNumber
                    val string = eval("new String('hello')") as JsString
                    val date = eval("new Date(1234)") as JsDate
                    val baseline = ownedCount(context)
                    repeat(100) {
                        assertEquals(3, array.size)
                        assertEquals(4, bytes.size)
                        assertEquals(false, boolean.toBoolean())
                        assertEquals(42, number.toNumber().toInt())
                        assertEquals("hello", string.toString())
                        assertEquals(1234L, date.toMillis())
                    }
                    assertEquals(baseline, ownedCount(context))
                }
            }
        }

    @Test
    fun closingContextClearsRuntimeHandlesAndAllowsWebViewReuse() =
        withEngineBackedWebView { backing, webView ->
            jsScoped(backing) {
                eval(
                    """
                    globalThis.__maps = [];
                    globalThis.Map = class extends Map {
                        constructor(...args) { super(...args); __maps.push(this); }
                    };
                    """.trimIndent(),
                )
            }
            val context = JsWebViewContext(webView)
            context.evaluateScript("new Uint8Array(1024 * 1024)").close()
            backing.evaluateScript("globalThis.__oldBridge = $JS_WEB_VIEW_BRIDGE_OBJECT").close()
            context.close()
            jsScoped(backing) {
                assertTrue(eval("__maps.length > 0 && __maps.every(map => map.size === 0)").boolean)
                assertEquals("undefined", eval("typeof $JS_WEB_VIEW_BRIDGE_OBJECT").string)
            }
            JsWebViewContext(webView).use { replacement ->
                assertEquals(42, replacement.evaluateScript("6 * 7").use { it.int })
                backing.evaluateScript("__oldBridge.dispose(); __oldBridge.dispatch(['w', 'throw 1'], 999)").close()
                assertEquals(43, replacement.evaluateScript("43").use { it.int })
            }
        }

    @Test
    fun disposingRuntimeRejectsPendingAndFutureCallbacks() =
        runTest {
            JsEngineContext().use { context ->
                val eventLoop = JsEventLoop(coroutineContext).apply { attachTo(context) }
                try {
                    jsScoped(context) {
                        eval(
                            """
                            globalThis.window = globalThis;
                            globalThis.messages = [];
                            globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {
                                postMessage(message) { messages.push(JSON.parse(message)); }
                            };
                            """.trimIndent(),
                        )
                        eval(jsWebViewRuntimeScript)
                        val result =
                            eval(
                                """
                                (() => {
                                    const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                                    bridge.dispatch(["f", 1], 1);
                                    bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                                    const pending = __callback({}).catch(error => error.message);
                                    bridge.dispose();
                                    const messageCount = messages.length;
                                    let late;
                                    try {
                                        __callback({});
                                        throw new Error("Closed callback must throw");
                                    } catch (error) {
                                        late = error.message;
                                    }
                                    bridge.dispatch(["w", "throw 1"], 3);
                                    if (messages.length !== messageCount) throw new Error("Closed runtime posted a message");
                                    return Promise.all([pending, late]).then(values => values.join(";"));
                                })()
                                """.trimIndent(),
                            )
                        assertEquals("JsContext is closed;JsContext is closed", result.await().string)
                    }
                    eventLoop.runAndComplete()
                } finally {
                    eventLoop.cancel()
                }
            }
        }

    @Test
    fun disposalFailureStillClosesWebView() {
        var closed = false
        val webView =
            object : JsWebView {
                override var onMessage: (String) -> Unit = {}

                override fun evaluateJavaScript(script: String) {
                    Regex(""",(\d+)\);$""").find(script)?.let {
                        onMessage("""["r",${it.groupValues[1]},42]""")
                    }
                }

                override fun disposeRuntime() {
                    error("Cannot submit disposal")
                }

                override fun close() {
                    closed = true
                }
            }
        val context = JsWebViewContext(webView)
        context.evaluateScript("42").close()
        assertFailsWith<IllegalStateException> { context.close() }
        assertTrue(closed)
        assertTrue(context.isClosed)
    }

    private fun ownedCount(context: JsContext): Int = context.createNumber(0).use { it.core.indexInScope }

    private inline fun withEngineBackedWebView(block: (JsEngineContext, JsWebView) -> Unit) {
        JsEngineContext().use { backing ->
            val webView =
                object : JsWebView {
                    override var onMessage: (String) -> Unit = {}

                    override fun evaluateJavaScript(script: String) {
                        backing.evaluateScript(script).close()
                    }

                    override fun close() {}
                }
            jsScoped(backing) {
                eval("globalThis.window = globalThis; globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {}")
                val native = eval(JS_WEB_VIEW_ANDROID_INTERFACE) as JsObject
                native["postMessage"] =
                    JsFunction { args ->
                        webView.onMessage(args[0].string)
                        JsUndefined()
                    }
            }
            block(backing, webView)
        }
    }
}
