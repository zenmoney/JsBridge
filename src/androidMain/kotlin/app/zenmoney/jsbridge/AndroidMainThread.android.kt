package app.zenmoney.jsbridge

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking as coroutineRunBlocking

/**
 * Runs [block] synchronously while allowing active [JsContext] implementations to keep processing calls.
 *
 * Unlike [kotlinx.coroutines.runBlocking], this helper can be called from a main-thread callback
 * even if [block] accesses a [JsWebViewContext] from a background dispatcher.
 * A [JsWebViewContext] must already be initialized, as it is when the call originates from its callback.
 */
fun <T> JsContext.Companion.runBlocking(block: suspend CoroutineScope.() -> T): T = AndroidMainThread.runBlocking(block)

/**
 * Main-thread dispatcher whose tasks are also processed inside [runBlocking].
 *
 * Use for bridge-compatible native calls. This does not process Android Looper messages,
 * animation frames, or platform callbacks awaited by the dispatched code.
 */
val JsContext.Companion.mainDispatcher: CoroutineDispatcher
    get() = jsContextMainDispatcher

private val jsContextMainDispatcher =
    object : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = Looper.myLooper() != Looper.getMainLooper()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = AndroidMainThread.dispatch(block)
    }

internal object AndroidMainThread {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dispatchLock = Any()
    private var activeEventLoopScope: CoroutineScope? = null
    private val pendingTasks = linkedSetOf<MainThreadTask>()

    private class MainThreadTask(
        private val block: Runnable,
    ) : Runnable {
        override fun run() {
            val claimed = synchronized(dispatchLock) { pendingTasks.remove(this) }
            if (claimed) {
                mainHandler.removeCallbacks(this)
                block.run()
            }
        }
    }

    fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T =
        coroutineRunBlocking {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                return@coroutineRunBlocking block()
            }

            val eventLoopScope = this
            val previousEventLoopScope =
                synchronized(dispatchLock) {
                    activeEventLoopScope.also {
                        activeEventLoopScope = eventLoopScope
                        // A bridge request can already be queued in Handler when a synchronous
                        // WebView callback starts. Its caller may be the thread needed by block.
                        // Transfer those requests too, instead of leaving them behind this callback.
                        pendingTasks.toList().forEach { task -> eventLoopScope.launch { task.run() } }
                    }
                }
            try {
                eventLoopScope.block()
            } finally {
                synchronized(dispatchLock) {
                    check(activeEventLoopScope === eventLoopScope)
                    activeEventLoopScope = previousEventLoopScope
                    if (previousEventLoopScope != null) {
                        // A cancelled inner callback may leave calls needed by its still-running
                        // outer callback. Its Handler fallback is blocked until that callback exits.
                        pendingTasks.toList().forEach { task -> previousEventLoopScope.launch { task.run() } }
                    }
                }
            }
        }

    fun dispatch(block: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block.run()
            return
        }

        synchronized(dispatchLock) {
            val task = MainThreadTask(block)
            pendingTasks += task
            // Keep a Handler fallback if the nested coroutine scope is cancelled before
            // executing this call. Whichever queue claims it first removes the other entry.
            mainHandler.post(task)
            activeEventLoopScope?.launch { task.run() }
        }
    }
}
