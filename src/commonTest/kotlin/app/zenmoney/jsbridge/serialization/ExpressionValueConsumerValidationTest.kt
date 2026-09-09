package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsString
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionValueConsumerValidationTest {
    @Test
    fun nestedConsumerValidationScalesLinearly() {
        assertEquals(
            "ok",
            evaluateString(
                """
                let descriptorCalls = 0;
                const original = Object.getOwnPropertyDescriptor;
                let runtime;
                try {
                    Object.getOwnPropertyDescriptor = (value, key) => {
                        descriptorCalls++;
                        return original(value, key);
                    };
                    runtime = ($expressionValueCodecFactorySource)();
                } finally {
                    Object.getOwnPropertyDescriptor = original;
                }
                class Box { constructor(child) { this.child = child; } }
                const encoder = runtime.createEncoder([{
                    tag: "box",
                    matches: value => value instanceof Box,
                    encode: (value, context) => [context.encodeChild(value.child)],
                }]);
                const decoder = runtime.createDecoder([{
                    tag: "box",
                    allocate: () => new Box(null),
                    populate: (value, fields, context) => { value.child = context.decodeChild(fields[0]); },
                }]);
                let previousEncode = 0;
                let previousDecode = 0;
                for (const length of [32, 64, 128]) {
                    let source = null;
                    for (let index = 0; index < length; index++) source = new Box(source);
                    descriptorCalls = 0;
                    const packet = encoder(source);
                    const encodeCalls = descriptorCalls;
                    descriptorCalls = 0;
                    let value = decoder(packet.wire, []);
                    const decodeCalls = descriptorCalls;
                    let count = 0;
                    while (value !== null) { count++; value = value.child; }
                    check(count === length, "all nested codecs must decode");
                    if (previousEncode !== 0) {
                        check(encodeCalls <= previousEncode * 2.5, "encoding must not revalidate every ancestor subtree");
                        check(decodeCalls <= previousDecode * 2.5, "decoding must not revalidate every ancestor subtree");
                    }
                    previousEncode = encodeCalls;
                    previousDecode = decodeCalls;
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun sourceValidatesTheFinalStateOfMutableChildFields() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                let getterCalls = 0;
                let mutate;
                const encoder = runtime.createEncoder([{
                    tag: "box",
                    matches: () => true,
                    encode(value, context) {
                        if (value.child === null) return [{ amount: 1 }];
                        const child = context.encodeChild(value.child);
                        mutate(child);
                        return [child];
                    },
                }]);
                const input = { child: { child: null } };
                mutate = child => { child[2].amount = 2; };
                check(JSON.parse(encoder(input).wire)[2][2].amount === 2, "valid assembly mutations must be preserved");
                for (const invalidMutation of [
                    child => { child[2].amount = -0; },
                    child => { child.extra = true; },
                    child => { child[2].self = child[2]; },
                    child => Object.defineProperty(child[2], "amount", {
                        configurable: true,
                        enumerable: true,
                        get() { getterCalls++; return 1; },
                    }),
                ]) {
                    mutate = invalidMutation;
                    let rejected = false;
                    try { encoder(input); } catch (_) { rejected = true; }
                    check(rejected, "final invalid metadata must not reuse an earlier successful validation");
                }
                check(getterCalls === 0, "validation must not execute a metadata getter");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun sourceRejectsMalformedFieldContainersBeforeCopyingOrInvokingGetters() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                let getterCalls = 0;
                let createFields;
                const encoder = runtime.createEncoder([{
                    tag: "box",
                    matches: () => true,
                    encode: () => createFields(),
                }]);
                for (const malformedFields of [
                    () => { const fields = [1]; fields.extra = true; return fields; },
                    () => { const fields = [1]; fields[Symbol("extra")] = true; return fields; },
                    () => { const fields = [1]; fields.length = 2; return fields; },
                    () => { const fields = [1]; Object.setPrototypeOf(fields, []); return fields; },
                    () => Object.freeze([1]),
                    () => { const fields = [1]; Object.defineProperty(fields, "0", { writable: false }); return fields; },
                    () => {
                        const fields = [1];
                        Object.defineProperty(fields, "0", {
                            configurable: true,
                            enumerable: true,
                            get() { getterCalls++; return 1; },
                        });
                        return fields;
                    },
                ]) {
                    createFields = malformedFields;
                    let rejected = false;
                    try { encoder({}); } catch (_) { rejected = true; }
                    check(rejected, "malformed source fields must be rejected before their shape is lost");
                }
                check(getterCalls === 0, "copying source fields must not execute index getters");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun destinationFieldsAreDetachedReadOnlySnapshotsAndDecodedValuesRemainMutable() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                const metadata = { nested: { amount: 1 } };
                const payload = { items: ["a", 3, [1, 2]], self: ["r", 2] };
                let getterCalls = 0;
                const decoder = runtime.createDecoder([{
                    tag: "box",
                    allocate(fields) {
                        check(Object.isFrozen(fields) && Object.isFrozen(fields[0].nested), "fields must be deeply frozen");
                        let mutationRejected = false;
                        try {
                            (function () { "use strict"; fields[0].nested.amount = -0; })();
                        } catch (error) { mutationRejected = error instanceof TypeError; }
                        check(mutationRejected, "a nested field mutation must fail explicitly in strict mode");
                        metadata.nested.amount = -0;
                        Object.defineProperty(payload, "items", {
                            configurable: true,
                            enumerable: true,
                            get() { getterCalls++; throw new Error("original payload must not be read again"); },
                        });
                        return {};
                    },
                    populate(value, fields, context) {
                        check(fields[0].nested.amount === 1, "caller mutations must not alter the snapshot");
                        value.child = context.decodeChild(fields[1]);
                        check(value.child.self === value.child, "preserve child graph identity");
                        value.child.items.push(3);
                        value.child.extra = true;
                        check(Object.isFrozen(fields[1][2]), "decoding must not mutate the protocol snapshot");
                    },
                }]);
                const decoded = decoder(["box", 1, metadata, ["o", 2, payload]], [], true);
                check(decoded.child.items.join(",") === "1,2,3" && decoded.child.extra, "decoded builtin values must remain mutable");
                check(getterCalls === 0, "snapshot reads must never invoke a later caller getter");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun destinationRevalidatesMutableOriginalsAcrossSeparateConsumerCalls() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                const shared = { amount: 1 };
                let allocations = 0;
                const decoder = runtime.createDecoder([{
                    tag: "box",
                    allocate() { allocations++; return {}; },
                    populate(value, fields) {
                        value.amount = fields[0].amount;
                        shared.amount = -0;
                    },
                }]);
                let rejected = false;
                try {
                    decoder(["a", 1, [["box", 2, shared], ["box", 3, shared]]], [], true);
                } catch (_) { rejected = true; }
                check(rejected && allocations === 1, "a mutable original must be validated again before another consumer allocation");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun consumerSnapshotsNormalizeEscapedKeysAndPreserveChildGraphReferences() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                class Box { constructor(metadata, child) { this.metadata = metadata; this.child = child; } }
                const encoder = runtime.createEncoder([{
                    tag: "box",
                    matches: value => value instanceof Box,
                    encode: (value, context) => [value.metadata, context.encodeChild(value.child)],
                }]);
                const decoder = runtime.createDecoder([{
                    tag: "box",
                    allocate: () => new Box(null, null),
                    populate(value, fields, context) {
                        value.metadata = fields[0];
                        value.child = context.decodeChild(fields[1]);
                    },
                }]);
                const metadata = JSON.parse('{"__proto__":1,"\\u0000__proto__":2}');
                const child = JSON.parse('{"__proto__":{"marker":3},"\\u0000__proto__":4}');
                child.self = child;
                const packet = encoder(new Box(metadata, child));
                const decoded = decoder(packet.wire, []);
                check(Object.getPrototypeOf(decoded.metadata) === Object.prototype, "metadata prototype must remain plain");
                check(decoded.metadata.__proto__ === 1 && decoded.metadata["\u0000__proto__"] === 2, "normalize metadata keys once");
                check(Object.getPrototypeOf(decoded.child) === Object.prototype, "decoded child prototype must remain plain");
                check(decoded.child.__proto__.marker === 3 && decoded.child["\u0000__proto__"] === 4, "normalize builtin payload keys once");
                check(decoded.child.self === decoded.child, "preserve graph references inside snapshotted children");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun snapshotsPreserveRejectionOfAliasedMaterializedPayloads() {
        assertEquals(
            "ok",
            evaluateString(
                """
                const runtime = ($expressionValueCodecFactorySource)();
                const decoder = runtime.createDecoder([{
                    tag: "box",
                    allocate: () => ({}),
                    populate: (value, fields, context) => { value.child = context.decodeChild(fields[0]); },
                }]);
                for (const tag of ["o", "a"]) {
                    for (const mode of ["both", "first", "last"]) {
                        const payload = tag === "o" ? { amount: 1 } : [1];
                        const first = mode === "last" ? [tag, 2, payload] : ["box", 2, [tag, 3, payload]];
                        const last = mode === "first" ? [tag, 4, payload] : ["box", 4, [tag, 5, payload]];
                        let rejected = false;
                        try { decoder(["a", 1, [first, last]], [], true); } catch (_) { rejected = true; }
                        check(rejected, "one payload must not acquire two graph identities across " + mode + " snapshots");
                    }
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    private fun evaluateString(body: String): String =
        JsContext().use { context ->
            (
                context.evaluateScript(
                    """
                    (() => {
                        const check = (condition, message) => { if (!condition) throw new Error(message); };
                        $body
                    })()
                    """.trimIndent(),
                ) as JsString
            ).use { it.toString() }
        }
}
