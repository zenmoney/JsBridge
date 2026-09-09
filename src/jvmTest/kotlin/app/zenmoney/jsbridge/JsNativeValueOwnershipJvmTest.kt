package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class JsNativeValueOwnershipJvmTest {
    @Test
    fun failedObjectClassificationReleasesIncomingNativeValues() {
        JsEngineContext().use { context ->
            val baseline = context.v8Runtime.referenceCount
            for (script in listOf(
                "({get then(){throw new Error('getter failed')}})",
                "new Proxy({}, {has(){throw new Error('has failed')}})",
            )) {
                repeat(25) {
                    assertFailsWith<Exception> { context.evaluateScript(script).close() }
                }
                assertEquals(baseline, context.v8Runtime.referenceCount, script)
            }
        }
    }

    @Test
    fun failedPropertyClassificationReleasesIncomingNativeValues() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val parent = assertIs<JsObject>(eval("({child: {get then(){throw new Error('getter failed')}}})"))
                val baseline = context.v8Runtime.referenceCount
                repeat(25) {
                    assertFailsWith<Exception> { parent["child"] }
                }
                assertEquals(baseline, context.v8Runtime.referenceCount)
            }
        }
    }

    @Test
    fun failedArrayPopulationReleasesTheNewNativeArray() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val number = JsNumber(1)
                val values =
                    sequence {
                        yield(number)
                        throw IllegalArgumentException("iterator failed")
                    }.asIterable()
                val baseline = context.v8Runtime.referenceCount
                repeat(25) {
                    assertFailsWith<IllegalArgumentException> { context.createArray(values).close() }
                }
                assertEquals(baseline, context.v8Runtime.referenceCount)
            }
        }
    }

    @Test
    fun repeatedViewConversionsReleaseTemporaryBufferHandles() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val bytes = assertIs<JsUint8Array>(eval("new Uint8Array([10,20,30,40]).subarray(1,3)"))
                val baseline = context.v8Runtime.referenceCount
                repeat(100) { assertContentEquals(byteArrayOf(20, 30), bytes.toByteArray()) }
                assertEquals(baseline, context.v8Runtime.referenceCount)
            }
        }
    }

    @Test
    fun viewMetadataIsReadBeforeAcquiringTheDirectBuffer() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val bytes =
                    assertIs<JsUint8Array>(
                        eval(
                            """
                            (() => {
                                const storage = new Uint8Array([10,20,30,40]);
                                const value = storage.subarray(1,3);
                                let bufferRead = false;
                                globalThis.__typedArrayReadOrder = [];
                                Object.defineProperties(value, {
                                    byteLength: {
                                        get() { __typedArrayReadOrder.push('length'); return 2; }
                                    },
                                    byteOffset: {
                                        get() {
                                            __typedArrayReadOrder.push('offset');
                                            if (bufferRead) throw new Error('metadata read after buffer acquisition');
                                            return 1;
                                        }
                                    },
                                    buffer: {
                                        get() {
                                            __typedArrayReadOrder.push('buffer');
                                            bufferRead = true;
                                            return storage.buffer;
                                        }
                                    }
                                });
                                return value;
                            })()
                            """.trimIndent(),
                        ),
                    )

                assertContentEquals(byteArrayOf(20, 30), bytes.toByteArray())
                assertEquals("length,offset,buffer", eval("__typedArrayReadOrder.join(',')").string)
            }
        }
    }

    @Test
    fun failingViewMetadataReadsDoNotLeakNativeReferences() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val bytes =
                    assertIs<JsUint8Array>(
                        eval(
                            """
                            (() => {
                                const value = new Uint8Array([10,20,30,40]).subarray(1,3);
                                Object.defineProperty(value, 'byteOffset', {
                                    get() { throw new Error('offset getter failed'); }
                                });
                                return value;
                            })()
                            """.trimIndent(),
                        ),
                    )
                val baseline = context.v8Runtime.referenceCount
                repeat(25) {
                    assertFailsWith<Exception> { bytes.toByteArray() }
                }
                assertEquals(baseline, context.v8Runtime.referenceCount)
            }
        }
    }

    @Test
    fun boxedBigIntsRemainObjectsWithoutLeakingNativeReferences() {
        JsEngineContext().use { context ->
            val baseline = context.v8Runtime.referenceCount
            for (literal in listOf(
                "1n",
                "9223372036854775808n",
                "18446744073709551616n",
                "-9223372036854775809n",
                "-18446744073709551617n",
                "10n ** 1000n",
                "-(10n ** 1000n)",
            )) {
                repeat(25) {
                    jsScoped(context) {
                        val boxed = assertIs<JsObject>(eval("Object($literal)"))
                        assertFalse(boxed is JsNumber, literal)
                        assertEquals(boxed, JsValueAlias(boxed), literal)
                    }
                }
                assertEquals(baseline, context.v8Runtime.referenceCount, literal)
            }
        }
    }

    @Test
    fun bigIntWrappingDoesNotCallJavaScriptCoercion() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val values =
                    assertIs<JsArray>(
                        eval(
                            """
                            const value = Object(18446744073709551616n);
                            const fail = () => { throw new Error('overridden conversion'); };
                            value.valueOf = fail;
                            BigInt.prototype.valueOf = fail;
                            Function.prototype.call = fail;
                            globalThis.Number = fail;
                            [value, 18446744073709551616n];
                            """.trimIndent(),
                        ),
                    )

                val boxed = assertIs<JsObject>(values[0])
                assertFalse(boxed is JsNumber)
                assertEquals(boxed, JsValueAlias(boxed))
                val number = assertIs<JsNumber>(values[1])
                assertEquals(18_446_744_073_709_551_616.0, number.double)
                assertEquals(number, JsValueAlias(number))
            }
        }
    }
}
