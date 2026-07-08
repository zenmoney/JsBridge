package app.zenmoney.jsbridge

import kotlin.jvm.JvmInline

internal enum class JsWebViewProtocolCode(
    val value: String,
) {
    COMMAND_CREATE_ARRAY("a"),
    COMMAND_CREATE_FUNCTION("f"),
    COMMAND_CREATE_PROMISE("p"),
    COMMAND_CREATE_UINT8ARRAY("y+"),

    COMMAND_EVALUATE("e"),

    COMMAND_READ_UINT8ARRAY("y?"),

    COMMAND_GET_OBJECT_VALUE("g"),
    COMMAND_SET_OBJECT_VALUE("s"),

    COMMAND_CALL_FUNCTION("c"),
    COMMAND_CONSTRUCT("n"),
    COMMAND_RELEASE("r"),

    COMMAND_COMPLETE_NATIVE_CALLBACK("+"),
    COMMAND_FAIL_NATIVE_CALLBACK("-"),

    CALLBACK_RESULT("r"),
    CALLBACK_ERROR("e"),
    CALLBACK_FUNCTION("f"),
    CALLBACK_PROMISE_EXECUTOR("p"),
    CALLBACK_DEALLOCATE("d"),

    VALUE_NULL("0"),
    VALUE_UNDEFINED("u"),
    VALUE_BOOLEAN("b"),
    VALUE_NUMBER("n"),
    VALUE_STRING("s"),
    VALUE_BYTE_ARRAY("y"),
    VALUE_HANDLE("h"),

    NUMBER_NAN("nan"),
    NUMBER_POSITIVE_INFINITY("+inf"),
    NUMBER_NEGATIVE_INFINITY("-inf"),
    NUMBER_NEGATIVE_ZERO("-0"),
    ;

    private val json: String = "\"$value\""

    fun toJson(): String = json

    fun toTag(): Char {
        check(value.length == 1) { "$this is not a one-character protocol code" }
        return value[0]
    }
}

@JvmInline
internal value class JsWebViewMessage private constructor(
    val value: String,
) {
    fun toScript(requestId: Int): String = "$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch($value,$requestId);"

    fun toScript(): String = "$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch($value);"

    companion object {
        private fun message(
            code: JsWebViewProtocolCode,
            arguments: String = "",
        ): JsWebViewMessage = JsWebViewMessage("""[${code.toJson()}$arguments]""")

        @Suppress("FunctionName")
        fun Evaluate(script: String): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_EVALUATE, ",${script.toJson()}")

        @Suppress("FunctionName")
        fun CreateArray(items: List<JsWebViewProtocolValue>): JsWebViewMessage =
            message(JsWebViewProtocolCode.COMMAND_CREATE_ARRAY, ",${items.toJsonArray { it.value }}")

        @Suppress("FunctionName")
        fun CreateUint8Array(value: ByteArray): JsWebViewMessage =
            message(JsWebViewProtocolCode.COMMAND_CREATE_UINT8ARRAY, ",${value.toJsonArray()}")

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
        fun Release(handle: Int): JsWebViewMessage = message(JsWebViewProtocolCode.COMMAND_RELEASE, ",$handle")

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
    val type: JsWebViewProtocolCode
        get() {
            check(value.length >= 5 && value[0] == '[' && value[1] == '"' && value[3] == '"') {
                "Invalid JsWebView protocol value"
            }
            return jsWebViewProtocolValueCode(value[2])
        }

    fun decodeBoolean(): Boolean =
        decode(JsWebViewProtocolCode.VALUE_BOOLEAN) {
            it.expect(',')
            it.readProtocolBoolean()
        }

    fun decodeNumber(): Double =
        decode(JsWebViewProtocolCode.VALUE_NUMBER) {
            it.expect(',')
            it.readProtocolNumber()
        }

    fun decodeString(): String =
        decode(JsWebViewProtocolCode.VALUE_STRING) {
            it.expect(',')
            it.readString()
        }

    fun decodeByteArray(): ByteArray =
        decode(JsWebViewProtocolCode.VALUE_BYTE_ARRAY) {
            it.expect(',')
            it.readByteArray()
        }

    fun decodeHandle(): JsWebViewProtocolHandle =
        decode(JsWebViewProtocolCode.VALUE_HANDLE) {
            it.expect(',')
            JsWebViewProtocolHandle.decode(it.readLong())
        }

    private inline fun <T> decode(
        expectedType: JsWebViewProtocolCode,
        block: (JsWebViewProtocolReader) -> T,
    ): T {
        val reader = valueReader()
        val actualType = reader.readValueType()
        check(actualType == expectedType) { "Expected $expectedType, got $actualType" }
        val result = block(reader)
        reader.expect(']')
        reader.expectEnd()
        return result
    }

    private fun valueReader(): JsWebViewProtocolReader =
        JsWebViewProtocolReader(value).also {
            it.expect('[')
        }

    companion object {
        @Suppress("FunctionName")
        fun Null(): JsWebViewProtocolValue = JsWebViewProtocolValue("[${JsWebViewProtocolCode.VALUE_NULL.toJson()}]")

        @Suppress("FunctionName")
        fun Undefined(): JsWebViewProtocolValue = JsWebViewProtocolValue("[${JsWebViewProtocolCode.VALUE_UNDEFINED.toJson()}]")

        @Suppress("FunctionName")
        fun Boolean(value: Boolean): JsWebViewProtocolValue =
            JsWebViewProtocolValue("[${JsWebViewProtocolCode.VALUE_BOOLEAN.toJson()},${if (value) 1 else 0}]")

        @Suppress("FunctionName")
        fun Number(value: Number): JsWebViewProtocolValue {
            val number = value.toDouble()
            val encodedNumber =
                when {
                    number.isNaN() -> JsWebViewProtocolCode.NUMBER_NAN.toJson()
                    number == Double.POSITIVE_INFINITY -> JsWebViewProtocolCode.NUMBER_POSITIVE_INFINITY.toJson()
                    number == Double.NEGATIVE_INFINITY -> JsWebViewProtocolCode.NUMBER_NEGATIVE_INFINITY.toJson()
                    number.toBits() == (-0.0).toBits() -> JsWebViewProtocolCode.NUMBER_NEGATIVE_ZERO.toJson()
                    else -> number.toString()
                }
            return JsWebViewProtocolValue("[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},$encodedNumber]")
        }

        @Suppress("FunctionName")
        fun String(value: String): JsWebViewProtocolValue =
            JsWebViewProtocolValue("[${JsWebViewProtocolCode.VALUE_STRING.toJson()},${value.toJson()}]")

        @Suppress("FunctionName")
        fun ByteArray(value: ByteArray): JsWebViewProtocolValue =
            JsWebViewProtocolValue(
                value.joinToString(
                    separator = ",",
                    prefix = "[${JsWebViewProtocolCode.VALUE_BYTE_ARRAY.toJson()},[",
                    postfix = "]]",
                ) {
                    (it.toInt() and 0xff).toString()
                },
            )

        @Suppress("FunctionName")
        fun Handle(
            handle: Int,
            type: JsWebViewProtocolHandleType,
        ): JsWebViewProtocolValue =
            JsWebViewProtocolValue(
                "[${JsWebViewProtocolCode.VALUE_HANDLE.toJson()},${JsWebViewProtocolHandle.encode(handle, type).encoded}]",
            )

        internal fun fromEncoded(value: String): JsWebViewProtocolValue = JsWebViewProtocolValue(value)
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
    }

    fun handle(message: String) {
        val reader = JsWebViewProtocolReader(message)
        reader.expect('[')
        val type = reader.readTag()
        reader.expect(',')
        when (type) {
            JsWebViewProtocolCode.CALLBACK_RESULT.toTag() -> readSuccessMessage(reader)
            JsWebViewProtocolCode.CALLBACK_ERROR.toTag() -> readFailureMessage(reader)
            JsWebViewProtocolCode.CALLBACK_FUNCTION.toTag() -> readFunctionMessage(reader)
            JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR.toTag() -> readPromiseExecutorMessage(reader)
            JsWebViewProtocolCode.CALLBACK_DEALLOCATE.toTag() -> readDeallocateMessage(reader)
            else -> throw IllegalArgumentException("Unknown JsWebView message kind: $type")
        }
        reader.expect(']')
        reader.expectEnd()
    }

    private fun readSuccessMessage(reader: JsWebViewProtocolReader) {
        val requestId = reader.readInt()
        reader.expect(',')
        listener.onSuccess(requestId, reader.readProtocolValue())
    }

    private fun readFailureMessage(reader: JsWebViewProtocolReader) {
        val requestId = reader.readInt()
        reader.expect(',')
        listener.onFailure(requestId, reader.readProtocolValue())
    }

    private fun readFunctionMessage(reader: JsWebViewProtocolReader) {
        val jsCallbackId = reader.readInt()
        reader.expect(',')
        val callbackId = reader.readInt()
        reader.expect(',')
        val thiz = reader.readProtocolValue()
        reader.expect(',')
        val args = reader.readProtocolValueList()
        listener.onFunction(jsCallbackId, callbackId, thiz, args)
    }

    private fun readPromiseExecutorMessage(reader: JsWebViewProtocolReader) {
        val executorCallbackId = reader.readInt()
        reader.expect(',')
        val resolve = reader.readProtocolValue()
        reader.expect(',')
        val reject = reader.readProtocolValue()
        listener.onPromiseExecutor(executorCallbackId, resolve, reject)
    }

    private fun readDeallocateMessage(reader: JsWebViewProtocolReader) {
        listener.onDeallocate(reader.readInt())
    }
}

private class JsWebViewProtocolReader(
    private val source: String,
) {
    private var index = 0

    fun expect(char: Char) {
        skipWhitespace()
        check(index < source.length && source[index] == char) { "Expected '$char' at $index" }
        index++
    }

    fun expectEnd() {
        skipWhitespace()
        check(index == source.length) { "Unexpected trailing data at $index" }
    }

    fun peek(char: Char): Boolean {
        skipWhitespace()
        return index < source.length && source[index] == char
    }

    fun readLong(): Long {
        skipWhitespace()
        val isNegative = index < source.length && source[index] == '-'
        if (isNegative) {
            index++
        }
        val start = index
        var value = 0L
        while (index < source.length) {
            val char = source[index]
            if (char !in '0'..'9') break
            value = value * 10 + (char - '0')
            index++
        }
        check(index > start) { "Expected integer at $start" }
        return if (isNegative) -value else value
    }

    fun readInt(): Int {
        val value = readLong()
        check(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer is out of range: $value" }
        return value.toInt()
    }

    fun readDouble(): Double {
        skipWhitespace()
        val start = index
        while (index < source.length && source[index] !in " \n\r\t,]}") {
            index++
        }
        return source.substring(start, index).toDouble()
    }

    fun readProtocolBoolean(): Boolean =
        when (val value = readInt()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("Unknown JsWebView boolean value: $value")
        }

    fun readProtocolNumber(): Double {
        if (peek('"')) {
            return when (val value = readString()) {
                JsWebViewProtocolCode.NUMBER_NAN.value -> Double.NaN
                JsWebViewProtocolCode.NUMBER_POSITIVE_INFINITY.value -> Double.POSITIVE_INFINITY
                JsWebViewProtocolCode.NUMBER_NEGATIVE_INFINITY.value -> Double.NEGATIVE_INFINITY
                JsWebViewProtocolCode.NUMBER_NEGATIVE_ZERO.value -> -0.0
                else -> throw IllegalArgumentException("Unknown JsWebView number value: $value")
            }
        }
        return readDouble().also {
            check(it.isFinite()) { "Non-finite JsWebView number must use a protocol code" }
        }
    }

    fun readByteArray(): ByteArray {
        expect('[')
        if (peek(']')) {
            expect(']')
            return ByteArray(0)
        }
        var bytes = ByteArray(16)
        var size = 0
        while (true) {
            if (size == bytes.size) {
                bytes = bytes.copyOf(bytes.size * 2)
            }
            bytes[size++] = readInt().toByte()
            if (peek(']')) {
                expect(']')
                return bytes.copyOf(size)
            }
            expect(',')
        }
    }

    fun readProtocolValue(): JsWebViewProtocolValue {
        skipWhitespace()
        val start = index
        expect('[')
        when (readValueType()) {
            JsWebViewProtocolCode.VALUE_NULL,
            JsWebViewProtocolCode.VALUE_UNDEFINED,
            -> {
                Unit
            }

            JsWebViewProtocolCode.VALUE_BOOLEAN -> {
                expect(',')
                readProtocolBoolean()
            }

            JsWebViewProtocolCode.VALUE_NUMBER -> {
                expect(',')
                readProtocolNumber()
            }

            JsWebViewProtocolCode.VALUE_STRING -> {
                expect(',')
                readString()
            }

            JsWebViewProtocolCode.VALUE_BYTE_ARRAY -> {
                expect(',')
                readByteArray()
            }

            JsWebViewProtocolCode.VALUE_HANDLE -> {
                expect(',')
                JsWebViewProtocolHandle.decode(readLong())
            }

            else -> {
                error("Expected JsWebView value code")
            }
        }
        expect(']')
        return JsWebViewProtocolValue.fromEncoded(source.substring(start, index))
    }

    fun readProtocolValueList(): List<JsWebViewProtocolValue> {
        expect('[')
        if (peek(']')) {
            expect(']')
            return emptyList()
        }
        val values = arrayListOf<JsWebViewProtocolValue>()
        while (true) {
            values.add(readProtocolValue())
            if (peek(']')) {
                expect(']')
                return values
            }
            expect(',')
        }
    }

    fun readString(): String {
        expect('"')
        val contentStart = index
        while (true) {
            check(index < source.length) { "Unterminated string at $contentStart" }
            when (source[index++]) {
                '"' -> return source.substring(contentStart, index - 1)
                '\\' -> break
            }
        }

        index = contentStart
        val result = StringBuilder()
        while (true) {
            check(index < source.length) { "Unterminated string at $contentStart" }
            when (val char = source[index++]) {
                '"' -> {
                    return result.toString()
                }

                '\\' -> {
                    check(index < source.length) { "Unterminated escape sequence at $index" }
                    result.append(readEscapedChar())
                }

                else -> {
                    result.append(char)
                }
            }
        }
    }

    fun readTag(): Char {
        expect('"')
        check(index + 1 < source.length && source[index + 1] == '"') { "Expected one-character tag at $index" }
        val tag = source[index]
        index += 2
        return tag
    }

    fun readValueType(): JsWebViewProtocolCode = jsWebViewProtocolValueCode(readTag())

    private fun readEscapedChar(): Char =
        when (val escaped = source[index++]) {
            '"' -> {
                '"'
            }

            '\\' -> {
                '\\'
            }

            '/' -> {
                '/'
            }

            'b' -> {
                '\b'
            }

            'n' -> {
                '\n'
            }

            'r' -> {
                '\r'
            }

            't' -> {
                '\t'
            }

            'f' -> {
                '\u000c'
            }

            'u' -> {
                check(index + 4 <= source.length) { "Invalid unicode escape at $index" }
                source.substring(index, index + 4).toInt(16).toChar().also {
                    index += 4
                }
            }

            else -> {
                escaped
            }
        }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in " \n\r\t") {
            index++
        }
    }
}

private fun jsWebViewProtocolValueCode(tag: Char): JsWebViewProtocolCode =
    when (tag) {
        '0' -> JsWebViewProtocolCode.VALUE_NULL
        'u' -> JsWebViewProtocolCode.VALUE_UNDEFINED
        'b' -> JsWebViewProtocolCode.VALUE_BOOLEAN
        'n' -> JsWebViewProtocolCode.VALUE_NUMBER
        's' -> JsWebViewProtocolCode.VALUE_STRING
        'y' -> JsWebViewProtocolCode.VALUE_BYTE_ARRAY
        'h' -> JsWebViewProtocolCode.VALUE_HANDLE
        else -> throw IllegalArgumentException("Unknown JsWebView value kind: $tag")
    }

internal const val JS_WEB_VIEW_BRIDGE_OBJECT = "__appZenmoneyJsBridge"
internal const val JS_WEB_VIEW_ANDROID_INTERFACE = "__appZenmoneyJsBridgeNative"
internal const val JS_WEB_VIEW_IOS_HANDLER = "appZenmoneyJsBridge"

internal val jsWebViewRuntimeScript: String =
    """
    (function () {
        if (window.$JS_WEB_VIEW_BRIDGE_OBJECT) return;

        const objectByHandle = new Map();
        const handleByObject = new WeakMap();
        const refCountByHandle = new Map();
        const pendingJsCallbacks = new Map();
        const finalizationRegistry = typeof FinalizationRegistry === "function"
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

        function retain(...values) {
            for (let i = 0; i < values.length; i++) {
                const value = values[i];
                const handle = handleByObject.get(value);
                if (handle === undefined) continue;
                retainHandle(handle, value);
            }
        }

        function retainHandle(handle, value, skipIfAlreadyRetained) {
            if (handle === 0) return;
            objectByHandle.set(handle, value);
            const refCount = Math.max(0, refCountByHandle.get(handle) || 0);
            if (refCount === 0 || !skipIfAlreadyRetained) {
                refCountByHandle.set(handle, refCount + 1);
            }
        }

        function release(...values) {
            for (let i = 0; i < values.length; i++) {
                const value = values[i];
                const handle = handleByObject.get(value);
                if (handle === undefined) continue;
                releaseHandle(handle);
            }
        }

        function releaseHandle(handle) {
            if (handle === 0) return;
            const refCount = refCountByHandle.get(handle);
            if (refCount === undefined) {
                return true;
            } else if (refCount <= 1) {
                refCountByHandle.delete(handle);
                return true;
            } else {
                refCountByHandle.set(handle, refCount - 1);
                return false;
            }
        }

        function releaseHandleAndDeleteIfUnused(handle) {
            if (releaseHandle(handle)) {
                objectByHandle.delete(handle);
            }
        }

        function post(message) {
            if (window.$JS_WEB_VIEW_ANDROID_INTERFACE && window.$JS_WEB_VIEW_ANDROID_INTERFACE.postMessage) {
                window.$JS_WEB_VIEW_ANDROID_INTERFACE.postMessage(message);
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
            switch (arg[0]) {
                case ${JsWebViewProtocolCode.VALUE_NULL.toJson()}: return null;
                case ${JsWebViewProtocolCode.VALUE_UNDEFINED.toJson()}: return undefined;
                case ${JsWebViewProtocolCode.VALUE_BOOLEAN.toJson()}:
                    if (arg[1] === 0) return false;
                    if (arg[1] === 1) return true;
                    throw new Error("Unknown JsWebView boolean " + arg[1]);
                case ${JsWebViewProtocolCode.VALUE_NUMBER.toJson()}: return decodeNumber(arg[1]);
                case ${JsWebViewProtocolCode.VALUE_STRING.toJson()}: return arg[1];
                case ${JsWebViewProtocolCode.VALUE_HANDLE.toJson()}: return objectByHandle.get(decodeHandle(arg[1]));
                default: throw new Error("Unknown JsWebView argument " + arg[0]);
            }
        }

        function decodeNumber(value) {
            if (typeof value === "number") return value;
            switch (value) {
                case ${JsWebViewProtocolCode.NUMBER_NAN.toJson()}: return NaN;
                case ${JsWebViewProtocolCode.NUMBER_POSITIVE_INFINITY.toJson()}: return Infinity;
                case ${JsWebViewProtocolCode.NUMBER_NEGATIVE_INFINITY.toJson()}: return -Infinity;
                case ${JsWebViewProtocolCode.NUMBER_NEGATIVE_ZERO.toJson()}: return -0;
                default: throw new Error("Unknown JsWebView number " + value);
            }
        }

        function encode(value) {
            if (value === null) return '[${JsWebViewProtocolCode.VALUE_NULL.toJson()}]';
            if (value === undefined) return '[${JsWebViewProtocolCode.VALUE_UNDEFINED.toJson()}]';
            if (value === true) return '[${JsWebViewProtocolCode.VALUE_BOOLEAN.toJson()},1]';
            if (value === false) return '[${JsWebViewProtocolCode.VALUE_BOOLEAN.toJson()},0]';
            const valueType = typeof value;
            if (valueType === "number") return encodeNumber(value);
            if (valueType === "string") return '[${JsWebViewProtocolCode.VALUE_STRING.toJson()},' + JSON.stringify(value) + ']';
            if (valueType !== "object" && valueType !== "function") {
                return '[${JsWebViewProtocolCode.VALUE_STRING.toJson()},' + JSON.stringify(String(value)) + ']';
            }
            let handle = handleByObject.get(value);
            if (handle === undefined) {
                if (nextHandle >= maxHandle) {
                    throw new Error("JsWebView handle limit reached");
                }
                handle = nextHandle++;
                handleByObject.set(value, handle);
                if (finalizationRegistry) {
                    finalizationRegistry.register(value, handle);
                }
            }
            retainHandle(handle, value, true);
            return '[${JsWebViewProtocolCode.VALUE_HANDLE.toJson()},' + encodeHandle(handle, typeOf(value)) + ']';
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

        function encodeNumber(value) {
            if (Number.isNaN(value)) {
                return '[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},${JsWebViewProtocolCode.NUMBER_NAN.toJson()}]';
            }
            if (value === Infinity) {
                return '[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},${JsWebViewProtocolCode.NUMBER_POSITIVE_INFINITY.toJson()}]';
            }
            if (value === -Infinity) {
                return '[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},${JsWebViewProtocolCode.NUMBER_NEGATIVE_INFINITY.toJson()}]';
            }
            if (Object.is(value, -0)) {
                return '[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},${JsWebViewProtocolCode.NUMBER_NEGATIVE_ZERO.toJson()}]';
            }
            return '[${JsWebViewProtocolCode.VALUE_NUMBER.toJson()},' + value + ']';
        }

        function encodeAsList(values) {
            let result = "[";
            for (let i = 0; i < values.length; i++) {
                if (i !== 0) {
                    result += ",";
                }
                result += encode(values[i]);
            }
            result += "]";
            return result;
        }

        function runCommand (command, requestId) {
            switch (command[0]) {
                case ${JsWebViewProtocolCode.COMMAND_EVALUATE.toJson()}: {
                    const errorKey = "__appZenmoneyEvalError" + requestId;
                    globalThis[errorKey] = null;
                    const value = (0, eval)(
                        "try {\n" +
                        command[1] +
                        "\n} catch (__appZenmoneyEvalError) { globalThis[" + JSON.stringify(errorKey) + "] = { error: __appZenmoneyEvalError }; }"
                    );
                    const errorBox = globalThis[errorKey];
                    delete globalThis[errorKey];
                    if (errorBox) {
                        throw errorBox.error;
                    }
                    return encode(value);
                }

                case ${JsWebViewProtocolCode.COMMAND_CREATE_ARRAY.toJson()}:
                    return encode(command[1].map(decode));

                case ${JsWebViewProtocolCode.COMMAND_CREATE_UINT8ARRAY.toJson()}:
                    return encode(Uint8Array.from(command[1]));

                case ${JsWebViewProtocolCode.COMMAND_READ_UINT8ARRAY.toJson()}:
                    return '[${JsWebViewProtocolCode.VALUE_BYTE_ARRAY.toJson()},[' + objectByHandle.get(command[1]).join(",") + ']]';

                case ${JsWebViewProtocolCode.COMMAND_CREATE_FUNCTION.toJson()}: {
                    const callbackId = command[1];
                    return encode(function (...args) {
                        const thiz = this;
                        return new Promise((resolve, reject) => {
                            const jsCallbackId = nextJsCallbackId++;

                            pendingJsCallbacks.set(jsCallbackId, {
                                resolve: function () {
                                    try {
                                        resolve.apply(this, arguments);
                                    } finally {
                                        release(thiz);
                                        release(...args);
                                    }
                                },
                                reject: function () {
                                    try {
                                        reject.apply(this, arguments);
                                    } finally {
                                        release(thiz);
                                        release(...args);
                                    }
                                }
                            });

                            const encodedThis = encode(thiz);
                            const encodedArgs = encodeAsList(args);
                            retain(thiz);
                            retain(...args);

                            try {
                                post(
                                    '[${JsWebViewProtocolCode.CALLBACK_FUNCTION.toJson()},' +
                                    jsCallbackId + ',' + callbackId + ',' + encodedThis + ',' + encodedArgs + ']'
                                );
                            } catch (error) {
                                const callback = pendingJsCallbacks.get(jsCallbackId);
                                if (callback) {
                                    pendingJsCallbacks.delete(jsCallbackId);
                                    callback.reject(error);
                                }
                            }
                        });
                    });
                }

                case ${JsWebViewProtocolCode.COMMAND_CREATE_PROMISE.toJson()}: {
                    const executorCallbackId = command[1];
                    return encode(new Promise((resolve, reject) => {
                        try {
                            post(
                                '[${JsWebViewProtocolCode.CALLBACK_PROMISE_EXECUTOR.toJson()},' +
                                executorCallbackId + ',' + encode(resolve) + ',' + encode(reject) + ']'
                            );
                        } catch (error) {
                            reject(error);
                        }
                    }));
                }

                case ${JsWebViewProtocolCode.COMMAND_GET_OBJECT_VALUE.toJson()}: {
                    const receiver = objectByHandle.get(command[1]);
                    return encode(receiver[command[2]]);
                }

                case ${JsWebViewProtocolCode.COMMAND_SET_OBJECT_VALUE.toJson()}: {
                    const receiver = objectByHandle.get(command[1]);
                    receiver[command[2]] = decode(command[3]);
                    return '[${JsWebViewProtocolCode.VALUE_UNDEFINED.toJson()}]';
                }

                case ${JsWebViewProtocolCode.COMMAND_CALL_FUNCTION.toJson()}: {
                    const f = objectByHandle.get(command[1]);
                    const thiz = command[2] == null ? globalThis : objectByHandle.get(command[2]);
                    const value = f.apply(thiz, command[3].map(decode));
                    return encode(value);
                }

                case ${JsWebViewProtocolCode.COMMAND_CONSTRUCT.toJson()}: {
                    const f = objectByHandle.get(command[1]);
                    const value = new f(...command[2].map(decode));
                    return encode(value);
                }

                case ${JsWebViewProtocolCode.COMMAND_RELEASE.toJson()}:
                    releaseHandleAndDeleteIfUnused(command[1]);
                    return '[${JsWebViewProtocolCode.VALUE_UNDEFINED.toJson()}]';

                default:
                    throw new Error("unexpected JsWebView message " + command[0]);
            }
        }

        window.$JS_WEB_VIEW_BRIDGE_OBJECT = {
            dispatch (message, requestId) {
                if (requestId !== undefined) {
                    try {
                        post('[${JsWebViewProtocolCode.CALLBACK_RESULT.toJson()},' + requestId + ',' + runCommand(message, requestId) + ']');
                    } catch (error) {
                        post('[${JsWebViewProtocolCode.CALLBACK_ERROR.toJson()},' + requestId + ',' + encode(error) + ']');
                    }
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
                }
            },
        };
    })();
    """.trimIndent()

private fun <T> List<T>.toJsonArray(item: (T) -> String): String = joinToString(separator = ",", prefix = "[", postfix = "]") { item(it) }

private fun ByteArray.toJsonArray(): String =
    joinToString(separator = ",", prefix = "[", postfix = "]") {
        (it.toInt() and 0xff).toString()
    }

internal fun String.toJson(): String =
    buildString {
        append('"')
        this@toJson.forEach {
            when (it) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\u2028', '\u2029' -> appendUnicodeEscape(it)
                else -> appendJsonCharacter(it)
            }
        }
        append('"')
    }

private fun StringBuilder.appendJsonCharacter(char: Char) {
    if (char < ' ') {
        appendUnicodeEscape(char)
    } else {
        append(char)
    }
}

private fun StringBuilder.appendUnicodeEscape(char: Char) {
    append("\\u")
    repeat(4) { shift ->
        append(HEX_DIGITS[(char.code shr (12 - shift * 4)) and 0xf])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
