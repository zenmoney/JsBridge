package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsWebViewRequestFailureTest {
    @Test
    fun nativeExecutionFailureCompletesItsRequestAndClosesTheContext() {
        val failure = IllegalStateException("Native evaluation failed")
        val webView = FailureReportingWebView { _, onFailure -> onFailure(failure) }
        JsWebViewContext(webView).use { context ->
            assertSame(failure, assertFailsWith<IllegalStateException> { context.evaluateScript("42") })
            assertTrue(context.isClosed)
            assertTrue(webView.closed)
        }
    }

    @Test
    fun nativeFailureOriginClosesContextRegardlessOfExceptionType() {
        val failure = JsException("Failure reported by the native callback")
        val webView = FailureReportingWebView { _, onFailure -> onFailure(failure) }
        JsWebViewContext(webView).use { context ->
            assertSame(failure, assertFailsWith<JsException> { context.evaluateScript("42") })
            assertTrue(context.isClosed)
            assertTrue(webView.closed)
        }
    }

    @Test
    fun submissionFailurePreservesTheContextAndItsNextRequest() {
        val failure = IllegalStateException("Request could not be submitted")
        var failSubmission = true
        val webView =
            FailureReportingWebView { script, _ ->
                if (failSubmission) throw failure
                reply(script, 43)
            }
        JsWebViewContext(webView).use { context ->
            assertSame(failure, assertFailsWith<IllegalStateException> { context.evaluateScript("42") })
            assertFalse(context.isClosed)
            assertFalse(webView.closed)
            failSubmission = false
            assertEquals(43, context.evaluateScript("43").use { it.int })
        }
    }

    @Test
    fun detachedSessionClosesOnlyTheContextWhoseRequestFailed() {
        val failure = JsWebViewContextDetachedException()
        val webView = FailureReportingWebView { _, onFailure -> onFailure(failure) }
        val context = JsWebViewContext(webView)
        try {
            assertSame(failure, assertFailsWith<JsWebViewContextDetachedException> { context.evaluateScript("42") })
            assertTrue(context.isClosed)
            assertTrue(webView.closed)
        } finally {
            context.close()
        }
    }

    @Test
    fun lateNativeFailureCannotReplaceACompletedReplyOrCloseItsContext() {
        var answer = 42
        val webView =
            FailureReportingWebView { script, onFailure ->
                reply(script, answer++)
                onFailure(IllegalStateException("Late native execution failure"))
                onFailure(JsWebViewContextDetachedException())
            }
        JsWebViewContext(webView).use { context ->
            assertEquals(42, context.evaluateScript("42").use { it.int })
            assertFalse(context.isClosed)
            assertEquals(43, context.evaluateScript("43").use { it.int })
            assertFalse(webView.closed)
        }
    }
}

private class FailureReportingWebView(
    private val execute: FailureReportingWebView.(String, (Throwable) -> Unit) -> Unit,
) : JsWebView {
    override var onMessage: (String) -> Unit = {}
    var closed = false
        private set

    override fun initializeRuntime() = Unit

    override fun evaluateJavaScript(script: String) = Unit

    override fun evaluateJavaScript(
        script: String,
        onFailure: (Throwable) -> Unit,
    ) {
        execute(script, onFailure)
    }

    fun reply(
        script: String,
        value: Int,
    ) {
        val requestId = checkNotNull(requestIdRegex.find(script)).groupValues[1]
        onMessage("""["r",$requestId,$value]""")
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val requestIdRegex = Regex(""",(\d+)\);$""")
    }
}
