package app.zenmoney.jsbridge

import androidx.collection.IntIntMap
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.ExpressionValueTag
import app.zenmoney.jsbridge.serialization.JsValueWire
import app.zenmoney.jsbridge.serialization.expressionValueCoreCodecFactorySource
import kotlin.jvm.JvmInline

internal enum class JsWebViewProtocolCode(
    val value: String,
) {
    COMMAND_CREATE_ARRAY("a"),
    COMMAND_CREATE_FUNCTION("f"),
    COMMAND_CREATE_PROMISE("p"),
    COMMAND_CREATE_UINT8ARRAY("y+"),

    COMMAND_WRAP_SCRIPT("w"),
    COMMAND_COMPLETE_EVALUATION("e!"),
    COMMAND_DECODE_EXPRESSION("v"),

    COMMAND_READ_UINT8ARRAY("y?"),

    COMMAND_GET_OBJECT_VALUE("g"),
    COMMAND_SET_OBJECT_VALUE("s"),

    COMMAND_CALL_FUNCTION("c"),
    COMMAND_CONSTRUCT("n"),
    COMMAND_UPDATE_REF_COUNTS("r*"),

    COMMAND_COMPLETE_NATIVE_CALLBACK("+"),
    COMMAND_FAIL_NATIVE_CALLBACK("-"),

    CALLBACK_RESULT("r"),
    CALLBACK_ERROR("e"),
    CALLBACK_FUNCTION("f"),
    CALLBACK_PROMISE_EXECUTOR("p"),
    CALLBACK_DEALLOCATE("d"),
    CALLBACK_SCRIPT_WRAPPED("x"),
    ;

    private val json: String = "\"$value\""

    fun toJson(): String = json
}

internal enum class JsWebViewProtocolValueType {
    NULL,
    UNDEFINED,
    BOOLEAN,
    NUMBER,
    BIGINT,
    STRING,
    UINT8_ARRAY,
    HANDLE,
}

private const val JS_WEB_VIEW_HANDLE_TAG = "h"

@JvmInline
internal value class JsWebViewMessage private constructor(
    val value: String,
) {
    fun toScript(
        requestId: Int,
        before: JsWebViewMessage? = null,
    ): String = buildScript(requestId, true, before)

    fun toScript(before: JsWebViewMessage? = null): String = buildScript(0, false, before)

    private fun buildScript(
        requestId: Int,
        hasRequestId: Boolean,
        before: JsWebViewMessage?,
    ): String =
        buildString {
            if (before != null) {
                append(JS_WEB_VIEW_BRIDGE_OBJECT)
                append(".dispatch(")
                append(before.value)
                append(");")
            }
            append(JS_WEB_VIEW_BRIDGE_OBJECT)
            append(".dispatch(")
            append(value)
            if (hasRequestId) {
                append(',')
                append(requestId)
            }
            append(");")
        }

    fun forEachRefCountChange(block: (handle: Int, change: Int) -> Unit) {
        var index = value.indexOf('[', 1) + 1
        while (value[index] != ']') {
            val handle = value.decodePackedProtocolInt(index)
            index = value.expectJsonChar(handle.decodedProtocolIndex(), ',')
            val change = value.decodePackedProtocolInt(index)
            block(handle.decodedProtocolInt(), change.decodedProtocolInt())
            index = change.decodedProtocolIndex()
            if (value[index] == ',') index++
        }
    }

    companion object {
        private fun message(
            code: JsWebViewProtocolCode,
            arguments: String = "",
        ): JsWebViewMessage = JsWebViewMessage("""[${code.toJson()}$arguments]""")

        @Suppress("FunctionName")
        fun WrapScript(
            script: String,
            transformHandle: Int? = null,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_WRAP_SCRIPT,
                ",${script.toJson()}" + (transformHandle?.let { ",$it" } ?: ""),
            )

        @Suppress("FunctionName")
        fun DecodeExpression(
            decoderHandle: Int,
            expression: String,
            resolvedReferenceValues: List<JsWebViewProtocolValue>,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_DECODE_EXPRESSION,
                ",$decoderHandle,$expression,${resolvedReferenceValues.toJsonArray { it.value }}",
            )

        @Suppress("FunctionName")
        fun CreateArray(items: List<JsWebViewProtocolValue>): JsWebViewMessage =
            message(JsWebViewProtocolCode.COMMAND_CREATE_ARRAY, ",${items.toJsonArray { it.value }}")

        @Suppress("FunctionName")
        fun CreateUint8Array(value: ByteArray): JsWebViewMessage =
            message(JsWebViewProtocolCode.COMMAND_CREATE_UINT8ARRAY, ",${JsWebViewProtocolValue.Uint8Array(value).value}")

        @Suppress("FunctionName")
        fun ReadUint8Array(handle: Int): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_READ_UINT8ARRAY, ",$handle")

        @Suppress("FunctionName")
        fun CreateFunction(callbackId: Int): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_CREATE_FUNCTION, ",$callbackId")

        @Suppress("FunctionName")
        fun CreatePromise(executorCallbackId: Int): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_CREATE_PROMISE,
                ",$executorCallbackId",
            )

        @Suppress("FunctionName")
        fun GetObjectValue(
            receiverHandle: Int,
            key: String,
        ): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_GET_OBJECT_VALUE, ",$receiverHandle,${key.toJson()}")

        @Suppress("FunctionName")
        fun GetObjectValue(
            receiverHandle: Int,
            index: Int,
        ): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_GET_OBJECT_VALUE, ",$receiverHandle,$index")

        @Suppress("FunctionName")
        fun SetObjectValue(
            receiverHandle: Int,
            key: String,
            value: JsWebViewProtocolValue,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_SET_OBJECT_VALUE,
                ",$receiverHandle,${key.toJson()},${value.value}",
            )

        @Suppress("FunctionName")
        fun CallFunction(
            functionHandle: Int,
            thisHandle: Int?,
            args: List<JsWebViewProtocolValue>,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_CALL_FUNCTION,
                ",$functionHandle,${thisHandle ?: "null"},${args.toJsonArray { it.value }}",
            )

        @Suppress("FunctionName")
        fun Construct(
            functionHandle: Int,
            args: List<JsWebViewProtocolValue>,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_CONSTRUCT,
                ",$functionHandle,${args.toJsonArray { it.value }}",
            )

        @Suppress("FunctionName")
        fun UpdateRefCounts(changes: IntIntMap): JsWebViewMessage =
            JsWebViewMessage(
                buildString {
                    append('[')
                    append(JsWebViewProtocolCode.COMMAND_UPDATE_REF_COUNTS.toJson())
                    append(",[")
                    var first = true
                    changes.forEach { handle, change ->
                        if (!first) append(',')
                        first = false
                        append(handle)
                        append(',')
                        append(change)
                    }
                    append("]]")
                },
            )

        @Suppress("FunctionName")
        fun CompleteNativeCallback(
            jsCallbackId: Int,
            result: JsWebViewProtocolValue,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_COMPLETE_NATIVE_CALLBACK,
                ",$jsCallbackId,${result.value}",
            )

        @Suppress("FunctionName")
        fun FailNativeCallback(
            jsCallbackId: Int,
            error: JsWebViewProtocolValue,
        ): JsWebViewMessage =
            message(
                JsWebViewProtocolCode.COMMAND_FAIL_NATIVE_CALLBACK,
                ",$jsCallbackId,${error.value}",
            )
    }
}

internal enum class JsWebViewProtocolHandleType(
    val code: Int,
) {
    OBJECT(0),
    ARRAY(1),
    FUNCTION(2),
    PROMISE(3),
    BOOLEAN_OBJECT(4),
    NUMBER_OBJECT(5),
    STRING_OBJECT(6),
    DATE(7),
    ERROR(8),
    UINT8_ARRAY(9),
    ;

    companion object {
        fun fromCode(code: Int): JsWebViewProtocolHandleType {
            for (type in entries) {
                if (type.code == code) return type
            }
            throw IllegalArgumentException("Unknown JsWebView handle type: $code")
        }
    }
}

@JvmInline
internal value class JsWebViewProtocolHandle private constructor(
    val encoded: Long,
) {
    val handle: Int
        get() = (encoded and UINT_MASK).toInt()

    val type: JsWebViewProtocolHandleType
        get() = JsWebViewProtocolHandleType.fromCode((encoded shr 32).toInt())

    companion object {
        private const val UINT_MASK = 0xffffffffL

        fun encode(
            handle: Int,
            type: JsWebViewProtocolHandleType,
        ): JsWebViewProtocolHandle {
            require(handle >= 0) { "JsWebView handle must be non-negative: $handle" }
            return JsWebViewProtocolHandle(
                (type.code.toLong() shl 32) or handle.toLong(),
            )
        }

        fun decode(encoded: Long): JsWebViewProtocolHandle {
            require(encoded >= 0) { "JsWebView encoded handle must be non-negative: $encoded" }
            val handle = encoded and UINT_MASK
            require(handle <= Int.MAX_VALUE) { "JsWebView handle is out of range: $handle" }
            JsWebViewProtocolHandleType.fromCode((encoded shr 32).toInt())
            return JsWebViewProtocolHandle(encoded)
        }
    }
}

@JvmInline
internal value class JsWebViewProtocolValue private constructor(
    val value: String,
) {
    val type: JsWebViewProtocolValueType
        get() = value.readProtocolValueType()

    fun decodeBoolean(): Boolean = ExpressionValueCodec.decodeBoolean(JsValueWire(value))

    fun decodeNumber(): Double = ExpressionValueCodec.decodeNumber(JsValueWire(value))

    fun decodeBigIntString(): String = ExpressionValueCodec.decodeBigIntString(JsValueWire(value))

    fun decodeBigInt(): Double = decodeBigIntString().toDouble()

    fun decodeString(): String = ExpressionValueCodec.decodeString(JsValueWire(value))

    fun decodeUint8Array(): ByteArray = ExpressionValueCodec.decodeUint8Array(JsValueWire(value))

    fun decodeHandle(): JsWebViewProtocolHandle {
        var index = ExpressionValueCodec.tagPayloadStart(value, 0, JS_WEB_VIEW_HANDLE_TAG)
        index = value.skipJsonWhitespace(index)
        check(index >= value.length || value[index] != '-') { "JsWebView handle must be non-negative" }
        val numberStart = index
        var encoded = 0L
        while (index < value.length && value[index] in '0'..'9') {
            val digit = value[index] - '0'
            check(encoded <= (Long.MAX_VALUE - digit) / 10) { "JsWebView handle is out of range" }
            encoded = encoded * 10 + digit
            index++
        }
        check(index > numberStart) { "Expected integer at $numberStart" }
        check(index == numberStart + 1 || value[numberStart] != '0') { "JsWebView handle has a leading zero" }
        value.expectJsonEnd(ExpressionValueCodec.expectTaggedValueEnd(value, index))
        return JsWebViewProtocolHandle.decode(encoded)
    }

    companion object {
        private val nullValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeNull().value)
        private val undefinedValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeUndefined().value)
        private val falseValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeBoolean(false).value)
        private val trueValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeBoolean(true).value)

        @Suppress("FunctionName")
        fun Null(): JsWebViewProtocolValue = nullValue

        @Suppress("FunctionName")
        fun Undefined(): JsWebViewProtocolValue = undefinedValue

        @Suppress("FunctionName")
        fun Boolean(value: Boolean): JsWebViewProtocolValue = if (value) trueValue else falseValue

        @Suppress("FunctionName")
        fun Number(value: Number): JsWebViewProtocolValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeNumber(value).value)

        @Suppress("FunctionName")
        fun BigInt(value: String): JsWebViewProtocolValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeBigInt(value).value)

        @Suppress("FunctionName")
        fun String(value: String): JsWebViewProtocolValue = JsWebViewProtocolValue(ExpressionValueCodec.encodeString(value).value)

        @Suppress("FunctionName")
        fun Uint8Array(value: ByteArray): JsWebViewProtocolValue =
            JsWebViewProtocolValue(ExpressionValueCodec.encodeUint8Array(value).value)

        @Suppress("FunctionName")
        fun Handle(
            handle: Int,
            type: JsWebViewProtocolHandleType,
        ): JsWebViewProtocolValue =
            JsWebViewProtocolValue(
                "[${JS_WEB_VIEW_HANDLE_TAG.toJson()},${JsWebViewProtocolHandle.encode(handle, type).encoded}]",
            )

        internal fun fromEncoded(value: String): JsWebViewProtocolValue = JsWebViewProtocolValue(value)
    }
}

private fun String.readProtocolValueType(): JsWebViewProtocolValueType =
    if (ExpressionValueCodec.hasTag(this, 0, JS_WEB_VIEW_HANDLE_TAG)) {
        JsWebViewProtocolValueType.HANDLE
    } else {
        when (ExpressionValueCodec.valueTypeOf(this)) {
            ExpressionValueCodec.ValueType.NULL -> JsWebViewProtocolValueType.NULL
            ExpressionValueCodec.ValueType.UNDEFINED -> JsWebViewProtocolValueType.UNDEFINED
            ExpressionValueCodec.ValueType.BOOLEAN -> JsWebViewProtocolValueType.BOOLEAN
            ExpressionValueCodec.ValueType.NUMBER -> JsWebViewProtocolValueType.NUMBER
            ExpressionValueCodec.ValueType.BIGINT -> JsWebViewProtocolValueType.BIGINT
            ExpressionValueCodec.ValueType.STRING -> JsWebViewProtocolValueType.STRING
            ExpressionValueCodec.ValueType.UINT8_ARRAY -> JsWebViewProtocolValueType.UINT8_ARRAY
        }
    }

internal class JsWebViewMessageHandler(
    private val listener: Listener,
) {
    internal interface Listener {
        fun onSuccess(
            requestId: Int,
            result: JsWebViewProtocolValue,
        )

        fun onFailure(
            requestId: Int,
            error: JsWebViewProtocolValue,
        )

        fun onFunction(
            jsCallbackId: Int,
            callbackId: Int,
            thiz: JsWebViewProtocolValue,
            args: List<JsWebViewProtocolValue>,
        )

        fun onPromiseExecutor(
            executorCallbackId: Int,
            resolve: JsWebViewProtocolValue,
            reject: JsWebViewProtocolValue,
        )

        fun onDeallocate(handle: Int)

        fun onScriptWrapped(
            requestId: Int,
            wrappedScript: String,
        ): Unit = error("Unexpected wrapped script")
    }

    fun handle(message: String) {
        var index = message.expectJsonChar(0, '[')
        val decodedType = message.decodePackedProtocolCallbackType(index)
        val type = decodedType.decodedProtocolCode()
        index = message.expectJsonChar(decodedType.decodedProtocolIndex(), ',')

        when (type) {
            JsWebViewProtocolCode.CALLBACK_RESULT,
            JsWebViewProtocolCode.CALLBACK_ERROR,
            JsWebViewProtocolCode.CALLBACK_SCRIPT_WRAPPED,
            -> {
                val request = message.decodePackedProtocolInt(index)
                val requestId = request.decodedProtocolInt()
                index = message.expectJsonChar(request.decodedProtocolIndex(), ',')
                val valueStart = message.skipJsonWhitespace(index)
                index = message.decodeProtocolValueEnd(valueStart)
                val value = JsWebViewProtocolValue.fromEncoded(message.substring(valueStart, index))
                message.expectProtocolMessageEnd(index)
                if (type == JsWebViewProtocolCode.CALLBACK_SCRIPT_WRAPPED) {
                    listener.onScriptWrapped(requestId, value.decodeString())
                } else if (type == JsWebViewProtocolCode.CALLBACK_RESULT) {
                    listener.onSuccess(requestId, value)
                } else {
                    listener.onFailure(requestId, value)
                }
            }

            JsWebViewProtocolCode.CALLBACK_FUNCTION -> {
                val jsCallback = message.decodePackedProtocolInt(index)
                val jsCallbackId = jsCallback.decodedProtocolInt()
                index = message.expectJsonChar(jsCallback.decodedProtocolIndex(), ',')
                val callback = message.decodePackedProtocolInt(index)
                val callbackId = callback.decodedProtocolInt()
                index = message.expectJsonChar(callback.decodedProtocolIndex(), ',')

                val thisStart = message.skipJsonWhitespace(index)
                index = message.decodeProtocolValueEnd(thisStart)
                val thiz = JsWebViewProtocolValue.fromEncoded(message.substring(thisStart, index))
                index = message.expectJsonChar(index, ',')

                index = message.expectJsonChar(index, '[')
                val args: List<JsWebViewProtocolValue>
                if (message.peekJsonChar(index, ']')) {
                    index = message.expectJsonChar(index, ']')
                    args = emptyList()
                } else {
                    val decodedArgs = arrayListOf<JsWebViewProtocolValue>()
                    while (true) {
                        val valueStart = message.skipJsonWhitespace(index)
                        index = message.decodeProtocolValueEnd(valueStart)
                        decodedArgs.add(JsWebViewProtocolValue.fromEncoded(message.substring(valueStart, index)))
                        if (message.peekJsonChar(index, ']')) {
                            index = message.expectJsonChar(index, ']')
                            break
                        }
                        index = message.expectJsonChar(index, ',')
                    }
                    args = decodedArgs
                }
                message.expectProtocolMessageEnd(index)
                listener.onFunction(jsCallbackId, callbackId, thiz, args)
            }

            JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR -> {
                val executorCallback = message.decodePackedProtocolInt(index)
                val executorCallbackId = executorCallback.decodedProtocolInt()
                index = message.expectJsonChar(executorCallback.decodedProtocolIndex(), ',')

                val resolveStart = message.skipJsonWhitespace(index)
                index = message.decodeProtocolValueEnd(resolveStart)
                val resolve = JsWebViewProtocolValue.fromEncoded(message.substring(resolveStart, index))
                index = message.expectJsonChar(index, ',')

                val rejectStart = message.skipJsonWhitespace(index)
                index = message.decodeProtocolValueEnd(rejectStart)
                val reject = JsWebViewProtocolValue.fromEncoded(message.substring(rejectStart, index))
                message.expectProtocolMessageEnd(index)
                listener.onPromiseExecutor(executorCallbackId, resolve, reject)
            }

            JsWebViewProtocolCode.CALLBACK_DEALLOCATE -> {
                val handle = message.decodePackedProtocolInt(index)
                index = handle.decodedProtocolIndex()
                message.expectProtocolMessageEnd(index)
                listener.onDeallocate(handle.decodedProtocolInt())
            }

            else -> {
                error("Expected JsWebView callback code")
            }
        }
    }
}

// Cursor decoders pack the next source index into the upper half, avoiding an allocated Pair result.
private fun String.decodePackedProtocolCallbackType(startIndex: Int): Long {
    val index = skipJsonWhitespace(startIndex)
    check(index + 2 < length && this[index] == '"' && this[index + 2] == '"') {
        "Expected JsWebView callback tag at $index"
    }
    val type =
        when (this[index + 1]) {
            JsWebViewProtocolCode.CALLBACK_RESULT.value[0] -> JsWebViewProtocolCode.CALLBACK_RESULT
            JsWebViewProtocolCode.CALLBACK_ERROR.value[0] -> JsWebViewProtocolCode.CALLBACK_ERROR
            JsWebViewProtocolCode.CALLBACK_FUNCTION.value[0] -> JsWebViewProtocolCode.CALLBACK_FUNCTION
            JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR.value[0] -> JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR
            JsWebViewProtocolCode.CALLBACK_DEALLOCATE.value[0] -> JsWebViewProtocolCode.CALLBACK_DEALLOCATE
            JsWebViewProtocolCode.CALLBACK_SCRIPT_WRAPPED.value[0] -> JsWebViewProtocolCode.CALLBACK_SCRIPT_WRAPPED
            else -> throw IllegalArgumentException("Unknown JsWebView callback kind at ${index + 1}")
        }
    return ((index + 3).toLong() shl 32) or type.ordinal.toLong()
}

private fun String.decodeProtocolValueEnd(startIndex: Int): Int {
    if (!ExpressionValueCodec.hasTag(this, startIndex, JS_WEB_VIEW_HANDLE_TAG)) {
        return ExpressionValueCodec.skipValue(this, startIndex)
    }
    var index = ExpressionValueCodec.tagPayloadStart(this, startIndex, JS_WEB_VIEW_HANDLE_TAG)
    index = skipProtocolHandle(index)
    return ExpressionValueCodec.expectTaggedValueEnd(this, index)
}

private fun Long.decodedProtocolCode(): JsWebViewProtocolCode = JsWebViewProtocolCode.entries[toInt()]

private fun String.decodePackedProtocolInt(startIndex: Int): Long {
    var index = skipJsonWhitespace(startIndex)
    val isNegative = index < length && this[index] == '-'
    if (isNegative) index++
    val numberStart = index
    val limit = if (isNegative) -(Int.MIN_VALUE.toLong()) else Int.MAX_VALUE.toLong()
    var value = 0L
    while (index < length && this[index] in '0'..'9') {
        val digit = this[index] - '0'
        check(value <= (limit - digit) / 10) { "Integer is out of range at $numberStart" }
        value = value * 10 + digit
        index++
    }
    check(index > numberStart) { "Expected integer at $numberStart" }
    check(index == numberStart + 1 || this[numberStart] != '0') { "Integer has a leading zero at $numberStart" }
    if (isNegative) value = -value
    return (index.toLong() shl 32) or (value and 0xffffffffL)
}

private fun Long.decodedProtocolIndex(): Int = (this ushr 32).toInt()

private fun Long.decodedProtocolInt(): Int = toInt()

private fun String.skipProtocolHandle(startIndex: Int): Int {
    var index = skipJsonWhitespace(startIndex)
    check(index >= length || this[index] != '-') { "JsWebView handle must be non-negative" }
    val numberStart = index
    var value = 0L
    while (index < length && this[index] in '0'..'9') {
        val digit = this[index] - '0'
        check(value <= (Long.MAX_VALUE - digit) / 10) { "JsWebView handle is out of range at $numberStart" }
        value = value * 10 + digit
        index++
    }
    check(index > numberStart) { "Expected integer at $numberStart" }
    check(index == numberStart + 1 || this[numberStart] != '0') { "JsWebView handle has a leading zero at $numberStart" }
    JsWebViewProtocolHandle.decode(value)
    return index
}

private fun String.expectProtocolMessageEnd(startIndex: Int) {
    expectJsonEnd(expectJsonChar(startIndex, ']'))
}

internal const val JS_WEB_VIEW_BRIDGE_OBJECT = "__appZenmoneyJsBridge"
internal const val JS_WEB_VIEW_ANDROID_INTERFACE = "__appZenmoneyJsBridgeNative"
internal const val JS_WEB_VIEW_IOS_HANDLER = "appZenmoneyJsBridge"

private val jsWebViewExpressionValueCodecTags =
    listOf(
        ExpressionValueTag.NULL,
        ExpressionValueTag.UNDEFINED,
        ExpressionValueTag.BOOLEAN,
        ExpressionValueTag.NUMBER,
        ExpressionValueTag.BIGINT,
        ExpressionValueTag.STRING,
        ExpressionValueTag.UINT8_ARRAY,
    )

internal val jsWebViewRuntimeScript: String = createJsWebViewRuntimeScript()

internal val jsWebViewDisposeRuntimeScript: String =
    "window.$JS_WEB_VIEW_BRIDGE_OBJECT && window.$JS_WEB_VIEW_BRIDGE_OBJECT.dispose();"

internal fun createJsWebViewRuntimeScript(sessionId: Int? = null): String =
    """
    (function () {
        const sessionId = ${sessionId ?: "null"};
        if (window.$JS_WEB_VIEW_BRIDGE_OBJECT && window.$JS_WEB_VIEW_BRIDGE_OBJECT.sessionId === sessionId) return;

        const coreCodec = ($expressionValueCoreCodecFactorySource)({
            enabledTags: [${jsWebViewExpressionValueCodecTags.joinToString(",") { it.toJson() }}],
            maxGraphId: 2147483647,
        });
        let wrapScript = ($jsWebViewAcornScriptWrapperSource);
        const objectByHandle = new Map();
        const handleByObject = new WeakMap();
        // Native wrappers share one reference; callbacks add temporary references to the same count.
        const refCountByHandle = new Map();
        const pendingJsCallbacks = new Map();
        const disposeCallbacks = new Set();
        // Handles retained for native transfer while encoding a message. If encoding or post fails,
        // their new native reference or restored strong entry must be rolled back.
        // Any successful post removes its handles, including those prepared by an outer call.
        const unpublishedHandles = new Set();
        let disposed = false;
        let finalizationRegistry = typeof FinalizationRegistry === "function"
            ? new FinalizationRegistry(handle => {
                try {
                    post('[${JsWebViewProtocolCode.CALLBACK_DEALLOCATE.toJson()},' + handle + ']');
                } catch (_) {
                }
            })
            : null;

        const maxHandle = 2147483647;
        let nextHandle = 1;
        let nextJsCallbackId = 1;

        objectByHandle.set(0, globalThis);
        handleByObject.set(globalThis, 0);

        function retainHandle(handle) {
            updateHandleRefCount(handle, 1);
        }

        function releaseHandle(handle) {
            updateHandleRefCount(handle, -1);
        }

        function retainCallbackHandles(handles) {
            for (let i = 0; i < handles.length; i += 2) retainHandle(handles[i]);
        }

        function releaseCallbackHandles(handles) {
            for (let i = 0; i < handles.length; i += 2) releaseHandle(handles[i]);
        }

        function commitHandles(handles) {
            // A nested call may send the same handle; an outer failure must then preserve it.
            if (unpublishedHandles.size === 0) return;
            for (let i = 0; i < handles.length; i += 2) unpublishedHandles.delete(handles[i]);
        }

        function rollbackHandles(handles) {
            // Pairs contain the handle and its change: 0 = existing, 1 = allocated, 2 = re-exported.
            for (let i = handles.length - 2; i >= 0; i -= 2) {
                const handle = handles[i];
                const state = handles[i + 1];
                if (state === 1 && unpublishedHandles.delete(handle)) {
                    const value = objectByHandle.get(handle);
                    handleByObject.delete(value);
                    if (finalizationRegistry) finalizationRegistry.unregister(value);
                    releaseHandle(handle);
                } else if (state === 2 && unpublishedHandles.delete(handle) && !refCountByHandle.has(handle)) {
                    objectByHandle.delete(handle);
                }
            }
        }

        function updateHandleRefCount(handle, change) {
            if (disposed || handle === 0) return;
            const refCount = (refCountByHandle.get(handle) || 0) + change;
            if (refCount > 0) {
                refCountByHandle.set(handle, refCount);
            } else {
                // A zero delta must also drop an unowned value kept alive by re-export.
                refCountByHandle.delete(handle);
                objectByHandle.delete(handle);
            }
        }

        function post(message) {
            if (disposed) throw new Error("JsContext is closed");
            if (window.$JS_WEB_VIEW_ANDROID_INTERFACE && window.$JS_WEB_VIEW_ANDROID_INTERFACE.postMessage) {
                window.$JS_WEB_VIEW_ANDROID_INTERFACE.postMessage(message, sessionId);
            } else if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$JS_WEB_VIEW_IOS_HANDLER) {
                window.webkit.messageHandlers.$JS_WEB_VIEW_IOS_HANDLER.postMessage(message);
            } else {
                throw new Error("JsWebView native bridge is not installed");
            }
        }

        const handleTypeFactor = 4294967296;

        function encodeHandle(handle, type) {
            return type * handleTypeFactor + handle;
        }

        function decodeHandle(encodedHandle) {
            return encodedHandle % handleTypeFactor;
        }

        function decode(arg) {
            if (Array.isArray(arg) && arg[0] === ${JS_WEB_VIEW_HANDLE_TAG.toJson()}) {
                if (arg.length !== 2 || typeof arg[1] !== "number") {
                    throw new Error("Invalid JsWebView handle");
                }
                return objectByHandle.get(decodeHandle(arg[1]));
            }
            const graph = Array.isArray(arg) && arg[0] === ${ExpressionValueTag.UINT8_ARRAY.toJson()}
                ? coreCodec.createGraphContext()
                : undefined;
            const decoded = coreCodec.decode(arg, graph);
            if (decoded !== coreCodec.notHandled) return decoded;
            throw new Error("Unknown JsWebView argument");
        }

        function encode(value, handles) {
            if (disposed) throw new Error("JsContext is closed");
            const valueType = typeof value;
            if (value === null || valueType !== "object" && valueType !== "function") {
                const encoded = coreCodec.encode(value);
                if (encoded === coreCodec.notHandled) throw new Error("Unsupported JsWebView value " + valueType);
                return coreCodec.stringifyJson(encoded);
            }
            let handle = handleByObject.get(value);
            let state = 0;
            if (handle === undefined) {
                if (nextHandle >= maxHandle) {
                    throw new Error("JsWebView handle limit reached");
                }
                handle = nextHandle++;
                handleByObject.set(value, handle);
                // Supply the first native reference without requiring a separate retain command.
                retainHandle(handle);
                unpublishedHandles.add(handle);
                state = 1;
                if (finalizationRegistry) {
                    finalizationRegistry.register(value, handle, value);
                }
            } else if (handle !== 0 && !objectByHandle.has(handle)) {
                state = 2;
                unpublishedHandles.add(handle);
            }
            if (handle !== 0) handles.push(handle, state);
            // Keep re-exported values reachable until the native refcount batch arrives.
            objectByHandle.set(handle, value);
            return '[${JS_WEB_VIEW_HANDLE_TAG.toJson()},' + encodeHandle(handle, typeOf(value)) + ']';
        }

        function typeOf(value) {
            try {
                if (value instanceof Boolean) return ${JsWebViewProtocolHandleType.BOOLEAN_OBJECT.code};
                if (value instanceof Number) return ${JsWebViewProtocolHandleType.NUMBER_OBJECT.code};
                if (value instanceof String) return ${JsWebViewProtocolHandleType.STRING_OBJECT.code};
                if (value instanceof Error) return ${JsWebViewProtocolHandleType.ERROR.code};
                if (value instanceof Date) return ${JsWebViewProtocolHandleType.DATE.code};
                if (value instanceof Uint8Array) return ${JsWebViewProtocolHandleType.UINT8_ARRAY.code};
                if (value instanceof Promise || typeof value === "object" && value && typeof value.then === "function") {
                    return ${JsWebViewProtocolHandleType.PROMISE.code};
                }
                if (Array.isArray(value)) return ${JsWebViewProtocolHandleType.ARRAY.code};
                if (typeof value === "function") return ${JsWebViewProtocolHandleType.FUNCTION.code};
            } catch (_) {
            }
            return ${JsWebViewProtocolHandleType.OBJECT.code};
        }

        function encodeAsList(values, handles) {
            let result = "[";
            for (let i = 0; i < values.length; i++) {
                if (i !== 0) {
                    result += ",";
                }
                result += encode(values[i], handles);
            }
            result += "]";
            return result;
        }

        function runCommand (command, handles) {
            switch (command[0]) {
                case ${JsWebViewProtocolCode.COMMAND_COMPLETE_EVALUATION.toJson()}: {
                    if (command[1]) throw command[2];
                    if (command[3] === undefined) return encode(command[2], handles);
                    const transform = objectByHandle.get(command[3]);
                    if (typeof transform !== "function") throw new Error("Unknown evaluation value transform handle");
                    return encode(transform(command[2]), handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_DECODE_EXPRESSION.toJson()}: {
                    if (command.length !== 4 || typeof command[1] !== "number" || !Array.isArray(command[3])) {
                        throw new Error("Invalid expression decode command");
                    }
                    const decoder = objectByHandle.get(command[1]);
                    if (typeof decoder !== "function") throw new Error("Unknown expression decoder handle");
                    return encode(decoder(command[2], command[3].map(decode), true), handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_CREATE_ARRAY.toJson()}:
                    return encode(command[1].map(decode), handles);

                case ${JsWebViewProtocolCode.COMMAND_CREATE_UINT8ARRAY.toJson()}: {
                    if (!Array.isArray(command[1]) || command[1][0] !== ${ExpressionValueTag.UINT8_ARRAY.toJson()}) {
                        throw new Error("Expected encoded Uint8Array");
                    }
                    const value = coreCodec.decode(command[1], coreCodec.createGraphContext());
                    return encode(value, handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_READ_UINT8ARRAY.toJson()}: {
                    const encoded = coreCodec.encode(objectByHandle.get(command[1]), coreCodec.createGraphContext());
                    if (encoded === coreCodec.notHandled || encoded[0] !== ${ExpressionValueTag.UINT8_ARRAY.toJson()}) {
                        throw new Error("Expected Uint8Array handle");
                    }
                    return coreCodec.stringifyJson(encoded);
                }

                case ${JsWebViewProtocolCode.COMMAND_CREATE_FUNCTION.toJson()}: {
                    const callbackId = command[1];
                    return encode(function (...args) {
                        if (disposed) throw new Error("JsContext is closed");
                        const thiz = this;
                        return new Promise((resolve, reject) => {
                            const jsCallbackId = nextJsCallbackId++;
                            const callbackHandles = [];
                            try {
                                const encodedThis = encode(thiz, callbackHandles);
                                const encodedArgs = encodeAsList(args, callbackHandles);
                                retainCallbackHandles(callbackHandles);
                                pendingJsCallbacks.set(jsCallbackId, { resolve, reject, handles: callbackHandles });
                                post(
                                    '[${JsWebViewProtocolCode.CALLBACK_FUNCTION.toJson()},' +
                                    jsCallbackId + ',' + callbackId + ',' + encodedThis + ',' + encodedArgs + ']'
                                );
                                commitHandles(callbackHandles);
                            } catch (error) {
                                if (pendingJsCallbacks.delete(jsCallbackId)) releaseCallbackHandles(callbackHandles);
                                rollbackHandles(callbackHandles);
                                reject(error);
                            }
                        });
                    }, handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_CREATE_PROMISE.toJson()}: {
                    const executorCallbackId = command[1];
                    return encode(new Promise((resolve, reject) => {
                        const executorHandles = [];
                        try {
                            post(
                                '[${JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR.toJson()},' +
                                executorCallbackId + ',' + encode(resolve, executorHandles) + ',' + encode(reject, executorHandles) + ']'
                            );
                            commitHandles(executorHandles);
                        } catch (error) {
                            rollbackHandles(executorHandles);
                            reject(error);
                        }
                    }), handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_GET_OBJECT_VALUE.toJson()}: {
                    const receiver = objectByHandle.get(command[1]);
                    return encode(receiver[command[2]], handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_SET_OBJECT_VALUE.toJson()}: {
                    const receiver = objectByHandle.get(command[1]);
                    receiver[command[2]] = decode(command[3]);
                    return encode(undefined, handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_CALL_FUNCTION.toJson()}: {
                    const f = objectByHandle.get(command[1]);
                    const thiz = command[2] == null ? globalThis : objectByHandle.get(command[2]);
                    const value = f.apply(thiz, command[3].map(decode));
                    return encode(value, handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_CONSTRUCT.toJson()}: {
                    const f = objectByHandle.get(command[1]);
                    const value = new f(...command[2].map(decode));
                    return encode(value, handles);
                }

                case ${JsWebViewProtocolCode.COMMAND_UPDATE_REF_COUNTS.toJson()}: {
                    const changes = command[1];
                    for (let i = 0; i < changes.length; i += 2) {
                        updateHandleRefCount(changes[i], changes[i + 1]);
                    }
                    return encode(undefined, handles);
                }

                default:
                    throw new Error("unexpected JsWebView message " + command[0]);
            }
        }

        const bridge = {
            sessionId,
            addDisposeCallback (callback) {
                if (disposed) {
                    try { callback(); } catch (_) {}
                    return () => {};
                }
                disposeCallbacks.add(callback);
                return () => { disposeCallbacks.delete(callback); };
            },
            dispose () {
                if (disposed) return;
                disposed = true;
                const callbacks = Array.from(disposeCallbacks);
                disposeCallbacks.clear();
                for (const callback of callbacks) {
                    try { callback(); } catch (_) {}
                }
                const error = new Error("JsContext is closed");
                for (const callback of pendingJsCallbacks.values()) callback.reject(error);
                pendingJsCallbacks.clear();
                objectByHandle.clear();
                refCountByHandle.clear();
                unpublishedHandles.clear();
                wrapScript = null;
                finalizationRegistry = null;
                if (window.$JS_WEB_VIEW_BRIDGE_OBJECT === bridge) delete window.$JS_WEB_VIEW_BRIDGE_OBJECT;
            },
            dispatch (message, requestId) {
                if (disposed) return;
                if (requestId !== undefined) {
                    const handles = [];
                    try {
                        if (message[0] === ${JsWebViewProtocolCode.COMMAND_WRAP_SCRIPT.toJson()}) {
                            const script = wrapScript(message[1], requestId, message[2]);
                            post('[${JsWebViewProtocolCode.CALLBACK_SCRIPT_WRAPPED.toJson()},' + requestId + ',' + coreCodec.stringifyJson(script) + ']');
                            return;
                        }
                        post('[${JsWebViewProtocolCode.CALLBACK_RESULT.toJson()},' + requestId + ',' + runCommand(message, handles) + ']');
                        commitHandles(handles);
                    } catch (error) {
                        rollbackHandles(handles);
                        const errorHandles = [];
                        try {
                            post('[${JsWebViewProtocolCode.CALLBACK_ERROR.toJson()},' + requestId + ',' + encode(error, errorHandles) + ']');
                            commitHandles(errorHandles);
                        } catch (postError) {
                            rollbackHandles(errorHandles);
                            throw postError;
                        }
                    }
                    return;
                }

                if (message[0] === ${JsWebViewProtocolCode.COMMAND_UPDATE_REF_COUNTS.toJson()}) {
                    runCommand(message);
                    return;
                }

                const jsCallbackId = message[1];
                const callback = pendingJsCallbacks.get(jsCallbackId);
                if (!callback) {
                    return;
                }
                pendingJsCallbacks.delete(jsCallbackId);
                try {
                    switch (message[0]) {
                        case ${JsWebViewProtocolCode.COMMAND_COMPLETE_NATIVE_CALLBACK.toJson()}:
                            callback.resolve(decode(message[2]));
                            break;
                        case ${JsWebViewProtocolCode.COMMAND_FAIL_NATIVE_CALLBACK.toJson()}:
                            callback.reject(decode(message[2]));
                            break;
                        default:
                            callback.reject(new Error("unexpected JsWebView message " + message[0]));
                    }
                } catch (e) {
                    callback.reject(e);
                } finally {
                    releaseCallbackHandles(callback.handles);
                }
            },
        };
        window.$JS_WEB_VIEW_BRIDGE_OBJECT = bridge;
    })();
    """.trimIndent()

private fun <T> List<T>.toJsonArray(item: (T) -> String): String = joinToString(separator = ",", prefix = "[", postfix = "]") { item(it) }
