package app.zenmoney.jsbridge

import androidx.collection.mutableIntObjectMapOf
import androidx.collection.mutableObjectListOf
import androidx.collection.mutableScatterMapOf
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

private const val MICROTASK_CHECKPOINT_ITERATIONS = 100

// Job.cancel(cause) adds one framework wrapper. Any nested CancellationException is the explicitly supplied cause.
private fun Throwable?.unwrapCancellationException(): Throwable? =
    when (this) {
        is CancellationException -> cause
        else -> this
    }

private fun Job?.isDescendantOf(parent: Job): Boolean {
    var job = this
    while (job != null) {
        if (job === parent) {
            return true
        }
        job = job.parent
    }
    return false
}

private class JsMicrotaskCheckpoint(
    private val runCheckpoint: JsFunction,
) : AutoCloseable {
    private var id = 0
    private var continuationById = mutableIntObjectMapOf<CancellableContinuation<Unit>>()

    suspend fun await() {
        if (runCheckpoint.isClosed) return
        val id = id++
        try {
            suspendCancellableCoroutine { cont ->
                continuationById[id] = cont
                try {
                    jsScoped(runCheckpoint.context) {
                        runCheckpoint(JsNumber(id))
                    }
                } catch (_: Exception) {
                    complete(id)
                }
            }
        } finally {
            continuationById.remove(id)
        }
    }

    fun complete(args: List<JsValue>) {
        val id = args.getOrNull(0)?.intOrNull ?: return
        complete(id)
    }

    private fun complete(id: Int) {
        val cont = continuationById.remove(id) ?: return
        cont.resume(Unit)
    }

    override fun close() {
        runCheckpoint.close()
        var continuations: ArrayList<CancellableContinuation<Unit>>? = null
        continuationById.forEachValue {
            if (continuations == null) {
                continuations = ArrayList(continuationById.size)
            }
            continuations.add(it)
        }
        continuationById.clear()
        continuations?.forEach { it.cancel() }
    }
}

/**
 * Runs JavaScript callbacks on the dispatcher supplied in [context].
 *
 * The context must contain a dispatcher confined to one thread. All [JsContext] operations associated with this
 * event loop, including [attachTo], must be performed on that dispatcher's thread. [run] and [runAndComplete] may be
 * called concurrently from coroutines outside this event loop; their executions are serialized internally.
 */
class JsEventLoop(
    context: CoroutineContext,
) : CoroutineScope {
    @Volatile
    var onCompletion: (isCancelled: Boolean, exception: Throwable?) -> Unit = { _, _ -> }

    private val dispatcher =
        requireNotNull(context[ContinuationInterceptor] as? CoroutineDispatcher) {
            "JsEventLoop context must contain a single-threaded CoroutineDispatcher"
        }.also {
            require(it !== Dispatchers.Unconfined) {
                "JsEventLoop does not support Dispatchers.Unconfined"
            }
        }
    private val completionException = CompletableDeferred<Throwable?>()

    private val job =
        Job().apply {
            invokeOnCompletion { cause ->
                val exception = cause.unwrapCancellationException()
                val handleCompletion: () -> Unit = {
                    jsTicks.forEach { tick -> tick.close() }
                    jsTicks.clear()
                    microtaskCheckpoints.forEach { checkpoint -> checkpoint.close() }
                    microtaskCheckpoints.clear()
                    timerJobs.clear()
                    completionException.complete(exception)
                    onCompletion(cause != null, exception)
                }
                if (dispatcher.isDispatchNeeded(coroutineContext)) {
                    dispatcher.dispatch(coroutineContext) {
                        handleCompletion()
                    }
                } else {
                    handleCompletion()
                }
            }
        }

    override val coroutineContext: CoroutineContext =
        context +
            job +
            CoroutineExceptionHandler { _, _ -> }

    private val jsTicks = mutableObjectListOf<JsFunction>()
    private val microtaskCheckpoints = mutableObjectListOf<JsMicrotaskCheckpoint>()
    private val timerJobs = mutableScatterMapOf<String, Job>()
    private val lock = Mutex()

    @Volatile
    private var isCompleting = false

    /**
     * Attaches [context] to this event loop.
     *
     * Must be called on the thread of the dispatcher passed to [JsEventLoop]. May be called while the event loop is
     * running. Attaching to an already closed event loop is allowed; native timer registration will report that it is
     * closed.
     */
    fun attachTo(context: JsContext) {
        require(context.core.eventLoop == null || context.core.eventLoop == this) { "JsContext already has an event loop" }
        if (context.core.eventLoop == this) {
            return
        }
        val (jsTick, microtaskCheckpoint) = createAttachment(context)
        context.core.eventLoop = this
        if (isCompleting || !job.isActive) {
            jsTick.close()
            microtaskCheckpoint.close()
            return
        }
        jsTicks.add(jsTick)
        microtaskCheckpoints.add(microtaskCheckpoint)
    }

    private fun createAttachment(context: JsContext): Pair<JsFunction, JsMicrotaskCheckpoint> =
        jsScoped(context) {
            lateinit var microtaskCheckpoint: JsMicrotaskCheckpoint
            val microtaskCallback =
                JsFunction {
                    microtaskCheckpoint.complete(it)
                    JsUndefined()
                }
            val nativeTimerEvent =
                JsFunction {
                    val shouldScheduleTimer = it.getOrNull(0)?.booleanOrNull
                    val id = it.getOrNull(1)?.intOrNull
                    if (shouldScheduleTimer == null || id == null) {
                        throw IllegalArgumentException("unexpected event loop event")
                    }
                    val contextId = this.context.core.id
                    val jobId = "$contextId.$id"
                    if (shouldScheduleTimer) {
                        check(!isCompleting && job.isActive) { "JsEventLoop is closed" }
                        val delayMs = it.getOrNull(2)?.longOrNull ?: 0L
                        val shouldRepeat = it.getOrNull(3)?.booleanOrNull ?: false
                        val timerJob =
                            launch(start = CoroutineStart.LAZY) {
                                yield()
                                do {
                                    delay(delayMs)
                                    if (shouldRepeat) {
                                        if (jobId !in timerJobs) return@launch
                                    } else if (timerJobs.remove(jobId) == null) {
                                        return@launch
                                    }
                                    tick(contextId, id)
                                } while (shouldRepeat)
                            }
                        timerJobs[jobId] = timerJob
                        timerJob.start()
                    } else {
                        timerJobs.remove(jobId)?.cancel()
                    }
                    JsUndefined()
                }
            evalBlockScoped(
                """
                (() => {
                    const RUN_ALL = "all";
                    const RUN_ONE = "one";
                    let nextId = 0;
                    function nextCallbackId () {
                        return nextId++;
                    }
                    function validateCallback (callback) {
                        if (typeof callback !== "function") {
                            throw new TypeError("The \"callback\" argument must be of type function.");
                        }
                    }
                    function createCallbackQueue (runMode) {
                        let pending = [];
                        let batch = null;
                        function removeFrom (queue, id) {
                            if (queue === null) return;
                            for (let i = queue.length - 1; i >= 0; i--) {
                                if (queue[i].id === id) {
                                    queue.splice(i, 1);
                                }
                            }
                        }
                        function runCallback (item) {
                            item.callback(...item.args);
                            return true;
                        }
                        function runAll () {
                            let didRun = false;
                            while (pending.length > 0) {
                                didRun = true;
                                runCallback(pending.shift());
                            }
                            return didRun;
                        }
                        function runOne () {
                            if (batch === null) {
                                batch = pending;
                                pending = [];
                            }
                            if (batch.length === 0) {
                                batch = null;
                                return false;
                            }
                            const didRun = runCallback(batch.shift());
                            if (batch.length === 0) {
                                batch = null;
                            }
                            return didRun;
                        }
                        return {
                            add(callback, args, id) {
                                validateCallback(callback);
                                id = id === undefined ? nextCallbackId() : id;
                                pending.push({
                                    id: id,
                                    callback: callback,
                                    args: args,
                                });
                                return id;
                            },
                            remove(id) {
                                removeFrom(pending, id);
                                removeFrom(batch, id);
                            },
                            isNotEmpty() {
                                return pending.length > 0 || (batch !== null && batch.length > 0);
                            },
                            run() {
                                switch (runMode) {
                                    case RUN_ALL:
                                        return runAll();
                                    case RUN_ONE:
                                        return runOne();
                                    default:
                                        throw new Error("Unexpected callback queue run mode.");
                                }
                            },
                        };
                    }
                    function createNativeTimerScheduler (timerQueue) {
                        const scheduled = new Map();
                        return {
                            schedule(callback, args, delay, shouldRepeat) {
                                validateCallback(callback);
                                const id = nextCallbackId();
                                shouldRepeat = Boolean(shouldRepeat);
                                const item = {
                                    id: id,
                                    callback: callback,
                                    args: args,
                                    shouldRepeat: shouldRepeat,
                                };
                                scheduled.set(id, item);
                                try {
                                    nativeTimerEvent(true, id, Number(delay) || 0, shouldRepeat);
                                } catch (e) {
                                    scheduled.delete(id);
                                    throw e;
                                }
                                return id;
                            },
                            remove(id) {
                                if (typeof id !== "number") {
                                    return;
                                }
                                scheduled.delete(id);
                                timerQueue.remove(id);
                                nativeTimerEvent(false, id);
                            },
                            activate(id) {
                                if (typeof id !== "number") {
                                    return;
                                }
                                const item = scheduled.get(id);
                                if (item === undefined) {
                                    return;
                                }
                                timerQueue.add(item.callback, item.args, item.id);
                                if (!item.shouldRepeat) {
                                    scheduled.delete(id);
                                }
                            },
                        };
                    }
                    const nextTickQueue = createCallbackQueue(RUN_ALL);
                    const timerQueue = createCallbackQueue(RUN_ONE);
                    const immediateQueue = createCallbackQueue(RUN_ONE);
                    const nativeTimerScheduler = createNativeTimerScheduler(timerQueue);
                    globalThis.clearImmediate = function clearImmediate (id) {
                        immediateQueue.remove(id);
                    };
                    globalThis.clearInterval = function clearInterval (id) {
                        nativeTimerScheduler.remove(id);
                    };
                    globalThis.clearTimeout = function clearTimeout (id) {
                        nativeTimerScheduler.remove(id);
                    };
                    globalThis.setImmediate = function setImmediate (callback, ...args) {
                        return immediateQueue.add(callback, args);
                    };
                    globalThis.setInterval = function setInterval (callback, delay, ...args) {
                        return nativeTimerScheduler.schedule(callback, args, delay, true);
                    };
                    globalThis.setTimeout = function setTimeout (callback, delay, ...args) {
                        return nativeTimerScheduler.schedule(callback, args, delay);
                    };
                    globalThis.process = globalThis.process || {};
                    globalThis.process.nextTick = function nextTick (callback, ...args) {
                        nextTickQueue.add(callback, args);
                    };
                    function tick (timerId) {
                        nativeTimerScheduler.activate(timerId);
                        const didRunNextTicks = nextTickQueue.run();
                        if (didRunNextTicks) {
                            return true;
                        }
                        const didRunCallback = timerQueue.isNotEmpty()
                            ? timerQueue.run()
                            : immediateQueue.run();
                        if (didRunCallback) {
                            nextTickQueue.run();
                            return true;
                        }
                        return nextTickQueue.isNotEmpty() ||
                            timerQueue.isNotEmpty() ||
                            immediateQueue.isNotEmpty();
                    }
                    async function runMicrotaskCheckpoint () {
                        for (let i = 0; i < $MICROTASK_CHECKPOINT_ITERATIONS; i++) {
                            await undefined;
                        }
                        microtaskCallback.apply(this, arguments);
                    }
                    return {
                        tick: tick,
                        runMicrotaskCheckpoint: runMicrotaskCheckpoint,
                    };
                })()
                """.trimIndent(),
                "nativeTimerEvent" to nativeTimerEvent,
                "microtaskCallback" to microtaskCallback,
            ).let {
                it as JsObject
                val tick = (it["tick"] as JsFunction).escape()
                try {
                    microtaskCheckpoint =
                        JsMicrotaskCheckpoint(
                            runCheckpoint = (it["runMicrotaskCheckpoint"] as JsFunction).escape(),
                        )
                    tick to microtaskCheckpoint
                } catch (e: Throwable) {
                    tick.close()
                    throw e
                }
            }
        }

    private fun tick(
        contextId: Int? = null,
        timerId: Int? = null,
    ): Boolean {
        if (!job.isActive) {
            return false
        }
        var timerId = timerId
        var shouldContinue: Boolean
        var didRun = false
        do {
            shouldContinue = false
            val tickCount = jsTicks.size
            for (index in 0 until tickCount) {
                val tick = jsTicks[index]
                if (!job.isActive || tick.isClosed) continue
                val tickShouldContinue =
                    jsScoped(tick.context) {
                        if (timerId != null &&
                            contextId != null &&
                            tick.context.core.id == contextId
                        ) {
                            tick(JsNumber(timerId))
                        } else {
                            tick()
                        }.boolean
                    }
                didRun = didRun || tickShouldContinue
                shouldContinue = shouldContinue || tickShouldContinue
            }
            timerId = null
        } while (job.isActive && shouldContinue)
        return didRun
    }

    suspend fun runAndComplete() {
        run(shouldComplete = true)
    }

    suspend fun run() {
        run(shouldComplete = false)
    }

    private suspend fun run(shouldComplete: Boolean) {
        val callerContext = currentCoroutineContext()
        check(!callerContext[Job].isDescendantOf(job)) {
            "JsEventLoop.run() cannot be called from a coroutine belonging to this event loop"
        }
        var shouldAwaitCompletion = false
        try {
            lock.withLock {
                if (isCompleting || !job.isActive) {
                    shouldAwaitCompletion = true
                    return@withLock
                }
                _run()
                if (shouldComplete) {
                    isCompleting = true
                    job.complete()
                    shouldAwaitCompletion = true
                }
            }
        } catch (e: CancellationException) {
            callerContext.ensureActive()
            if (job.isActive) {
                throw e
            }
            shouldAwaitCompletion = true
        }
        if (shouldAwaitCompletion || !job.isActive) {
            completionException.await()?.let { throw it }
        }
    }

    @Suppress("FunctionName")
    private suspend fun _run() {
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
            if (hasChildren) continue
            val shouldContinueAfterMicrotasks =
                withContext(coroutineContext) {
                    val contextCount = microtaskCheckpoints.size
                    withTimeoutOrNull(10000) {
                        for (index in 0 until contextCount) {
                            if (!job.isActive) {
                                return@withTimeoutOrNull
                            }
                            microtaskCheckpoints[index].await()
                        }
                    }
                    tick() || contextCount != microtaskCheckpoints.size
                }
            if (shouldContinueAfterMicrotasks || job.children.any()) continue
            break
        }
    }

    fun cancel(exception: Throwable? = null) {
        cancel(exception?.message ?: "", exception)
    }
}
