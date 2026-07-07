package app.zenmoney.jsbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class JsWebViewContextTest : JsWebViewContextBaseTest() {
    override fun createContext(): JsContext = JsWebViewContext(ApplicationProvider.getApplicationContext<Context>())

    override val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf(
            "globalThis.onunhandledrejection:callback probe",
            "globalThis.addEventListener:callback probe",
        )

    @Test
    fun supportsBlockingCallsFromMainThread() {
        var result: JsValue? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = context.evaluateScript("1 + 2")
        }

        assertEquals(JsNumber(context, 3), result)
    }
}
