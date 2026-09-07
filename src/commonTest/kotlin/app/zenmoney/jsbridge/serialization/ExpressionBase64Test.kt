package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsString
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionBase64Test {
    @Test
    fun availableAndFallbackCodecsUseTheSameCanonicalFormat() {
        assertEquals(
            "ok",
            evaluateWithCodecs(
                """
                for (let size = 0; size <= 64; size++) {
                    const storage = new Uint8Array(size + 4);
                    for (let i = 0; i < storage.length; i++) storage[i] = (i * 31 + size) & 255;
                    const bytes = storage.subarray(2, 2 + size);
                    const expected = fallback.bytesToBase64(bytes);
                    check(codec.bytesToBase64(bytes) === expected, "native encoding must respect view boundaries and padding");
                    for (const decoder of [codec, fallback]) {
                        const decoded = decoder.base64ToBytes(expected);
                        check(decoded.length === size, "decoded byte length");
                        check(decoded.every((byte, i) => byte === bytes[i]), "decoded bytes");
                    }
                }
                for (const invalid of [
                    "A", "AA", "AAA", "AB==", "AAB=", "====", "A===", "=AAA", "AA=A", "AA==AAAA",
                    "AA-_", "AA!A", "AA==    ", "    ", "AA==\n\r\t ", "AA==\u000c\u000c\u000c\u000c",
                ]) {
                    for (const decoder of [codec, fallback]) {
                        let rejected = false;
                        try { decoder.base64ToBytes(invalid); } catch (_) { rejected = true; }
                        check(rejected, "non-canonical base64 accepted: " + JSON.stringify(invalid));
                    }
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun capturesAvailableBase64MethodsAndValidatesBeforeCallingThem() {
        assertEquals(
            "ok",
            evaluateWithCodecs(
                """
                let encodeCalls = 0;
                let decodeCalls = 0;
                const nativeEncode = Uint8Array.prototype.toBase64;
                const nativeDecode = Uint8Array.fromBase64;
                Uint8Array.prototype.toBase64 = function () {
                    encodeCalls++;
                    return nativeEncode ? nativeEncode.call(this) : fallback.bytesToBase64(this);
                };
                Uint8Array.fromBase64 = function (value, options) {
                    check(this === Uint8Array, "static decoder receiver");
                    check(options.lastChunkHandling === "strict", "native decoder must enforce canonical trailing bits");
                    decodeCalls++;
                    return nativeDecode ? nativeDecode.call(this, value, options) : fallback.base64ToBytes(value);
                };
                try {
                    const captured = ($expressionValueCoreCodecFactorySource)();
                    Uint8Array.prototype.toBase64 = Uint8Array.fromBase64 = () => { throw new Error("mutated codec"); };
                    const bytes = Uint8Array.from([9, 0, 128, 255, 9]).subarray(1, 4);
                    check(captured.bytesToBase64(bytes) === "AID/", "use captured native encoder");
                    check(Array.from(captured.base64ToBytes("AID/")).join(",") === "0,128,255", "use captured native decoder");
                    check(encodeCalls === 1 && decodeCalls === 1, "available native codecs must be called");

                    let rejected = false;
                    try { captured.base64ToBytes("AA==    "); } catch (_) { rejected = true; }
                    check(rejected && decodeCalls === 1, "reject whitespace before invoking the built-in decoder");
                    const memory = new WebAssembly.Memory({ initial: 1, maximum: 2 });
                    const detached = new Uint8Array(memory.buffer);
                    memory.grow(1);
                    rejected = false;
                    try { captured.bytesToBase64(detached); } catch (_) { rejected = true; }
                    check(rejected && encodeCalls === 1, "reject detached buffers before invoking the built-in encoder");
                } finally {
                    restoreMethods();
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    private fun evaluateWithCodecs(body: String): String =
        JsContext().use { context ->
            (
                context.evaluateScript(
                    """
                    (() => {
                        const encodeDescriptor = Object.getOwnPropertyDescriptor(Uint8Array.prototype, "toBase64");
                        const decodeDescriptor = Object.getOwnPropertyDescriptor(Uint8Array, "fromBase64");
                        function restoreMethods() {
                            if (encodeDescriptor) Object.defineProperty(Uint8Array.prototype, "toBase64", encodeDescriptor);
                            else delete Uint8Array.prototype.toBase64;
                            if (decodeDescriptor) Object.defineProperty(Uint8Array, "fromBase64", decodeDescriptor);
                            else delete Uint8Array.fromBase64;
                        }
                        let fallback;
                        try {
                            Uint8Array.prototype.toBase64 = undefined;
                            Uint8Array.fromBase64 = undefined;
                            fallback = ($expressionValueCoreCodecFactorySource)();
                        } finally {
                            restoreMethods();
                        }
                        const codec = ($expressionValueCoreCodecFactorySource)();
                        const check = (condition, message) => { if (!condition) throw new Error(message); };
                        $body
                    })()
                    """.trimIndent(),
                ) as JsString
            ).use { it.toString() }
        }
}
