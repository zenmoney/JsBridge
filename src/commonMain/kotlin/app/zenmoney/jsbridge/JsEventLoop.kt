package app.zenmoney.jsbridge

import androidx.collection.ObjectList
import androidx.collection.mutableIntLongMapOf
import androidx.collection.mutableIntObjectMapOf
import androidx.collection.mutableObjectListOf
import androidx.collection.mutableScatterMapOf
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
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

/** Selects who schedules and executes `setTimeout` and `setInterval` callbacks. */
enum class JsTimerMode {
    /** Install timers scheduled by this event loop. Also works in engines without browser timers. */
    EVENT_LOOP,

    /** Leave the existing timer functions untouched. Their work is not awaited by [JsEventLoop.run]. */
    PRESERVE,

    /**
     * Wrap existing browser timer functions and await registrations made through the wrappers.
     * The browser retains ownership of timing, IDs, callback execution and errors. Intervals keep
     * [JsEventLoop.run] waiting until cleared. Cancelling the loop stops observation, not the timers.
     *
     * Timers created before attachment, calls through saved original functions, and string handlers
     * are not observed. Returned callback promises are not awaited. Original clear functions saved
     * before attachment bypass observation too; use the wrapped clear functions for observed timers.
     */
    OBSERVE,
}

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

private fun <T : Any> ObjectList<T>.findNextIndex(
    current: T,
    next: T?,
): Int {
    for (index in 0 until size) {
        if (this[index] === current) {
            return index + 1
        }
    }
    if (next != null) {
        for (index in 0 until size) {
            if (this[index] === next) {
                return index
            }
        }
    }
    return -1
}

private class JsMicrotaskCheckpoint(
    private val runCheckpoint: JsFunction,
    private val disposeTimers: JsFunction?,
) : AutoCloseable {
    val context: JsContext
        get() = runCheckpoint.context

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
        // During WebView shutdown its runtime invokes disposal itself. Otherwise restore the
        // browser functions here; a failed navigation RPC must not strand loop completion.
        if (disposeTimers != null) {
            if (!disposeTimers.isClosed) runCatching { jsScoped(context) { disposeTimers() } }
            disposeTimers.close()
        }
        runCheckpoint.close()
        var continuations: ArrayList<CancellableContinuation<Unit>>? = null
        continuationById.forEachValue {
            if (continuations == null) {
                continuations = ArrayList(continuationById.size)
            }
            continuations.add(it)
        }
        continuationById.clear()
        continuations?.forEach { it.resume(Unit) }
    }
}

/**
 * Coordinates JavaScript work and runs callbacks owned by this loop on the dispatcher supplied in [context].
 * Observed browser timer callbacks continue to execute in the browser.
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
                dispatch {
                    jsTicks.forEach { tick -> tick.close() }
                    jsTicks.clear()
                    microtaskCheckpoints.forEach { checkpoint -> checkpoint.close() }
                    microtaskCheckpoints.clear()
                    timerJobs.clear()
                    observedTimerRevisions.clear()
                    completionException.complete(exception)
                    onCompletion(cause != null, exception)
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
    private val observedTimerRevisions = mutableIntLongMapOf()
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
        attachTo(context, timerMode = JsTimerMode.EVENT_LOOP)
    }

    /**
     * Attaches [context] using [timerMode]. Must run on this loop's dispatcher thread.
     * Reattaching the same context keeps its original configuration. [JsTimerMode.OBSERVE] requires
     * existing browser timer functions; it never moves callback execution to the native dispatcher.
     */
    fun attachTo(
        context: JsContext,
        timerMode: JsTimerMode,
    ) {
        check(!context.isClosed) { "JsContext is already closed" }
        require(context.core.eventLoop == null || context.core.eventLoop == this) { "JsContext already has an event loop" }
        if (context.core.eventLoop == this) {
            return
        }
        // The common attachment script can emit timer events while installing the functions
        // (for example, through a page accessor). Give those callbacks their owner immediately.
        context.core.eventLoop = this
        val (jsTick, microtaskCheckpoint) =
            try {
                createAttachment(context, timerMode)
            } catch (error: Throwable) {
                if (context.core.eventLoop === this) {
                    runCatching { detachFrom(context) }.exceptionOrNull()?.let(error::addSuppressed)
                    context.core.eventLoop = null
                }
                throw error
            }
        if (isCompleting || !job.isActive) {
            jsTick.close()
            microtaskCheckpoint.close()
            return
        }
        jsTicks.add(jsTick)
        microtaskCheckpoints.add(microtaskCheckpoint)
    }

    internal fun dispatch(block: () -> Unit) {
        if (dispatcher.isDispatchNeeded(coroutineContext)) {
            dispatcher.dispatch(coroutineContext) { block() }
        } else {
            block()
        }
    }

    internal fun detachFrom(context: JsContext) {
        require(context.core.eventLoop == this) { "JsContext is not attached to this event loop" }
        observedTimerRevisions.remove(context.core.id)
        for (index in jsTicks.size - 1 downTo 0) {
            if (jsTicks[index].context === context) {
                jsTicks.removeAt(index).close()
            }
        }
        for (index in microtaskCheckpoints.size - 1 downTo 0) {
            if (microtaskCheckpoints[index].context === context) {
                microtaskCheckpoints.removeAt(index).close()
            }
        }
        val timerKeys = mutableListOf<String>()
        val timerKeyPrefix = "${context.core.id}."
        timerJobs.forEach { key, _ ->
            if (key.startsWith(timerKeyPrefix)) {
                timerKeys += key
            }
        }
        timerKeys.forEach { key -> timerJobs.remove(key)?.cancel() }
    }

    private fun createAttachment(
        context: JsContext,
        timerMode: JsTimerMode,
    ): Pair<JsFunction, JsMicrotaskCheckpoint> =
        jsScoped(context) {
            var timerEventsValid = true
            lateinit var microtaskCheckpoint: JsMicrotaskCheckpoint
            val microtaskCallback =
                JsFunction {
                    microtaskCheckpoint.complete(it)
                    JsUndefined()
                }
            val nativeTimerEvent =
                JsFunction {
                    if (!timerEventsValid) return@JsFunction JsUndefined()
                    val event = it.getOrNull(0)?.stringOrNull
                    if (event == "observe") {
                        updateObservedTimers(this.context, it[1].long, it[2].int > 0)
                        return@JsFunction JsUndefined()
                    }
                    val id = it.getOrNull(1)?.intOrNull
                    if ((event != "schedule" && event != "cancel") || id == null) {
                        throw IllegalArgumentException("unexpected event loop event")
                    }
                    val contextId = this.context.core.id
                    val jobId = "$contextId.$id"
                    if (event == "schedule") {
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
                                    if (shouldRepeat && delayMs <= 0L) {
                                        yield()
                                    }
                                } while (shouldRepeat)
                            }
                        timerJobs[jobId] = timerJob
                        timerJob.start()
                    } else {
                        timerJobs.remove(jobId)?.cancel()
                    }
                    JsUndefined()
                }
            try {
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
                                        nativeTimerEvent("schedule", id, Number(delay) || 0, shouldRepeat);
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
                                    nativeTimerEvent("cancel", id);
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
                        const nativeTimerScheduler = ${timerMode == JsTimerMode.EVENT_LOOP}
                            ? createNativeTimerScheduler(timerQueue) : null;
                        let observedTimerRevision = 0;
                        let observedTimerCount = 0;
                        let timerStateWithoutCallbacks = '0:0:0';
                        let timerStateWithCallbacks = '1:0:0';
                        let disposeTimers = null;
                        let timerEventsEnabled = false;
                        globalThis.clearImmediate = function clearImmediate (id) {
                            immediateQueue.remove(id);
                        };
                        globalThis.setImmediate = function setImmediate (callback, ...args) {
                            return immediateQueue.add(callback, args);
                        };
                        try {
                            if (${timerMode == JsTimerMode.OBSERVE}) {
                                const names = ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'];
                                const originals = names.map(name => globalThis[name]);
                                const descriptors = names.map(name => Object.getOwnPropertyDescriptor(globalThis, name));
                                names.forEach((name, index) => {
                                    const descriptor = descriptors[index];
                                    if (typeof originals[index] !== 'function' ||
                                        (descriptor && !descriptor.configurable && !descriptor.writable)) {
                                        throw new TypeError('Cannot observe browser timer function ' + name);
                                    }
                                });
                                const scheduled = new Map();
                                let running = 0;
                                let disposed = false;
                                let listener = nativeTimerEvent;
                                let unregister = null;
                                function ignoreNotificationError() {}
                                function changed() {
                                    const wasPending = observedTimerCount > 0;
                                    observedTimerCount = scheduled.size + running;
                                    const isPending = observedTimerCount > 0;
                                    // Only crossing the idle boundary changes native waiting. In particular,
                                    // an interval needs no bridge calls or Promises for its individual ticks.
                                    if (wasPending === isPending) return;
                                    observedTimerRevision++;
                                    const state = ':' + observedTimerRevision + ':' + (isPending ? '1' : '0');
                                    timerStateWithoutCallbacks = '0' + state;
                                    timerStateWithCallbacks = '1' + state;
                                    if (disposed || !timerEventsEnabled) return;
                                    try {
                                        const result = listener("observe", observedTimerRevision, observedTimerCount);
                                        // A WebView native callback returns a Promise. Observation must never change
                                        // browser exception handling, including when the bridge is being disposed.
                                        if (result && typeof result.catch === 'function') result.catch(ignoreNotificationError);
                                    } catch (_) {}
                                }
                                function schedule(original, repeat, receiver, args) {
                                    'use strict';
                                    const callback = args[0];
                                    // Preserve browser string/TrustedScript handling and its CSP checks unchanged.
                                    if (disposed || typeof callback !== 'function') return original.apply(receiver, args);
                                    let id;
                                    const wrapped = function () {
                                        if (disposed) return callback.apply(this, arguments);
                                        running++;
                                        changed();
                                        try {
                                            return callback.apply(this, arguments);
                                        } finally {
                                            running--;
                                            if (!repeat && scheduled.get(id) === wrapped) scheduled.delete(id);
                                            changed();
                                        }
                                    };
                                    args[0] = wrapped;
                                    id = original.apply(receiver, args);
                                    // Delay coercion can dispose the attachment reentrantly. Do not repopulate
                                    // its cleared map with a callback that will now bypass observation.
                                    if (!disposed) {
                                        scheduled.set(id, wrapped);
                                        changed();
                                    }
                                    return id;
                                }
                                function clear(original, receiver, args) {
                                    if (disposed) return original.apply(receiver, args);
                                    // Web IDL long conversion, performed once even for an object with valueOf().
                                    const id = (+args[0]) | 0;
                                    args[0] = id;
                                    const result = original.apply(receiver, args);
                                    if (scheduled.delete(id)) changed();
                                    return result;
                                }
                                const wrappers = [
                                    function setTimeout(callback, delay) { 'use strict'; return schedule(originals[0], false, this, arguments); },
                                    function setInterval(callback, delay) { 'use strict'; return schedule(originals[1], true, this, arguments); },
                                    function clearTimeout(id) { 'use strict'; return clear(originals[2], this, arguments); },
                                    function clearInterval(id) { 'use strict'; return clear(originals[3], this, arguments); },
                                ];
                                function dispose() {
                                    if (disposed) return;
                                    disposed = true;
                                    listener = null;
                                    if (unregister) { unregister(); unregister = null; }
                                    scheduled.clear();
                                    observedTimerCount = 0;
                                    names.forEach((name, index) => {
                                        try {
                                            const current = Object.getOwnPropertyDescriptor(globalThis, name);
                                            if (current && current.value === wrappers[index]) {
                                                if (descriptors[index]) Object.defineProperty(globalThis, name, descriptors[index]);
                                                else delete globalThis[name];
                                            }
                                        } catch (_) {} // A page may freeze the global after attachment; wrappers now delegate only.
                                    });
                                }
                                disposeTimers = dispose;
                                try {
                                    names.forEach((name, index) => {
                                        const descriptor = descriptors[index];
                                        Object.defineProperty(globalThis, name, {
                                            value: wrappers[index],
                                            writable: descriptor && 'writable' in descriptor ? descriptor.writable : true,
                                            enumerable: descriptor ? descriptor.enumerable : true,
                                            configurable: descriptor ? descriptor.configurable : true,
                                        });
                                    });
                                    const bridge = globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT;
                                    if (bridge && typeof bridge.addDisposeCallback === 'function') {
                                        unregister = bridge.addDisposeCallback(dispose);
                                    }
                                } catch (error) {
                                    dispose();
                                    throw error;
                                }
                            } else if (${timerMode == JsTimerMode.EVENT_LOOP}) {
                                globalThis.clearInterval = function clearInterval (id) {
                                    nativeTimerScheduler.remove(id);
                                };
                                globalThis.clearTimeout = function clearTimeout (id) {
                                    nativeTimerScheduler.remove(id);
                                };
                                globalThis.setInterval = function setInterval (callback, delay, ...args) {
                                    return nativeTimerScheduler.schedule(callback, args, delay, true);
                                };
                                globalThis.setTimeout = function setTimeout (callback, delay, ...args) {
                                    return nativeTimerScheduler.schedule(callback, args, delay);
                                };
                            }
                            globalThis.process = globalThis.process || {};
                            globalThis.process.nextTick = function nextTick (callback, ...args) {
                                nextTickQueue.add(callback, args);
                            };
                        } catch (error) {
                            if (disposeTimers) disposeTimers();
                            throw error;
                        }
                        // Accessors above can schedule timers and then throw. Publish observation
                        // only once installation has succeeded; tick also captures those registrations.
                        timerEventsEnabled = true;
                        function runCallbacks (timerId) {
                            if (nativeTimerScheduler) nativeTimerScheduler.activate(timerId);
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
                        function tick (timerId) {
                            const didRun = runCallbacks(timerId);
                            // Capture pending browser work in the same RPC as the tick. Native
                            // notifications may still be in transit; their revisions cannot overwrite this snapshot.
                            return ${timerMode == JsTimerMode.OBSERVE}
                                ? (didRun ? timerStateWithCallbacks : timerStateWithoutCallbacks)
                                : didRun;
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
                            disposeTimers: disposeTimers,
                        };
                    })()
                    """.trimIndent(),
                    "nativeTimerEvent" to nativeTimerEvent,
                    "microtaskCallback" to microtaskCallback,
                ).let {
                    it as JsObject
                    val disposeTimers = it["disposeTimers"] as? JsFunction
                    var tick: JsFunction? = null
                    try {
                        tick = (it["tick"] as JsFunction).escape()
                        microtaskCheckpoint =
                            JsMicrotaskCheckpoint(
                                runCheckpoint = (it["runMicrotaskCheckpoint"] as JsFunction).escape(),
                                disposeTimers = disposeTimers?.escape(),
                            )
                        tick to microtaskCheckpoint
                    } catch (e: Throwable) {
                        if (disposeTimers != null) runCatching { disposeTimers() }
                        tick?.close()
                        throw e
                    }
                }
            } catch (error: Throwable) {
                // In-flight events from a failed attachment must not affect a later retry.
                timerEventsValid = false
                throw error
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
            var attachmentsChanged = false
            var index = 0
            while (index < jsTicks.size) {
                val tick = jsTicks[index]
                val nextTick = if (index + 1 < jsTicks.size) jsTicks[index + 1] else null
                if (job.isActive && !tick.isClosed) {
                    val timerIdForTick =
                        if (timerId != null && contextId != null && tick.context.core.id == contextId) {
                            timerId
                        } else {
                            null
                        }
                    val tickShouldContinue =
                        jsScoped(tick.context) {
                            val result =
                                if (timerIdForTick != null) {
                                    tick(JsNumber(timerIdForTick))
                                } else {
                                    tick.invoke()
                                }
                            if (result is JsString) {
                                val state = result.string
                                var revision = 0L
                                var index = 2
                                // The attachment returns a canonical "didRun:revision:pending" snapshot.
                                // Parse in place without a list or substring allocations on every tick.
                                while (state[index] != ':') {
                                    revision = revision * 10 + (state[index] - '0')
                                    index++
                                }
                                updateObservedTimers(tick.context, revision, state[index + 1] == '1')
                                state[0] == '1'
                            } else {
                                result.boolean
                            }
                        }
                    if (timerIdForTick != null) {
                        timerId = null
                    }
                    didRun = didRun || tickShouldContinue
                    shouldContinue = shouldContinue || tickShouldContinue
                }
                if (index < jsTicks.size && jsTicks[index] === tick) {
                    index++
                } else {
                    index = jsTicks.findNextIndex(tick, nextTick)
                    if (index < 0) {
                        attachmentsChanged = true
                        break
                    }
                }
            }
            if (attachmentsChanged) {
                shouldContinue = true
            } else {
                timerId = null
            }
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
                    var attachmentsChanged = false
                    withTimeoutOrNull(10000) {
                        var index = 0
                        while (index < microtaskCheckpoints.size) {
                            if (!job.isActive) {
                                return@withTimeoutOrNull
                            }
                            val checkpoint = microtaskCheckpoints[index]
                            val nextCheckpoint =
                                if (index + 1 < microtaskCheckpoints.size) {
                                    microtaskCheckpoints[index + 1]
                                } else {
                                    null
                                }
                            checkpoint.await()
                            if (index < microtaskCheckpoints.size && microtaskCheckpoints[index] === checkpoint) {
                                index++
                            } else {
                                index = microtaskCheckpoints.findNextIndex(checkpoint, nextCheckpoint)
                                if (index < 0) {
                                    attachmentsChanged = true
                                    return@withTimeoutOrNull
                                }
                            }
                        }
                    }
                    tick() || attachmentsChanged
                }
            if (shouldContinueAfterMicrotasks || job.children.any()) continue
            break
        }
    }

    private fun updateObservedTimers(
        context: JsContext,
        revision: Long,
        hasPendingTimers: Boolean,
    ) {
        if (isCompleting || !job.isActive || context.isClosed || context.core.eventLoop !== this) return
        val contextId = context.core.id
        if (revision <= observedTimerRevisions.getOrDefault(contextId, -1L)) return
        observedTimerRevisions[contextId] = revision
        val jobId = "$contextId.browser"
        if (hasPendingTimers) {
            if (jobId !in timerJobs) timerJobs[jobId] = Job(job)
        } else {
            (timerJobs.remove(jobId) as? CompletableJob)?.complete()
        }
    }

    fun cancel(exception: Throwable? = null) {
        cancel(exception?.message ?: "", exception)
    }
}
