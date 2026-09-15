package app.zenmoney.jsbridge

import androidx.collection.MutableIntObjectMap
import androidx.collection.mutableIntIntMapOf
import androidx.collection.mutableIntObjectMapOf
import app.zenmoney.jsbridge.serialization.JsValueWire
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlinx.coroutines.launch

private class JsWebViewThrownError(
    val error: JsWebViewProtocolValue,
) : Exception()

private const val NATIVE_EXCEPTION_TAG = "app.zenmoney.jsbridge.nativeException"

private typealias JsWebViewRequestCompletion = (result: Result<JsWebViewProtocolValue>, nativeFailure: Boolean) -> Unit
private typealias JsWebViewPendingRequests = MutableIntObjectMap<JsWebViewRequestCompletion>

class JsWebViewContext internal constructor(
    createWebView: (contextId: Int) -> JsWebView,
) : JsContext(Unit) {
    companion object {}

    constructor() : this(createWebView = ::createJsWebView)

    internal constructor(webView: JsWebView) : this(createWebView = { webView })

    override val core = JsContextCore(this)
    override var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any? = { value, state ->
        toBasicPlainValue(value, state)
    }

    private var requestId = 1
    private val pendingRequestsLock = Lock()
    private val pendingRequests: JsWebViewPendingRequests = mutableIntObjectMapOf()
    private val pendingHandleRefCountChanges = mutableIntIntMapOf()

    init {
        invokeOnClose {
            cancelPendingRequests(takePendingRequests())
        }
    }

    private var createWebView: ((contextId: Int) -> JsWebView)? = createWebView
    private var webView: JsWebView? = null
    private val webViewMessageHandler =
        JsWebViewMessageHandler(
            object : JsWebViewMessageHandler.Listener {
                override fun onSuccess(
                    requestId: Int,
                    result: JsWebViewProtocolValue,
                ) {
                    completeRequest(
                        requestId,
                        Result.success(result),
                    )
                }

                override fun onFailure(
                    requestId: Int,
                    error: JsWebViewProtocolValue,
                ) {
                    completeRequest(
                        requestId,
                        Result.failure(JsWebViewThrownError(error)),
                    )
                }

                override fun onFunction(
                    jsCallbackId: Int,
                    callbackId: Int,
                    thiz: JsWebViewProtocolValue,
                    args: List<JsWebViewProtocolValue>,
                ) {
                    dispatchWebViewNativeCallback {
                        invokeWebViewFunctionCallback(
                            jsCallbackId = jsCallbackId,
                            callbackId = callbackId,
                            thiz = thiz,
                            args = args,
                        )
                    }
                }

                override fun onPromiseExecutor(
                    executorCallbackId: Int,
                    resolve: JsWebViewProtocolValue,
                    reject: JsWebViewProtocolValue,
                ) {
                    dispatchWebViewNativeCallback {
                        invokeWebViewPromiseExecutor(
                            callbackId = executorCallbackId,
                            resolve = resolve,
                            reject = reject,
                        )
                    }
                }

                override fun onDeallocate(handle: Int) {
                    dispatchWebViewNativeCallback {
                        onWebViewValueDeallocated(handle)
                    }
                }
            },
        )

    private var callbackId = 0
    private val refCounts = mutableIntIntMapOf()
    private val tagsByHandle = mutableIntObjectMapOf<MutableMap<String, Any>>()
    private val functionCallbackIds = mutableIntIntMapOf()
    private val functionByCallbackId = mutableIntObjectMapOf<JsFunctionScope.(args: List<JsValue>) -> JsValue>()
    private val promiseExecutorByCallbackId = mutableIntObjectMapOf<JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit>()

    override val globalThis: JsObject =
        createWebViewObject(0, JsWebViewProtocolHandleType.OBJECT)
            .also { registerWebViewValue(it) }
    override val NULL: JsNull = JsWebViewNull(this).also { registerWebViewValue(it) }
    override val UNDEFINED: JsUndefined = JsWebViewUndefined(this).also { registerWebViewValue(it) }

    override fun evaluateScript(script: String): JsValue = executeWebViewMessageBlockingAndDecode(JsWebViewMessage.Evaluate(script), "eval")

    override fun callFunction(
        f: JsFunction,
        args: List<JsValue>,
        thiz: JsValue,
    ): JsValue =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.CallFunction(
                functionHandle = (f as JsWebViewObject).handle,
                thisHandle = (thiz as? JsWebViewObject)?.handle,
                args = args.map(::createWebViewProtocolValue),
            ),
            "call",
        )

    override fun callFunctionAsConstructor(
        f: JsFunction,
        args: List<JsValue>,
    ): JsValue =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.Construct(
                functionHandle = (f as JsWebViewObject).handle,
                args = args.map(::createWebViewProtocolValue),
            ),
            "construct",
        )

    override fun createArray(value: Iterable<JsValue>): JsArray =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.CreateArray(value.map(::createWebViewProtocolValue)),
            "createArray",
        ) as JsArray

    override fun createBoolean(value: Boolean): JsBoolean = JsWebViewBoolean(this, value).also { registerWebViewValue(it) }

    override fun createBooleanObject(value: Boolean): JsBooleanObject =
        evaluateScript("new Boolean(${if (value) "true" else "false"})") as JsBooleanObject

    override fun createDate(millis: Long): JsDate = evaluateScript("new Date($millis)") as JsDate

    override fun createError(exception: Throwable): JsObject =
        (evaluateScript("new Error(${(exception.message ?: exception.toString()).toJson()})") as JsObject).also {
            it.setTag(NATIVE_EXCEPTION_TAG, exception)
        }

    private var creatingException = false

    override fun createException(error: JsValue): JsException {
        // Reading an error can execute getters, coercions and Object.keys in the page. If that
        // throws too, never recursively inspect its error.
        val fallbackMessage = if (error is JsObject) "JavaScript exception" else error.toString()
        val fallbackName = if (error is JsString) "Error" else ""
        val cause = (error as? JsObject)?.let { getTag(it, NATIVE_EXCEPTION_TAG) as? Throwable }
        if (creatingException) return JsException(fallbackMessage, cause, name = fallbackName)
        creatingException = true
        try {
            return JsException(
                message =
                    runCatching {
                        if (error is JsString) {
                            error.toString()
                        } else {
                            (error as? JsObject)
                                ?.getValue("message")
                                ?.use { it.takeIf { it !is JsUndefined }?.toString() }
                                ?: fallbackMessage
                        }
                    }.getOrDefault(fallbackMessage),
                cause = cause,
                data = runCatching { (error as? JsObject)?.toPlainMap() ?: emptyMap() }.getOrDefault(emptyMap()),
                name =
                    runCatching {
                        (error as? JsObject)
                            ?.getValue("name")
                            ?.use { (it as? JsString)?.toString() }
                            ?: fallbackName
                    }.getOrDefault(fallbackName),
            )
        } finally {
            creatingException = false
        }
    }

    override fun createFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction {
        val callbackId = registerFunctionCallback(value)
        val function =
            try {
                executeWebViewMessageBlockingAndDecode(
                    JsWebViewMessage.CreateFunction(callbackId),
                    "createFunction",
                ) as JsFunction
            } catch (e: Throwable) {
                functionByCallbackId.remove(callbackId)
                throw e
            }
        functionCallbackIds[(function as JsWebViewObject).handle] = callbackId
        return function
    }

    override fun createNumber(value: Number): JsNumber = JsWebViewNumber(this, value.toDouble()).also { registerWebViewValue(it) }

    override fun createNumberObject(value: Number): JsNumberObject = evaluateScript("new Number(${value.toDouble()})") as JsNumberObject

    override fun createObject(): JsObject = evaluateScript("({})") as JsObject

    override fun createPromise(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): JsPromise {
        val callbackId = registerPromiseExecutor(executor)
        return try {
            executeWebViewMessageBlockingAndDecode(
                JsWebViewMessage.CreatePromise(callbackId),
                "createPromise",
            ) as JsPromise
        } catch (e: Throwable) {
            promiseExecutorByCallbackId.remove(callbackId)
            throw e
        }
    }

    override fun createString(value: String): JsString = JsWebViewString(this, value).also { registerWebViewValue(it) }

    override fun createStringObject(value: String): JsStringObject = evaluateScript("new String(${value.toJson()})") as JsStringObject

    override fun createUint8Array(value: ByteArray): JsUint8Array =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.CreateUint8Array(value),
            "createUint8Array",
        ) as JsUint8Array

    override fun <T : JsValue> createValueAlias(value: T): T {
        if (value.isSingleton()) {
            return value
        }
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is JsWebViewObject -> {
                createWebViewObject(value.handle, value.handleType)
                    .also { registerWebViewValue(it) }
            }

            is JsBoolean -> {
                createBoolean(value.toBoolean())
            }

            is JsNumber -> {
                createNumber(value.toNumber())
            }

            is JsString -> {
                createString(value.toString())
            }

            else -> {
                value
            }
        } as T
    }

    override fun closeValue(value: JsValue) {
        if (value is JsWebViewObject && !value.isSingleton()) {
            if (releaseWebViewHandle(value.handle)) {
                enqueueHandleRefCountChange(value.handle, -1)
            }
        }
        core.removeValue(value)
    }

    override fun close() {
        createWebView = null
        core.close {
            cancelPendingRequests(takePendingRequests())
            functionByCallbackId.clear()
            promiseExecutorByCallbackId.clear()
            functionCallbackIds.clear()
            tagsByHandle.clear()
            refCounts.clear()
            pendingRequestsLock.withLock { pendingHandleRefCountChanges.clear() }
            closeWebView()
        }
    }

    override fun getObjectValue(
        obj: JsArray,
        index: Int,
    ): JsValue =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.GetObjectValue((obj as JsWebViewObject).handle, index),
            "get",
        )

    override fun getObjectValue(
        obj: JsObject,
        key: String,
    ): JsValue =
        executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.GetObjectValue((obj as JsWebViewObject).handle, key),
            "get",
        )

    internal fun registerWebViewValue(value: JsValue) {
        if (value is JsWebViewObject && !value.isSingleton()) {
            retainWebViewHandle(value.handle)
        }
        core.addValue(value)
    }

    private fun onWebViewValueDeallocated(handle: Int) {
        refCounts.remove(handle)
        tagsByHandle.remove(handle)
        val callbackId = functionCallbackIds.getOrElse(handle) { return }
        functionCallbackIds.remove(handle)
        functionByCallbackId.remove(callbackId)
    }

    internal fun getTag(
        jsObject: JsObject,
        key: String,
    ): Any? = tagsByHandle[(jsObject as JsWebViewObject).handle]?.get(key)

    internal fun setTag(
        jsObject: JsObject,
        key: String,
        value: Any,
    ) {
        tagsByHandle
            .getOrPut((jsObject as JsWebViewObject).handle) { mutableMapOf() }[key] = value
    }

    internal fun removeTag(
        jsObject: JsObject,
        key: String,
    ) {
        val handle = (jsObject as JsWebViewObject).handle
        tagsByHandle[handle]?.let {
            it.remove(key)
            if (it.isEmpty()) {
                tagsByHandle.remove(handle)
            }
        }
    }

    private fun invokeWebViewFunctionCallback(
        jsCallbackId: Int,
        callbackId: Int,
        thiz: JsWebViewProtocolValue,
        args: List<JsWebViewProtocolValue>,
    ) {
        try {
            val callback = checkNotNull(functionByCallbackId[callbackId]) { "unknown WebView function callback $callbackId" }
            jsFunctionScoped(this) {
                _thiz = createWebViewValue(thiz).autoClose()
                val result = callback(args.map(::createWebViewValue).autoClose())
                sendWebViewCommand(
                    JsWebViewMessage.CompleteNativeCallback(
                        jsCallbackId = jsCallbackId,
                        result = createWebViewProtocolValue(result),
                    ),
                )
            }
        } catch (e: Throwable) {
            if (e is JsWebViewThrownError) {
                sendWebViewCommand(
                    JsWebViewMessage.FailNativeCallback(
                        jsCallbackId = jsCallbackId,
                        error = e.error,
                    ),
                )
            } else {
                createError(e).use { error ->
                    sendWebViewCommand(
                        JsWebViewMessage.FailNativeCallback(
                            jsCallbackId = jsCallbackId,
                            error = createWebViewProtocolValue(error),
                        ),
                    )
                }
            }
        }
    }

    private fun invokeWebViewPromiseExecutor(
        callbackId: Int,
        resolve: JsWebViewProtocolValue,
        reject: JsWebViewProtocolValue,
    ) {
        val executor = promiseExecutorByCallbackId.remove(callbackId) ?: error("Unknown WebView promise executor $callbackId")
        jsScoped(this) {
            val reject = createWebViewValue(reject).autoClose() as JsFunction
            try {
                executor(
                    createWebViewValue(resolve).autoClose() as JsFunction,
                    reject,
                )
            } catch (e: Throwable) {
                val error =
                    if (e is JsWebViewThrownError) {
                        createWebViewValue(e.error)
                    } else {
                        createError(e)
                    }.autoClose()
                reject(error)
            }
        }
    }

    internal fun readWebViewUint8Array(handle: Int): ByteArray =
        try {
            executeWebViewMessageBlocking(
                JsWebViewMessage.ReadUint8Array(handle),
                "readUint8Array",
            ).decodeUint8Array()
        } catch (e: JsWebViewThrownError) {
            throw createWebViewValue(e.error).use { createException(it) }
        }

    internal fun executeWebViewMessageBlockingAndDecode(
        message: JsWebViewMessage,
        debug: String,
    ): JsValue =
        try {
            createWebViewValue(executeWebViewMessageBlocking(message, debug))
        } catch (e: JsWebViewThrownError) {
            throw createWebViewValue(e.error).use { createException(it) }
        }

    internal fun decodeExpressionValue(
        decoder: JsFunction,
        wire: JsValueWire,
        resolvedReferenceValues: List<JsValue>,
    ): JsValue {
        require(decoder.context === this) { "Expression decoder belongs to another JsContext" }
        return executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.DecodeExpression(
                decoderHandle = (decoder as JsWebViewObject).handle,
                expression = wire.value,
                resolvedReferenceValues = resolvedReferenceValues.map(::createWebViewProtocolValue),
            ),
            "decodeExpression",
        )
    }

    private fun executeWebViewMessageBlocking(
        message: JsWebViewMessage,
        debug: String,
    ): JsWebViewProtocolValue {
        val request = JsWebViewBlockingRequest<JsWebViewProtocolValue>()
        var nativeFailure = false
        val id =
            executeWebViewMessage(message) { result, failedNativeExecution ->
                // Publish the failure origin together with the result through the blocking request.
                nativeFailure = failedNativeExecution
                request.complete(result)
            }
        try {
            return request.await(debug)
        } catch (error: Throwable) {
            // Native execution failed without a protocol reply, so any refcount prefix may or may not
            // have run. Close instead of retrying that batch. This runs on the context's dispatcher,
            // where completed synchronous replies have already been decoded into their scopes.
            if (nativeFailure) close()
            throw error
        } finally {
            withPendingRequests {
                it.remove(id)
            }
        }
    }

    private fun executeWebViewMessage(
        message: JsWebViewMessage,
        complete: JsWebViewRequestCompletion,
    ): Int {
        var id = -1
        withPendingRequests {
            if (!core.isClosed) {
                id = requestId++
                it[id] = complete
            }
        }
        if (id < 0) {
            complete(Result.failure(IllegalStateException("JsContext is closed")), false)
            return -1
        }
        try {
            evaluateWebViewMessage(message, id)
        } catch (e: Throwable) {
            completeRequest(id, Result.failure(e))
        }
        return id
    }

    private fun sendWebViewCommand(message: JsWebViewMessage) {
        if (core.isClosed) return
        evaluateWebViewMessage(message)
    }

    private fun enqueueHandleRefCountChange(
        handle: Int,
        change: Int,
    ) {
        pendingRequestsLock.withLock {
            if (core.isClosed) return
            // Keep zero deltas: reacquire (+1) followed by close (-1) must still remove a
            // re-exported value that JS holds in transit without a counted reference.
            pendingHandleRefCountChanges[handle] = pendingHandleRefCountChanges.getOrDefault(handle, 0) + change
        }
    }

    private fun evaluateWebViewMessage(
        message: JsWebViewMessage,
        requestId: Int = 0,
    ) {
        val initializedWebView = getOrCreateWebView()
        val changes =
            pendingRequestsLock.withLock {
                if (pendingHandleRefCountChanges.isEmpty()) {
                    null
                } else {
                    JsWebViewMessage.UpdateRefCounts(pendingHandleRefCountChanges).also { pendingHandleRefCountChanges.clear() }
                }
            }
        try {
            val script = if (requestId == 0) message.toScript(changes) else message.toScript(requestId, changes)
            initializedWebView.evaluateJavaScript(script) { error ->
                // A completed reply has already removed its request. A later native completion
                // must not replace that reply or invalidate its context.
                if (requestId != 0) completeRequest(requestId, Result.failure(error), nativeFailure = true)
            }
        } catch (e: Throwable) {
            // Decode the saved batch only on submission failure; the normal path reuses the map.
            changes?.forEachRefCountChange(::enqueueHandleRefCountChange)
            throw e
        }
    }

    private fun completeRequest(
        requestId: Int,
        result: Result<JsWebViewProtocolValue>,
        nativeFailure: Boolean = false,
    ) {
        val request =
            withPendingRequests {
                it.remove(requestId)
            }
        request?.invoke(result, nativeFailure)
    }

    private fun cancelPendingRequests(requests: List<JsWebViewRequestCompletion>) {
        requests.forEach {
            it(Result.failure(IllegalStateException("JsContext is closed")), false)
        }
    }

    private fun takePendingRequests(): List<JsWebViewRequestCompletion> =
        withPendingRequests { requests ->
            buildList(requests.size) {
                requests.forEachValue { add(it) }
            }.also { requests.clear() }
        }

    private fun getOrCreateWebView(): JsWebView {
        check(!core.isClosed) { "JsContext is closed" }
        webView?.let { return it }
        val factory = checkNotNull(createWebView) { "JsWebView factory has already been used" }
        createWebView = null
        return factory(id).also { createdWebView ->
            try {
                createdWebView.onMessage = webViewMessageHandler::handle
                createdWebView.initializeRuntime()
                webView = createdWebView
            } catch (e: Throwable) {
                createdWebView.onMessage = {}
                runCatching { createdWebView.disposeRuntime() }
                runCatching { createdWebView.close() }
                throw e
            }
        }
    }

    private fun closeWebView() {
        val initializedWebView = webView
        webView = null
        if (initializedWebView != null) {
            initializedWebView.onMessage = {}
            try {
                initializedWebView.disposeRuntime()
            } finally {
                initializedWebView.close()
            }
        }
    }

    private fun dispatchWebViewNativeCallback(block: () -> Unit) {
        if (core.isClosed) return
        checkNotNull(core.eventLoop) { "JsContext has no event loop attached" }
            .launch {
                if (!core.isClosed) {
                    block()
                }
            }
    }

    private inline fun <T> withPendingRequests(block: (JsWebViewPendingRequests) -> T): T =
        pendingRequestsLock.withLock { block(pendingRequests) }

    private fun registerFunctionCallback(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): Int {
        val callbackId = callbackId++
        functionByCallbackId[callbackId] = value
        return callbackId
    }

    private fun registerPromiseExecutor(executor: JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit): Int {
        val callbackId = callbackId++
        promiseExecutorByCallbackId[callbackId] = executor
        return callbackId
    }

    private fun retainWebViewHandle(handle: Int) {
        if (handle == 0) return
        val count = refCounts.getOrDefault(handle, -1)
        refCounts[handle] = if (count == -1) 1 else count + 1
        // JS supplies the first reference when allocating a handle; only reacquisition needs a command.
        if (count == 0) {
            enqueueHandleRefCountChange(handle, 1)
        }
    }

    private fun releaseWebViewHandle(handle: Int): Boolean {
        val count = refCounts.getOrElse(handle) { return true }
        if (count <= 1) {
            // Remember previously received handles until onWebViewValueDeallocated removes them.
            refCounts[handle] = 0
            return true
        }
        refCounts[handle] = count - 1
        return false
    }
}

private fun JsWebViewContext.createWebViewProtocolValue(value: JsValue): JsWebViewProtocolValue =
    when (value) {
        NULL -> JsWebViewProtocolValue.Null()
        UNDEFINED -> JsWebViewProtocolValue.Undefined()
        is JsWebViewObject -> JsWebViewProtocolValue.Handle(value.handle, value.handleType)
        is JsBoolean -> JsWebViewProtocolValue.Boolean(value.toBoolean())
        is JsNumber -> JsWebViewProtocolValue.Number(value.toNumber())
        is JsString -> JsWebViewProtocolValue.String(value.toString())
        else -> throw IllegalArgumentException("Cannot pass $value to JsWebViewContext")
    }

private fun JsWebViewContext.createWebViewValue(value: JsWebViewProtocolValue): JsValue =
    when (value.type) {
        JsWebViewProtocolValueType.NULL -> {
            NULL
        }

        JsWebViewProtocolValueType.UNDEFINED -> {
            UNDEFINED
        }

        JsWebViewProtocolValueType.BOOLEAN -> {
            createBoolean(value.decodeBoolean())
        }

        JsWebViewProtocolValueType.NUMBER -> {
            createNumber(value.decodeNumber())
        }

        JsWebViewProtocolValueType.BIGINT -> {
            createNumber(value.decodeBigInt())
        }

        JsWebViewProtocolValueType.STRING -> {
            createString(value.decodeString())
        }

        JsWebViewProtocolValueType.UINT8_ARRAY -> {
            createUint8Array(value.decodeUint8Array())
        }

        JsWebViewProtocolValueType.HANDLE -> {
            val handle = value.decodeHandle()
            if (handle.handle == 0 && handle.type == JsWebViewProtocolHandleType.OBJECT) {
                globalThis
            } else {
                createWebViewObject(handle.handle, handle.type).also { registerWebViewValue(it) }
            }
        }
    }

private fun JsWebViewContext.createWebViewObject(
    handle: Int,
    type: JsWebViewProtocolHandleType,
): JsObject =
    when (type) {
        JsWebViewProtocolHandleType.OBJECT -> JsWebViewObject(this, handle)
        JsWebViewProtocolHandleType.ARRAY -> JsWebViewArray(this, handle)
        JsWebViewProtocolHandleType.FUNCTION -> JsWebViewFunction(this, handle)
        JsWebViewProtocolHandleType.PROMISE -> JsWebViewPromise(this, handle)
        JsWebViewProtocolHandleType.BOOLEAN_OBJECT -> JsWebViewBooleanObject(this, handle)
        JsWebViewProtocolHandleType.NUMBER_OBJECT -> JsWebViewNumberObject(this, handle)
        JsWebViewProtocolHandleType.STRING_OBJECT -> JsWebViewStringObject(this, handle)
        JsWebViewProtocolHandleType.DATE -> JsWebViewDate(this, handle)
        JsWebViewProtocolHandleType.ERROR -> JsWebViewObject(this, handle)
        JsWebViewProtocolHandleType.UINT8_ARRAY -> JsWebViewUint8Array(this, handle)
    }

internal abstract class JsWebViewValue(
    context: JsContext,
) : JsValueCoreOwner {
    override val _core = JsValueCore(context)
    override val context: JsContext
        get() = _core.context

    override fun close() {
        _core.close(this)
    }
}

private class JsWebViewNull(
    context: JsContext,
) : JsWebViewValue(context),
    JsNull {
    override fun toString(): String = "null"

    override fun equals(other: Any?): Boolean = other is JsNull && context === other.context

    override fun hashCode(): Int = 0
}

private class JsWebViewUndefined(
    context: JsContext,
) : JsWebViewValue(context),
    JsUndefined {
    override fun toString(): String = "undefined"

    override fun equals(other: Any?): Boolean = other is JsUndefined && context === other.context

    override fun hashCode(): Int = 1
}

private class JsWebViewBoolean(
    context: JsContext,
    private val value: Boolean,
) : JsWebViewValue(context),
    JsBoolean {
    override fun toBoolean(): Boolean = value

    override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean =
        other is JsBoolean && other !is JsObject && context === other.context && value == other.toBoolean()

    override fun hashCode(): Int = value.hashCode()
}

private class JsWebViewNumber(
    context: JsContext,
    private val value: Double,
) : JsWebViewValue(context),
    JsNumber {
    override fun toNumber(): Number = value

    override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean =
        other is JsNumber && other !is JsObject && context === other.context && toNumber() == other.toNumber()

    override fun hashCode(): Int = value.hashCode()
}

private class JsWebViewString(
    context: JsContext,
    private val value: String,
) : JsWebViewValue(context),
    JsString {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean =
        other is JsString && other !is JsObject && context === other.context && value == other.toString()

    override fun hashCode(): Int = value.hashCode()
}

internal open class JsWebViewObject(
    context: JsWebViewContext,
    val handle: Int,
    val handleType: JsWebViewProtocolHandleType = JsWebViewProtocolHandleType.OBJECT,
) : JsWebViewValue(context),
    JsObject {
    override fun equals(other: Any?): Boolean = other is JsWebViewObject && context === other.context && handle == other.handle

    override fun hashCode(): Int = 31 * context.hashCode() + handle

    override fun set(
        key: String,
        value: JsValue?,
    ) {
        val webViewContext = context as JsWebViewContext
        webViewContext.executeWebViewMessageBlockingAndDecode(
            JsWebViewMessage.SetObjectValue(
                handle,
                key,
                webViewContext.createWebViewProtocolValue(value ?: webViewContext.NULL),
            ),
            "set",
        )
    }
}

private class JsWebViewArray(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.ARRAY),
    JsArray {
    override val size: Int
        get() =
            (context as JsWebViewContext)
                .executeWebViewMessageBlockingAndDecode(JsWebViewMessage.GetObjectValue(handle, "length"), "get")
                .use { (it as JsNumber).toNumber().toInt() }
}

private class JsWebViewFunction(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.FUNCTION),
    JsFunction

private class JsWebViewPromise(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.PROMISE),
    JsPromise

private class JsWebViewBooleanObject(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.BOOLEAN_OBJECT),
    JsBooleanObject {
    override fun toBoolean(): Boolean {
        val context = context as JsWebViewContext
        return context.getObjectValue(this, "valueOf").use { valueOf ->
            context.callFunction(valueOf as JsFunction, emptyList(), this).use { (it as JsBoolean).toBoolean() }
        }
    }
}

private class JsWebViewNumberObject(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.NUMBER_OBJECT),
    JsNumberObject {
    override fun toNumber(): Number {
        val context = context as JsWebViewContext
        return context.getObjectValue(this, "valueOf").use { valueOf ->
            context.callFunction(valueOf as JsFunction, emptyList(), this).use { (it as JsNumber).toNumber() }
        }
    }
}

private class JsWebViewStringObject(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.STRING_OBJECT),
    JsStringObject {
    override fun toString(): String {
        val context = context as JsWebViewContext
        return context.getObjectValue(this, "valueOf").use { valueOf ->
            context.callFunction(valueOf as JsFunction, emptyList(), this).use { (it as JsString).toString() }
        }
    }
}

private class JsWebViewDate(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.DATE),
    JsDate {
    private val millis by lazy(LazyThreadSafetyMode.NONE) {
        context.getObjectValue(this, "getTime").use { getTime ->
            context.callFunction(getTime as JsFunction, emptyList(), this).use { (it as JsNumber).toNumber().toLong() }
        }
    }

    override fun toMillis(): Long = millis

    override fun equals(other: Any?): Boolean = other is JsDate && context === other.context && toMillis() == other.toMillis()

    override fun hashCode(): Int = toMillis().toInt()
}

private class JsWebViewUint8Array(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.UINT8_ARRAY),
    JsUint8Array {
    override val size: Int
        get() {
            val context = context as JsWebViewContext
            return context.getObjectValue(this, "byteLength").use { (it as JsNumber).toNumber().toInt() }
        }

    override fun toByteArray(): ByteArray {
        val context = context as JsWebViewContext
        return context.readWebViewUint8Array(handle)
    }
}
