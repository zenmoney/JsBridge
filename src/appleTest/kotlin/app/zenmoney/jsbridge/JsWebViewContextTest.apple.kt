package app.zenmoney.jsbridge

import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsWebViewContextTest : JsWebViewContextBaseTest() {
    override fun createContext(): JsContext = JsWebViewContext()

    @Test
    fun executesFromBackgroundThread() {
        val request = JsWebViewBlockingRequest<Double>()
        val queue = dispatch_queue_create("app.zenmoney.jsbridge.test", null)
        dispatch_async(queue) {
            request.complete(
                runCatching {
                    JsWebViewContext().use { context ->
                        val result = context.evaluateScript("40 + 2")

                        assertIs<JsNumber>(result).toNumber().toDouble()
                    }
                },
            )
        }

        assertEquals(42.0, request.await("background test"))
    }
}
