package app.zenmoney.jsbridge

import androidx.collection.ObjectList
import androidx.collection.mutableIntLongMapOf
import androidx.collection.mutableIntObjectMapOf
import androidx.collection.mutableObjectListOf
import androidx.collection.mutableScatterMapOf
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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

/**
 * Policies for the JavaScript API groups connected by [JsEventLoop.attachTo].
 * Each group defaults to replacing existing functions and installing missing ones.
 *
 * @property timers Policy for `setTimeout`, `setInterval`, `clearTimeout` and `clearInterval`, which share a timer ID pool.
 * @property immediate Policy for `setImmediate` and `clearImmediate`.
 * @property nextTick Policy for `process.nextTick`. Installing it creates `process` if it is absent.
 */
data class JsEventLoopPolicies(
    val timers: JsEventLoopPolicy = JsEventLoopPolicy(ExistingApiAction.REPLACE, MissingApiAction.INSTALL),
    val immediate: JsEventLoopPolicy = JsEventLoopPolicy(ExistingApiAction.REPLACE, MissingApiAction.INSTALL),
    val nextTick: JsEventLoopPolicy = JsEventLoopPolicy(ExistingApiAction.REPLACE, MissingApiAction.INSTALL),
)

/**
 * How an API group is connected to [JsEventLoop]. A group is present only when all its members are functions.
 * Otherwise [ifMissing] applies to the whole group, including any existing members.
 *
 * [ExistingApiAction.KEEP] with [MissingApiAction.SKIP] leaves properties untouched without reading getters.
 * [ExistingApiAction.REPLACE] with [MissingApiAction.INSTALL] installs functions without reading the originals.
 * Other combinations check availability and may invoke getters. Getter errors and failures to install or observe
 * functions fail attachment; they are not treated as missing APIs. Failed attachment rolls back its changes.
 */
data class JsEventLoopPolicy(
    val ifPresent: ExistingApiAction,
    val ifMissing: MissingApiAction,
) {
    /** Action for a complete group of existing functions. */
    enum class ExistingApiAction {
        /** Leave the functions unchanged. Their work is not awaited by [JsEventLoop.run]. */
        KEEP,

        /**
         * Wrap existing functions and await registrations made through the wrappers. The original scheduler
         * retains ownership of timing, handles, callback execution and errors. Unreplaceable functions cause
         * attachment to fail. Intervals keep [JsEventLoop.run] waiting until cleared.
         * Cancelling the loop stops observation, not the scheduled work, and restores the original functions
         * if the installed wrappers have not been replaced or locked by the page.
         *
         * Work scheduled before attachment, calls through saved original functions, and string timer handlers
         * are not observed. Returned callback promises are not awaited. Original clear functions saved before
         * attachment bypass observation too; use the wrapped clear functions for observed work.
         */
        OBSERVE,

        /** Replace the group with functions scheduled by this event loop. */
        REPLACE,
    }

    /** Action for a group with missing or non-function members. */
    enum class MissingApiAction {
        /** Leave the entire group untouched. Its work is not awaited by [JsEventLoop.run]. */
        SKIP,

        /** Install the entire group with functions scheduled by this event loop. */
        INSTALL,

        /** Fail attachment with [JsException] identifying the missing group and function. */
        FAIL,
    }
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
    private val disposeAttachment: JsFunction?,
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
        if (disposeAttachment != null) {
            if (!disposeAttachment.isClosed) runCatching { jsScoped(context) { disposeAttachment() } }
            disposeAttachment.close()
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
 * Observed callbacks continue to execute through their original scheduler.
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
                    observedWorkRevisions.clear()
                    queuedCallbacks.close()
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
    private val observedWorkRevisions = mutableIntLongMapOf()
    private val lock = Mutex()
    private val queuedCallbacks = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var isCompleting = false

    /**
     * Attaches [context] using the API group [policies].
     * By default existing APIs are replaced and missing APIs are installed.
     * Reattaching the same context keeps its original configuration.
     *
     * Must be called on the thread of the dispatcher passed to [JsEventLoop]. May be called while the event loop is
     * running. Attaching to an already closed event loop is allowed; native timer registration will report that it is
     * closed.
     */
    fun attachTo(
        context: JsContext,
        policies: JsEventLoopPolicies = JsEventLoopPolicies(),
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
                createAttachment(context, policies)
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
        queuedCallbacks.trySend(Unit)
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
        observedWorkRevisions.remove(context.core.id)
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
        policies: JsEventLoopPolicies,
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
                    if (event == "queued") {
                        if (!isCompleting && job.isActive && !this.context.isClosed && this.context.core.eventLoop === this@JsEventLoop) {
                            queuedCallbacks.trySend(Unit)
                        }
                        return@JsFunction JsUndefined()
                    }
                    if (event == "observe") {
                        updateObservedWork(this.context, it[1].long, it[2].int > 0)
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
                        function createCallbackQueue (runMode, notify = false) {
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
                                    if (notify) notifyQueuedCallbacks();
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
                                        const result = nativeTimerEvent("schedule", id, Number(delay) || 0, shouldRepeat);
                                        // WebView native functions return Promises. IDs are allocated locally;
                                        // a rejected registration must release its callback asynchronously.
                                        if (result && typeof result.catch === 'function') {
                                            result.catch(() => {
                                                scheduled.delete(id);
                                                timerQueue.remove(id);
                                            });
                                        }
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
                                    const wasScheduled = scheduled.delete(id);
                                    timerQueue.remove(id);
                                    if (wasScheduled) ignoreRejection(nativeTimerEvent("cancel", id));
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
                        const nextTickQueue = createCallbackQueue(RUN_ALL, true);
                        const timerQueue = createCallbackQueue(RUN_ONE);
                        const immediateQueue = createCallbackQueue(RUN_ONE, true);
                        let nativeTimerScheduler = null;
                        let observedWorkRevision = 0;
                        let observedWorkCount = 0;
                        let stateWithoutCallbacks = '0:0:0';
                        let stateWithCallbacks = '1:0:0';
                        let timerEventsEnabled = false;
                        let queueWakeupPending = false;
                        let ticking = false;
                        let disposed = false;
                        let listener = nativeTimerEvent;
                        let unregister = null;
                        const installedProperties = [];
                        const observers = [];
                        function ignoreNotificationError() {}
                        function ignoreRejection (result) {
                            if (result && typeof result.catch === 'function') result.catch(ignoreNotificationError);
                        }
                        function notifyQueuedCallbacks () {
                            if (!timerEventsEnabled || disposed || queueWakeupPending || ticking) return;
                            queueWakeupPending = true;
                            try {
                                ignoreRejection(nativeTimerEvent("queued"));
                            } catch (_) {}
                        }
                        // Define properties explicitly: assignment can silently ignore read-only placeholders.
                        // Observers preserve descriptor flags; replacements make configurable properties writable.
                        function installProperty (target, name, value, observed = false) {
                            const descriptor = Object.getOwnPropertyDescriptor(target, name);
                            if (descriptor ? !descriptor.configurable && !descriptor.writable : !Object.isExtensible(target)) {
                                throw new TypeError('Cannot install event loop property ' + name);
                            }
                            Object.defineProperty(target, name, {
                                value,
                                writable: observed && descriptor && 'writable' in descriptor ? descriptor.writable : true,
                                configurable: descriptor ? descriptor.configurable : true,
                                enumerable: descriptor ? descriptor.enumerable : true,
                            });
                            installedProperties.push({ target, name, value, descriptor, observed });
                        }
                        function restoreProperties (rollback) {
                            for (let i = installedProperties.length - 1; i >= 0; i--) {
                                const item = installedProperties[i];
                                if (!rollback && !item.observed) continue;
                                try {
                                    const current = Object.getOwnPropertyDescriptor(item.target, item.name);
                                    if (current && current.value === item.value) {
                                        if (item.descriptor) Object.defineProperty(item.target, item.name, item.descriptor);
                                        else delete item.target[item.name];
                                    }
                                } catch (_) {} // Frozen observer wrappers continue to delegate after disposal.
                            }
                        }
                        function changeObservedWork (delta) {
                            const wasPending = observedWorkCount > 0;
                            observedWorkCount += delta;
                            const isPending = observedWorkCount > 0;
                            // Only crossing the shared idle boundary changes native waiting.
                            if (wasPending === isPending) return;
                            observedWorkRevision++;
                            const state = ':' + observedWorkRevision + ':' + (isPending ? '1' : '0');
                            stateWithoutCallbacks = '0' + state;
                            stateWithCallbacks = '1' + state;
                            // tick returns this same snapshot, so changes inside its callbacks need no
                            // separate native notification (or WebView callback Promise).
                            if (disposed || !timerEventsEnabled || ticking) return;
                            try {
                                // WebView callbacks return Promises. Observation must preserve scheduler errors.
                                ignoreRejection(listener("observe", observedWorkRevision, observedWorkCount));
                            } catch (_) {}
                        }
                        function createObserver (callbackKey = false, coerceTimerId = false) {
                            const scheduled = new Map();
                            let running = 0;
                            let previousCount = 0;
                            function changed() {
                                if (disposed) return;
                                const count = scheduled.size + running;
                                const delta = count - previousCount;
                                previousCount = count;
                                changeObservedWork(delta);
                            }
                            const observer = {
                                schedule(original, repeat, receiver, args) {
                                    const callback = args[0];
                                    // Preserve string/TrustedScript handling and CSP checks in the original API.
                                    if (disposed || typeof callback !== 'function') return original.apply(receiver, args);
                                    let id;
                                    let finished = false;
                                    const wrapped = function () {
                                        if (disposed) return callback.apply(this, arguments);
                                        running++;
                                        changed();
                                        try {
                                            return callback.apply(this, arguments);
                                        } finally {
                                            finished = true;
                                            running--;
                                            const key = callbackKey ? wrapped : id;
                                            if (!repeat && scheduled.get(key) === wrapped) scheduled.delete(key);
                                            changed();
                                        }
                                    };
                                    args[0] = wrapped;
                                    id = original.apply(receiver, args);
                                    // Coercion can dispose the attachment, and a custom scheduler can call back
                                    // synchronously. Neither case should leave a phantom pending callback.
                                    if (!disposed && (repeat || !finished)) {
                                        scheduled.set(callbackKey ? wrapped : id, wrapped);
                                        changed();
                                    }
                                    return id;
                                },
                                clear(original, receiver, args) {
                                    if (disposed) return original.apply(receiver, args);
                                    // Browser timers use Web IDL long conversion, exactly once. Immediate handles
                                    // belong to their scheduler and may be opaque objects: never coerce them.
                                    const id = coerceTimerId ? (+args[0]) | 0 : args[0];
                                    args[0] = id;
                                    const result = original.apply(receiver, args);
                                    if (scheduled.delete(id)) changed();
                                    return result;
                                },
                                dispose() { scheduled.clear(); },
                            };
                            observers.push(observer);
                            return observer;
                        }
                        function resolveApiGroup (group, getTarget, names, ifPresent, ifMissing) {
                            // These combinations do not need to inspect existing functions.
                            if (ifPresent === 'KEEP' && ifMissing === 'SKIP') return { action: 'KEEP' };
                            const target = getTarget();
                            if (ifPresent === 'REPLACE' && ifMissing === 'INSTALL') return { action: 'REPLACE', target };
                            const originals = names.map(name => target && target[name]);
                            const missingIndex = originals.findIndex(original => typeof original !== 'function');
                            if (missingIndex !== -1) {
                                if (ifMissing === 'FAIL') {
                                    throw new TypeError('Missing event loop API group ' + group + ': ' + names[missingIndex]);
                                }
                                return { action: ifMissing === 'INSTALL' ? 'REPLACE' : 'KEEP', target };
                            }
                            if (ifPresent === 'OBSERVE') {
                                names.forEach(name => {
                                    const descriptor = Object.getOwnPropertyDescriptor(target, name);
                                    if (descriptor && !descriptor.configurable && !descriptor.writable) {
                                        throw new TypeError('Cannot observe event loop function ' + name);
                                    }
                                });
                            }
                            return { action: ifPresent, target, originals };
                        }
                        function disposeAttachment (rollback = false) {
                            if (!disposed) {
                                disposed = true;
                                timerEventsEnabled = false;
                                // Native completion/detachment already retires the observer's waiter.
                                // Update the snapshot without sending an idle notification back to native.
                                changeObservedWork(-observedWorkCount);
                                listener = null;
                                if (unregister) {
                                    try { unregister(); } catch (_) {}
                                    unregister = null;
                                }
                                observers.forEach(observer => observer.dispose());
                            }
                            restoreProperties(rollback);
                        }
                        try {
                            const timers = resolveApiGroup('TIMERS', () => globalThis,
                                ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'],
                                '${policies.timers.ifPresent}',
                                '${policies.timers.ifMissing}');
                            if (timers.action === 'OBSERVE') {
                                const originals = timers.originals;
                                const observer = createObserver(false, true);
                                installProperty(globalThis, 'setTimeout', function setTimeout(callback, delay) {
                                    'use strict'; return observer.schedule(originals[0], false, this, arguments);
                                }, true);
                                installProperty(globalThis, 'setInterval', function setInterval(callback, delay) {
                                    'use strict'; return observer.schedule(originals[1], true, this, arguments);
                                }, true);
                                installProperty(globalThis, 'clearTimeout', function clearTimeout(id) {
                                    'use strict'; return observer.clear(originals[2], this, arguments);
                                }, true);
                                installProperty(globalThis, 'clearInterval', function clearInterval(id) {
                                    'use strict'; return observer.clear(originals[3], this, arguments);
                                }, true);
                            } else if (timers.action === 'REPLACE') {
                                nativeTimerScheduler = createNativeTimerScheduler(timerQueue);
                                installProperty(globalThis, 'clearInterval', function clearInterval (id) {
                                    nativeTimerScheduler.remove(id);
                                });
                                installProperty(globalThis, 'clearTimeout', function clearTimeout (id) {
                                    nativeTimerScheduler.remove(id);
                                });
                                installProperty(globalThis, 'setInterval', function setInterval (callback, delay, ...args) {
                                    return nativeTimerScheduler.schedule(callback, args, delay, true);
                                });
                                installProperty(globalThis, 'setTimeout', function setTimeout (callback, delay, ...args) {
                                    return nativeTimerScheduler.schedule(callback, args, delay);
                                });
                            }
                            const immediate = resolveApiGroup('IMMEDIATE', () => globalThis, ['setImmediate', 'clearImmediate'],
                                '${policies.immediate.ifPresent}',
                                '${policies.immediate.ifMissing}');
                            if (immediate.action === 'OBSERVE') {
                                const originals = immediate.originals;
                                const observer = createObserver();
                                installProperty(globalThis, 'setImmediate', function setImmediate(callback) {
                                    'use strict'; return observer.schedule(originals[0], false, this, arguments);
                                }, true);
                                installProperty(globalThis, 'clearImmediate', function clearImmediate(id) {
                                    'use strict'; return observer.clear(originals[1], this, arguments);
                                }, true);
                            } else if (immediate.action === 'REPLACE') {
                                installProperty(globalThis, 'clearImmediate', function clearImmediate (id) {
                                    immediateQueue.remove(id);
                                });
                                installProperty(globalThis, 'setImmediate', function setImmediate (callback, ...args) {
                                    return immediateQueue.add(callback, args);
                                });
                            }
                            const nextTick = resolveApiGroup('NEXT_TICK', () => globalThis.process, ['nextTick'],
                                '${policies.nextTick.ifPresent}',
                                '${policies.nextTick.ifMissing}');
                            if (nextTick.action === 'OBSERVE') {
                                const process = nextTick.target;
                                const originals = nextTick.originals;
                                // nextTick has no cancellation handle; each callback needs its own registry key.
                                const observer = createObserver(true);
                                installProperty(process, 'nextTick', function nextTick(callback) {
                                    'use strict'; return observer.schedule(originals[0], false, this, arguments);
                                }, true);
                            } else if (nextTick.action === 'REPLACE') {
                                const existingProcess = nextTick.target;
                                const process = existingProcess == null ? {} : existingProcess;
                                if (typeof process !== 'object' && typeof process !== 'function') {
                                    throw new TypeError('Cannot install event loop property process');
                                }
                                if (existingProcess == null) installProperty(globalThis, 'process', process);
                                installProperty(process, 'nextTick', function nextTick (callback, ...args) {
                                    nextTickQueue.add(callback, args);
                                });
                            }
                            if (observers.length > 0) {
                                const bridge = $JS_WEB_VIEW_BRIDGE;
                                if (bridge && typeof bridge.addDisposeCallback === 'function') {
                                    unregister = bridge.addDisposeCallback(disposeAttachment);
                                }
                            }
                        } catch (error) {
                            disposeAttachment(true);
                            throw error;
                        }
                        // Accessors can schedule work and then throw. Publish observation only after
                        // installation succeeds; tick also captures registrations made during installation.
                        timerEventsEnabled = !disposed;
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
                            ticking = true;
                            let didRun = false;
                            try {
                                didRun = runCallbacks(timerId);
                            } finally {
                                ticking = false;
                                // A true result makes native tick again, including work queued by
                                // the following microtasks. Only an idle tick needs another wakeup.
                                queueWakeupPending = didRun;
                            }
                            // Capture pending browser work in the same RPC as the tick. Native
                            // notifications may still be in transit; their revisions cannot overwrite this snapshot.
                            return observers.length > 0
                                ? (didRun ? stateWithCallbacks : stateWithoutCallbacks)
                                : didRun;
                        }
                        async function drainMicrotasks () {
                            for (let i = 0; i < $MICROTASK_CHECKPOINT_ITERATIONS; i++) {
                                await undefined;
                            }
                            ignoreRejection(microtaskCallback.apply(this, arguments));
                        }
                        function runMicrotaskCheckpoint () {
                            // The native continuation is completed by microtaskCallback, not this Promise.
                            // Return undefined to avoid exporting and later releasing a Promise handle per RPC.
                            ignoreRejection(drainMicrotasks.apply(this, arguments));
                        }
                        return {
                            tick: tick,
                            runMicrotaskCheckpoint: runMicrotaskCheckpoint,
                            disposeAttachment: observers.length > 0 ? disposeAttachment : null,
                        };
                    })()
                    """.trimIndent(),
                    "nativeTimerEvent" to nativeTimerEvent,
                    "microtaskCallback" to microtaskCallback,
                ).let {
                    it as JsObject
                    val disposeAttachment = it["disposeAttachment"] as? JsFunction
                    var tick: JsFunction? = null
                    try {
                        tick = (it["tick"] as JsFunction).escape()
                        microtaskCheckpoint =
                            JsMicrotaskCheckpoint(
                                runCheckpoint = (it["runMicrotaskCheckpoint"] as JsFunction).escape(),
                                // OBSERVE may resolve to SKIP or INSTALL. Only actual observers need cleanup.
                                disposeAttachment = disposeAttachment?.escape(),
                            )
                        tick to microtaskCheckpoint
                    } catch (e: Throwable) {
                        if (disposeAttachment != null) runCatching { disposeAttachment(JsBoolean(true)) }
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
        // This scan covers every context, including work that already signalled a wakeup. Consume
        // that signal before scanning; notifications arriving during/after the scan still wake run().
        queuedCallbacks.tryReceive()
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
                        try {
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
                                    updateObservedWork(tick.context, revision, state[index + 1] == '1')
                                    state[0] == '1'
                                } else {
                                    result.boolean
                                }
                            }
                        } catch (_: JsWebViewContextDetachedException) {
                            // The failed RPC closed this detached context. Retire its tick and
                            // continue processing the remaining attachments.
                            false
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
            job.children.forEach { child ->
                hasChildren = true
                do {
                    // A pending native Promise can depend on a callback queued after the last tick.
                    // Wake for that work while still waiting for the Promise's coroutine to finish.
                    select<Unit> {
                        child.onJoin { }
                        queuedCallbacks.onReceiveCatching { }
                    }
                    withContext(coroutineContext) {
                        // An eager dispatcher must not execute callbacks inside their registration.
                        yield()
                        tick()
                    }
                } while (!child.isCompleted)
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
                            // The WebView acknowledgement resumes us from a native callback job.
                            // Let that job finish before checking whether the loop is idle again.
                            yield()
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

    private fun updateObservedWork(
        context: JsContext,
        revision: Long,
        hasPendingWork: Boolean,
    ) {
        if (isCompleting || !job.isActive || context.isClosed || context.core.eventLoop !== this) return
        val contextId = context.core.id
        if (revision <= observedWorkRevisions.getOrDefault(contextId, -1L)) return
        observedWorkRevisions[contextId] = revision
        val jobId = "$contextId.observed"
        if (hasPendingWork) {
            if (jobId !in timerJobs) timerJobs[jobId] = Job(job)
        } else {
            (timerJobs.remove(jobId) as? CompletableJob)?.complete()
        }
    }

    fun cancel(exception: Throwable? = null) {
        cancel(exception?.message ?: "", exception)
    }
}
