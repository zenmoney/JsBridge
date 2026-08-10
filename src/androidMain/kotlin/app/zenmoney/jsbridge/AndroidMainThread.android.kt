package app.zenmoney.jsbridge

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking as coroutineRunBlocking

/**
 * Runs [block] synchronously while allowing active [JsContext] implementations to keep processing calls.
 *
 * Unlike [kotlinx.coroutines.runBlocking], this helper can be called from a main-thread callback
 * even if [block] accesses a [JsWebViewContext] from a background dispatcher.
 * A [JsWebViewContext] must already be initialized, as it is when the call originates from its callback.
 */
fun <T> JsContext.Companion.runBlocking(block: suspend CoroutineScope.() -> T): T = AndroidMainThread.runBlocking(block)

internal object AndroidMainThread {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dispatchLock = Any()
    private var activeEventLoopScope: CoroutineScope? = null

    fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T =
        coroutineRunBlocking {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return@coroutineRunBlocking block()
            }

            val eventLoopScope = this
            val previousEventLoopScope =
                synchronized(dispatchLock) {
                    activeEventLoopScope.also { activeEventLoopScope = eventLoopScope }
                }
            try {
                eventLoopScope.block()
            } finally {
                synchronized(dispatchLock) {
                    check(activeEventLoopScope === eventLoopScope)
                    activeEventLoopScope = previousEventLoopScope
                }
            }
        }

    fun dispatch(block: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block.run()
            return
        }

        synchronized(dispatchLock) {
            val eventLoopScope = activeEventLoopScope
            if (eventLoopScope == null) {
                mainHandler.post(block)
            } else {
                // Register the task before the event loop can be restored and its scope completed.
                eventLoopScope.launch { block.run() }
            }
        }
    }
}
