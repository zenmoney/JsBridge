package app.zenmoney.jsbridge

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

expect sealed class JsContext(
    unit: Unit,
) : AutoCloseable {
    internal abstract val core: JsContextCore

    abstract var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any?

    abstract val globalThis: JsObject

    internal abstract val NULL: JsNull
    internal abstract val UNDEFINED: JsUndefined

    @Throws(JsException::class)
    internal abstract fun evaluateScript(script: String): JsValue

    @Throws(JsException::class)
    internal abstract fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    @Throws(JsException::class)
    internal abstract fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    internal abstract fun createArray(value: Iterable<JsValue>): JsArray

    internal abstract fun createBoolean(value: Boolean): JsBoolean

    internal abstract fun createBooleanObject(value: Boolean): JsBooleanObject

    internal abstract fun createDate(millis: Long): JsDate

    internal abstract fun createError(exception: Throwable): JsObject

    internal abstract fun createException(error: JsValue): JsException

    internal abstract fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction

    internal abstract fun createNumber(value: Number): JsNumber

    internal abstract fun createNumberObject(value: Number): JsNumberObject

    internal abstract fun createObject(): JsObject

    internal abstract fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    internal abstract fun createString(value: String): JsString

    internal abstract fun createStringObject(value: String): JsStringObject

    internal abstract fun createUint8Array(value: ByteArray): JsUint8Array

    internal abstract fun <T : JsValue> createValueAlias(value: T): T

    internal abstract fun closeValue(value: JsValue)

    abstract override fun close()

    internal abstract fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue

    internal abstract fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue
}

expect class JsEngineContext : JsContext {
    constructor()

    override val core: JsContextCore
    override var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any?
    override val globalThis: JsObject
    override val NULL: JsNull
    override val UNDEFINED: JsUndefined

    override fun evaluateScript(script: String): JsValue

    override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue

    override fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue

    override fun createArray(value: Iterable<JsValue>): JsArray

    override fun createBoolean(value: Boolean): JsBoolean

    override fun createBooleanObject(value: Boolean): JsBooleanObject

    override fun createDate(millis: Long): JsDate

    override fun createError(exception: Throwable): JsObject

    override fun createException(error: JsValue): JsException

    override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction

    override fun createNumber(value: Number): JsNumber

    override fun createNumberObject(value: Number): JsNumberObject

    override fun createObject(): JsObject

    override fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise

    override fun createString(value: String): JsString

    override fun createStringObject(value: String): JsStringObject

    override fun createUint8Array(value: ByteArray): JsUint8Array

    override fun <T : JsValue> createValueAlias(value: T): T

    override fun closeValue(value: JsValue)

    override fun close()

    override fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue

    override fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue
}

@Suppress("FunctionName")
fun JsContext(): JsEngineContext = JsEngineContext()

internal const val JS_NATIVE_PROMISE_GLOBAL_PROPERTY = "__appZenmoneyNativePromise"

internal val jsPromiseRejectionTrackingScript: String =
    """
    (function () {
        if (globalThis.__appZenmoneyPromiseRejectionTrackingInstalled) return;

        const nativePromise = globalThis.Promise;
        if (typeof nativePromise !== "function") return;

        globalThis.__appZenmoneyPromiseRejectionTrackingInstalled = true;

        try {
            Object.defineProperty(globalThis, "$JS_NATIVE_PROMISE_GLOBAL_PROPERTY", {
                configurable: false,
                enumerable: false,
                value: nativePromise,
                writable: false,
            });
        } catch (_) {
            globalThis.$JS_NATIVE_PROMISE_GLOBAL_PROPERTY = nativePromise;
        }

        const nativeThen = nativePromise.prototype.then;
        const nativeCatch = nativePromise.prototype.catch;
        const nativeFinally = nativePromise.prototype.finally;
        const promiseStates = new WeakMap();

        function deferToHostTurn(callback) {
            if (typeof globalThis.setTimeout === "function") {
                globalThis.setTimeout(callback, 0);
            } else {
                nativeThen.call(nativePromise.resolve(), callback);
            }
        }

        function createPromiseRejectionEvent(type, promise, reason) {
            if (typeof globalThis.PromiseRejectionEvent === "function") {
                try {
                    return new globalThis.PromiseRejectionEvent(type, {
                        promise: promise,
                        reason: reason,
                        cancelable: true,
                    });
                } catch (_) {
                }
            }
            if (typeof globalThis.Event === "function") {
                const event = new globalThis.Event(type, { cancelable: true });
                event.promise = promise;
                event.reason = reason;
                return event;
            }
            return {
                type: type,
                promise: promise,
                reason: reason,
                defaultPrevented: false,
                preventDefault() {
                    this.defaultPrevented = true;
                },
            };
        }

        function dispatchPromiseRejectionEvent(type, promise, reason) {
            const event = createPromiseRejectionEvent(type, promise, reason);
            if (typeof globalThis.dispatchEvent === "function" && typeof globalThis.Event === "function") {
                globalThis.dispatchEvent(event);
            } else {
                const handler = globalThis["on" + type];
                if (typeof handler === "function") {
                    handler.call(globalThis, event);
                }
            }
        }

        function track(promise) {
            if (
                !promise ||
                typeof promise !== "object" && typeof promise !== "function" ||
                promiseStates.has(promise)
            ) {
                return promise;
            }
            const state = {
                handled: false,
                notified: false,
                reason: undefined,
            };
            promiseStates.set(promise, state);
            nativeThen.call(
                promise,
                undefined,
                reason => {
                    state.reason = reason;
                    deferToHostTurn(() => {
                        deferToHostTurn(() => {
                            if (!state.handled) {
                                state.notified = true;
                                dispatchPromiseRejectionEvent("unhandledrejection", promise, reason);
                            }
                        });
                    });
                },
            );
            try {
                Object.defineProperty(promise, "constructor", {
                    configurable: true,
                    enumerable: false,
                    value: trackedPromise,
                    writable: true,
                });
            } catch (_) {
            }
            return promise;
        }

        function markHandled(promise) {
            const state = promiseStates.get(promise);
            if (!state || state.handled) return;
            state.handled = true;
            if (state.notified) {
                deferToHostTurn(() => dispatchPromiseRejectionEvent("rejectionhandled", promise, state.reason));
            }
        }

        nativePromise.prototype.then = function then(onFulfilled, onRejected) {
            track(this);
            markHandled(this);
            return track(nativeThen.call(this, onFulfilled, onRejected));
        };
        nativePromise.prototype.catch = function catchPromise(onRejected) {
            track(this);
            markHandled(this);
            return track(nativeCatch.call(this, onRejected));
        };
        if (typeof nativeFinally === "function") {
            nativePromise.prototype.finally = function finallyPromise(onFinally) {
                track(this);
                markHandled(this);
                return track(nativeFinally.call(this, onFinally));
            };
        }

        const trackedPromise = function Promise(executor) {
            if (typeof new.target === "undefined") {
                throw new TypeError("Promise constructor cannot be invoked without 'new'");
            }
            const promise = typeof Reflect === "object" && typeof Reflect.construct === "function"
                ? Reflect.construct(nativePromise, [executor], new.target)
                : new nativePromise(executor);
            return track(promise);
        };
        trackedPromise.prototype = nativePromise.prototype;
        if (typeof Object.setPrototypeOf === "function") {
            Object.setPrototypeOf(trackedPromise, nativePromise);
        }
        globalThis.Promise = trackedPromise;
    })();
    """.trimIndent()

class JsPlainValueState internal constructor() {
    private val values = mutableMapOf<JsValue, Any?>()

    operator fun contains(value: JsValue): Boolean = values.containsKey(value)

    operator fun get(value: JsValue): Any? = values[value]

    operator fun set(
        value: JsValue,
        plainValue: Any?,
    ) {
        values[value] = plainValue
    }

    fun remove(value: JsValue) {
        values.remove(value)
    }
}

@OptIn(ExperimentalAtomicApi::class)
private val index = AtomicInt(0)

internal class JsContextCore(
    context: JsContext,
) : AutoCloseable {
    @Suppress("PropertyName")
    internal var _scope: JsScope? = JsScope().also { it._context = context }
    var scopeValuesPool: MutableList<ArrayList<AutoCloseable>>? = arrayListOf()
    var eventLoop: JsEventLoop? = null

    @OptIn(ExperimentalAtomicApi::class)
    val id = index.incrementAndFetch()

    private var tag: Any? = null
    private var tagReader: JsFunction? = null
    private var tagSetter: JsFunction? = null
    private var plainValueFrame: PlainValueFrame? = null

    val scope: JsScope
        get() = checkNotNull(_scope) { "JsContext is already closed" }

    fun addValue(value: JsValue) {
        scope.tryAutoClose(value)
    }

    fun removeValue(value: JsValue) {
        _scope?.tryEscape(value)
    }

    fun toPlainValue(value: JsValue): Any? =
        withPlainValueState { state ->
            toPlainValue(value, state)
        }

    fun toPlainMap(value: JsObject): Map<String, Any?> =
        withPlainValueState { state ->
            toPlainMap(value, state)
        }

    fun toPlainList(value: JsArray): List<Any?> =
        withPlainValueState { state ->
            toPlainList(value, state)
        }

    fun getTag(
        jsObject: JsObject,
        key: String,
    ): Any? {
        (_scope?.context as? JsWebViewContext)?.let {
            return it.getTag(jsObject, key)
        }
        return jsScoped(jsObject.context) {
            initTagReaderAndSetter(context)
            tagReader!!(jsObject, JsString(key))
            tag?.also { tag = null }
        }
    }

    fun setTag(
        jsObject: JsObject,
        key: String,
        value: Any,
    ) {
        (_scope?.context as? JsWebViewContext)?.let {
            it.setTag(jsObject, key, value)
            return
        }
        jsScoped(jsObject.context) {
            val read =
                JsFunction {
                    context.core.tag = value
                    context.UNDEFINED
                }
            initTagReaderAndSetter(context)
            tagSetter!!(jsObject, JsString(key), read)
        }
    }

    fun removeTag(
        jsObject: JsObject,
        key: String,
    ) {
        (_scope?.context as? JsWebViewContext)?.let {
            it.removeTag(jsObject, key)
            return
        }
        jsScoped(jsObject.context) {
            initTagReaderAndSetter(context)
            tagSetter!!(jsObject, JsString(key), context.UNDEFINED)
        }
    }

    private fun <T> withPlainValueState(block: JsScope.(JsPlainValueState) -> T): T {
        plainValueFrame?.let {
            return it.scope.block(it.state)
        }
        return jsScoped(scope.context) {
            val frame = PlainValueFrame(this, JsPlainValueState())
            plainValueFrame = frame
            try {
                block(frame.state)
            } finally {
                plainValueFrame = null
            }
        }
    }

    private fun JsScope.toPlainValue(
        value: JsValue,
        state: JsPlainValueState,
    ): Any? {
        if (value in state) {
            return state[value]
        }
        return context.getPlainValueOf(this, value, state).also { plainValue ->
            if (value is JsObject && plainValue.isReferencePlainValue()) {
                state[value] = plainValue
            }
        }
    }

    private fun JsScope.toPlainMap(
        value: JsObject,
        state: JsPlainValueState,
    ): Map<String, Any?> {
        if (value in state) {
            @Suppress("UNCHECKED_CAST")
            return state[value] as Map<String, Any?>
        }
        val result = linkedMapOf<String, Any?>()
        state[value] = result
        try {
            value.keys.forEach { property ->
                result[property] = value[property].toPlainValue()
            }
            return result
        } catch (e: Throwable) {
            if (state[value] === result) {
                state.remove(value)
            }
            throw e
        }
    }

    private fun JsScope.toPlainList(
        value: JsArray,
        state: JsPlainValueState,
    ): List<Any?> {
        if (value in state) {
            @Suppress("UNCHECKED_CAST")
            return state[value] as List<Any?>
        }
        val result = ArrayList<Any?>(value.size)
        state[value] = result
        try {
            for (index in 0 until value.size) {
                result.add(value[index].toPlainValue())
            }
            return result
        } catch (e: Throwable) {
            if (state[value] === result) {
                state.remove(value)
            }
            throw e
        }
    }

    private fun initTagReaderAndSetter(context: JsContext) {
        if (tagReader == null) {
            tagReader =
                context.evaluateScript(
                    """
                    (function (object, key) {
                        const getter = object[Symbol.for('appZenmoneyTag' + key)];
                        if (getter) {
                            getter();
                        }
                    })
                    """.trimIndent(),
                ) as JsFunction
            tagSetter =
                context.evaluateScript(
                    """
                    (function (object, key, getter) {
                        object[Symbol.for('appZenmoneyTag' + key)] = getter;
                    })
                    """.trimIndent(),
                ) as JsFunction
        }
    }

    override fun close() {
        scopeValuesPool = null
        _scope.also { _scope = null }?.close()
        eventLoop = null
        tag = null
        tagReader = null
        tagSetter = null
        plainValueFrame = null
    }

    private fun Any?.isReferencePlainValue(): Boolean = this != null && this !is Boolean && this !is Number && this !is String

    private data class PlainValueFrame(
        val scope: JsScope,
        val state: JsPlainValueState,
    )
}

val JsContext.isClosed: Boolean
    get() = core._scope == null

val JsContext.eventLoop: JsEventLoop?
    get() = core.eventLoop
