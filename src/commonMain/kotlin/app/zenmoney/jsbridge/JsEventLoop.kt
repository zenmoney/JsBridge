package app.zenmoney.jsbridge

import androidx.collection.mutableIntObjectMapOf
import androidx.collection.mutableObjectListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
    private val nextTickCallbacks = mutableObjectListOf<() -> Unit>()
    private var immediateCallbacks = mutableIntObjectMapOf<() -> Unit>()

    fun attachTo(context: JsContext) {
        context.globalObject["process"]
            .let {
                when (it) {
                    context.NULL,
                    context.UNDEFINED,
                    -> JsObject(context).also { process -> context.globalObject["process"] = process }
                    is JsObject -> it
                    else -> {
                        it.close()
                        null
                    }
                }
            }?.use { process ->
                process["nextTick"] =
                    JsFunctionClosedOnException(context) {
                        nextTick(it)
                        context.UNDEFINED
                    }
            }
        context.globalObject["clearImmediate"] =
            JsFunctionClosedOnException(context) {
                clearImmediate(it)
                context.UNDEFINED
            }
        context.globalObject["clearInterval"] =
            JsFunctionClosedOnException(context) {
                clearInterval(it)
                context.UNDEFINED
            }
        context.globalObject["clearTimeout"] =
            JsFunctionClosedOnException(context) {
                clearTimeout(it)
                context.UNDEFINED
            }
        context.globalObject["setImmediate"] = JsFunctionClosedOnException(context) { setImmediate(it) }
        context.globalObject["setInterval"] = JsFunctionClosedOnException(context) { setInterval(it) }
        context.globalObject["setTimeout"] = JsFunctionClosedOnException(context) { setTimeout(it) }
    }

    fun tick() {
        var i = 0
        while (i < nextTickCallbacks.size) {
            if (!job.isActive) {
                return
            }
            nextTickCallbacks[i]()
            i++
        }
        nextTickCallbacks.clear()
        val callbacks = immediateCallbacks
        immediateCallbacks = mutableIntObjectMapOf()
        callbacks.forEachValue {
            if (!job.isActive) {
                return
            }
            it()
        }
    }

    suspend fun runUntilIdle() {
        if (!job.isActive) {
            return
        }
        tick()
        job.complete()
        job.join()
    }

    fun Deferred<JsValue>.toJsPromise(context: JsContext): JsPromise {
        lateinit var resolve: JsFunction
        lateinit var reject: JsFunction
        val promise =
            JsPromise(context) { res, rej ->
                resolve = res
                reject = rej
            }
        launch {
            val value =
                try {
                    this@toJsPromise.await()
                } catch (e: CancellationException) {
                    resolve.close()
                    reject.close()
                    throw e
                } catch (e: Exception) {
                    val error = e.toJsObject(context)
                    reject(error)
                    resolve.close()
                    reject.close()
                    error.close()
                    return@launch
                }
            resolve(value)
            resolve.close()
            reject.close()
            value.close()
        }
        return promise
    }

    private fun nextTick(args: List<JsValue>) {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        nextTickCallbacks.add {
            callback.invokeAndClose(args)
        }
    }

    private fun setTimeout(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        args[1].close()
        val args = args.subList(2, args.size)
        val id = i++
        val context = callback.context
        timeoutJobs[id] =
            launch {
                delay(delayMs)
                callback.invokeAndClose(args)
                tick()
            }
        return JsNumber(context, id)
    }

    private fun clearTimeout(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt()
        args.forEach { it.close() }
        if (id == null) return
        timeoutJobs.remove(id)?.cancel()
    }

    private fun setInterval(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val delayMs =
            (args.getOrNull(1) as? JsNumber)?.toNumber()?.toLong()
                ?: throw IllegalArgumentException("The \"delay\" argument must be of type number.")
        args[1].close()
        val args = args.subList(2, args.size)
        val id = i++
        val context = callback.context
        intervalJobs[id] =
            launch {
                while (true) {
                    delay(delayMs)
                    callback.invokeAndClose(args)
                    tick()
                }
            }
        return JsNumber(context, id)
    }

    private fun clearInterval(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt()
        args.forEach { it.close() }
        if (id == null) return
        intervalJobs.remove(id)?.cancel()
    }

    private fun setImmediate(args: List<JsValue>): JsNumber {
        val callback = args.getOrNull(0)
        if (callback !is JsFunction) {
            throw IllegalArgumentException("The \"callback\" argument must be of type function.")
        }
        val args = args.subList(1, args.size)
        val id = i++
        val context = callback.context
        immediateCallbacks[i] = {
            callback.invokeAndClose(args)
        }
        return JsNumber(context, id)
    }

    private fun clearImmediate(args: List<JsValue>) {
        val id = (args.getOrNull(0) as? JsNumber)?.toNumber()?.toInt()
        args.forEach { it.close() }
        if (id == null) return
        immediateCallbacks.remove(id)
    }
}

@Suppress("FunctionName")
private fun JsFunctionClosedOnException(
    context: JsContext,
    value: (args: List<JsValue>) -> JsValue,
): JsFunction =
    JsFunction(context) { args ->
        this.close()
        try {
            value(args)
        } catch (e: Throwable) {
            args.forEach {
                try {
                    it.close()
                } catch (_: Throwable) {
                }
            }
            throw e
        }
    }

private fun JsFunction.invokeAndClose(
    args: List<JsValue> = emptyList(),
    thiz: JsValue = context.globalObject,
) = try {
    apply(thiz, args).close()
} finally {
    thiz.close()
    args.forEach { it.close() }
    close()
}
