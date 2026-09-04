package app.zenmoney.jsbridge

import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsWebViewContextProtocolTest {
    @Test
    fun expressionDecoderUsesRawWebViewCommandAndExpressionEncodedReferences() {
        val webView =
            FakeJsWebView { script ->
                val requestId = requestIdRegex.find(script)?.groupValues?.get(1) ?: return@FakeJsWebView
                val result =
                    when {
                        script.contains("""dispatch(["e",""") -> """["h",1]"""
                        script.contains("""dispatch(["g",""") -> """["h",8589934594]"""
                        script.contains("""dispatch(["c",""") -> """["h",8589934595]"""
                        script.contains("""dispatch(["v",""") -> """["h",4]"""
                        else -> error("Unexpected script: $script")
                    }
                onMessage("""["r",$requestId,$result]""")
            }
        val context = JsWebViewContext(webView)

        ExpressionValueCodec.createDecoder(context).use { decoder ->
            context.createString("external").use { reference ->
                decoder
                    .decode(
                        JsValueWire("""["o",1,{"name":"Ada","external":["x",0]}]"""),
                        listOf(reference),
                    ).use { assertIs<JsObject>(it) }
            }
        }

        val decodeScript = webView.scripts.single { it.contains("""dispatch(["v",""") }
        assertTrue(
            decodeScript.contains(
                """dispatch(["v",3,["o",1,{"name":"Ada","external":["x",0]}],["external"]]""",
            ),
        )
        assertFalse(decodeScript.contains("JSON.parse"))
        context.close()
    }

    @Test
    fun contextOwnsRequestIdsAndRoutesResponses() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},7]""")
                }
            }
        val context = JsWebViewContext(webView)

        val result = context.evaluateScript("6 + 1")

        assertEquals(7.0, assertIs<JsNumber>(result).toNumber())
        assertEquals(jsWebViewRuntimeScript, webView.scripts[0])
        assertTrue(webView.scripts[1].contains("""__appZenmoneyJsBridge.dispatch(["e""""))
        assertEquals(2, webView.scripts.size)
        context.close()
        assertTrue(webView.isClosed)
    }

    @Test
    fun decodesBigIntAsOrdinaryNumberAtNativeBoundary() {
        val webView =
            FakeJsWebView { script ->
                val requestId = requestIdRegex.find(script)?.groupValues?.get(1) ?: return@FakeJsWebView
                val result =
                    when {
                        script.contains("""dispatch(["e",""") -> """["i","9007199254740993"]"""
                        script.contains("""dispatch(["a",""") -> """["h",4294967297]"""
                        else -> error("Unexpected script: $script")
                    }
                onMessage("""["r",$requestId,$result]""")
            }
        val context = JsWebViewContext(webView)

        context.evaluateScript("9007199254740993n").use { value ->
            assertEquals(9007199254740992.0, assertIs<JsNumber>(value).toNumber())
            context.createArray(listOf(value)).close()
        }

        val createArrayScript = webView.scripts.single { it.contains("""dispatch(["a",""") }
        assertTrue(createArrayScript.contains("""dispatch(["a",[9.007199254740992E15]"""))
        assertFalse(createArrayScript.contains("""["i"""))
        context.close()
    }

    @Test
    fun createsWebViewLazilyAndDoesNotCreateItOnClose() {
        var createCount = 0
        val context =
            JsWebViewContext {
                createCount++
                FakeJsWebView()
            }

        assertEquals(0, createCount)
        context.close()

        assertEquals(0, createCount)
    }

    @Test
    fun initializesRuntimeOnlyOnce() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["u"]]""")
                }
            }
        val context = JsWebViewContext { webView }

        context.evaluateScript("undefined")
        context.evaluateScript("undefined")

        assertEquals(jsWebViewRuntimeScript, webView.scripts[0])
        assertEquals(1, webView.scripts.count { it == jsWebViewRuntimeScript })
        assertEquals(3, webView.scripts.size)
        context.close()
    }

    @Test
    fun readsUint8ArrayInSingleCommand() {
        val webView =
            FakeJsWebView { script ->
                val requestId = requestIdRegex.find(script)?.groupValues?.get(1) ?: return@FakeJsWebView
                val result =
                    when {
                        script.contains("""["y+",""") -> """["h",38654705668]"""
                        script.contains("""["y?",""") -> """["ui8",1,"AID/"]"""
                        script.contains("""["r",""") -> """["u"]"""
                        else -> error("Unexpected script: $script")
                    }
                onMessage("""["r",$requestId,$result]""")
            }
        val context = JsWebViewContext(webView)

        val value = JsUint8Array(context, byteArrayOf(0, 128.toByte(), 255.toByte()))

        assertContentEquals(byteArrayOf(0, 128.toByte(), 255.toByte()), value.toByteArray())
        assertEquals(1, webView.scripts.count { it.contains("""["y?",""") })
        assertEquals(3, webView.scripts.size)
        context.close()
    }

    @Test
    fun releasesHandlesWithoutWaitingForResponse() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",7]]""")
                }
            }
        val context = JsWebViewContext(webView)
        val value = context.evaluateScript("({})")

        value.close()

        assertEquals("""__appZenmoneyJsBridge.dispatch(["r",7]);""", webView.scripts.last())
        context.close()
    }

    @Test
    fun doesNotReleaseHandlesWhileClosingContext() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",7]]""")
                }
            }
        val context = JsWebViewContext(webView)
        context.evaluateScript("({})")

        context.close()

        assertTrue(JsWebViewMessage.Release(7).toScript() !in webView.scripts)
    }

    @Test
    fun closeCancelsPendingRequestsAndClosesWebView() =
        runTest {
            val evaluated = CompletableDeferred<Unit>()
            val webView =
                FakeJsWebView { script ->
                    if (requestIdRegex.containsMatchIn(script)) {
                        evaluated.complete(Unit)
                    }
                }
            val context = JsWebViewContext(webView)
            val result =
                async(Dispatchers.Default) {
                    runCatching {
                        context.evaluateScript("new Promise(() => {})")
                    }
                }

            evaluated.await()
            context.close()

            assertIs<IllegalStateException>(result.await().exceptionOrNull())
            assertTrue(webView.isClosed)
        }

    @Test
    fun closeAsyncCancelsPendingProtocolRequest() =
        runTest {
            val evaluated = CompletableDeferred<Unit>()
            val webView =
                FakeJsWebView { script ->
                    if (requestIdRegex.containsMatchIn(script)) {
                        evaluated.complete(Unit)
                    }
                }
            val context = JsWebViewContext(webView)
            val result =
                async(Dispatchers.Default) {
                    runCatching { context.evaluateScript("new Promise(() => {})") }
                }

            evaluated.await()
            val closeJob = context.closeAsync()

            assertEquals("JsContext is closed", result.await().exceptionOrNull()?.message)
            closeJob.join()
            assertTrue(closeJob.isCompleted)
        }

    @Test
    fun customWebViewCloseImplementationControlsDisposal() {
        var wasDisposed = false
        val webView =
            FakeJsWebView(
                onEvaluate = { script ->
                    requestIdRegex.find(script)?.let {
                        onMessage("""["r",${it.groupValues[1]},["u"]]""")
                    }
                },
                onClose = { wasDisposed = true },
            )
        val context = JsWebViewContext(webView)

        context.evaluateScript("undefined")
        context.close()

        assertTrue(wasDisposed)
        assertFalse(webView.isClosed)
    }

    private class FakeJsWebView(
        private val onClose: FakeJsWebView.() -> Unit = { isClosed = true },
        private val onEvaluate: FakeJsWebView.(String) -> Unit = {},
    ) : JsWebView {
        override var onMessage: (String) -> Unit = {}
        val scripts = mutableListOf<String>()
        var isClosed = false

        override fun evaluateJavaScript(script: String) {
            scripts += script
            onEvaluate(script)
        }

        override fun close() {
            onClose()
        }
    }

    private companion object {
        val requestIdRegex = Regex(""",(\d+)\);$""")
    }
}
