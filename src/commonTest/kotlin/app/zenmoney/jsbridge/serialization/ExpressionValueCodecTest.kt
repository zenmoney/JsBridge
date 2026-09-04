package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsException
import app.zenmoney.jsbridge.JsString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExpressionValueCodecTest {
    @Test
    fun encodesAndDecodesStandaloneValuesWithoutJsContext() {
        assertEquals("null", ExpressionValueCodec.encodeNull().value)
        assertEquals("""["u"]""", ExpressionValueCodec.encodeUndefined().value)
        assertEquals("false", ExpressionValueCodec.encodeBoolean(false).value)
        assertEquals("true", ExpressionValueCodec.encodeBoolean(true).value)
        assertEquals("4.0", ExpressionValueCodec.encodeNumber(4).value)
        assertEquals("""["n","nan"]""", ExpressionValueCodec.encodeNumber(Double.NaN).value)
        assertEquals("""["n","+inf"]""", ExpressionValueCodec.encodeNumber(Double.POSITIVE_INFINITY).value)
        assertEquals("""["n","-inf"]""", ExpressionValueCodec.encodeNumber(Double.NEGATIVE_INFINITY).value)
        assertEquals("""["n","-0"]""", ExpressionValueCodec.encodeNumber(-0.0).value)
        assertEquals("""["i","9007199254740993"]""", ExpressionValueCodec.encodeBigInt("9007199254740993").value)
        assertEquals("\"line\\nvalue\"", ExpressionValueCodec.encodeString("line\nvalue").value)
        assertEquals("""["ui8",1,"AID/"]""", ExpressionValueCodec.encodeUint8Array(byteArrayOf(0, 128.toByte(), 255.toByte())).value)

        assertEquals(false, ExpressionValueCodec.decodeBoolean(JsValueWire("false")))
        assertEquals(true, ExpressionValueCodec.decodeBoolean(JsValueWire("true")))
        assertEquals(4.0, ExpressionValueCodec.decodeNumber(JsValueWire("4.0")))
        assertTrue(ExpressionValueCodec.decodeNumber(JsValueWire("""["n","nan"]""")).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, ExpressionValueCodec.decodeNumber(JsValueWire("""["n","+inf"]""")))
        assertEquals(Double.NEGATIVE_INFINITY, ExpressionValueCodec.decodeNumber(JsValueWire("""["n","-inf"]""")))
        assertEquals((-0.0).toBits(), ExpressionValueCodec.decodeNumber(JsValueWire("""["n","-0"]""")).toBits())
        assertEquals(
            "9007199254740993",
            ExpressionValueCodec.decodeBigIntString(JsValueWire("""["i","9007199254740993"]""")),
        )
        assertEquals("line\nvalue", ExpressionValueCodec.decodeString(JsValueWire("\"line\\nvalue\"")))
        assertContentEquals(
            byteArrayOf(0, 128.toByte(), 255.toByte()),
            ExpressionValueCodec.decodeUint8Array(JsValueWire("""["ui8",1,"AID/"]""")),
        )
    }

    @Test
    fun encodesExpressionPrimitiveForms() =
        withContext {
            assertEquals(
                """[null,["u"],false,true,2.5,["n","nan"],["n","+inf"],["n","-inf"],["n","-0"],["i","9007199254740993"],"value"]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    return JSON.stringify([
                        codec.encode(null),
                        codec.encode(undefined),
                        codec.encode(false),
                        codec.encode(true),
                        codec.encode(2.5),
                        codec.encode(NaN),
                        codec.encode(Infinity),
                        codec.encode(-Infinity),
                        codec.encode(-0),
                        codec.encode(9007199254740993n),
                        codec.encode("value"),
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun decodesExpressionPrimitiveForms() =
        withContext {
            assertEquals(
                """[true,true,true,true,true,true,true,true,true]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const graph = codec.createGraphContext();
                    return JSON.stringify([
                        codec.decode(null, graph) === null,
                        codec.decode(["u"], graph) === undefined,
                        codec.decode(true, graph) === true,
                        codec.decode(2.5, graph) === 2.5,
                        Number.isNaN(codec.decode(["n", "nan"], graph)),
                        codec.decode(["n", "+inf"], graph) === Infinity,
                        codec.decode(["n", "-inf"], graph) === -Infinity,
                        Object.is(codec.decode(["n", "-0"], graph), -0),
                        codec.decode(["i", "9007199254740993"], graph) === 9007199254740993n,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun materializedDecodeReusesObjectAndDenseArrayPayloads() =
        withContext {
            assertEquals(
                """[true,true,true,true,true,true]""",
                evaluateString(
                    """
                    const originalJsonParse = JSON.parse;
                    let runtime;
                    try {
                        JSON.parse = () => { throw new Error("JSON.parse must not run for materialized wire"); };
                        runtime = ($expressionValueCodecFactorySource)();
                    } finally {
                        JSON.parse = originalJsonParse;
                    }
                    const decoder = runtime.createDecoder();

                    const nestedDensePayload = [1, 2, 3];
                    const objectPayload = { items: ["a", 2, nestedDensePayload] };
                    const decodedObject = decoder(["o", 1, objectPayload], [], true);

                    const nestedObjectPayload = { answer: 42 };
                    const densePayload = [["o", 2, nestedObjectPayload], ["r", 2]];
                    const decodedDense = decoder(["a", 1, densePayload], [], true);

                    return JSON.stringify([
                        decodedObject === objectPayload,
                        decodedObject.items === nestedDensePayload,
                        decodedObject.items.join(",") === "1,2,3",
                        decodedDense === densePayload,
                        decodedDense[0] === nestedObjectPayload,
                        decodedDense[0] === decodedDense[1],
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun materializedSparseDecodeDistinguishesHolesFromUndefined() =
        withContext {
            assertEquals(
                """[4,false,true,true,false,true,"tail"]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const decoded = runtime.createDecoder()(["A", 1, 4, 1, ["u"], 3, "tail"], [], true);
                    return JSON.stringify([
                        decoded.length,
                        0 in decoded,
                        1 in decoded,
                        decoded[1] === undefined,
                        2 in decoded,
                        3 in decoded,
                        decoded[3],
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun roundTripsEveryBuiltInObjectRepresentation() =
        withContext {
            assertEquals(
                """[true,true,true,true,true,true,true,true,true,true,true,true,true]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const encodeGraph = codec.createGraphContext();
                    const encodeNode = value => codec.encode(value, encodeGraph, encodeNode);
                    const decodeGraph = codec.createGraphContext();
                    const decodeNode = node => codec.decode(node, decodeGraph, decodeNode);
                    const source = {
                        sparse: new Array(3),
                        date: new Date("2024-01-02T03:04:05.000Z"),
                        regexp: /a+b/gi,
                        error: new TypeError("failure"),
                        i8: new Int8Array([-1, 2]),
                        ui8: new Uint8Array([0, 128, 255]),
                        ui8c: new Uint8ClampedArray([1, 2]),
                        i16: new Int16Array([1, -2, 300]),
                        ui16: new Uint16Array([1, 2]),
                        i32: new Int32Array([-1, 2]),
                        ui32: new Uint32Array([1, 2]),
                        f32: new Float32Array([1.5, -2.25]),
                        f64: new Float64Array([1.5, -2.25]),
                        buffer: new Uint8Array([4, 5, 6]).buffer,
                    };
                    if (typeof BigInt64Array === "function") source.bi64 = new BigInt64Array([1n, -2n]);
                    if (typeof BigUint64Array === "function") source.bui64 = new BigUint64Array([1n, 2n]);
                    source.sparse[1] = undefined;
                    source.self = source;
                    source.sameBytes = source.ui8;

                    const decoded = decodeNode(encodeNode(source));
                    return JSON.stringify([
                        decoded.self === decoded,
                        decoded.sameBytes === decoded.ui8,
                        decoded.sparse.length === 3 && !(0 in decoded.sparse) && decoded.sparse[1] === undefined,
                        decoded.date.toISOString() === "2024-01-02T03:04:05.000Z",
                        decoded.regexp.source === "a+b" && decoded.regexp.flags === "gi",
                        decoded.error.name === "TypeError" && decoded.error.message === "failure",
                        Array.from(decoded.i8).join(",") === "-1,2",
                        Array.from(decoded.ui8).join(",") === "0,128,255",
                        Array.from(decoded.ui8c).join(",") === "1,2",
                        Array.from(decoded.i16).join(",") === "1,-2,300",
                        Array.from(decoded.ui16).join(",") === "1,2",
                        Array.from(decoded.i32).join(",") === "-1,2",
                        Array.from(new Uint8Array(decoded.buffer)).join(",") === "4,5,6",
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun explicitSubsetDeclinesBuiltInRepresentationsOutsideIt() =
        withContext {
            assertEquals(
                """[true,true,true]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)({
                        enabledTags: ["0", "u", "b", "n", "i", "s", "r", "ui8"],
                        maxGraphId: 2147483647,
                    });
                    const graph = codec.createGraphContext();
                    return JSON.stringify([
                        codec.encode(new Date(0), graph) === codec.notHandled,
                        codec.decode(["d", 1, "1970-01-01T00:00:00.000Z"], graph) === codec.notHandled,
                        codec.encode(new Uint8Array([1]), graph)[0] === "ui8",
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun encodesUint8ArrayAsPlaywrightKindTagAndPreservesGraphIdentity() =
        withContext {
            assertEquals(
                """{"nodes":[["ui8",1,"AID/"],["r",1]],"same":true,"bytes":[0,128,255]}""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const encodeGraph = codec.createGraphContext();
                    const source = Uint8Array.from([0, 128, 255]);
                    const firstNode = codec.encode(source, encodeGraph);
                    const secondNode = codec.encode(source, encodeGraph);

                    const decodeGraph = codec.createGraphContext();
                    const firstValue = codec.decode(firstNode, decodeGraph);
                    const secondValue = codec.decode(secondNode, decodeGraph);
                    return JSON.stringify({
                        nodes: [firstNode, secondNode],
                        same: firstValue === secondValue,
                        bytes: Array.from(firstValue),
                    });
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun validatesBase64InOnePassWithoutRejectingEarlierGroups() =
        withContext {
            assertEquals(
                "255,255,255,255,255",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const value = codec.decode(["ui8", 1, "//////8="], codec.createGraphContext());
                    return Array.from(value).join(",");
                    """.trimIndent(),
                ),
            )

            listOf(
                "AA=A",
                "AA==AAAA",
            ).forEach { payload ->
                assertFailsWith<JsException> {
                    context.evaluateScript(
                        """
                        (() => {
                            const codec = ($expressionValueCodecFactorySource)();
                            return codec.decode(["ui8", 1, "$payload"], codec.createGraphContext());
                        })()
                        """.trimIndent(),
                    )
                }
            }
        }

    @Test
    fun builtInCodecsEncodeTheirValueDespiteUnrelatedObjectShape() =
        withContext {
            assertEquals(
                """[[1,2,255],"2024-01-02T03:04:05.000Z",["a+b","gi"],[4,5,6],[7],{"a":8},["TypeError","failure"]]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const encodeGraph = codec.createGraphContext();
                    const encodeNode = value => codec.encode(value, encodeGraph, encodeNode);
                    const decodeGraph = codec.createGraphContext();
                    const decodeNode = node => codec.decode(node, decodeGraph, decodeNode);

                    const bytes = new Uint8Array([1, 2, 255]);
                    Object.defineProperty(bytes, "byteLength", { value: 99 });
                    bytes.extra = "ignored";
                    Object.preventExtensions(bytes);

                    const date = new Date("2024-01-02T03:04:05.000Z");
                    date.extra = "ignored";
                    date.toISOString = () => "2000-01-01T00:00:00.000Z";
                    Object.defineProperty(date, Symbol.toPrimitive, { value: () => 0 });
                    Object.preventExtensions(date);

                    const regexp = /a+b/gi;
                    regexp.lastIndex = 3;
                    regexp.extra = "ignored";
                    Object.defineProperty(regexp, "global", { value: false });
                    Object.defineProperty(regexp, "ignoreCase", { value: false });
                    Object.preventExtensions(regexp);

                    const buffer = new Uint8Array([4, 5, 6]).buffer;
                    buffer.extra = "ignored";
                    Object.preventExtensions(buffer);

                    const array = [7];
                    Object.preventExtensions(array);

                    const object = { a: 8 };
                    Object.preventExtensions(object);

                    const error = new TypeError("failure");
                    error.extra = "ignored";
                    Object.preventExtensions(error);

                    const decodedBytes = decodeNode(encodeNode(bytes));
                    const decodedDate = decodeNode(encodeNode(date));
                    const decodedRegExp = decodeNode(encodeNode(regexp));
                    const decodedBuffer = decodeNode(encodeNode(buffer));
                    const decodedArray = decodeNode(encodeNode(array));
                    const decodedObject = decodeNode(encodeNode(object));
                    const decodedError = decodeNode(encodeNode(error));
                    return JSON.stringify([
                        Array.from(decodedBytes),
                        decodedDate.toISOString(),
                        [decodedRegExp.source, decodedRegExp.flags],
                        Array.from(new Uint8Array(decodedBuffer)),
                        decodedArray,
                        decodedObject,
                        [decodedError.name, decodedError.message],
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun rejectsDetachedUint8ArrayWithoutConsumingGraphId() =
        withContext {
            assertEquals(
                """["Cannot encode a detached Uint8Array",["ui8",1,""]]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const graph = codec.createGraphContext();
                    const memory = new WebAssembly.Memory({ initial: 1, maximum: 2 });
                    const detached = new Uint8Array(memory.buffer);
                    memory.grow(1);
                    let message = null;
                    try {
                        codec.encode(detached, graph);
                    } catch (error) {
                        message = error.message;
                    }
                    return JSON.stringify([message, codec.encode(new Uint8Array(0), graph)]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun rejectsUint8ArrayBackedByResizableArrayBufferWhenSupported() =
        withContext {
            val result =
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const buffer = new ArrayBuffer(1, { maxByteLength: 2 });
                    if (buffer.resizable !== true) return "unsupported";
                    try {
                        codec.encode(new Uint8Array(buffer), codec.createGraphContext());
                        return "encoded";
                    } catch (error) {
                        return error.message;
                    }
                    """.trimIndent(),
                )

            assertTrue(
                result == "unsupported" ||
                    result == "Cannot encode a Uint8Array backed by a resizable ArrayBuffer",
            )
        }

    @Test
    fun capturesBigIntAndMapIntrinsicsWhenInstalled() =
        withContext {
            assertEquals(
                """[true,true]""",
                evaluateString(
                    """
                    const codec = ($expressionValueCodecFactorySource)();
                    const encodeGraph = codec.createGraphContext();
                    const bytes = Uint8Array.from([1]);
                    const first = codec.encode(bytes, encodeGraph);
                    const originalBigInt = globalThis.BigInt;
                    const originalGet = Map.prototype.get;
                    const originalHas = Map.prototype.has;
                    const originalSet = Map.prototype.set;
                    let second;
                    let decodedBigInt;
                    try {
                        globalThis.BigInt = () => 0n;
                        Map.prototype.get = () => undefined;
                        Map.prototype.has = () => false;
                        Map.prototype.set = () => { throw new Error("mutated Map.set"); };
                        second = codec.encode(bytes, encodeGraph);
                        decodedBigInt = codec.decode(["i", "9007199254740993"], codec.createGraphContext());
                    } finally {
                        globalThis.BigInt = originalBigInt;
                        Map.prototype.get = originalGet;
                        Map.prototype.has = originalHas;
                        Map.prototype.set = originalSet;
                    }
                    return JSON.stringify([
                        first[0] === "ui8" && second[0] === "r" && second[1] === first[1],
                        decodedBigInt === 9007199254740993n,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun rejectsMalformedRecognizedValues() =
        withContext {
            listOf(
                "codec.decode([\"0\"], graph)",
                "codec.decode([\"b\", true], graph)",
                "codec.decode([\"s\", \"value\"], graph)",
                "codec.decode([\"n\", 1], graph)",
                "codec.decode([\"n\", 1, 2], graph)",
                "codec.decode([\"i\", \"01\"], graph)",
                "codec.decode([\"A\", 1], graph, decodeChild)",
                "codec.decode([\"A\", 1, 0], graph, decodeChild)",
                "codec.decode([\"A\", 1, 1, 0, true], graph, decodeChild)",
                "codec.decode([\"A\", 1, -1], graph, decodeChild)",
                "codec.decode([\"A\", 1, 2, 1, true, 0, false], graph, decodeChild)",
                "codec.decode([\"A\", 1, 2, 2, true], graph, decodeChild)",
                "codec.decode([\"ui8\", 0, \"AA==\"], graph)",
                "codec.decode([\"ui8\", 1, \"AA\"], graph)",
                "codec.decode([\"r\", 1], graph)",
            ).forEach { expression ->
                assertFailsWith<JsException> {
                    context.evaluateScript(
                        """
                        (() => {
                            const codec = ($expressionValueCodecFactorySource)();
                            const graph = codec.createGraphContext();
                            const decodeChild = node => codec.decode(node, graph, decodeChild);
                            return $expression;
                        })()
                        """.trimIndent(),
                    )
                }
            }
        }

    private fun <T> withContext(block: ContextScope.() -> T): T =
        JsContext().use { context ->
            ContextScope(context).block()
        }

    private class ContextScope(
        val context: JsContext,
    ) {
        fun evaluateString(body: String): String =
            assertIs<JsString>(
                context.evaluateScript(
                    """
                    (() => {
                    $body
                    })()
                    """.trimIndent(),
                ),
            ).toString()
    }
}
