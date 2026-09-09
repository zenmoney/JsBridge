package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsBoolean
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.boolean
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.jsScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExpressionValueCodecWireTest {
    @Test
    fun decoderResultBelongsToReceivingScopeAndBorrowsResolvedReferences() {
        JsContext().use { context ->
            ExpressionValueCodec.createDecoder(context).use { decoder ->
                context.evaluateScript("({ replacement: true })").use { replacement ->
                    val decoded =
                        JsScope(context).use { jsScope ->
                            with(jsScope) {
                                decoder.decode(JsValueWire("""["x",0]"""), listOf(replacement)).also {
                                    assertTrue(it in this)
                                    assertFalse(replacement in this)
                                }
                            }
                        }
                    assertTrue(decoded.isClosed)
                    assertFalse(replacement.isClosed)
                }
            }
        }
    }

    @Test
    fun callerCanEscapeDecodedValueBeyondScopeAndDecoderLifetime() {
        JsContext().use { context ->
            val decoded =
                ExpressionValueCodec.createDecoder(context).use { decoder ->
                    jsScoped(context) {
                        decoder.decode(JsValueWire("\"escaped\""), emptyList()).escape()
                    }
                }
            decoded.use {
                assertFalse(it.isClosed)
                assertEquals("escaped", assertIs<JsString>(it).toString())
            }
        }
    }

    @Test
    fun decoderRejectsScopeAndResolvedReferencesFromAnotherContext() {
        JsContext().use { context ->
            JsContext().use { otherContext ->
                ExpressionValueCodec.createDecoder(context).use { decoder ->
                    jsScoped(otherContext) {
                        assertFailsWith<IllegalArgumentException> {
                            decoder.decode(JsValueWire("null"), emptyList())
                        }
                    }
                    otherContext.evaluateScript("({})").use { reference ->
                        jsScoped(context) {
                            assertFailsWith<IllegalArgumentException> {
                                decoder.decode(JsValueWire("""["x",0]"""), listOf(reference))
                            }
                        }
                        assertFalse(reference.isClosed)
                    }
                }
            }
        }
    }

    @Test
    fun publicCodecOwnsWireAndReferenceTable() {
        JsContext().use { context ->
            val codec =
                ExpressionValueCodec {
                    eval("[value => value !== null && value.byReference === true]")
                }
            codec.createEncoder(context).use { encoder ->
                ExpressionValueCodec.createDecoder(context).use { decoder ->
                    context.evaluateScript("({ byReference: true })").use { source ->
                        context.evaluateScript("({ replacement: true })").use { replacement ->
                            encoder.encode(source) { wire, referenceValues ->
                                assertEquals("""["x",0]""", wire.value)
                                assertEquals(1, referenceValues.size)
                                context.globalThis["source"] = source
                                context.globalThis["selected"] = referenceValues[0]
                                assertEquals(
                                    true,
                                    assertIs<JsBoolean>(context.evaluateScript("source === selected")).boolean,
                                )

                                decoder.decode(wire, listOf(replacement)).also { decoded ->
                                    context.globalThis["replacement"] = replacement
                                    context.globalThis["decoded"] = decoded
                                    assertEquals(
                                        true,
                                        assertIs<JsBoolean>(
                                            context.evaluateScript("decoded === replacement"),
                                        ).boolean,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun configuredCodecOwnsConstructionScope() {
        JsContext().use { context ->
            lateinit var configuration: JsValue
            var configurationCount = 0
            val codec =
                ExpressionValueCodec {
                    configurationCount++
                    eval("[]").also { configuration = it }
                }

            codec.createEncoder(context).use {
                assertEquals(1, configurationCount)
                assertTrue(configuration.isClosed)
            }
            codec.createDecoder(context).use {
                assertEquals(2, configurationCount)
                assertTrue(configuration.isClosed)
            }
        }
    }

    @Test
    fun emitsExactPrimitiveObjectDenseAndSparseWireForms() =
        withContext {
            assertEquals(
                """["null","[\"u\"]","false","true","42","[\"n\",\"nan\"]","[\"i\",\"9007199254740993\"]","\"text\"","[\"o\",1,{\"name\":\"Ada\",\"items\":[\"a\",2,[1,2,3]],\"self\":[\"r\",1]}]","[\"a\",1,[1,2,3]]","[\"A\",1,1000,0,\"head\",999,\"tail\"]"]""",
                evaluateString(
                    """
                    const encoder = ($expressionValueCodecFactorySource)().createEncoder();
                    const object = { name: "Ada", items: [1, 2, 3] };
                    object.self = object;
                    const sparse = new Array(1000);
                    sparse[0] = "head";
                    sparse[999] = "tail";
                    return JSON.stringify([
                        encoder(null).wire,
                        encoder(undefined).wire,
                        encoder(false).wire,
                        encoder(true).wire,
                        encoder(42).wire,
                        encoder(NaN).wire,
                        encoder(9007199254740993n).wire,
                        encoder("text").wire,
                        encoder(object).wire,
                        encoder([1, 2, 3]).wire,
                        encoder(sparse).wire,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun escapesJavascriptLineSeparatorsInWire() =
        withContext {
            assertEquals(
                "\"line\\u2028paragraph\\u2029\"",
                evaluateString(
                    """
                    const encoder = ($expressionValueCodecFactorySource)().createEncoder();
                    return encoder("line\u2028paragraph\u2029").wire;
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun exactGraphWireDecodesSelfReferenceWithIdentity() =
        withContext {
            assertEquals(
                """["[\"o\",1,{\"self\":[\"r\",1]}]",true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const source = {};
                    source.self = source;
                    const encoded = runtime.createEncoder()(source);
                    const decoded = runtime.createDecoder()(encoded.wire, []);
                    return JSON.stringify([encoded.wire, decoded.self === decoded]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun protoAndNulPrefixedKeysUseReversibleJsonEscaping() =
        withContext {
            assertEquals(
                """[true,true,true,true,true,true,true,true,true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const source = { safe: 1 };
                    const nulProto = "\u0000__proto__";
                    Object.defineProperty(source, "__proto__", {
                        value: { polluted: true },
                        writable: true,
                        enumerable: true,
                        configurable: true,
                    });
                    source[nulProto] = "collision";
                    const encoded = runtime.createEncoder()(source);
                    const parsed = JSON.parse(encoded.wire);
                    const wirePayload = parsed[2];
                    const decoded = runtime.createDecoder()(encoded.wire, []);
                    return JSON.stringify([
                        JSON.stringify(parsed) === encoded.wire,
                        Object.prototype.hasOwnProperty.call(wirePayload, nulProto),
                        Object.prototype.hasOwnProperty.call(wirePayload, "\u0000" + nulProto),
                        !Object.prototype.hasOwnProperty.call(wirePayload, "__proto__"),
                        Object.getPrototypeOf(decoded) === Object.prototype,
                        Object.prototype.hasOwnProperty.call(decoded, "__proto__"),
                        decoded.__proto__.polluted === true,
                        Object.prototype.hasOwnProperty.call(decoded, nulProto),
                        decoded[nulProto] === "collision",
                        decoded.safe === 1,
                        Object.prototype.polluted === undefined,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun wireStringificationIgnoresInheritedToJsonHooks() =
        withContext {
            assertEquals(
                """["[\"o\",1,{\"value\":1}]","[\"a\",1,[1,2]]"]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const encoder = runtime.createEncoder();
                    const originalObjectToJSON = Object.getOwnPropertyDescriptor(Object.prototype, "toJSON");
                    const originalArrayToJSON = Object.getOwnPropertyDescriptor(Array.prototype, "toJSON");
                    let objectWire;
                    let arrayWire;
                    try {
                        Object.defineProperty(Object.prototype, "toJSON", {
                            configurable: true,
                            value() { return "object hook"; },
                        });
                        Object.defineProperty(Array.prototype, "toJSON", {
                            configurable: true,
                            value() { return "array hook"; },
                        });
                        objectWire = encoder({ value: 1 }).wire;
                        arrayWire = encoder([1, 2]).wire;
                    } finally {
                        if (originalObjectToJSON === undefined) {
                            delete Object.prototype.toJSON;
                        } else {
                            Object.defineProperty(Object.prototype, "toJSON", originalObjectToJSON);
                        }
                        if (originalArrayToJSON === undefined) {
                            delete Array.prototype.toJSON;
                        } else {
                            Object.defineProperty(Array.prototype, "toJSON", originalArrayToJSON);
                        }
                    }
                    return JSON.stringify([objectWire, arrayWire]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun decoderUsesCapturedSetSizeGetterForReferenceValidation() =
        withContext {
            assertEquals(
                "true",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const decoder = runtime.createDecoder();
                    const originalSize = Object.getOwnPropertyDescriptor(Set.prototype, "size");
                    let rejected = false;
                    try {
                        Object.defineProperty(Set.prototype, "size", {
                            configurable: true,
                            get() { return 1; },
                        });
                        try {
                            decoder("null", [{}]);
                        } catch (_) {
                            rejected = true;
                        }
                    } finally {
                        Object.defineProperty(Set.prototype, "size", originalSize);
                    }
                    return JSON.stringify(rejected);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun roundTripsCoreGraphAndBuiltins() =
        withContext {
            assertEquals(
                """[true,true,true,true,true,true,true,true,true,true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const encoder = runtime.createEncoder();
                    const decoder = runtime.createDecoder();
                    const value = {
                        sparse: new Array(3),
                        date: new Date("2024-01-02T03:04:05.000Z"),
                        regexp: /a+b/gi,
                        error: new TypeError("failure"),
                        bytes: Uint8Array.from([0, 128, 255]),
                        words: new Int16Array([1, -2, 300]),
                        buffer: Uint8Array.from([4, 5, 6]).buffer,
                    };
                    value.sparse[1] = undefined;
                    value.self = value;
                    value.sameBytes = value.bytes;

                    const encoded = encoder(value);
                    const decoded = decoder(encoded.wire, []);
                    return JSON.stringify([
                        encoded.referenceValues.length === 0,
                        decoded !== value,
                        decoded.self === decoded,
                        decoded.sameBytes === decoded.bytes,
                        decoded.sparse.length === 3,
                        !(0 in decoded.sparse) && 1 in decoded.sparse && decoded.sparse[1] === undefined && !(2 in decoded.sparse),
                        decoded.date.toISOString() === "2024-01-02T03:04:05.000Z",
                        decoded.regexp.source === "a+b" && decoded.regexp.flags === "gi" && decoded.regexp.lastIndex === 0,
                        decoded.error instanceof Error && decoded.error.name === "TypeError" && decoded.error.message === "failure",
                        decoded.bytes instanceof Uint8Array && Array.from(decoded.bytes).join(",") === "0,128,255",
                        decoded.words instanceof Int16Array && Array.from(decoded.words).join(",") === "1,-2,300",
                        decoded.buffer instanceof ArrayBuffer && Array.from(new Uint8Array(decoded.buffer)).join(",") === "4,5,6",
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun rejectsDetachedUint8Array() =
        withContext {
            assertEquals(
                "Cannot encode a detached Uint8Array",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const memory = new WebAssembly.Memory({ initial: 1, maximum: 2 });
                    const detached = new Uint8Array(memory.buffer);
                    memory.grow(1);
                    try {
                        runtime.createEncoder()({ bytes: detached });
                        return "encoded";
                    } catch (error) {
                        return error.message;
                    }
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun consumerCodecParticipatesInCycle() =
        withContext {
            assertEquals(
                """[true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const sourceCodec = {
                        tag: "map",
                        matches(value) { return value instanceof Map; },
                        encode(value, context) {
                            return [Array.from(value, entry => [context.encodeChild(entry[0]), context.encodeChild(entry[1])])];
                        },
                    };
                    const destinationCodec = {
                        tag: "map",
                        allocate() { return new Map(); },
                        populate(value, fields, context) {
                            for (const entry of fields[0]) {
                                value.set(context.decodeChild(entry[0]), context.decodeChild(entry[1]));
                            }
                        },
                    };
                    const value = new Map();
                    value.set("self", value);
                    const encoded = runtime.createEncoder([sourceCodec])(value);
                    const decoded = runtime.createDecoder([destinationCodec])(encoded.wire, []);
                    return JSON.stringify([
                        decoded instanceof Map,
                        decoded.get("self") === decoded,
                        JSON.parse(encoded.wire)[0] === "map",
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun rejectsInvalidConsumerGraphIdBeforeAllocation() =
        withContext {
            assertEquals(
                """[0,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    let allocations = 0;
                    const decoder = runtime.createDecoder([{
                        tag: "resource",
                        allocate() {
                            allocations++;
                            return {};
                        },
                        populate() {},
                    }]);
                    let invalidRejected = false;
                    let duplicateRejected = false;
                    try {
                        decoder('["resource",0]', []);
                    } catch (_) {
                        invalidRejected = true;
                    }
                    try {
                        decoder('["a",1,[["resource",1]]]', []);
                    } catch (_) {
                        duplicateRejected = true;
                    }
                    return JSON.stringify([allocations, invalidRejected, duplicateRejected]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun consumerRegistriesCaptureValidatedDefinitions() =
        withContext {
            assertEquals(
                """["stable","stable"]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const sourceCodec = {
                        tag: "stable",
                        matches(value) { return value && value.selected === true; },
                        encode() { return ["stable"]; },
                    };
                    const destinationCodec = {
                        tag: "stable",
                        allocate() { return {}; },
                        populate(value, fields) { value.result = fields[0]; },
                    };
                    const encoder = runtime.createEncoder([sourceCodec]);
                    const decoder = runtime.createDecoder([destinationCodec]);
                    sourceCodec.tag = "mutated";
                    sourceCodec.matches = () => false;
                    sourceCodec.encode = () => ["mutated"];
                    destinationCodec.tag = "mutated";
                    destinationCodec.allocate = () => { throw new Error("mutated allocate"); };
                    destinationCodec.populate = () => { throw new Error("mutated populate"); };

                    const encoded = encoder({ selected: true });
                    const decoded = decoder(encoded.wire, []);
                    return JSON.stringify([JSON.parse(encoded.wire)[0], decoded.result]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun consumerCodecReceivesImmutableRootAndNestedPositions() =
        withContext {
            assertEquals(
                """[["matches","root",true,true],["encode","root",true,true],["matches","child",false,true],["encode","child",false,true]]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const events = [];
                    const sourceCodec = {
                        tag: "node",
                        matches(value, position) {
                            events.push(["matches", value.name, position.isRoot, Object.isFrozen(position)]);
                            return value && value.kind === "node";
                        },
                        encode(value, context) {
                            events.push(["encode", value.name, context.position.isRoot, Object.isFrozen(context.position)]);
                            return [value.name, context.encodeChild(value.child)];
                        },
                    };
                    const root = { kind: "node", name: "root" };
                    const child = { kind: "node", name: "child" };
                    root.child = child;
                    child.child = root;
                    runtime.createEncoder([sourceCodec])(root);
                    return JSON.stringify(events);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun referenceCodecReceivesPositionOnlyForFirstOccurrence() =
        withContext {
            assertEquals(
                """[true,true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const source = { selected: true };
                    const sourcePositions = [];
                    let positionsAreFrozen = true;
                    const referenceCodec = (value, position) => {
                        positionsAreFrozen = positionsAreFrozen && Object.isFrozen(position);
                        if (value === source) sourcePositions.push(position.isRoot);
                        return value === source;
                    };
                    const encoder = runtime.createEncoder([referenceCodec]);
                    const root = encoder(source);
                    const nested = encoder([source, source]);
                    const nestedWire = JSON.parse(nested.wire);
                    return JSON.stringify([
                        sourcePositions.length === 2 && sourcePositions[0] === true && sourcePositions[1] === false,
                        positionsAreFrozen,
                        root.referenceValues.length === 1 && nested.referenceValues.length === 1,
                        nestedWire[2][0][1] === 0 && nestedWire[2][1][1] === 0,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun explicitReferenceExtensionUsesSideTable() =
        withContext {
            assertEquals(
                """[true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const encoder = runtime.createEncoder([value => value === source]);
                    const decoder = runtime.createDecoder();
                    const source = { byReference: true };
                    const replacement = { destination: true };
                    const encoded = encoder([source, source]);
                    const wire = JSON.parse(encoded.wire);
                    const decoded = decoder(encoded.wire, [replacement]);
                    return JSON.stringify([
                        encoded.referenceValues.length === 1 && encoded.referenceValues[0] === source,
                        wire[2][0][0] === "x" && wire[2][1][0] === "x",
                        decoded[0] === replacement && decoded[1] === replacement,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    @Test
    fun referenceCodecPositionControlsPrecedenceAmongConsumerCodecs() =
        withContext {
            assertEquals(
                """[true,true,true,true]""",
                evaluateString(
                    """
                    const runtime = ($expressionValueCodecFactorySource)();
                    const valueCodec = {
                        tag: "value",
                        matches(value) { return value && value.selected === true; },
                        encode() { return ["copied"]; },
                    };
                    const source = { selected: true };
                    const matchesReference = value => value === source;
                    const referenceFirst = runtime.createEncoder([matchesReference, valueCodec])(source);
                    const valueFirst = runtime.createEncoder([valueCodec, matchesReference])(source);
                    return JSON.stringify([
                        JSON.parse(referenceFirst.wire)[0] === "x",
                        referenceFirst.referenceValues.length === 1,
                        JSON.parse(valueFirst.wire)[0] === "value",
                        valueFirst.referenceValues.length === 0,
                    ]);
                    """.trimIndent(),
                ),
            )
        }

    private fun <T> withContext(block: ContextScope.() -> T): T = JsContext().use { context -> ContextScope(context).block() }

    private class ContextScope(
        private val context: JsContext,
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
