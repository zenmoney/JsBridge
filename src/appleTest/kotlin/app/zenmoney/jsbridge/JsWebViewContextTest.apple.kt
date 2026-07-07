package app.zenmoney.jsbridge

import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsWebViewContextTest : JsWebViewContextBaseTest() {
    override fun createContext(): JsContext = JsWebViewContext()

    override val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf(
            "globalThis.onunhandledrejection:callback probe",
            "globalThis.addEventListener:callback probe",
        )
    override val expectPromiseOverride: Boolean = false

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
