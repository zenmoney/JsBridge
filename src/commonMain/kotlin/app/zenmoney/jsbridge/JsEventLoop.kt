package app.zenmoney.jsbridge

import androidx.collection.mutableIntObjectMapOf
import androidx.collection.mutableObjectListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class JsEventLoop(
    context: CoroutineContext,
) : CoroutineScope {
    private val job = Job(context[Job])

    override val coroutineContext: CoroutineContext = context + job

    private var i = 0
    private val timeoutJobs = mutableIntObjectMapOf<Job>()
    private val intervalJobs = mutableIntObjectMapOf<Job>()
    private val nextTickCallbacks = mutableObjectListOf<Pair<JsFunction, List<JsValue>>>()
    private var immediateCallbacks = mutableIntObjectMapOf<Pair<JsFunction, List<JsValue>>>()

    fun attachTo(context: JsContext) {
        require(context.core.eventLoop == null || context.core.eventLoop == this) { "JsContext already has an event loop" }
        context.core.eventLoop = this
        jsScoped(context) {
            context.globalThis["process"]
                .autoClose()
                .let {
                    when (it) {
                        context.NULL,
                        context.UNDEFINED,
                        -> JsObject().also { process -> context.globalThis["process"] = process }

                        is JsObject -> it
                        else -> null
                    }
                }?.let { process ->
                    process["nextTick"] =
                        JsFunction {
                            nextTick(it)
                            context.UNDEFINED
                        }
                }
            context.globalThis["clearImmediate"] =
                JsFunction {
                    clearImmediate(it)
                    context.UNDEFINED
                }
            context.globalThis["clearInterval"] =
                JsFunction {
                    clearInterval(it)
                    context.UNDEFINED
                }
            context.globalThis["clearTimeout"] =
                JsFunction {
                    clearTimeout(it)
                    context.UNDEFINED
                }
            context.globalThis["setImmediate"] = JsFunction { setImmediate(it) }
            context.globalThis["setInterval"] = JsFunction { setInterval(it) }
            context.globalThis["setTimeout"] = JsFunction { setTimeout(it) }
        }
    }

    fun tick() {
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
        immediateCallbacks = mutableIntObjectMapOf()
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

    suspend fun runAndWaitForCompletion() {
        if (!job.isActive) {
            return
        }
        tick()
        job.complete()
        job.join()
    }

    private fun JsScope.nextTick(args: List<JsValue>) {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        escape(callback)
        escape(args)
        nextTickCallbacks.add(Pair(callback, args))
    }

    private fun JsScope.setTimeout(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        val args = args.subList(2, args.size)
        val id = i++
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
        return JsNumber(id)
    }

    private fun clearTimeout(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt() ?: return
        timeoutJobs.remove(id)?.cancel()
    }

    private fun JsScope.setInterval(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        val args = args.subList(2, args.size)
        val id = i++
        escape(callback)
        escape(args)
        intervalJobs[id] =
            launch {
                while (true) {
                    delay(delayMs)
                    jsScoped(callback.context) {
                        autoClose(callback)
                        autoClose(args)
                        callback(args)
                    }
                    tick()
                }
            }.apply {
                invokeOnCompletion {
                    closeValues(callback, args)
                }
            }
        return JsNumber(id)
    }

    private fun clearInterval(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt() ?: return
        intervalJobs.remove(id)?.cancel()
    }

    private fun JsScope.setImmediate(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        val id = i++
        escape(callback)
        escape(args)
        immediateCallbacks[i] = Pair(callback, args)
        return JsNumber(id)
    }

    private fun clearImmediate(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt() ?: return
        immediateCallbacks.remove(id)?.also { (callback, args) ->
            closeValues(callback, args)
        }
    }
}

private fun closeValues(
    value: JsValue,
    other: List<JsValue>,
) {
    value.close()
    other.forEach { it.close() }
}

private fun closeValues(
    value: JsValue,
    other: JsValue,
) {
    value.close()
    other.close()
}
