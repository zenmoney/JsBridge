package app.zenmoney.jsbridge

import androidx.collection.intIntMapOf
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                val decoded =
                    jsScoped(context) {
                        decoder
                            .decode(
                                JsValueWire("""["o",1,{"name":"Ada","external":["x",0]}]"""),
                                listOf(reference),
                            ).also {
                                assertIs<JsObject>(it)
                                assertTrue(it in this)
                            }
                    }
                assertTrue(decoded.isClosed)
                assertFalse(reference.isClosed)
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
    fun retainsOnlyReacquiredHandlesAndReleasesOnlyTheLastWrapper() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    val result =
                        if (script.endsWith(JsWebViewMessage.Evaluate("undefined").toScript(it.groupValues[1].toInt()))) {
                            """["u"]"""
                        } else {
                            """["h",7]"""
                        }
                    onMessage("""["r",${it.groupValues[1]},$result]""")
                }
            }
        val context = JsWebViewContext(webView)
        assertTrue(webView.scripts.isEmpty())

        val first = context.evaluateScript("globalThis.value = {}")
        val second = context.evaluateScript("value")
        val alias = context.createValueAlias(first)
        assertEquals(3, webView.scripts.size)

        first.close()
        second.close()
        context.evaluateScript("undefined").close()
        assertFalse(webView.scripts.last().startsWith("""$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["r*","""))
        alias.close()
        assertEquals(4, webView.scripts.size)
        context.evaluateScript("undefined").close()
        assertTrue(webView.scripts.last().startsWith(JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, -1)).toScript()))

        val reacquired = context.evaluateScript("value")
        assertEquals(6, webView.scripts.size)
        context.evaluateScript("undefined").close()
        assertTrue(webView.scripts.last().startsWith(JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, 1)).toScript()))
        reacquired.close()
        context.close()
    }

    @Test
    fun batchesReleasesBeforeTheNextRequestWithoutSeparateWebViewCalls() {
        var nextHandle = 7
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",${nextHandle++}]]""")
                }
            }
        val context = JsWebViewContext(webView)
        val values = List(3) { context.evaluateScript("({})") }
        val scriptCount = webView.scripts.size

        values.forEach { it.close() }
        assertEquals(scriptCount, webView.scripts.size)
        context.evaluateScript("42").close()

        assertEquals(scriptCount + 1, webView.scripts.size)
        assertEquals(
            JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, -1, 8, -1, 9, -1)).toScript() +
                JsWebViewMessage.Evaluate("42").toScript(4),
            webView.scripts.last(),
        )
        context.close()
    }

    @Test
    fun coalescesReacquireAndReleaseButKeepsZeroDeltaForTransitCleanup() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",7]]""")
                }
            }
        val context = JsWebViewContext(webView)
        context.evaluateScript("globalThis.value = {}").close()
        context.evaluateScript("value").close()
        val scriptCount = webView.scripts.size

        context.evaluateScript("value").close()

        assertEquals(scriptCount + 1, webView.scripts.size)
        assertEquals(
            JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, 0)).toScript() + JsWebViewMessage.Evaluate("value").toScript(3),
            webView.scripts.last(),
        )
        context.close()
    }

    @Test
    fun preservesPendingRefCountChangesWhenWebViewRejectsSubmission() {
        var rejectSubmission = false
        val webView =
            FakeJsWebView { script ->
                check(!rejectSubmission) { "Submission failed" }
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",7]]""")
                }
            }
        val context = JsWebViewContext(webView)
        context.evaluateScript("({})").close()
        rejectSubmission = true
        assertFailsWith<IllegalStateException> { context.evaluateScript("42") }
        rejectSubmission = false

        context.evaluateScript("42").close()

        assertEquals(
            JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, -1)).toScript() + JsWebViewMessage.Evaluate("42").toScript(3),
            webView.scripts.last(),
        )
        context.close()
    }

    @Test
    fun mergesFailedBatchWithChangesQueuedDuringSubmission() {
        var nextHandle = 7
        var rejectSubmission = false
        var duringSubmission: (() -> Unit)? = null
        val webView =
            FakeJsWebView { script ->
                if (rejectSubmission) {
                    duringSubmission?.also { duringSubmission = null }?.invoke()
                    error("Submission failed")
                }
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",${nextHandle++}]]""")
                }
            }
        val context = JsWebViewContext(webView)
        val first = context.evaluateScript("({})")
        val second = context.evaluateScript("({})")
        first.close()
        duringSubmission = { second.close() }
        rejectSubmission = true
        repeat(2) {
            assertFailsWith<IllegalStateException> { context.evaluateScript("42") }
        }
        rejectSubmission = false

        context.evaluateScript("42").close()

        assertEquals(
            JsWebViewMessage.UpdateRefCounts(intIntMapOf(8, -1, 7, -1)).toScript() + JsWebViewMessage.Evaluate("42").toScript(5),
            webView.scripts.last(),
        )
        context.close()
    }

    @Test
    fun flushesPendingRefCountChangesBeforeCompletingNativeCallback() =
        runTest {
            val webView =
                FakeJsWebView { script ->
                    val id = requestIdRegex.find(script)?.groupValues?.get(1) ?: return@FakeJsWebView
                    val result = if (script.contains("""dispatch(["f",""")) """["h",8589934600]""" else """["h",7]"""
                    onMessage("""["r",$id,$result]""")
                }
            val context = JsWebViewContext(webView)
            val eventLoop = JsEventLoop(coroutineContext)
            context.core.eventLoop = eventLoop
            context.createFunction { context.UNDEFINED }
            context.evaluateScript("globalThis.value = {}").close()

            webView.onMessage("""["f",1,0,["h",0],[["h",7]]]""")
            testScheduler.runCurrent()

            assertEquals(
                JsWebViewMessage.UpdateRefCounts(intIntMapOf(7, 0)).toScript() +
                    JsWebViewMessage.CompleteNativeCallback(1, JsWebViewProtocolValue.Undefined()).toScript(),
                webView.scripts.last(),
            )
            context.close()
            eventLoop.runAndComplete()
        }

    @Test
    fun closingContextDiscardsQueuedReleasesWithoutFlushing() {
        val webView =
            FakeJsWebView { script ->
                requestIdRegex.find(script)?.let {
                    onMessage("""["r",${it.groupValues[1]},["h",7]]""")
                }
            }
        val context = JsWebViewContext(webView)
        context.evaluateScript("({})").close()
        val scriptCount = webView.scripts.size

        context.close()

        assertEquals(scriptCount + 1, webView.scripts.size)
        assertEquals(jsWebViewDisposeRuntimeScript, webView.scripts.last())
        assertTrue(webView.isClosed)
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
        val scriptCount = webView.scripts.size

        context.close()

        assertEquals(scriptCount + 1, webView.scripts.size)
        assertEquals(jsWebViewDisposeRuntimeScript, webView.scripts.last())
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

            assertEquals(IllegalStateException::class, result.await().exceptionOrNull()?.let { it::class })
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

            val failure = result.await().exceptionOrNull()
            assertEquals(IllegalStateException::class, failure?.let { it::class })
            assertEquals("JsContext is closed", failure?.message)
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
