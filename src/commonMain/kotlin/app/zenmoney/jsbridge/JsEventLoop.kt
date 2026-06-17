package app.zenmoney.jsbridge

import androidx.collection.mutableObjectListOf
import androidx.collection.mutableScatterMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

class JsEventLoop(
    context: CoroutineContext,
) : CoroutineScope {
    @Volatile
    var onCompletion: (isCancelled: Boolean, exception: Throwable?) -> Unit = { _, _ -> }

    private val job =
        Job().apply {
            invokeOnCompletion {
                val dispatcher = context[CoroutineDispatcher] ?: Dispatchers.Unconfined
                dispatcher.dispatch(context) {
                    nextTickCallbacks.forEach { (callback, args) -> closeValues(callback, args) }
                    nextTickCallbacks.clear()
                    immediateCallbacks.forEachValue { (callback, args) -> closeValues(callback, args) }
                    immediateCallbacks.clear()
                }
                var exception = it
                while (exception is CancellationException) {
                    exception = exception.cause
                }
                onCompletion(it != null, exception)
            }
        }

    override val coroutineContext: CoroutineContext =
        context +
            job +
            CoroutineExceptionHandler { _, _ -> }

    private val timeoutJobs = mutableScatterMapOf<String, Job>()
    private val intervalJobs = mutableScatterMapOf<String, Job>()
    private val nextTickCallbacks = mutableObjectListOf<Pair<JsFunction, List<JsValue>>>()
    private var immediateCallbacks = mutableScatterMapOf<String, Pair<JsFunction, List<JsValue>>>()

    fun attachTo(context: JsContext) {
        require(context.core.eventLoop == null || context.core.eventLoop == this) { "JsContext already has an event loop" }
        context.core.eventLoop = this
        jsScoped(context) {
            context.globalThis["__appZenmoneyEventLoopOnEvent"] =
                JsFunction {
                    val event = it.getOrNull(0).toString()
                    var args = it.subList(1, it.size)
                    when (event) {
                        "nextTick" -> {
                            nextTick(args)
                        }

                        "clearImmediate" -> {
                            clearImmediate(args)
                        }

                        "clearInterval" -> {
                            clearInterval(args)
                        }

                        "clearTimeout" -> {
                            clearTimeout(args)
                        }

                        else -> {
                            val id =
                                it.getOrNull(1)?.toCallbackId()
                                    ?: throw IllegalArgumentException("unexpected event loop event")
                            args = it.subList(2, it.size)
                            when (event) {
                                "setImmediate" -> setImmediate(args, id)
                                "setInterval" -> setInterval(args, id)
                                "setTimeout" -> setTimeout(args, id)
                                else -> throw IllegalArgumentException("unexpected event loop event")
                            }
                        }
                    }
                    JsUndefined()
                }
            eval(
                """
                globalThis.__appZenmoneyEventLoopOnEvent.index = __appZenmoneyEventLoopOnEvent.index || 0;
                globalThis.clearImmediate = function clearImmediate() {
                     __appZenmoneyEventLoopOnEvent("clearImmediate", ...arguments);
                };
                globalThis.clearInterval = function clearInterval() {
                    __appZenmoneyEventLoopOnEvent("clearInterval", ...arguments);
                };
                globalThis.clearTimeout = function clearTimeout () {
                    __appZenmoneyEventLoopOnEvent("clearTimeout", ...arguments);
                };
                globalThis.setImmediate = function setImmediate () {
                    const i = __appZenmoneyEventLoopOnEvent.index++;
                    __appZenmoneyEventLoopOnEvent("setImmediate", i, ...arguments);
                    return i;
                };
                globalThis.setInterval = function setInterval () {
                    const i = __appZenmoneyEventLoopOnEvent.index++;
                    __appZenmoneyEventLoopOnEvent("setInterval", i, ...arguments);
                    return i;
                };
                globalThis.setTimeout = function setTimeout () {
                    const i = __appZenmoneyEventLoopOnEvent.index++;
                    __appZenmoneyEventLoopOnEvent("setTimeout", i, ...arguments);
                    return i;
                };
                globalThis.process = globalThis.process || {};
                globalThis.process.nextTick = function nextTick () {
                    __appZenmoneyEventLoopOnEvent("nextTick", ...arguments);
                };
                """.trimIndent(),
            )
        }
    }

    private fun tick() {
        while (nextTickCallbacks.isNotEmpty() || immediateCallbacks.isNotEmpty()) {
            runNextTickCallbacks()
            runImmediateCallbacks()
            runNextTickCallbacks()
        }
    }

    private fun runNextTickCallbacks() {
        var i = 0
        while (i < nextTickCallbacks.size) {
            val (callback, args) = nextTickCallbacks[i++]
            if (!job.isActive) {
                closeValues(callback, args)
                continue
            }
            jsScoped(callback.context) {
                autoClose(callback)
                autoClose(args)
                callback(args)
            }
        }
        nextTickCallbacks.clear()
    }

    private fun runImmediateCallbacks() {
        val callbacks = immediateCallbacks
        immediateCallbacks = mutableScatterMapOf()
        callbacks.forEachValue { (callback, args) ->
            if (!job.isActive) {
                closeValues(callback, args)
                return@forEachValue
            }
            jsScoped(callback.context) {
                autoClose(callback)
                autoClose(args)
                callback(args)
            }
        }
    }

    suspend fun runAndComplete() {
        if (!job.isActive) {
            return
        }
        run()
        job.complete()
        job.join()
    }

    suspend fun run() {
        if (!job.isActive) {
            return
        }
        withContext(coroutineContext) {
            tick()
        }
        while (true) {
            var hasChildren = false
            job.children.forEach {
                hasChildren = true
                it.join()
                withContext(coroutineContext) {
                    tick()
                }
            }
            if (!hasChildren) break
        }
    }

    fun cancel(exception: Throwable? = null) {
        cancel(exception?.message ?: "", exception)
    }

    private fun JsScope.nextTick(args: List<JsValue>) {
        ensureActive()
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        escape(callback)
        escape(args)
        nextTickCallbacks.add(Pair(callback, args))
    }

    private fun JsScope.setTimeout(
        args: List<JsValue>,
        id: String,
    ) {
        ensureActive()
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        val args = args.subList(2, args.size)
        escape(callback)
        escape(args)
        timeoutJobs[id] =
            launch {
                delay(delayMs)
                jsScoped(callback.context) {
                    autoClose(callback)
                    autoClose(args)
                    callback(args)
                }
                tick()
            }.apply {
                invokeOnCompletion {
                    closeValues(callback, args)
                }
            }
    }

    private fun clearTimeout(args: List<JsValue>) {
        val id = args.getOrNull(0)?.toCallbackId() ?: return
        timeoutJobs.remove(id)?.cancel()
    }

    private fun JsScope.setInterval(
        args: List<JsValue>,
        id: String,
    ) {
        ensureActive()
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        val args = args.subList(2, args.size)
        escape(callback)
        escape(args)
        intervalJobs[id] =
            launch {
                while (true) {
                    delay(delayMs)
                    jsScoped(callback.context) {
                        callback(args)
                    }
                    tick()
                }
            }.apply {
                invokeOnCompletion {
                    closeValues(callback, args)
                }
            }
    }

    private fun clearInterval(args: List<JsValue>) {
        val id = args.getOrNull(0)?.toCallbackId() ?: return
        intervalJobs.remove(id)?.cancel()
    }

    private fun JsScope.setImmediate(
        args: List<JsValue>,
        id: String,
    ) {
        ensureActive()
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        escape(callback)
        escape(args)
        immediateCallbacks[id] = Pair(callback, args)
    }

    private fun clearImmediate(args: List<JsValue>) {
        val id = args.getOrNull(0)?.toCallbackId() ?: return
        immediateCallbacks.remove(id)?.also { (callback, args) ->
            closeValues(callback, args)
        }
    }

    private fun JsValue.toCallbackId(): String? = intOrNull?.let { "${context.core.id}.$it" }
}

private fun closeValues(
    value: JsValue,
    other: List<JsValue>,
) {
    value.close()
    other.forEach { it.close() }
}
