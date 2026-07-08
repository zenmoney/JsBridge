package app.zenmoney.jsbridge

import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private class JsWebViewThrownError(
    val error: JsWebViewProtocolValue,
) : Exception()

private const val NATIVE_EXCEPTION_TAG = "app.zenmoney.jsbridge.nativeException"

private typealias JsWebViewPendingRequests = MutableMap<Int, (Result<JsWebViewProtocolValue>) -> Unit>

@OptIn(ExperimentalAtomicApi::class)
class JsWebViewContext internal constructor(
    private val createWebView: () -> JsWebView,
) : JsContext(Unit) {
    companion object {}

    constructor() : this (::createJsWebView)

    internal constructor(webView: JsWebView) : this({ webView })

    override val core = JsContextCore(this)
    override var getPlainValueOf: JsScope.(value: JsValue, state: JsPlainValueState) -> Any? = { value, state ->
        toBasicPlainValue(value, state)
    }

    private var isClosed = false
    private var isClosing = false

    private var requestId = 1
    private val pendingRequestsLock = AtomicInt(0)
    private val pendingRequests: JsWebViewPendingRequests = mutableMapOf()

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
    private val refCounts = mutableMapOf<Int, Int>()
    private val tagsByHandle = mutableMapOf<Int, MutableMap<String, Any>>()
    private val functionCallbackIds = mutableMapOf<Int, Int>()
    private val functionByCallbackId = mutableMapOf<Int, JsFunctionScope.(args: List<JsValue>) -> JsValue>()
    private val promiseExecutorByCallbackId = mutableMapOf<Int, JsScope.(resolve: JsFunction, reject: JsFunction) -> Unit>()

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

    override fun createException(error: JsValue): JsException =
        JsException(
            message =
                runCatching {
                    if (error is JsString) {
                        error.toString()
                    } else {
                        (error as? JsObject)
                            ?.getValue("message")
                            ?.use { it.takeIf { it !is JsUndefined }?.toString() }
                            ?: error.toString()
                    }
                }.getOrElse { error.toString() },
            cause = (error as? JsObject)?.getTag(NATIVE_EXCEPTION_TAG),
            data = (error as? JsObject)?.toPlainMap() ?: emptyMap(),
            name =
                runCatching {
                    (error as? JsObject)
                        ?.getValue("name")
                        ?.use { (it as? JsString)?.toString() }
                        ?: if (error is JsString) "Error" else ""
                }.getOrDefault(if (error is JsString) "Error" else ""),
        )

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
                executeWebViewMessageBlockingAndDecode(
                    message = JsWebViewMessage.Release(value.handle),
                    debug = "release",
                    allowWhileClosing = true,
                )
            }
        }
        core.removeValue(value)
    }

    override fun close() {
        if (isClosing || isClosed) return
        isClosing = true
        cancelPendingRequests(takePendingRequests())
        core.close()
        isClosed = true
        cancelPendingRequests(takePendingRequests())
        functionByCallbackId.clear()
        promiseExecutorByCallbackId.clear()
        functionCallbackIds.clear()
        tagsByHandle.clear()
        refCounts.clear()
        closeWebView()
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
        functionCallbackIds
            .remove(handle)
            ?.let { functionByCallbackId.remove(it) }
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
                sendWebViewMessage(
                    JsWebViewMessage.CompleteNativeCallback(
                        jsCallbackId = jsCallbackId,
                        result = createWebViewProtocolValue(result),
                    ),
                )
            }
        } catch (e: Throwable) {
            if (e is JsWebViewThrownError) {
                sendWebViewMessage(
                    JsWebViewMessage.FailNativeCallback(
                        jsCallbackId = jsCallbackId,
                        error = e.error,
                    ),
                )
            } else {
                createError(e).use { error ->
                    sendWebViewMessage(
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
                allowWhileClosing = false,
            ).decodeByteArray()
        } catch (e: JsWebViewThrownError) {
            throw createWebViewValue(e.error).use { createException(it) }
        }

    internal fun executeWebViewMessageBlockingAndDecode(
        message: JsWebViewMessage,
        debug: String,
        allowWhileClosing: Boolean = false,
    ): JsValue =
        try {
            createWebViewValue(executeWebViewMessageBlocking(message, debug, allowWhileClosing))
        } catch (e: JsWebViewThrownError) {
            throw createWebViewValue(e.error).use { createException(it) }
        }

    private fun executeWebViewMessageBlocking(
        message: JsWebViewMessage,
        debug: String,
        allowWhileClosing: Boolean,
    ): JsWebViewProtocolValue {
        val request = JsWebViewBlockingRequest<JsWebViewProtocolValue>()
        val id = executeWebViewMessage(message, allowWhileClosing, request::complete)
        try {
            return request.await(debug)
        } finally {
            withPendingRequests {
                it.remove(id)
            }
        }
    }

    private fun executeWebViewMessage(
        message: JsWebViewMessage,
        allowWhileClosing: Boolean,
        complete: (Result<JsWebViewProtocolValue>) -> Unit,
    ): Int {
        if (isClosed || (isClosing && !allowWhileClosing)) {
            complete(Result.failure(IllegalStateException("JsContext is closed")))
            return -1
        }
        val id = requestId++
        withPendingRequests {
            it[id] = complete
        }
        try {
            getOrCreateWebView().evaluateJavaScript(message.toScript(id))
        } catch (e: Throwable) {
            completeRequest(id, Result.failure(e))
        }
        return id
    }

    private fun sendWebViewMessage(message: JsWebViewMessage) {
        if (isClosing || isClosed) return
        getOrCreateWebView().evaluateJavaScript(message.toScript())
    }

    private fun completeRequest(
        requestId: Int,
        result: Result<JsWebViewProtocolValue>,
    ) {
        val request =
            withPendingRequests {
                it.remove(requestId)
            }
        request?.invoke(result)
    }

    private fun cancelPendingRequests(requests: List<(Result<JsWebViewProtocolValue>) -> Unit>) {
        requests.forEach {
            it(Result.failure(IllegalStateException("JsContext is closed")))
        }
    }

    private fun takePendingRequests(): List<(Result<JsWebViewProtocolValue>) -> Unit> =
        withPendingRequests { requests ->
            requests.values.toList().also {
                requests.clear()
            }
        }

    private fun getOrCreateWebView(): JsWebView {
        webView?.let { return it }
        check(!isClosing && !isClosed) { "JsContext is closed" }
        return createWebView().also { createdWebView ->
            try {
                createdWebView.onMessage = webViewMessageHandler::handle
                createdWebView.evaluateJavaScript(jsWebViewRuntimeScript)
                webView = createdWebView
            } catch (e: Throwable) {
                createdWebView.onMessage = {}
                runCatching { createdWebView.close() }
                throw e
            }
        }
    }

    private fun closeWebView() {
        val initializedWebView = webView
        webView = null
        initializedWebView?.onMessage = {}
        initializedWebView?.close()
    }

    private fun dispatchWebViewNativeCallback(block: () -> Unit) {
        if (isClosing || isClosed) return
        checkNotNull(core.eventLoop) { "JsContext has no event loop attached" }
            .launch { block() }
    }

    private inline fun <T> withPendingRequests(block: (JsWebViewPendingRequests) -> T): T {
        while (!pendingRequestsLock.compareAndSet(0, 1)) {
        }
        try {
            return block(pendingRequests)
        } finally {
            pendingRequestsLock.store(0)
        }
    }

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
        refCounts[handle] =
            refCounts.getOrElse(handle) { 0 } + 1
    }

    private fun releaseWebViewHandle(handle: Int): Boolean {
        val count = refCounts[handle] ?: return true
        if (count <= 1) {
            refCounts.remove(handle)
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
        JsWebViewProtocolCode.VALUE_NULL -> {
            NULL
        }

        JsWebViewProtocolCode.VALUE_UNDEFINED -> {
            UNDEFINED
        }

        JsWebViewProtocolCode.VALUE_BOOLEAN -> {
            createBoolean(value.decodeBoolean())
        }

        JsWebViewProtocolCode.VALUE_NUMBER -> {
            createNumber(value.decodeNumber())
        }

        JsWebViewProtocolCode.VALUE_STRING -> {
            createString(value.decodeString())
        }

        JsWebViewProtocolCode.VALUE_BYTE_ARRAY -> {
            createUint8Array(value.decodeByteArray())
        }

        JsWebViewProtocolCode.VALUE_HANDLE -> {
            val handle = value.decodeHandle()
            if (handle.handle == 0 && handle.type == JsWebViewProtocolHandleType.OBJECT) {
                globalThis
            } else {
                createWebViewObject(handle.handle, handle.type).also { registerWebViewValue(it) }
            }
        }

        else -> {
            error("Expected JsWebView value code, got ${value.type}")
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

    override fun equals(other: Any?): Boolean = other is JsNull

    override fun hashCode(): Int = 0
}

private class JsWebViewUndefined(
    context: JsContext,
) : JsWebViewValue(context),
    JsUndefined {
    override fun toString(): String = "undefined"

    override fun equals(other: Any?): Boolean = other is JsUndefined

    override fun hashCode(): Int = 1
}

private class JsWebViewBoolean(
    context: JsContext,
    private val value: Boolean,
) : JsWebViewValue(context),
    JsBoolean {
    override fun toBoolean(): Boolean = value

    override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean = other is JsBoolean && other !is JsObject && value == other.toBoolean()

    override fun hashCode(): Int = value.hashCode()
}

private class JsWebViewNumber(
    context: JsContext,
    private val value: Double,
) : JsWebViewValue(context),
    JsNumber {
    override fun toNumber(): Number = value

    override fun toString(): String = value.toString()

    override fun equals(other: Any?): Boolean = other is JsNumber && other !is JsObject && value == other.toNumber().toDouble()

    override fun hashCode(): Int = value.hashCode()
}

private class JsWebViewString(
    context: JsContext,
    private val value: String,
) : JsWebViewValue(context),
    JsString {
    override fun toString(): String = value

    override fun equals(other: Any?): Boolean = other is JsString && other !is JsObject && value == other.toString()

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
                .let { (it as JsNumber).toNumber().toInt() }
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
        val valueOf = context.getObjectValue(this, "valueOf") as JsFunction
        return (context.callFunction(valueOf, emptyList(), this) as JsBoolean).toBoolean()
    }
}

private class JsWebViewNumberObject(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.NUMBER_OBJECT),
    JsNumberObject {
    override fun toNumber(): Number {
        val context = context as JsWebViewContext
        val valueOf = context.getObjectValue(this, "valueOf") as JsFunction
        return (context.callFunction(valueOf, emptyList(), this) as JsNumber).toNumber()
    }
}

private class JsWebViewStringObject(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.STRING_OBJECT),
    JsStringObject {
    override fun toString(): String {
        val context = context as JsWebViewContext
        val valueOf = context.getObjectValue(this, "valueOf") as JsFunction
        return (context.callFunction(valueOf, emptyList(), this) as JsString).toString()
    }
}

private class JsWebViewDate(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.DATE),
    JsDate {
    override fun toMillis(): Long {
        val context = context as JsWebViewContext
        val getTime = context.getObjectValue(this, "getTime") as JsFunction
        return (context.callFunction(getTime, emptyList(), this) as JsNumber).toNumber().toLong()
    }

    override fun equals(other: Any?): Boolean = other is JsDate && toMillis() == other.toMillis()

    override fun hashCode(): Int = toMillis().hashCode()
}

private class JsWebViewUint8Array(
    context: JsWebViewContext,
    handle: Int,
) : JsWebViewObject(context, handle, JsWebViewProtocolHandleType.UINT8_ARRAY),
    JsUint8Array {
    override val size: Int
        get() {
            val context = context as JsWebViewContext
            return (context.getObjectValue(this, "byteLength") as JsNumber).toNumber().toInt()
        }

    override fun toByteArray(): ByteArray {
        val context = context as JsWebViewContext
        return context.readWebViewUint8Array(handle)
    }
}
