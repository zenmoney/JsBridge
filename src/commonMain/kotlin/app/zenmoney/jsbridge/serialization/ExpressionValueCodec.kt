package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsFunction
import app.zenmoney.jsbridge.JsObject
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.JsWebViewContext
import app.zenmoney.jsbridge.decodeJsonString
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.expectJsonChar
import app.zenmoney.jsbridge.expectJsonEnd
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.matchesJsonLiteral
import app.zenmoney.jsbridge.skipJsonLiteral
import app.zenmoney.jsbridge.skipJsonNumber
import app.zenmoney.jsbridge.skipJsonString
import app.zenmoney.jsbridge.skipJsonWhitespace
import app.zenmoney.jsbridge.toJson
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal object ExpressionValueTag {
    const val NULL = "0"
    const val UNDEFINED = "u"
    const val BOOLEAN = "b"
    const val NUMBER = "n"
    const val BIGINT = "i"
    const val STRING = "s"
    const val OBJECT = "o"
    const val ARRAY = "a"
    const val SPARSE_ARRAY = "A"
    const val GRAPH_REFERENCE = "r"
    const val REFERENCE_VALUE = "x"
    const val DATE = "d"
    const val REGEXP = "re"
    const val ERROR = "e"
    const val ARRAY_BUFFER = "ab"
    const val INT8_ARRAY = "i8"
    const val UINT8_ARRAY = "ui8"
    const val UINT8_CLAMPED_ARRAY = "ui8c"
    const val INT16_ARRAY = "i16"
    const val UINT16_ARRAY = "ui16"
    const val INT32_ARRAY = "i32"
    const val UINT32_ARRAY = "ui32"
    const val FLOAT32_ARRAY = "f32"
    const val FLOAT64_ARRAY = "f64"
    const val BIGINT64_ARRAY = "bi64"
    const val BIGUINT64_ARRAY = "bui64"

    const val NUMBER_NAN = "nan"
    const val NUMBER_POSITIVE_INFINITY = "+inf"
    const val NUMBER_NEGATIVE_INFINITY = "-inf"
    const val NUMBER_NEGATIVE_ZERO = "-0"
}

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toExpressionBase64(): String = Base64.Default.encode(this)

internal fun decodeExpressionBase64(
    value: CharSequence,
    startIndex: Int = 0,
    endIndex: Int = value.length,
): ByteArray {
    require(startIndex in 0..endIndex && endIndex <= value.length) { "Invalid base64 range" }
    val length = endIndex - startIndex
    require(length % 4 == 0) { "Expected canonical padded base64" }
    if (length == 0) return ByteArray(0)

    val padding =
        when {
            value[endIndex - 1] != '=' -> 0
            value[endIndex - 2] != '=' -> 1
            else -> 2
        }
    val result = ByteArray(length / 4 * 3 - padding)
    var sourceIndex = startIndex
    var destinationIndex = 0
    while (sourceIndex < endIndex) {
        val first = expressionBase64Digit(value[sourceIndex])
        val second = expressionBase64Digit(value[sourceIndex + 1])
        val thirdCharacter = value[sourceIndex + 2]
        val fourthCharacter = value[sourceIndex + 3]
        val isLastGroup = sourceIndex + 4 == endIndex

        result[destinationIndex++] = ((first shl 2) or (second shr 4)).toByte()
        if (thirdCharacter == '=') {
            require(isLastGroup && fourthCharacter == '=' && second and 0x0f == 0) {
                "Expected canonical padded base64"
            }
        } else {
            val third = expressionBase64Digit(thirdCharacter)
            result[destinationIndex++] = ((second shl 4) or (third shr 2)).toByte()
            if (fourthCharacter == '=') {
                require(isLastGroup && third and 0x03 == 0) { "Expected canonical padded base64" }
            } else {
                val fourth = expressionBase64Digit(fourthCharacter)
                result[destinationIndex++] = ((third shl 6) or fourth).toByte()
            }
        }
        sourceIndex += 4
    }
    check(destinationIndex == result.size)
    return result
}

private fun expressionBase64Digit(char: Char): Int =
    when (char) {
        in 'A'..'Z' -> char - 'A'
        in 'a'..'z' -> char - 'a' + 26
        in '0'..'9' -> char - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> throw IllegalArgumentException("Invalid base64 character")
    }

private fun String.matchesExpressionMarker(
    index: Int,
    marker: String,
): Boolean =
    index + marker.length < length &&
        regionMatches(index, marker, 0, marker.length) &&
        this[index + marker.length] == '"'

private fun String.expressionPayloadStart(expectedType: ExpressionValueCodec.ValueType): Int =
    ExpressionValueCodec.tagPayloadStart(
        this,
        0,
        when (expectedType) {
            ExpressionValueCodec.ValueType.NUMBER -> ExpressionValueTag.NUMBER
            ExpressionValueCodec.ValueType.BIGINT -> ExpressionValueTag.BIGINT
            ExpressionValueCodec.ValueType.UINT8_ARRAY -> ExpressionValueTag.UINT8_ARRAY
            else -> error("Expected a tagged expression value with a payload")
        },
    )

private fun String.decodePackedExpressionValueType(startIndex: Int): Long {
    var index = skipJsonWhitespace(startIndex)
    check(index < length && this[index] == '"') { "Expected '\"' at $index" }
    index++
    check(index < length) { "Expected expression-value tag at $index" }
    val firstCharacter = this[index++]
    val type =
        if (firstCharacter == ExpressionValueTag.UNDEFINED[0]) {
            if (index < length && this[index] == '"') {
                ExpressionValueCodec.ValueType.UNDEFINED
            } else {
                check(index + 2 < length && this[index] == 'i' && this[index + 1] == '8') {
                    "Unknown expression-value kind at ${index - 1}"
                }
                index += 2
                ExpressionValueCodec.ValueType.UINT8_ARRAY
            }
        } else {
            when (firstCharacter) {
                ExpressionValueTag.NUMBER[0] -> ExpressionValueCodec.ValueType.NUMBER
                ExpressionValueTag.BIGINT[0] -> ExpressionValueCodec.ValueType.BIGINT
                else -> throw IllegalArgumentException("Unknown expression-value kind at ${index - 1}")
            }
        }
    check(index < length && this[index] == '"') { "Expected '\"' at $index" }
    return ((index + 1).toLong() shl 32) or type.ordinal.toLong()
}

private fun Long.decodedExpressionValueType(): ExpressionValueCodec.ValueType = ExpressionValueCodec.ValueType.entries[toInt()]

private fun Long.decodedExpressionIndex(): Int = (this ushr 32).toInt()

private fun String.skipExpressionSpecialNumber(startIndex: Int): Int {
    var index = skipJsonWhitespace(startIndex)
    check(index < length && this[index] == '"') { "Expected expression-value number marker at $index" }
    index++
    val markerLength =
        when {
            matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NAN) -> {
                ExpressionValueTag.NUMBER_NAN.length
            }

            matchesExpressionMarker(index, ExpressionValueTag.NUMBER_POSITIVE_INFINITY) -> {
                ExpressionValueTag.NUMBER_POSITIVE_INFINITY.length
            }

            matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NEGATIVE_INFINITY) -> {
                ExpressionValueTag.NUMBER_NEGATIVE_INFINITY.length
            }

            matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NEGATIVE_ZERO) -> {
                ExpressionValueTag.NUMBER_NEGATIVE_ZERO.length
            }

            else -> {
                throw IllegalArgumentException("Unknown expression-value number at $index")
            }
        }
    return index + markerLength + 1
}

private fun String.skipExpressionGraphId(startIndex: Int): Int {
    var index = skipJsonWhitespace(startIndex)
    val numberStart = index
    var value = 0L
    while (index < length && this[index] in '0'..'9') {
        val digit = this[index] - '0'
        check(value <= (Int.MAX_VALUE.toLong() - digit) / 10) { "Invalid expression-value graph id" }
        value = value * 10 + digit
        index++
    }
    check(index > numberStart && value in 1..Int.MAX_VALUE) { "Invalid expression-value graph id" }
    check(index == numberStart + 1 || this[numberStart] != '0') { "Invalid expression-value graph id" }
    return index
}

private fun String.requireCanonicalExpressionBigInt() {
    check(isNotEmpty()) { "BigInt payload is empty" }
    var index = if (this[0] == '-') 1 else 0
    check(index < length) { "Invalid BigInt payload" }
    if (this[index] == '0') {
        check(index == 0 && length == 1) { "Invalid BigInt payload" }
        return
    }
    check(this[index] in '1'..'9') { "Invalid BigInt payload" }
    index++
    while (index < length) {
        check(this[index] in '0'..'9') { "Invalid BigInt payload" }
        index++
    }
}

/**
 * Small protocol-neutral JavaScript codec shared by WebView and the complete expression-value codec.
 *
 * Keep this source limited to scalar values, graph references, and Uint8Array. Structured values and the remaining
 * built-in types belong only to [expressionValueCodecFactorySource], which is not embedded into WebView.
 */
internal val expressionValueCoreCodecFactorySource: String =
    """
    (function (configuration, additionalTags) {
        "use strict";

        if (configuration === undefined) configuration = {};
        if (configuration === null || typeof configuration !== "object") {
            throw new Error("Expression-value codec configuration must be an object");
        }

        const notHandled = {};
        const tags = {
            nullValue: ${ExpressionValueTag.NULL.toJson()},
            undefinedValue: ${ExpressionValueTag.UNDEFINED.toJson()},
            boolean: ${ExpressionValueTag.BOOLEAN.toJson()},
            number: ${ExpressionValueTag.NUMBER.toJson()},
            bigint: ${ExpressionValueTag.BIGINT.toJson()},
            string: ${ExpressionValueTag.STRING.toJson()},
            graphReference: ${ExpressionValueTag.GRAPH_REFERENCE.toJson()},
            uint8Array: ${ExpressionValueTag.UINT8_ARRAY.toJson()},
        };
        const allTags = [
            tags.nullValue, tags.undefinedValue, tags.boolean, tags.number, tags.bigint, tags.string,
            tags.graphReference, tags.uint8Array,
        ];

        const getPrototypeOf = Object.getPrototypeOf;
        const getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
        const isArray = Array.isArray;
        const ArrayBufferConstructor = globalThis.ArrayBuffer;
        const BigIntConstructor = typeof globalThis.BigInt === "function" ? globalThis.BigInt : null;
        const MapConstructor = globalThis.Map;
        const SetConstructor = globalThis.Set;
        const StringConstructor = globalThis.String;
        const Uint8ArrayConstructor = globalThis.Uint8Array;
        const jsonStringify = JSON.stringify;
        const bindCall = Function.prototype.call.bind(Function.prototype.call);
        const uint8ArrayToBase64 = typeof Uint8ArrayConstructor.prototype.toBase64 === "function"
            ? bindCall.bind(undefined, Uint8ArrayConstructor.prototype.toBase64)
            : null;
        const uint8ArrayFromBase64 = typeof Uint8ArrayConstructor.fromBase64 === "function"
            ? bindCall.bind(undefined, Uint8ArrayConstructor.fromBase64, Uint8ArrayConstructor)
            : null;
        const bigIntToString =
            BigIntConstructor === null ? null : bindCall.bind(undefined, BigIntConstructor.prototype.toString);
        const mapGet = bindCall.bind(undefined, MapConstructor.prototype.get);
        const mapHas = bindCall.bind(undefined, MapConstructor.prototype.has);
        const mapSet = bindCall.bind(undefined, MapConstructor.prototype.set);
        const setAdd = bindCall.bind(undefined, SetConstructor.prototype.add);
        const setHas = bindCall.bind(undefined, SetConstructor.prototype.has);
        const typedArrayPrototype = getPrototypeOf(Uint8ArrayConstructor.prototype);
        const typedArrayName =
            bindCall.bind(
                undefined,
                getOwnPropertyDescriptor(typedArrayPrototype, Symbol.toStringTag).get,
            );
        const typedArrayBuffer =
            bindCall.bind(undefined, getOwnPropertyDescriptor(typedArrayPrototype, "buffer").get);
        const typedArrayLength =
            bindCall.bind(undefined, getOwnPropertyDescriptor(typedArrayPrototype, "length").get);
        const arrayBufferResizableDescriptor =
            getOwnPropertyDescriptor(ArrayBufferConstructor.prototype, "resizable");
        const arrayBufferResizable =
            arrayBufferResizableDescriptor === undefined
                ? null
                : bindCall.bind(undefined, arrayBufferResizableDescriptor.get);
        const isFiniteNumber = Number.isFinite;
        const isSafeInteger = Number.isSafeInteger;
        const isNaNValue = Number.isNaN;
        const isSameValue = Object.is;
        const regexpTest = bindCall.bind(undefined, RegExp.prototype.test);
        const stringCharCodeAt = bindCall.bind(undefined, String.prototype.charCodeAt);
        const stringReplace = bindCall.bind(undefined, StringConstructor.prototype.replace);
        const base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        const knownTags = new SetConstructor(allTags);
        if (additionalTags !== undefined) {
            if (!isArray(additionalTags)) throw new Error("Additional expression-value tags must be an array");
            for (let index = 0; index < additionalTags.length; index++) {
                const tag = additionalTags[index];
                if (typeof tag !== "string" || setHas(knownTags, tag)) {
                    throw new Error("Invalid additional expression-value codec tag " + tag);
                }
                allTags[allTags.length] = tag;
                setAdd(knownTags, tag);
            }
        }
        const configuredTags = configuration.enabledTags;
        const enabledTags = new SetConstructor();
        if (configuredTags === undefined) {
            for (let index = 0; index < allTags.length; index++) setAdd(enabledTags, allTags[index]);
        } else {
            if (!isArray(configuredTags)) throw new Error("enabledTags must be an array");
            for (let index = 0; index < configuredTags.length; index++) {
                const tag = configuredTags[index];
                if (typeof tag !== "string" || !setHas(knownTags, tag)) {
                    throw new Error("Unknown expression-value codec tag " + tag);
                }
                if (setHas(enabledTags, tag)) throw new Error("Duplicate enabled expression-value codec tag " + tag);
                setAdd(enabledTags, tag);
            }
        }
        const maxGraphId = configuration.maxGraphId === undefined ? Number.MAX_SAFE_INTEGER : configuration.maxGraphId;
        if (!isSafeInteger(maxGraphId) || maxGraphId <= 0) throw new Error("Invalid expression-value graph id limit");

        function createGraphContext() {
            return {
                decodedIdentities: new MapConstructor(),
                decodedValues: new MapConstructor(),
                encodedIds: new MapConstructor(),
                nextId: 1,
            };
        }

        function escapeJavascriptLineSeparators(value) {
            return stringReplace(
                value,
                /[\u2028\u2029]/g,
                separator => separator === "\u2028" ? "\\u2028" : "\\u2029",
            );
        }

        function stringifyJson(value) {
            const result = jsonStringify(value);
            if (typeof result !== "string") throw new Error("Expression value is not JSON-compatible");
            return escapeJavascriptLineSeparators(result);
        }

        function encode(value, graphContext) {
            if (value === null) return enabled(tags.nullValue) ? null : notHandled;
            if (value === undefined) return enabled(tags.undefinedValue) ? [tags.undefinedValue] : notHandled;
            if (value === false || value === true) return enabled(tags.boolean) ? value : notHandled;

            const valueType = typeof value;
            if (valueType === "number") return enabled(tags.number) ? encodeNumber(value) : notHandled;
            if (valueType === "bigint") {
                if (!enabled(tags.bigint)) return notHandled;
                if (bigIntToString === null) throw new Error("BigInt is not supported by this context");
                return [tags.bigint, bigIntToString(value)];
            }
            if (valueType === "string") return enabled(tags.string) ? value : notHandled;
            if (valueType === "symbol") throw new Error("Symbol is not supported by the expression-value codec");
            if (valueType !== "object" && valueType !== "function") return notHandled;

            const graph = requireGraphContext(graphContext);
            const graphReference = encodeGraphReference(value, graph);
            if (graphReference !== notHandled) return graphReference;
            if (typedArrayName(value) !== "Uint8Array" || !enabled(tags.uint8Array)) return notHandled;
            return encodeUint8Array(value, graph);
        }

        function encodeNumber(value) {
            if (isNaNValue(value)) return [tags.number, ${ExpressionValueTag.NUMBER_NAN.toJson()}];
            if (value === Infinity) return [tags.number, ${ExpressionValueTag.NUMBER_POSITIVE_INFINITY.toJson()}];
            if (value === -Infinity) return [tags.number, ${ExpressionValueTag.NUMBER_NEGATIVE_INFINITY.toJson()}];
            if (isSameValue(value, -0)) return [tags.number, ${ExpressionValueTag.NUMBER_NEGATIVE_ZERO.toJson()}];
            return value;
        }

        function encodeGraphReference(value, graphContext) {
            if (!enabled(tags.graphReference)) return notHandled;
            const graph = requireGraphContext(graphContext);
            const existingId = mapGet(graph.encodedIds, value);
            return existingId === undefined ? notHandled : [tags.graphReference, existingId];
        }

        function encodeUint8Array(value, graph) {
            const base64 = bytesToBase64(value, "Uint8Array");
            const id = allocateGraphId(graph, value);
            return [tags.uint8Array, id, base64];
        }

        function decode(node, graphContext) {
            if (node === null) return enabled(tags.nullValue) ? null : notHandled;
            if (typeof node === "boolean") return enabled(tags.boolean) ? node : notHandled;
            if (typeof node === "number") {
                if (!isFiniteNumber(node) || isSameValue(node, -0)) throw new Error("Invalid untagged Number");
                return enabled(tags.number) ? node : notHandled;
            }
            if (typeof node === "string") return enabled(tags.string) ? node : notHandled;
            if (!isArray(node) || typeof node[0] !== "string") throw new Error("Expected an expression value");
            if (!enabled(node[0])) return notHandled;

            switch (node[0]) {
                case tags.nullValue:
                case tags.boolean:
                case tags.string:
                    throw new Error("Primitive values must use their raw expression form");
                case tags.undefinedValue:
                    requireArity(node, 1);
                    return undefined;
                case tags.number:
                    requireArity(node, 2);
                    return decodeNumber(node[1]);
                case tags.bigint:
                    requireArity(node, 2);
                    return decodeBigInt(node[1]);
                case tags.graphReference:
                    requireArity(node, 2);
                    return decodeGraphReference(node[1], requireGraphContext(graphContext));
                case tags.uint8Array:
                    return decodeUint8Array(node, requireGraphContext(graphContext));
                default:
                    return notHandled;
            }
        }

        function decodeNumber(value) {
            if (value === ${ExpressionValueTag.NUMBER_NAN.toJson()}) return NaN;
            if (value === ${ExpressionValueTag.NUMBER_POSITIVE_INFINITY.toJson()}) return Infinity;
            if (value === ${ExpressionValueTag.NUMBER_NEGATIVE_INFINITY.toJson()}) return -Infinity;
            if (value === ${ExpressionValueTag.NUMBER_NEGATIVE_ZERO.toJson()}) return -0;
            throw new Error("Invalid special Number");
        }

        function decodeBigInt(value) {
            if (typeof value !== "string" || !regexpTest(/^(?:0|-?[1-9][0-9]*)${'$'}/, value)) {
                throw new Error("Invalid BigInt payload");
            }
            if (BigIntConstructor === null) throw new Error("BigInt is not supported by this context");
            return BigIntConstructor(value);
        }

        function decodeGraphReference(id, graph) {
            requireGraphId(id);
            if (!mapHas(graph.decodedValues, id)) throw new Error("Unresolved expression-value graph reference " + id);
            return mapGet(graph.decodedValues, id);
        }

        function decodeUint8Array(node, graph) {
            requireArity(node, 3);
            requireUnusedGraphId(node[1], graph);
            const result = base64ToBytes(node[2]);
            registerGraphValue(node[1], result, graph);
            return result;
        }

        function allocateGraphId(graphContext, value) {
            const graph = requireGraphContext(graphContext);
            const id = graph.nextId++;
            if (!isGraphId(id)) throw new Error("Expression-value graph id limit reached");
            mapSet(graph.encodedIds, value, id);
            return id;
        }

        function registerGraphValue(id, value, graphContext) {
            const graph = requireGraphContext(graphContext);
            requireUnusedGraphId(id, graph);
            registerDecodedIdentity(value, id, graph);
            mapSet(graph.decodedValues, id, value);
        }

        function registerDecodedIdentity(value, id, graph) {
            if (!isObjectIdentity(value)) return;
            const existingId = mapGet(graph.decodedIdentities, value);
            if (existingId !== undefined && existingId !== id) {
                throw new Error("Decoded object is already registered under graph id " + existingId);
            }
            mapSet(graph.decodedIdentities, value, id);
        }

        function requireUnusedGraphId(id, graph) {
            requireGraphId(id);
            if (mapHas(graph.decodedValues, id)) throw new Error("Duplicate expression-value graph id " + id);
        }

        function enabled(tag) {
            return setHas(enabledTags, tag);
        }

        function isReservedTag(tag) {
            return setHas(knownTags, tag);
        }

        function requireGraphContext(graph) {
            if (!graph ||
                !(graph.encodedIds instanceof MapConstructor) ||
                !(graph.decodedValues instanceof MapConstructor) ||
                !(graph.decodedIdentities instanceof MapConstructor)) {
                throw new Error("A expression-value graph context is required");
            }
            return graph;
        }

        function isGraphId(id) {
            return isSafeInteger(id) && id > 0 && id <= maxGraphId;
        }

        function requireGraphId(id) {
            if (!isGraphId(id)) throw new Error("Invalid expression-value graph id " + id);
        }

        function isObjectIdentity(value) {
            return value !== null && (typeof value === "object" || typeof value === "function");
        }

        function requireArity(node, expected) {
            if (node.length !== expected) {
                throw new Error("Invalid arity for expression value " + node[0] + ": expected " + expected);
            }
        }

        function bytesToBase64(bytes, owner) {
            requireStableByteBuffer(bytes, owner || "Uint8Array");
            if (uint8ArrayToBase64 !== null) return uint8ArrayToBase64(bytes);
            let result = "";
            const length = typedArrayLength(bytes);
            for (let index = 0; index < length; index += 3) {
                const a = bytes[index];
                const hasB = index + 1 < length;
                const hasC = index + 2 < length;
                const b = hasB ? bytes[index + 1] : 0;
                const c = hasC ? bytes[index + 2] : 0;

                result += base64Alphabet[a >>> 2];
                result += base64Alphabet[((a & 3) << 4) | (b >>> 4)];
                result += hasB ? base64Alphabet[((b & 15) << 2) | (c >>> 6)] : "=";
                result += hasC ? base64Alphabet[c & 63] : "=";
            }
            return result;
        }

        function requireStableByteBuffer(bytes, owner) {
            let buffer;
            try {
                buffer = typedArrayBuffer(bytes);
            } catch (_) {
                throw new Error("Cannot encode a detached " + owner);
            }
            requireStableArrayBuffer(buffer, owner);
        }

        function requireStableArrayBuffer(buffer, owner) {
            if (getPrototypeOf(buffer) !== ArrayBufferConstructor.prototype) {
                throw new Error("Cannot encode a " + owner + " backed by a shared or non-standard ArrayBuffer");
            }
            try {
                new Uint8ArrayConstructor(buffer, 0, 0);
            } catch (_) {
                throw new Error("Cannot encode a detached " + owner);
            }
            let resizable = false;
            if (arrayBufferResizable !== null) {
                try {
                    resizable = arrayBufferResizable(buffer);
                } catch (_) {
                }
            }
            if (resizable) throw new Error("Cannot encode a " + owner + " backed by a resizable ArrayBuffer");
        }

        function base64ToBytes(value) {
            if (typeof value !== "string" || value.length % 4 !== 0) {
                throw new Error("Invalid canonical base64 payload");
            }
            if (uint8ArrayFromBase64 !== null) {
                // The built-in decoder accepts whitespace even in strict mode; the wire format does not.
                if (regexpTest(/[^A-Za-z0-9+/=]/, value)) throw new Error("Invalid canonical base64 payload");
                return uint8ArrayFromBase64(value, { alphabet: "base64", lastChunkHandling: "strict" });
            }

            let padding = 0;
            if (value[value.length - 1] === "=" && value[value.length - 2] === "=") padding = 2;
            else if (value[value.length - 1] === "=") padding = 1;

            const result = new Uint8ArrayConstructor(value.length / 4 * 3 - padding);
            let output = 0;
            for (let index = 0; index < value.length; index += 4) {
                const isLastGroup = index + 4 === value.length;
                const a = base64Digit(value[index]);
                const b = base64Digit(value[index + 1]);
                const c = isLastGroup && padding === 2 ? 0 : base64Digit(value[index + 2]);
                const d = isLastGroup && padding !== 0 ? 0 : base64Digit(value[index + 3]);

                if (
                    isLastGroup &&
                    (padding === 2 && (b & 15) !== 0 || padding === 1 && (c & 3) !== 0)
                ) {
                    throw new Error("Non-canonical base64 payload");
                }

                if (output < result.length) result[output++] = (a << 2) | (b >>> 4);
                if (output < result.length) result[output++] = ((b & 15) << 4) | (c >>> 2);
                if (output < result.length) result[output++] = ((c & 3) << 6) | d;
            }
            return result;
        }

        function base64Digit(character) {
            const code = stringCharCodeAt(character, 0);
            if (code >= 65 && code <= 90) return code - 65;
            if (code >= 97 && code <= 122) return code - 97 + 26;
            if (code >= 48 && code <= 57) return code - 48 + 52;
            if (code === 43) return 62;
            if (code === 47) return 63;
            throw new Error("Invalid canonical base64 payload");
        }

        return {
            allocateGraphId: allocateGraphId,
            base64ToBytes: base64ToBytes,
            bytesToBase64: bytesToBase64,
            createGraphContext: createGraphContext,
            decode: decode,
            enabled: enabled,
            encode: encode,
            encodeGraphReference: encodeGraphReference,
            escapeJavascriptLineSeparators: escapeJavascriptLineSeparators,
            isReservedTag: isReservedTag,
            notHandled: notHandled,
            registerGraphValue: registerGraphValue,
            requireGraphContext: requireGraphContext,
            requireStableArrayBuffer: requireStableArrayBuffer,
            requireStableByteBuffer: requireStableByteBuffer,
            requireUnusedGraphId: requireUnusedGraphId,
            stringifyJson: stringifyJson,
            tags: tags,
            typedArrayBuffer: typedArrayBuffer,
        };
    })
    """.trimIndent()

/**
 * JavaScript factory for the complete expression-value wire codec.
 *
 * It adds the Playwright-compatible structured built-ins, consumer codecs, reference selection, the reference-value
 * side table, and the expression wire around the small shared core.
 */
internal val expressionValueCodecFactorySource: String =
    """
    (function (configuration) {
        "use strict";

        const coreCodec = (
            function (coreCodec) {
                "use strict";

                const notHandled = coreCodec.notHandled;
                const tags = coreCodec.tags;
                tags.object = ${ExpressionValueTag.OBJECT.toJson()};
                tags.array = ${ExpressionValueTag.ARRAY.toJson()};
                tags.sparseArray = ${ExpressionValueTag.SPARSE_ARRAY.toJson()};
                tags.date = ${ExpressionValueTag.DATE.toJson()};
                tags.regexp = ${ExpressionValueTag.REGEXP.toJson()};
                tags.error = ${ExpressionValueTag.ERROR.toJson()};
                tags.arrayBuffer = ${ExpressionValueTag.ARRAY_BUFFER.toJson()};
                tags.int8Array = ${ExpressionValueTag.INT8_ARRAY.toJson()};
                tags.uint8ClampedArray = ${ExpressionValueTag.UINT8_CLAMPED_ARRAY.toJson()};
                tags.int16Array = ${ExpressionValueTag.INT16_ARRAY.toJson()};
                tags.uint16Array = ${ExpressionValueTag.UINT16_ARRAY.toJson()};
                tags.int32Array = ${ExpressionValueTag.INT32_ARRAY.toJson()};
                tags.uint32Array = ${ExpressionValueTag.UINT32_ARRAY.toJson()};
                tags.float32Array = ${ExpressionValueTag.FLOAT32_ARRAY.toJson()};
                tags.float64Array = ${ExpressionValueTag.FLOAT64_ARRAY.toJson()};
                tags.bigint64Array = ${ExpressionValueTag.BIGINT64_ARRAY.toJson()};
                tags.biguint64Array = ${ExpressionValueTag.BIGUINT64_ARRAY.toJson()};
                const enabled = coreCodec.enabled;
                const allocateGraphId = coreCodec.allocateGraphId;
                const base64ToBytes = coreCodec.base64ToBytes;
                const bytesToBase64 = coreCodec.bytesToBase64;
                const createGraphContext = coreCodec.createGraphContext;
                const encodeGraphReference = coreCodec.encodeGraphReference;
                const escapeJavascriptLineSeparators = coreCodec.escapeJavascriptLineSeparators;
                const isReservedTag = coreCodec.isReservedTag;
                const registerGraphValue = coreCodec.registerGraphValue;
                const requireGraphContext = coreCodec.requireGraphContext;
                const requireStableArrayBuffer = coreCodec.requireStableArrayBuffer;
                const requireStableByteBuffer = coreCodec.requireStableByteBuffer;
                const requireUnusedGraphId = coreCodec.requireUnusedGraphId;
                const stringifyJson = coreCodec.stringifyJson;
                const typedArrayDefinitions = [
                    [tags.int8Array, "Int8Array"],
                    [tags.uint8ClampedArray, "Uint8ClampedArray"],
                    [tags.int16Array, "Int16Array"],
                    [tags.uint16Array, "Uint16Array"],
                    [tags.int32Array, "Int32Array"],
                    [tags.uint32Array, "Uint32Array"],
                    [tags.float32Array, "Float32Array"],
                    [tags.float64Array, "Float64Array"],
                    [tags.bigint64Array, "BigInt64Array"],
                    [tags.biguint64Array, "BigUint64Array"],
                ];

                const objectPrototype = Object.prototype;
                const arrayPrototype = Array.prototype;
                const getPrototypeOf = Object.getPrototypeOf;
                const getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
                const defineProperty = Object.defineProperty;
                const ownKeys = Reflect.ownKeys;
                const isArray = Array.isArray;
                const ArrayConstructor = globalThis.Array;
                const ArrayBufferConstructor = globalThis.ArrayBuffer;
                const DateConstructor = globalThis.Date;
                const ErrorConstructor = globalThis.Error;
                const MapConstructor = globalThis.Map;
                const NumberConstructor = globalThis.Number;
                const RegExpConstructor = globalThis.RegExp;
                const SetConstructor = globalThis.Set;
                const StringConstructor = globalThis.String;
                const Uint8ArrayConstructor = globalThis.Uint8Array;
                const bindCall = Function.prototype.call.bind(Function.prototype.call);
                const arraySort = bindCall.bind(undefined, ArrayConstructor.prototype.sort);
                const mapGet = bindCall.bind(undefined, MapConstructor.prototype.get);
                const mapSet = bindCall.bind(undefined, MapConstructor.prototype.set);
                const setAdd = bindCall.bind(undefined, SetConstructor.prototype.add);
                const setHas = bindCall.bind(undefined, SetConstructor.prototype.has);
                const typedArrayPrototype = getPrototypeOf(Uint8ArrayConstructor.prototype);
                const typedArrayName =
                    bindCall.bind(
                        undefined,
                        getOwnPropertyDescriptor(typedArrayPrototype, Symbol.toStringTag).get,
                    );
                const typedArrayBuffer = coreCodec.typedArrayBuffer;
                const typedArrayByteLength =
                    bindCall.bind(undefined, getOwnPropertyDescriptor(typedArrayPrototype, "byteLength").get);
                const typedArrayByteOffset =
                    bindCall.bind(undefined, getOwnPropertyDescriptor(typedArrayPrototype, "byteOffset").get);
                const dateToISOString = bindCall.bind(undefined, DateConstructor.prototype.toISOString);
                const regexpSourceGetter =
                    bindCall.bind(undefined, getOwnPropertyDescriptor(RegExpConstructor.prototype, "source").get);
                const regexpFlagGetters = [];
                const regexpFlagDefinitions = [
                    ["hasIndices", "d"],
                    ["global", "g"],
                    ["ignoreCase", "i"],
                    ["multiline", "m"],
                    ["dotAll", "s"],
                    ["unicode", "u"],
                    ["unicodeSets", "v"],
                    ["sticky", "y"],
                ];
                for (let index = 0; index < regexpFlagDefinitions.length; index++) {
                    const definition = regexpFlagDefinitions[index];
                    const descriptor = getOwnPropertyDescriptor(RegExpConstructor.prototype, definition[0]);
                    if (descriptor && typeof descriptor.get === "function") {
                        regexpFlagGetters[regexpFlagGetters.length] = [
                            definition[1],
                            bindCall.bind(undefined, descriptor.get),
                        ];
                    }
                }
                const isInteger = Number.isInteger;
                const stringIndexOf = bindCall.bind(undefined, String.prototype.indexOf);
                const compareFirstNumericField = (a, b) => a[0] - b[0];

                const typedArrayByTag = new MapConstructor();
                const typedArrayByName = new MapConstructor();
                for (let index = 0; index < typedArrayDefinitions.length; index++) {
                    const definition = typedArrayDefinitions[index];
                    const constructor = globalThis[definition[1]];
                    if (typeof constructor !== "function") continue;
                    const codec = {
                        tag: definition[0],
                        name: definition[1],
                        constructor: constructor,
                        bytesPerElement: constructor.BYTES_PER_ELEMENT,
                    };
                    mapSet(typedArrayByTag, codec.tag, codec);
                    mapSet(typedArrayByName, codec.name, codec);
                }

                const standardErrorPrototypes = new SetConstructor();
                const standardErrorNames = [
                    "Error", "EvalError", "RangeError", "ReferenceError", "SyntaxError", "TypeError", "URIError", "AggregateError",
                ];
                for (let index = 0; index < standardErrorNames.length; index++) {
                    const constructor = globalThis[standardErrorNames[index]];
                    if (typeof constructor === "function") setAdd(standardErrorPrototypes, constructor.prototype);
                }

                function encode(value, graphContext, encodeChild) {
                    const coreValue = coreCodec.encode(value, graphContext, encodeChild);
                    if (coreValue !== notHandled) return coreValue;

                    if (value === null) return notHandled;
                    const valueType = typeof value;
                    if (valueType !== "object" && valueType !== "function") return notHandled;

                    const graph = requireGraphContext(graphContext);
                    const prototype = getPrototypeOf(value);
                    const typedArray = mapGet(typedArrayByName, typedArrayName(value));
                    if (typedArray && enabled(typedArray.tag)) return encodeTypedArray(value, graph, typedArray);
                    if (prototype === ArrayBufferConstructor.prototype && enabled(tags.arrayBuffer)) {
                        return encodeArrayBuffer(value, graph);
                    }
                    if (prototype === DateConstructor.prototype && enabled(tags.date)) return encodeDate(value, graph);
                    if (prototype === RegExpConstructor.prototype && enabled(tags.regexp)) return encodeRegExp(value, graph);
                    if (setHas(standardErrorPrototypes, prototype) && enabled(tags.error)) return encodeError(value, graph);
                    if (isArray(value) && (enabled(tags.array) || enabled(tags.sparseArray))) {
                        return encodeArray(value, graph, requireChildCodec(encodeChild, "encode"));
                    }
                    if (prototype === objectPrototype && enabled(tags.object)) {
                        return encodeObject(value, graph, requireChildCodec(encodeChild, "encode"));
                    }
                    return notHandled;
                }

                function encodeTypedArray(value, graph, codec) {
                    requireStableByteBuffer(value, codec.name);
                    const byteLength = typedArrayByteLength(value);
                    const bytes = new Uint8ArrayConstructor(typedArrayBuffer(value), typedArrayByteOffset(value), byteLength);
                    const base64 = bytesToBase64(bytes, codec.name);
                    const id = allocateGraphId(graph, value);
                    return [codec.tag, id, base64];
                }

                function encodeArrayBuffer(value, graph) {
                    requireStableArrayBuffer(value, "ArrayBuffer");
                    const base64 = bytesToBase64(new Uint8ArrayConstructor(value), "ArrayBuffer");
                    const id = allocateGraphId(graph, value);
                    return [tags.arrayBuffer, id, base64];
                }

                function encodeDate(value, graph) {
                    let isoString;
                    try {
                        isoString = dateToISOString(value);
                    } catch (_) {
                        throw new Error("Invalid Date is not serializable");
                    }
                    const id = allocateGraphId(graph, value);
                    return [tags.date, id, isoString];
                }

                function encodeRegExp(value, graph) {
                    const source = regexpSourceGetter(value);
                    let flags = "";
                    for (let index = 0; index < regexpFlagGetters.length; index++) {
                        const definition = regexpFlagGetters[index];
                        if (definition[1](value)) flags += definition[0];
                    }
                    const id = allocateGraphId(graph, value);
                    return [tags.regexp, id, source, flags];
                }

                function encodeError(value, graph) {
                    const name = value.name;
                    const message = value.message;
                    const observedStack = value.stack;
                    if (typeof name !== "string" || typeof message !== "string") {
                        throw new Error("Error name and message must be strings");
                    }
                    const header = name + ": " + message;
                    const stack =
                        typeof observedStack === "string" && stringIndexOf(observedStack, header) === 0
                            ? observedStack
                            : header + "\n" + (typeof observedStack === "string" ? observedStack : "");
                    const id = allocateGraphId(graph, value);
                    return [tags.error, id, name, message, stack];
                }

                function encodeArray(value, graph, encodeChild) {
                    if (getPrototypeOf(value) !== arrayPrototype) throw new Error("Array with a modified prototype is not serializable");
                    const lengthDescriptor = getOwnPropertyDescriptor(value, "length");
                    if (!lengthDescriptor ||
                        lengthDescriptor.writable !== true ||
                        lengthDescriptor.enumerable !== false ||
                        lengthDescriptor.configurable !== false ||
                        !isCanonicalArrayLength(lengthDescriptor.value)) {
                        throw new Error("Array has a non-standard length descriptor");
                    }

                    const length = lengthDescriptor.value;
                    const descriptors = [];
                    const keys = ownKeys(value);
                    for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                        const key = keys[keyIndex];
                        if (key === "length") continue;
                        if (typeof key !== "string" || !isCanonicalArrayIndex(key, length)) {
                            throw new Error("Array has an unsupported own property");
                        }
                        descriptors[descriptors.length] = [NumberConstructor(key), requirePlainDataDescriptor(value, key)];
                    }
                    arraySort(descriptors, compareFirstNumericField);

                    const dense = descriptors.length === length;
                    const tag = dense ? tags.array : tags.sparseArray;
                    if (!enabled(tag)) return notHandled;

                    const id = allocateGraphId(graph, value);
                    if (dense) {
                        const payload = new ArrayConstructor(length);
                        for (let entryIndex = 0; entryIndex < descriptors.length; entryIndex++) {
                            payload[entryIndex] = encodeChild(descriptors[entryIndex][1].value);
                        }
                        return [tag, id, payload];
                    }

                    const result = new ArrayConstructor(3 + descriptors.length * 2);
                    result[0] = tag;
                    result[1] = id;
                    result[2] = length;
                    for (let entryIndex = 0; entryIndex < descriptors.length; entryIndex++) {
                        const entry = descriptors[entryIndex];
                        result[3 + entryIndex * 2] = entry[0];
                        result[4 + entryIndex * 2] = encodeChild(entry[1].value);
                    }
                    return result;
                }

                function encodeObject(value, graph, encodeChild) {
                    const descriptors = [];
                    const keys = ownKeys(value);
                    for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                        const key = keys[keyIndex];
                        if (typeof key !== "string") throw new Error("Object has a Symbol property");
                        descriptors[descriptors.length] = [key, requirePlainDataDescriptor(value, key)];
                    }

                    const id = allocateGraphId(graph, value);
                    const payload = {};
                    for (let entryIndex = 0; entryIndex < descriptors.length; entryIndex++) {
                        const entry = descriptors[entryIndex];
                        defineProperty(payload, entry[0], {
                            value: encodeChild(entry[1].value),
                            writable: true,
                            enumerable: true,
                            configurable: true,
                        });
                    }
                    return [tags.object, id, payload];
                }

                function decode(node, graphContext, decodeChild) {
                    const coreValue = coreCodec.decode(node, graphContext, decodeChild);
                    if (coreValue !== notHandled) return coreValue;

                    if (node === null || typeof node === "boolean" || typeof node === "number" || typeof node === "string") {
                        return notHandled;
                    }
                    if (!isArray(node) || typeof node[0] !== "string") throw new Error("Expected an expression value");
                    if (!enabled(node[0])) return notHandled;

                    const graph = graphContext;
                    switch (node[0]) {
                        case tags.object:
                            return decodeObject(node, requireGraphContext(graph), requireChildCodec(decodeChild, "decode"));
                        case tags.array:
                            return decodeDenseArray(node, requireGraphContext(graph), requireChildCodec(decodeChild, "decode"));
                        case tags.sparseArray:
                            return decodeSparseArray(node, requireGraphContext(graph), requireChildCodec(decodeChild, "decode"));
                        case tags.date:
                            return decodeDate(node, requireGraphContext(graph));
                        case tags.regexp:
                            return decodeRegExp(node, requireGraphContext(graph));
                        case tags.error:
                            return decodeError(node, requireGraphContext(graph));
                        case tags.arrayBuffer:
                            return decodeArrayBuffer(node, requireGraphContext(graph));
                        default: {
                            const typedArray = mapGet(typedArrayByTag, node[0]);
                            return typedArray === undefined
                                ? notHandled
                                : decodeTypedArray(node, requireGraphContext(graph), typedArray);
                        }
                    }
                }

                function decodeObject(node, graph, decodeChild) {
                    requireArity(node, 3);
                    const result = node[2];
                    if (result === null || typeof result !== "object" || isArray(result) || getPrototypeOf(result) !== objectPrototype) {
                        throw new Error("Invalid Object payload");
                    }
                    requireUnusedGraphId(node[1], graph);
                    registerGraphValue(node[1], result, graph);
                    const keys = ownKeys(result);
                    for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                        const key = keys[keyIndex];
                        if (typeof key !== "string") throw new Error("Object payload has a Symbol property");
                        const descriptor = requirePlainDataDescriptor(result, key);
                        defineProperty(result, key, {
                            value: decodeChild(descriptor.value),
                            writable: true,
                            enumerable: true,
                            configurable: true,
                        });
                    }
                    return result;
                }

                function decodeDenseArray(node, graph, decodeChild) {
                    requireArity(node, 3);
                    const result = node[2];
                    if (!isArray(result) || getPrototypeOf(result) !== arrayPrototype) throw new Error("Invalid dense Array payload");
                    const lengthDescriptor = getOwnPropertyDescriptor(result, "length");
                    if (!lengthDescriptor ||
                        lengthDescriptor.writable !== true ||
                        lengthDescriptor.enumerable !== false ||
                        lengthDescriptor.configurable !== false ||
                        !isCanonicalArrayLength(lengthDescriptor.value)) {
                        throw new Error("Dense Array has a non-standard length descriptor");
                    }
                    const length = lengthDescriptor.value;
                    const keys = ownKeys(result);
                    if (keys.length !== length + 1) throw new Error("Dense Array payload must not contain holes or extra properties");
                    for (let index = 0; index < length; index++) {
                        const key = StringConstructor(index);
                        if (!getOwnPropertyDescriptor(result, key)) throw new Error("Dense Array payload contains a hole");
                        requirePlainDataDescriptor(result, key);
                    }
                    requireUnusedGraphId(node[1], graph);
                    registerGraphValue(node[1], result, graph);
                    for (let index = 0; index < length; index++) {
                        const key = StringConstructor(index);
                        const descriptor = requirePlainDataDescriptor(result, key);
                        defineProperty(result, key, {
                            value: decodeChild(descriptor.value),
                            writable: true,
                            enumerable: true,
                            configurable: true,
                        });
                    }
                    return result;
                }

                function decodeSparseArray(node, graph, decodeChild) {
                    if (node.length < 3 || node.length % 2 !== 1) throw new Error("Invalid sparse Array arity");
                    const length = node[2];
                    if (!isCanonicalArrayLength(length)) throw new Error("Invalid sparse Array length");
                    if ((node.length - 3) / 2 >= length) throw new Error("Sparse Array must contain at least one hole");
                    requireUnusedGraphId(node[1], graph);
                    const result = new ArrayConstructor(length);
                    registerGraphValue(node[1], result, graph);
                    let previousIndex = -1;
                    for (let nodeIndex = 3; nodeIndex < node.length; nodeIndex += 2) {
                        const index = node[nodeIndex];
                        if (!isInteger(index) || index <= previousIndex || index < 0 || index >= length) {
                            throw new Error("Invalid or unordered sparse Array index");
                        }
                        previousIndex = index;
                        defineProperty(result, StringConstructor(index), {
                            value: decodeChild(node[nodeIndex + 1]),
                            writable: true,
                            enumerable: true,
                            configurable: true,
                        });
                    }
                    return result;
                }

                function decodeDate(node, graph) {
                    requireArity(node, 3);
                    requireUnusedGraphId(node[1], graph);
                    if (typeof node[2] !== "string") throw new Error("Invalid Date payload");
                    const result = new DateConstructor(node[2]);
                    let isoString;
                    try {
                        isoString = dateToISOString(result);
                    } catch (_) {
                        throw new Error("Invalid Date payload");
                    }
                    if (isoString !== node[2]) throw new Error("Non-canonical Date payload");
                    registerGraphValue(node[1], result, graph);
                    return result;
                }

                function decodeRegExp(node, graph) {
                    requireArity(node, 4);
                    requireUnusedGraphId(node[1], graph);
                    if (typeof node[2] !== "string" || typeof node[3] !== "string") throw new Error("Invalid RegExp payload");
                    const result = new RegExpConstructor(node[2], node[3]);
                    registerGraphValue(node[1], result, graph);
                    return result;
                }

                function decodeError(node, graph) {
                    requireArity(node, 5);
                    requireUnusedGraphId(node[1], graph);
                    if (typeof node[2] !== "string" || typeof node[3] !== "string" || typeof node[4] !== "string") {
                        throw new Error("Invalid Error payload");
                    }
                    const result = new ErrorConstructor(node[3]);
                    result.name = node[2];
                    result.stack = node[4];
                    registerGraphValue(node[1], result, graph);
                    return result;
                }

                function decodeArrayBuffer(node, graph) {
                    requireArity(node, 3);
                    requireUnusedGraphId(node[1], graph);
                    const bytes = base64ToBytes(node[2]);
                    const result = typedArrayBuffer(bytes);
                    registerGraphValue(node[1], result, graph);
                    return result;
                }

                function decodeTypedArray(node, graph, codec) {
                    requireArity(node, 3);
                    requireUnusedGraphId(node[1], graph);
                    const bytes = base64ToBytes(node[2]);
                    if (typedArrayByteLength(bytes) % codec.bytesPerElement !== 0) {
                        throw new Error("Typed-array byte length is not aligned for " + codec.tag);
                    }
                    const result = new codec.constructor(typedArrayBuffer(bytes));
                    registerGraphValue(node[1], result, graph);
                    return result;
                }

                function requireChildCodec(codec, operation) {
                    if (typeof codec !== "function") throw new Error("A expression-value child " + operation + " function is required");
                    return codec;
                }

                function requireArity(node, expected) {
                    if (node.length !== expected) {
                        throw new Error("Invalid arity for expression value " + node[0] + ": expected " + expected);
                    }
                }

                function requirePlainDataDescriptor(value, key) {
                    const descriptor = getOwnPropertyDescriptor(value, key);
                    if (!descriptor || !("value" in descriptor) ||
                        descriptor.writable !== true ||
                        descriptor.enumerable !== true ||
                        descriptor.configurable !== true) {
                        throw new Error("Property " + StringConstructor(key) + " is not a normal data property");
                    }
                    return descriptor;
                }

                function isCanonicalArrayLength(value) {
                    return isInteger(value) && value >= 0 && value <= 4294967295;
                }

                function isCanonicalArrayIndex(key, length) {
                    if (key === "") return false;
                    const index = NumberConstructor(key);
                    return isInteger(index) && index >= 0 && index < length && StringConstructor(index) === key;
                }

                return {
                    allocateGraphId: allocateGraphId,
                    base64ToBytes: base64ToBytes,
                    bytesToBase64: bytesToBase64,
                    createGraphContext: createGraphContext,
                    decode: decode,
                    encode: encode,
                    encodeGraphReference: encodeGraphReference,
                    escapeJavascriptLineSeparators: escapeJavascriptLineSeparators,
                    isReservedTag: isReservedTag,
                    notHandled: notHandled,
                    registerGraphValue: registerGraphValue,
                    requireUnusedGraphId: requireUnusedGraphId,
                    stringifyJson: stringifyJson,
                    tags: tags,
                };
            }
        )(
            ($expressionValueCoreCodecFactorySource)(configuration, [
                ${ExpressionValueTag.OBJECT.toJson()},
                ${ExpressionValueTag.ARRAY.toJson()},
                ${ExpressionValueTag.SPARSE_ARRAY.toJson()},
                ${ExpressionValueTag.DATE.toJson()},
                ${ExpressionValueTag.REGEXP.toJson()},
                ${ExpressionValueTag.ERROR.toJson()},
                ${ExpressionValueTag.ARRAY_BUFFER.toJson()},
                ${ExpressionValueTag.INT8_ARRAY.toJson()},
                ${ExpressionValueTag.UINT8_CLAMPED_ARRAY.toJson()},
                ${ExpressionValueTag.INT16_ARRAY.toJson()},
                ${ExpressionValueTag.UINT16_ARRAY.toJson()},
                ${ExpressionValueTag.INT32_ARRAY.toJson()},
                ${ExpressionValueTag.UINT32_ARRAY.toJson()},
                ${ExpressionValueTag.FLOAT32_ARRAY.toJson()},
                ${ExpressionValueTag.FLOAT64_ARRAY.toJson()},
                ${ExpressionValueTag.BIGINT64_ARRAY.toJson()},
                ${ExpressionValueTag.BIGUINT64_ARRAY.toJson()},
            ]),
        );
        const objectPrototype = Object.prototype;
        const arrayPrototype = Array.prototype;
        const freeze = Object.freeze;
        const getPrototypeOf = Object.getPrototypeOf;
        const getOwnPropertyDescriptor = Object.getOwnPropertyDescriptor;
        const defineProperty = Object.defineProperty;
        const deleteProperty = Reflect.deleteProperty;
        const ownKeys = Reflect.ownKeys;
        const isArray = Array.isArray;
        const ArrayConstructor = globalThis.Array;
        const MapConstructor = globalThis.Map;
        const SetConstructor = globalThis.Set;
        const StringConstructor = globalThis.String;
        const bindCall = Function.prototype.call.bind(Function.prototype.call);
        const arrayConcat = bindCall.bind(undefined, ArrayConstructor.prototype.concat);
        const arraySlice = bindCall.bind(undefined, ArrayConstructor.prototype.slice);
        const mapGet = bindCall.bind(undefined, MapConstructor.prototype.get);
        const mapHas = bindCall.bind(undefined, MapConstructor.prototype.has);
        const mapSet = bindCall.bind(undefined, MapConstructor.prototype.set);
        const setAdd = bindCall.bind(undefined, SetConstructor.prototype.add);
        const setDelete = bindCall.bind(undefined, SetConstructor.prototype.delete);
        const setHas = bindCall.bind(undefined, SetConstructor.prototype.has);
        const setSize = bindCall.bind(undefined, getOwnPropertyDescriptor(SetConstructor.prototype, "size").get);
        const stringSlice = bindCall.bind(undefined, StringConstructor.prototype.slice);
        const isInteger = Number.isInteger;
        const isFiniteNumber = Number.isFinite;
        const isSameValue = Object.is;
        const jsonParse = JSON.parse;
        const jsonStringify = JSON.stringify;
        const referenceValueTag = ${ExpressionValueTag.REFERENCE_VALUE.toJson()};
        const wireKeyPrefix = "\u0000";
        const rootPosition = freeze({ isRoot: true });
        const nestedPosition = freeze({ isRoot: false });

        function createEncoder(configuration) {
            const registry = createSourceCodecRegistry(configuration);
            return function (value) {
                return encodeWire(
                    value,
                    registry.codecs,
                    registry.referenceCodec,
                    registry.referenceCodecIndex,
                );
            };
        }

        function createDecoder(configuration) {
            const codecs = createDestinationCodecRegistry(configuration);
            return function (wire, resolvedReferenceValues, materialized) {
                return decodeWire(wire, resolvedReferenceValues, codecs, materialized === true);
            };
        }

        function encodeWire(value, codecs, referenceCodec, referenceCodecIndex) {
            const hasReferenceCodec = referenceCodec !== undefined;
            if (hasReferenceCodec) {
                if (typeof referenceCodec !== "function") throw new Error("Reference codec must be a function");
                if (!isInteger(referenceCodecIndex) ||
                    referenceCodecIndex < 0 || referenceCodecIndex > codecs.length) {
                    throw new Error("Reference codec index is out of range");
                }
            }
            const graph = coreCodec.createGraphContext();
            graph.referenceSlots = new MapConstructor();
            const referenceValues = [];
            const wireNode = encodeNode(
                value,
                rootPosition,
                graph,
                codecs,
                referenceCodec,
                referenceCodecIndex,
                hasReferenceCodec,
                referenceValues,
            );
            const wire = stringifyExpression(wireNode);
            return {
                wire: coreCodec.escapeJavascriptLineSeparators(wire),
                referenceValues: referenceValues,
            };
        }

        function encodeNode(
            value,
            position,
            graph,
            codecs,
            referenceCodec,
            referenceCodecIndex,
            hasReferenceCodec,
            referenceValues,
        ) {
            const valueType = typeof value;
            if (value === null || valueType !== "object" && valueType !== "function") {
                const encoded = coreCodec.encode(value, graph);
                if (encoded !== coreCodec.notHandled) return encoded;
                throw new Error("Unsupported JavaScript value type " + valueType);
            }

            const referenceSlot = mapGet(graph.referenceSlots, value);
            if (referenceSlot !== undefined) return [referenceValueTag, referenceSlot];
            const graphReference = coreCodec.encodeGraphReference(value, graph);
            if (graphReference !== coreCodec.notHandled) return graphReference;

            for (let codecIndex = 0; codecIndex <= codecs.length; codecIndex++) {
                if (hasReferenceCodec && codecIndex === referenceCodecIndex &&
                    bindCall(referenceCodec, undefined, value, position)) {
                    const slot = referenceValues.length;
                    mapSet(graph.referenceSlots, value, slot);
                    referenceValues[slot] = value;
                    return [referenceValueTag, slot];
                }

                if (codecIndex === codecs.length) continue;
                const codec = codecs[codecIndex];
                if (!bindCall(codec.matches, codec.receiver, value, position)) continue;
                const id = coreCodec.allocateGraphId(graph, value);
                const encodeContext = {
                    position: position,
                    encodeChild: child => encodeNode(
                        child,
                        nestedPosition,
                        graph,
                        codecs,
                        referenceCodec,
                        referenceCodecIndex,
                        hasReferenceCodec,
                        referenceValues,
                    ),
                };
                const fields = bindCall(codec.encode, codec.receiver, value, encodeContext);
                if (!isArray(fields)) throw new Error("Consumer codec " + codec.tag + " must return protocol fields");
                validateProtocolFields(fields, graph, false);
                return arrayConcat([codec.tag, id], fields);
            }

            const encoded = coreCodec.encode(
                value,
                graph,
                child => encodeNode(
                    child,
                    nestedPosition,
                    graph,
                    codecs,
                    referenceCodec,
                    referenceCodecIndex,
                    hasReferenceCodec,
                    referenceValues,
                ),
            );
            if (encoded !== coreCodec.notHandled) return encoded;
            throw new Error("Unsupported object or function");
        }

        function decodeWire(wire, resolvedReferenceValues, codecs, materialized) {
            if (!materialized && typeof wire !== "string") throw new Error("Expression-value wire must be a string");
            if (!isArray(resolvedReferenceValues)) throw new Error("Resolved reference values must be an array");
            const graph = coreCodec.createGraphContext();
            graph.usedReferenceSlots = new SetConstructor();
            graph.normalizedWireRecords = new SetConstructor();
            const root = materialized ? wire : jsonParse(wire);
            const result = decodeNode(root, graph, resolvedReferenceValues, codecs);
            if (setSize(graph.usedReferenceSlots) !== resolvedReferenceValues.length) {
                throw new Error("Resolved reference-value table contains an unreferenced slot");
            }
            return result;
        }

        function decodeNode(node, graph, resolvedReferenceValues, codecs) {
            if (isArray(node) && node[0] === coreCodec.tags.object && node.length === 3) {
                normalizeWireRecord(node[2], graph);
            }
            const coreValue = coreCodec.decode(
                node,
                graph,
                child => decodeNode(child, graph, resolvedReferenceValues, codecs),
            );
            if (coreValue !== coreCodec.notHandled) return coreValue;

            if (!isArray(node) || typeof node[0] !== "string") throw new Error("Expected an expression value");

            if (node[0] === referenceValueTag) {
                requireArity(node, 2);
                const slot = node[1];
                if (!isInteger(slot) || slot < 0 || slot >= resolvedReferenceValues.length) {
                    throw new Error("Invalid reference-value slot " + slot);
                }
                setAdd(graph.usedReferenceSlots, slot);
                return resolvedReferenceValues[slot];
            }

            const consumerCodec = mapGet(codecs, node[0]);
            if (consumerCodec) return decodeConsumerNode(node, graph, resolvedReferenceValues, codecs, consumerCodec);
            throw new Error("Unknown expression-value tag " + node[0]);
        }

        function stringifyExpression(value) {
            return stringifyExpressionValue(value, new SetConstructor());
        }

        function stringifyExpressionValue(value, active) {
            if (value === null || typeof value === "boolean" || typeof value === "string") {
                return jsonStringify(value);
            }
            if (typeof value === "number") {
                if (!isFiniteNumber(value)) throw new Error("Expression metadata number must be finite");
                return jsonStringify(value);
            }
            if (typeof value !== "object") throw new Error("Expression field is not serializable");
            if (setHas(active, value)) throw new Error("Expression metadata must not contain cycles");
            setAdd(active, value);
            let result;
            if (isArray(value)) {
                result = "[";
                for (let index = 0; index < value.length; index++) {
                    if (index !== 0) result += ",";
                    const descriptor = requirePlainDataDescriptor(value, StringConstructor(index));
                    result += stringifyExpressionValue(descriptor.value, active);
                }
                result += "]";
            } else {
                if (getPrototypeOf(value) !== objectPrototype) throw new Error("Expression record must be plain");
                result = "{";
                const keys = ownKeys(value);
                for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                    const key = keys[keyIndex];
                    if (typeof key !== "string") throw new Error("Expression record has a Symbol key");
                    if (keyIndex !== 0) result += ",";
                    const descriptor = requirePlainDataDescriptor(value, key);
                    result += jsonStringify(encodeWireKey(key)) + ":";
                    result += stringifyExpressionValue(descriptor.value, active);
                }
                result += "}";
            }
            setDelete(active, value);
            return result;
        }

        function encodeWireKey(key) {
            return key === "__proto__" || key.length > 0 && key[0] === wireKeyPrefix
                ? wireKeyPrefix + key
                : key;
        }

        function decodeWireKey(key) {
            if (key === "__proto__") throw new Error("Raw __proto__ expression key is not allowed");
            if (key.length === 0 || key[0] !== wireKeyPrefix) return key;
            if (key === wireKeyPrefix + "__proto__") return "__proto__";
            if (key.length > 1 && key[1] === wireKeyPrefix) return stringSlice(key, 1);
            throw new Error("Non-canonical NUL-prefixed expression key");
        }

        function normalizeWireRecord(value, graph) {
            if (value === null || typeof value !== "object" || isArray(value) || getPrototypeOf(value) !== objectPrototype) {
                return;
            }
            if (setHas(graph.normalizedWireRecords, value)) return;
            setAdd(graph.normalizedWireRecords, value);

            const keys = ownKeys(value);
            let requiresNormalization = false;
            for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                const key = keys[keyIndex];
                if (typeof key !== "string") throw new Error("Expression record has a Symbol key");
                if (decodeWireKey(key) !== key) {
                    requiresNormalization = true;
                    break;
                }
            }
            if (!requiresNormalization) return;

            const entries = new ArrayConstructor(keys.length);
            const decodedKeys = new SetConstructor();
            for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                const key = keys[keyIndex];
                if (typeof key !== "string") throw new Error("Expression record has a Symbol key");
                const decodedKey = decodeWireKey(key);
                if (setHas(decodedKeys, decodedKey)) throw new Error("Duplicate decoded expression key " + decodedKey);
                setAdd(decodedKeys, decodedKey);
                entries[keyIndex] = [decodedKey, requirePlainDataDescriptor(value, key)];
            }
            for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                if (!deleteProperty(value, keys[keyIndex])) throw new Error("Cannot normalize expression record key");
            }
            for (let entryIndex = 0; entryIndex < entries.length; entryIndex++) {
                const entry = entries[entryIndex];
                defineProperty(value, entry[0], entry[1]);
            }
        }

        function decodeConsumerNode(node, graph, resolvedReferenceValues, codecs, codec) {
            if (node.length < 2) throw new Error("Invalid consumer node " + node[0]);
            const id = node[1];
            coreCodec.requireUnusedGraphId(id, graph);
            const fields = arraySlice(node, 2);
            validateProtocolFields(fields, graph, true);

            const blockedContext = {
                decodeChild: () => {
                    throw new Error("Consumer allocate must not decode child nodes");
                },
            };
            const result = bindCall(codec.allocate, codec.receiver, fields, blockedContext);
            if (!isObjectIdentity(result)) throw new Error("Consumer allocate must return an object or function");
            coreCodec.registerGraphValue(id, result, graph);

            const decodeContext = {
                decodeChild: child => decodeNode(child, graph, resolvedReferenceValues, codecs),
            };
            bindCall(codec.populate, codec.receiver, result, fields, decodeContext);
            return result;
        }

        function createSourceCodecRegistry(configuration) {
            if (configuration === undefined) {
                return { codecs: [], referenceCodec: undefined, referenceCodecIndex: undefined };
            }
            if (!isArray(configuration)) throw new Error("Source codec configuration must be an array");
            const tags = new SetConstructor();
            const codecs = [];
            let referenceCodec;
            let referenceCodecIndex;
            for (let codecIndex = 0; codecIndex < configuration.length; codecIndex++) {
                const codec = configuration[codecIndex];
                if (typeof codec === "function") {
                    if (referenceCodec !== undefined) {
                        throw new Error("Source codec configuration contains more than one reference codec");
                    }
                    referenceCodec = codec;
                    referenceCodecIndex = codecs.length;
                    continue;
                }
                const tag = codec && codec.tag;
                const matches = codec && codec.matches;
                const encode = codec && codec.encode;
                requireConsumerTag(tag);
                if (typeof matches !== "function" || typeof encode !== "function") {
                    throw new Error("Source codec must provide matches and encode");
                }
                if (setHas(tags, tag)) throw new Error("Duplicate source codec tag " + tag);
                setAdd(tags, tag);
                codecs[codecs.length] = { tag: tag, matches: matches, encode: encode, receiver: codec };
            }
            return {
                codecs: codecs,
                referenceCodec: referenceCodec,
                referenceCodecIndex: referenceCodecIndex,
            };
        }

        function createDestinationCodecRegistry(configuration) {
            if (configuration === undefined) return new MapConstructor();
            if (!isArray(configuration)) throw new Error("Destination codec configuration must be an array");
            const result = new MapConstructor();
            for (let codecIndex = 0; codecIndex < configuration.length; codecIndex++) {
                const codec = configuration[codecIndex];
                const tag = codec && codec.tag;
                const allocate = codec && codec.allocate;
                const populate = codec && codec.populate;
                requireConsumerTag(tag);
                if (typeof allocate !== "function" || typeof populate !== "function") {
                    throw new Error("Destination codec must provide allocate and populate");
                }
                if (mapHas(result, tag)) throw new Error("Duplicate destination codec tag " + tag);
                mapSet(result, tag, { tag: tag, allocate: allocate, populate: populate, receiver: codec });
            }
            return result;
        }

        function requireConsumerTag(tag) {
            if (typeof tag !== "string" || tag.length === 0 ||
                tag === referenceValueTag || coreCodec.isReservedTag(tag)) {
                throw new Error("Invalid or reserved consumer codec tag " + tag);
            }
        }

        function validateProtocolFields(fields, graph, normalizeWireKeys) {
            const active = new SetConstructor();
            validateProtocolField(fields, active, graph, normalizeWireKeys);
        }

        function validateProtocolField(value, active, graph, normalizeWireKeys) {
            if (value === null || typeof value === "boolean" || typeof value === "string") return;
            if (typeof value === "number") {
                if (!isFiniteNumber(value) || isSameValue(value, -0)) {
                    throw new Error("Codec metadata number must be finite and must not be negative zero");
                }
                return;
            }
            if (typeof value !== "object") throw new Error("Codec protocol field is not JSON-compatible");
            if (setHas(active, value)) throw new Error("Codec protocol metadata must not contain cycles");
            setAdd(active, value);
            if (isArray(value)) {
                if (getPrototypeOf(value) !== arrayPrototype) throw new Error("Codec metadata array must be plain");
                const lengthDescriptor = getOwnPropertyDescriptor(value, "length");
                if (!lengthDescriptor ||
                    lengthDescriptor.writable !== true ||
                    lengthDescriptor.enumerable !== false ||
                    lengthDescriptor.configurable !== false) {
                    throw new Error("Codec metadata array has a non-standard length descriptor");
                }
                const keys = ownKeys(value);
                if (keys.length !== value.length + 1) throw new Error("Codec metadata array must be dense without extra properties");
                for (let itemIndex = 0; itemIndex < value.length; itemIndex++) {
                    const descriptor = requirePlainDataDescriptor(value, StringConstructor(itemIndex));
                    validateProtocolField(descriptor.value, active, graph, normalizeWireKeys);
                }
            } else {
                if (getPrototypeOf(value) !== objectPrototype) throw new Error("Codec metadata record must be plain");
                if (normalizeWireKeys) normalizeWireRecord(value, graph);
                const keys = ownKeys(value);
                for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                    const key = keys[keyIndex];
                    if (typeof key !== "string") throw new Error("Codec metadata record has a Symbol key");
                    const descriptor = requirePlainDataDescriptor(value, key);
                    validateProtocolField(descriptor.value, active, graph, normalizeWireKeys);
                }
            }
            setDelete(active, value);
        }

        function requirePlainDataDescriptor(value, key) {
            const descriptor = getOwnPropertyDescriptor(value, key);
            if (!descriptor || !("value" in descriptor) ||
                descriptor.writable !== true ||
                descriptor.enumerable !== true ||
                descriptor.configurable !== true) {
                throw new Error("Codec metadata property is not a normal data property");
            }
            return descriptor;
        }

        function isObjectIdentity(value) {
            return value !== null && (typeof value === "object" || typeof value === "function");
        }

        function requireArity(node, expected) {
            if (node.length !== expected) throw new Error("Invalid arity for tag " + node[0]);
        }

        return {
            allocateGraphId: coreCodec.allocateGraphId,
            base64ToBytes: coreCodec.base64ToBytes,
            bytesToBase64: coreCodec.bytesToBase64,
            createDecoder: createDecoder,
            createEncoder: createEncoder,
            createGraphContext: coreCodec.createGraphContext,
            decode: coreCodec.decode,
            encode: coreCodec.encode,
            encodeGraphReference: coreCodec.encodeGraphReference,
            isReservedTag: coreCodec.isReservedTag,
            notHandled: coreCodec.notHandled,
            registerGraphValue: coreCodec.registerGraphValue,
            requireUnusedGraphId: coreCodec.requireUnusedGraphId,
            tags: coreCodec.tags,
        };
    })
    """.trimIndent()

/** Default [JsValue] wire codec used by transport. */
object ExpressionValueCodec : JsValueCodec {
    internal fun hasTag(
        value: String,
        startIndex: Int,
        tag: String,
    ): Boolean {
        require(tag.isNotEmpty()) { "Expression-value tag must not be empty" }
        var index = value.skipJsonWhitespace(startIndex)
        if (index >= value.length || value[index] != '[') return false
        index = value.skipJsonWhitespace(index + 1)
        return index + tag.length + 1 < value.length &&
            value[index] == '"' &&
            value.regionMatches(index + 1, tag, 0, tag.length) &&
            value[index + tag.length + 1] == '"'
    }

    internal fun tagPayloadStart(
        value: String,
        startIndex: Int,
        tag: String,
    ): Int {
        require(tag.isNotEmpty()) { "Expression-value tag must not be empty" }
        var index = value.expectJsonChar(startIndex, '[')
        index = value.skipJsonWhitespace(index)
        check(
            index + tag.length + 1 < value.length &&
                value[index] == '"' &&
                value.regionMatches(index + 1, tag, 0, tag.length) &&
                value[index + tag.length + 1] == '"',
        ) { "Expected expression-value tag $tag at $index" }
        return value.expectJsonChar(index + tag.length + 2, ',')
    }

    internal fun expectTaggedValueEnd(
        value: String,
        startIndex: Int,
    ): Int = value.expectJsonChar(startIndex, ']')

    internal enum class ValueType {
        NULL,
        UNDEFINED,
        BOOLEAN,
        NUMBER,
        BIGINT,
        STRING,
        UINT8_ARRAY,
    }

    private val nullWire = JsValueWire("null")
    private val undefinedWire = JsValueWire("[${ExpressionValueTag.UNDEFINED.toJson()}]")
    private val falseWire = JsValueWire("false")
    private val trueWire = JsValueWire("true")
    private val nanWire = JsValueWire("[${ExpressionValueTag.NUMBER.toJson()},${ExpressionValueTag.NUMBER_NAN.toJson()}]")
    private val positiveInfinityWire =
        JsValueWire("[${ExpressionValueTag.NUMBER.toJson()},${ExpressionValueTag.NUMBER_POSITIVE_INFINITY.toJson()}]")
    private val negativeInfinityWire =
        JsValueWire("[${ExpressionValueTag.NUMBER.toJson()},${ExpressionValueTag.NUMBER_NEGATIVE_INFINITY.toJson()}]")
    private val negativeZeroWire =
        JsValueWire("[${ExpressionValueTag.NUMBER.toJson()},${ExpressionValueTag.NUMBER_NEGATIVE_ZERO.toJson()}]")

    /** Encodes a standalone JavaScript `null` value without creating a [JsContext]. */
    fun encodeNull(): JsValueWire = nullWire

    /** Encodes a standalone JavaScript `undefined` value without creating a [JsContext]. */
    fun encodeUndefined(): JsValueWire = undefinedWire

    /** Encodes a standalone JavaScript Boolean value without creating a [JsContext]. */
    fun encodeBoolean(value: Boolean): JsValueWire = if (value) trueWire else falseWire

    /** Encodes a standalone JavaScript Number value without creating a [JsContext]. */
    fun encodeNumber(value: Number): JsValueWire {
        val number = value.toDouble()
        return when {
            number.isNaN() -> nanWire
            number == Double.POSITIVE_INFINITY -> positiveInfinityWire
            number == Double.NEGATIVE_INFINITY -> negativeInfinityWire
            number.toBits() == (-0.0).toBits() -> negativeZeroWire
            else -> JsValueWire(number.toString())
        }
    }

    /** Encodes a standalone JavaScript BigInt from its canonical decimal representation. */
    fun encodeBigInt(value: String): JsValueWire {
        value.requireCanonicalExpressionBigInt()
        return JsValueWire("[${ExpressionValueTag.BIGINT.toJson()},${value.toJson()}]")
    }

    /** Encodes a standalone JavaScript String value without creating a [JsContext]. */
    fun encodeString(value: String): JsValueWire = JsValueWire(value.toJson())

    /** Encodes a standalone JavaScript Uint8Array value without creating a [JsContext]. */
    fun encodeUint8Array(value: ByteArray): JsValueWire =
        JsValueWire("[${ExpressionValueTag.UINT8_ARRAY.toJson()},1,\"${value.toExpressionBase64()}\"]")

    internal fun valueTypeOf(value: String): ValueType {
        val index = value.skipJsonWhitespace(0)
        check(index < value.length) { "Expected expression value at $index" }
        return when (value[index]) {
            'n' -> {
                value.expectJsonEnd(value.skipJsonLiteral(index, "null"))
                ValueType.NULL
            }

            'f' -> {
                value.expectJsonEnd(value.skipJsonLiteral(index, "false"))
                ValueType.BOOLEAN
            }

            't' -> {
                value.expectJsonEnd(value.skipJsonLiteral(index, "true"))
                ValueType.BOOLEAN
            }

            '"' -> {
                ValueType.STRING
            }

            '-', in '0'..'9' -> {
                ValueType.NUMBER
            }

            '[' -> {
                value
                    .decodePackedExpressionValueType(value.expectJsonChar(index, '['))
                    .decodedExpressionValueType()
            }

            else -> {
                throw IllegalArgumentException("Unknown expression-value kind at $index")
            }
        }
    }

    internal fun skipValue(
        value: String,
        startIndex: Int,
    ): Int {
        var index = value.skipJsonWhitespace(startIndex)
        check(index < value.length) { "Expected expression value at $index" }
        when (value[index]) {
            'n' -> return value.skipJsonLiteral(index, "null")
            'f' -> return value.skipJsonLiteral(index, "false")
            't' -> return value.skipJsonLiteral(index, "true")
            '"' -> return value.skipJsonString(index)
            '-', in '0'..'9' -> return value.skipJsonNumber(index)
            '[' -> index++
            else -> throw IllegalArgumentException("Unknown expression-value kind at $index")
        }

        val decodedType = value.decodePackedExpressionValueType(index)
        val type = decodedType.decodedExpressionValueType()
        index = decodedType.decodedExpressionIndex()
        index =
            when (type) {
                ValueType.UNDEFINED -> {
                    index
                }

                ValueType.NUMBER -> {
                    value.skipExpressionSpecialNumber(value.expectJsonChar(index, ','))
                }

                ValueType.BIGINT -> {
                    value.skipJsonString(value.expectJsonChar(index, ','))
                }

                ValueType.UINT8_ARRAY -> {
                    index = value.expectJsonChar(index, ',')
                    index = value.skipExpressionGraphId(index)
                    value.skipJsonString(value.expectJsonChar(index, ','))
                }

                else -> {
                    error("Expected a tagged expression value")
                }
            }
        return expectTaggedValueEnd(value, index)
    }

    /** Decodes a standalone JavaScript Boolean value without creating a [JsContext]. */
    fun decodeBoolean(wire: JsValueWire): Boolean {
        val value = wire.value
        var index = value.skipJsonWhitespace(0)
        check(index < value.length) { "Expected expression boolean at $index" }
        val result: Boolean
        when {
            value.matchesJsonLiteral(index, "false") -> {
                index += 5
                result = false
            }

            value.matchesJsonLiteral(index, "true") -> {
                index += 4
                result = true
            }

            else -> {
                throw IllegalArgumentException("Unknown expression boolean at $index")
            }
        }
        value.expectJsonEnd(index)
        return result
    }

    /** Decodes a standalone JavaScript Number value without creating a [JsContext]. */
    fun decodeNumber(wire: JsValueWire): Double {
        val value = wire.value
        var index = value.skipJsonWhitespace(0)
        if (index >= value.length || value[index] != '[') {
            val start = index
            index = value.skipJsonNumber(index)
            val result =
                if (start == 0 && index == value.length) {
                    value.toDouble()
                } else {
                    value.substring(start, index).toDouble()
                }
            check(result.isFinite()) { "Non-finite Number must use an expression-value tag" }
            check(result.toBits() != (-0.0).toBits()) { "Negative zero must use an expression-value tag" }
            value.expectJsonEnd(index)
            return result
        }

        index = value.expressionPayloadStart(ValueType.NUMBER)
        index = value.skipJsonWhitespace(index)
        check(index < value.length && value[index] == '"') { "Expected expression-value number marker at $index" }
        index++
        val result =
            when {
                value.matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NAN) -> {
                    index += ExpressionValueTag.NUMBER_NAN.length + 1
                    Double.NaN
                }

                value.matchesExpressionMarker(index, ExpressionValueTag.NUMBER_POSITIVE_INFINITY) -> {
                    index += ExpressionValueTag.NUMBER_POSITIVE_INFINITY.length + 1
                    Double.POSITIVE_INFINITY
                }

                value.matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NEGATIVE_INFINITY) -> {
                    index += ExpressionValueTag.NUMBER_NEGATIVE_INFINITY.length + 1
                    Double.NEGATIVE_INFINITY
                }

                value.matchesExpressionMarker(index, ExpressionValueTag.NUMBER_NEGATIVE_ZERO) -> {
                    index += ExpressionValueTag.NUMBER_NEGATIVE_ZERO.length + 1
                    -0.0
                }

                else -> {
                    throw IllegalArgumentException("Unknown expression-value number at $index")
                }
            }
        value.expectJsonEnd(expectTaggedValueEnd(value, index))
        return result
    }

    /** Decodes a standalone JavaScript BigInt to its exact canonical decimal representation. */
    fun decodeBigIntString(wire: JsValueWire): String {
        val value = wire.value
        val result =
            value.decodeJsonString(value.expressionPayloadStart(ValueType.BIGINT)) {
                value.expectJsonEnd(expectTaggedValueEnd(value, it))
            }
        result.requireCanonicalExpressionBigInt()
        return result
    }

    /** Decodes a standalone JavaScript String value without creating a [JsContext]. */
    fun decodeString(wire: JsValueWire): String {
        val value = wire.value
        return value.decodeJsonString(value.skipJsonWhitespace(0)) {
            value.expectJsonEnd(it)
        }
    }

    /** Decodes a standalone JavaScript Uint8Array value without creating a [JsContext]. */
    fun decodeUint8Array(wire: JsValueWire): ByteArray {
        val value = wire.value
        var index = value.expressionPayloadStart(ValueType.UINT8_ARRAY)
        index = value.skipJsonWhitespace(index)
        index = value.skipExpressionGraphId(index)
        index = value.skipJsonWhitespace(index)
        check(index < value.length && value[index] == ',') { "Expected ',' at $index" }
        index = value.skipJsonWhitespace(index + 1)
        check(index < value.length && value[index] == '"') { "Expected '\"' at $index" }

        val stringStart = index
        val contentStart = ++index
        while (true) {
            check(index < value.length) { "Unterminated string at $contentStart" }
            when (value[index++]) {
                '"' -> {
                    value.expectJsonEnd(expectTaggedValueEnd(value, index))
                    return decodeExpressionBase64(value, contentStart, index - 1)
                }

                '\\' -> {
                    val decoded =
                        value.decodeJsonString(stringStart) {
                            value.expectJsonEnd(expectTaggedValueEnd(value, it))
                        }
                    return decodeExpressionBase64(decoded)
                }
            }
        }
    }

    override fun createEncoder(context: JsContext): JsValueEncoder = createEncoder(context, null)

    override fun createDecoder(context: JsContext): JsValueDecoder = createDecoder(context, null)

    /**
     * Creates an expression codec whose JavaScript configuration is created once inside each construction
     * scope. The configuration is codec-owned and cannot escape into transport orchestration.
     */
    operator fun invoke(createConfiguration: JsScope.() -> JsValue): JsValueCodec =
        object : JsValueCodec {
            override fun createEncoder(context: JsContext): JsValueEncoder =
                this@ExpressionValueCodec.createEncoder(context, createConfiguration)

            override fun createDecoder(context: JsContext): JsValueDecoder =
                this@ExpressionValueCodec.createDecoder(context, createConfiguration)
        }

    private fun createEncoder(
        context: JsContext,
        createConfiguration: (JsScope.() -> JsValue)?,
    ): JsValueEncoder =
        jsScoped(context) {
            val configuration = createConfiguration?.invoke(this)
            requireConfigurationContext(context, configuration)
            val runtime = eval("($expressionValueCodecFactorySource)()") as JsObject
            val createEncoder = runtime["createEncoder"] as JsFunction
            val encoder =
                (configuration?.let { createEncoder(it) } ?: createEncoder()) as JsFunction
            ExpressionValueEncoder(context, encoder.escape())
        }

    private fun createDecoder(
        context: JsContext,
        createConfiguration: (JsScope.() -> JsValue)?,
    ): JsValueDecoder =
        jsScoped(context) {
            val configuration = createConfiguration?.invoke(this)
            requireConfigurationContext(context, configuration)
            val runtime = eval("($expressionValueCodecFactorySource)()") as JsObject
            val createDecoder = runtime["createDecoder"] as JsFunction
            val decoder =
                (configuration?.let { createDecoder(it) } ?: createDecoder()) as JsFunction
            ExpressionValueDecoder(context, decoder.escape())
        }

    private fun requireConfigurationContext(
        context: JsContext,
        configuration: JsValue?,
    ) {
        require(configuration == null || configuration.context === context) {
            "ExpressionValueCodec configuration belongs to another JsContext"
        }
    }
}

private class ExpressionValueEncoder(
    override val context: JsContext,
    private val encoder: JsFunction,
) : JsValueEncoder {
    override fun <R> encode(
        value: JsValue,
        block: JsScope.(wire: JsValueWire, referenceValues: JsArray) -> R,
    ): R {
        require(value.context === context) { "JsValueEncoder cannot encode a JsValue from another JsContext" }
        return jsScoped(context) {
            val encoded = encoder(value) as JsObject
            val wire = JsValueWire((encoded["wire"] as JsString).toString())
            block(wire, encoded["referenceValues"] as JsArray)
        }
    }

    override fun close() {
        encoder.close()
    }
}

private class ExpressionValueDecoder(
    override val context: JsContext,
    private val decoder: JsFunction,
) : JsValueDecoder {
    override fun decode(
        wire: JsValueWire,
        resolvedReferenceValues: List<JsValue>,
    ): JsValue {
        resolvedReferenceValues.forEach {
            require(it.context === context) {
                "JsValueDecoder cannot resolve a JsValue from another JsContext"
            }
        }
        if (context is JsWebViewContext) {
            return context.decodeExpressionValue(decoder, wire, resolvedReferenceValues)
        }
        return jsScoped(context) {
            decoder(JsString(wire.value), JsArray(resolvedReferenceValues)).escape()
        }
    }

    override fun close() {
        decoder.close()
    }
}
