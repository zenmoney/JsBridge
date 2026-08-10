package app.zenmoney.jsbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class JsWebViewContextTest : JsWebViewContextBaseTest() {
    override fun createContext(): JsContext = JsWebViewContext(ApplicationProvider.getApplicationContext<Context>())

    private val mainThreadDispatcher =
        object : CoroutineDispatcher() {
            private val handler = Handler(Looper.getMainLooper())

            override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != Looper.getMainLooper()

            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) {
                check(handler.post(block))
            }
        }

    private fun initializeWebViewRuntime() {
        // Wait for lazy WebView runtime initialization before blocking the main thread.
        context.evaluateScript("undefined").close()
    }

    override val expectedUnhandledRejectionCallbackEvents: List<String> =
        listOf(
            "globalThis.onunhandledrejection:callback probe",
            "globalThis.addEventListener:callback probe",
        )

    @Test
    fun supportsBlockingCallsFromMainThreadAfterInitialization() {
        var result: JsValue? = null
        initializeWebViewRuntime()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = context.evaluateScript("1 + 2")
        }

        assertEquals(JsNumber(context, 3), result)
    }

    @Test
    fun runBlockingSupportsJsWebViewCallsFromMainThreadCallback() {
        initializeWebViewRuntime()
        lateinit var eventLoop: JsEventLoop
        lateinit var callbackPromise: JsValue
        var result: Int? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            eventLoop =
                JsEventLoop(mainThreadDispatcher).apply {
                    attachTo(context)
                }
            context.globalThis["runBlockingCallback"] =
                JsFunction(context) {
                    result =
                        JsContext.runBlocking {
                            withContext(Dispatchers.Default) {
                                context.evaluateScript("40 + 2").use { it.int }
                            }
                        }
                    context.UNDEFINED
                }
            callbackPromise = context.evaluateScript("runBlockingCallback()")
        }

        try {
            callbackPromise.use {
                JsContext.runBlocking {
                    it.awaitEscaped().close()
                }
            }
        } finally {
            eventLoop.cancel()
            JsContext.runBlocking { eventLoop.run() }
        }

        assertEquals(42, result)
    }
}
