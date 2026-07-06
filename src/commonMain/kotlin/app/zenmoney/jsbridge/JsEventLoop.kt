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
                    jsTicks.forEach { tick -> tick.close() }
                    jsTicks.clear()
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

    private val jsTicks = mutableObjectListOf<JsFunction>()
    private val timerJobs = mutableScatterMapOf<String, Job>()

    fun attachTo(context: JsContext) {
        require(context.core.eventLoop == null || context.core.eventLoop == this) { "JsContext already has an event loop" }
        if (context.core.eventLoop == this) {
            return
        }
        context.core.eventLoop = this
        jsScoped(context) {
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
                        val delayMs = it.getOrNull(2)?.longOrNull ?: 0L
                        val shouldRepeat = it.getOrNull(3)?.booleanOrNull ?: false
                        timerJobs[jobId] =
                            launch {
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
                                scheduled.set(id, {
                                    id: id,
                                    callback: callback,
                                    args: args,
                                    shouldRepeat: shouldRepeat,
                                });
                                nativeTimerEvent(true, id, Number(delay) || 0, shouldRepeat);
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
                    return tick;
                })()
                """.trimIndent(),
                "nativeTimerEvent" to nativeTimerEvent,
            ).let {
                jsTicks.add((it as JsFunction).escape())
            }
        }
    }

    private fun tick(
        contextId: Int? = null,
        timerId: Int? = null,
    ) {
        if (!job.isActive) {
            return
        }
        var timerId = timerId
        var shouldContinue: Boolean
        do {
            shouldContinue = false
            jsTicks.forEach { tick ->
                if (!job.isActive || tick.isClosed) return@forEach
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
                shouldContinue = shouldContinue || tickShouldContinue
            }
            timerId = null
        } while (job.isActive && shouldContinue)
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
}
