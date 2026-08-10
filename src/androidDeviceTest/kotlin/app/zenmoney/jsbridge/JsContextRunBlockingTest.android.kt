package app.zenmoney.jsbridge

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class JsContextRunBlockingTest {
    @Test
    fun supportsJsEngineContextFromMainThread() {
        var result: Int? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            JsContext().use { context ->
                result =
                    JsContext.runBlocking {
                        withContext(Dispatchers.Default) {}
                        context.evaluateScript("40 + 2").use { it.int }
                    }
            }
        }

        assertEquals(42, result)
    }
}
