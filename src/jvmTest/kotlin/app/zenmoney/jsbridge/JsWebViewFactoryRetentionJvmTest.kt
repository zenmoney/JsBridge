package app.zenmoney.jsbridge

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JsWebViewFactoryRetentionJvmTest {
    @Test
    fun initializationReleasesTheFactoryCapture() {
        val (context, capture) = createContextWithFactoryCapture()
        context.use {
            assertRetained(capture)
            context.evaluateScript("undefined").close()
            assertReleased(capture)
        }
    }

    @Test
    fun closingWithoutInitializationReleasesTheFactoryCapture() {
        val (context, capture) = createContextWithFactoryCapture()
        context.use {
            assertRetained(capture)
            context.close()
            assertReleased(capture)
        }
    }

    private fun createContextWithFactoryCapture(): Pair<JsWebViewContext, WeakReference<ByteArray>> {
        val captured = ByteArray(1024)
        val reference = WeakReference(captured)
        val context =
            JsWebViewContext {
                check(captured[0] == 0.toByte())
                FactoryWebView()
            }
        return context to reference
    }

    private fun assertRetained(reference: WeakReference<*>) {
        assertNotNull(reference.get())
    }

    private fun assertReleased(reference: WeakReference<*>) {
        repeat(40) {
            System.gc()
            if (reference.get() == null) return
            Thread.sleep(25)
        }
        assertNull(reference.get(), "The context must release values captured only by its factory")
    }

    private class FactoryWebView : JsWebView {
        override var onMessage: (String) -> Unit = {}

        override fun evaluateJavaScript(script: String) {
            requestIdRegex.find(script)?.let { match ->
                onMessage("""["r",${match.groupValues[1]},["u"]]""")
            }
        }

        override fun close() {}
    }

    private companion object {
        val requestIdRegex = Regex(""",(\d+)\);$""")
    }
}
