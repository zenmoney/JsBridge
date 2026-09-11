package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsFunction
import app.zenmoney.jsbridge.JsObject
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.boolean
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.eval
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.invoke
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.map
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueCodec
import app.zenmoney.jsbridge.serialization.JsValueDecoder
import app.zenmoney.jsbridge.serialization.JsValueEncoder
import app.zenmoney.jsbridge.serialization.JsValueWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsValueTransportEncodedTest {
    @Test
    fun transportsJavascriptEncodedGraphAndBothMixedCodecDirections() {
        for ((nativeEncode, nativeDecode) in listOf(false to false, true to false, false to true)) {
            JsContext().use { sourceContext ->
                JsContext().use { destinationContext ->
                    val mappedValues = arrayListOf<JsValue>()
                    val resolvedValues = arrayListOf<JsValue>()
                    val resolvedPayloads = arrayListOf<String>()
                    val codec =
                        ExpressionValueCodec {
                            eval("[value => value !== null && value.byReference === true]")
                        }
                    JsValueTransportSource(
                        context = sourceContext,
                        codec = codec,
                        referenceValueMapper = { value ->
                            mappedValues += value
                            jsScoped(sourceContext) { assertIs<JsObject>(value)["id"].toString() }
                        },
                    ).use { source ->
                        JsValueTransportDestination(
                            context = destinationContext,
                            referenceValueResolver =
                                JsValueTransportReferenceValueResolver<String> { payload ->
                                    resolvedPayloads += payload
                                    eval("({ id: '$payload', resolved: true })").also { resolvedValues += it }
                                },
                        ).use { destination ->
                            JsValueTransportPacket<String>().use { packet ->
                                jsScoped(sourceContext) {
                                    val value =
                                        eval(
                                            """
                                            (() => {
                                                const first = { id: "first", byReference: true };
                                                const second = { id: "second", byReference: true };
                                                const bytes = Uint8Array.from([0, 128, 255]);
                                                const graph = { first, second, firstAgain: first, bytes, sameBytes: bytes };
                                                graph.self = graph;
                                                return graph;
                                            })()
                                            """.trimIndent(),
                                        )
                                    if (nativeEncode) {
                                        assertSame(packet, source.encode(value, packet))
                                    } else {
                                        val encode =
                                            assertIs<JsFunction>(
                                                eval(
                                                    """
                                                    (${ExpressionValueCodec.javaScriptFactorySource})().createEncoder([
                                                        value => value !== null && value.byReference === true,
                                                    ])
                                                    """.trimIndent(),
                                                ),
                                            )
                                        val encoded = assertIs<JsObject>(encode(value))
                                        val referenceValues = assertIs<JsArray>(encoded["referenceValues"])
                                        assertSame(packet, source.pack(encoded, packet))
                                        assertFalse(encoded.isClosed)
                                        assertFalse(referenceValues.isClosed)
                                        assertTrue(encoded in this)
                                        assertTrue(referenceValues in this)
                                        assertEquals(2, referenceValues.size)
                                    }
                                    assertFalse(value.isClosed)
                                }
                                assertEquals(listOf("first", "second"), List(packet.payloadCount) { packet.payload(it) })
                                assertTrue(mappedValues.all { it.isClosed })

                                jsScoped(destinationContext) {
                                    val decoded =
                                        if (nativeDecode) {
                                            destination.decode(packet)
                                        } else {
                                            val unpacked = destination.unpack(packet)
                                            assertTrue(unpacked in this)
                                            assertIs<JsString>(unpacked["wire"])
                                            assertEquals(2, assertIs<JsArray>(unpacked["resolvedReferenceValues"]).size)
                                            val decode =
                                                assertIs<JsFunction>(
                                                    eval("(${ExpressionValueCodec.javaScriptFactorySource})().createDecoder()"),
                                                )
                                            decode(unpacked["wire"], unpacked["resolvedReferenceValues"])
                                        }
                                    assertTrue(resolvedValues.all { it.isClosed })
                                    assertEquals(listOf("first", "second"), resolvedPayloads)
                                    destinationContext.globalThis["transported"] = decoded
                                    assertEquals(
                                        List(7) { true },
                                        assertIs<JsArray>(
                                            eval(
                                                """
                                                [
                                                    transported.self === transported,
                                                    transported.first === transported.firstAgain,
                                                    transported.first !== transported.second,
                                                    transported.first.id === "first" && transported.first.resolved === true,
                                                    transported.second.id === "second" && transported.second.resolved === true,
                                                    transported.bytes === transported.sameBytes,
                                                    Array.from(transported.bytes).join(",") === "0,128,255",
                                                ]
                                                """.trimIndent(),
                                            ),
                                        ).map { it.boolean },
                                    )
                                }
                                assertEquals(2, packet.payloadCount)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun preservesOpaqueWireAndDuplicateReferenceEntriesWithoutCreatingCodecs() {
        JsContext().use { context ->
            val mappedValues = arrayListOf<JsValue>()
            val resolvedPayloads = arrayListOf<String>()
            JsValueTransportSource(
                context = context,
                codec = UnusedTransportCodec,
                referenceValueMapper = { value ->
                    mappedValues += value
                    jsScoped(context) { assertIs<JsObject>(value)["id"].toString() }
                },
            ).use { source ->
                JsValueTransportDestination(
                    context = context,
                    codec = UnusedTransportCodec,
                    referenceValueResolver =
                        JsValueTransportReferenceValueResolver<String> { payload ->
                            resolvedPayloads += payload
                            eval("({ id: '$payload' })")
                        },
                ).use { destination ->
                    JsValueTransportPacket<String>().use { packet ->
                        jsScoped(context) {
                            val references = assertIs<JsArray>(eval("(() => { const a = { id: 'a' }; return [a, a, { id: 'b' }]; })()"))
                            val wire = JsValueWire("opaque\u0000custom wire\n[not an expression]")
                            assertSame(packet, source.pack(wire, references, packet))
                            assertFalse(references.isClosed)
                            assertTrue(references in this)
                            assertTrue(mappedValues.all { it in this && !it.isClosed })
                            assertEquals(listOf("a", "a", "b"), List(packet.payloadCount) { packet.payload(it) })
                            val unpacked = destination.unpack(packet)
                            assertEquals(wire.value, assertIs<JsString>(unpacked["wire"]).toString())
                            assertEquals(listOf("a", "a", "b"), resolvedPayloads)
                            assertEquals(
                                listOf("a", "a", "b"),
                                assertIs<JsArray>(unpacked["resolvedReferenceValues"]).map {
                                    assertIs<JsObject>(it)["id"].toString()
                                },
                            )
                        }
                        assertTrue(mappedValues.all { it.isClosed })
                    }
                }
            }
        }
    }

    @Test
    fun unpackedObjectCanEscapeCallerScopeAndDestinationLifetime() {
        JsContext().use { context ->
            JsValueTransportPacket<Unit>().use { packet ->
                JsValueTransportSource<Unit>(context, UnusedTransportCodec).use { source ->
                    jsScoped(context) {
                        source.pack(JsValueWire("opaque"), JsArray(emptyList()), packet)
                    }
                }
                val unpacked =
                    JsValueTransportDestination<Unit>(context, UnusedTransportCodec).use { destination ->
                        jsScoped(context) { destination.unpack(packet).escape() }
                    }
                unpacked.use {
                    assertFalse(it.isClosed)
                    jsScoped(context) {
                        assertEquals("opaque", assertIs<JsString>(it["wire"]).toString())
                        assertEquals(0, assertIs<JsArray>(it["resolvedReferenceValues"]).size)
                    }
                }
            }
        }
    }

    @Test
    fun invalidEncodedFieldsDoNotMapReferencesOrPopulatePacket() {
        JsContext().use { context ->
            var mapperCalls = 0
            JsValueTransportSource(
                context = context,
                codec = UnusedTransportCodec,
                referenceValueMapper = { _: JsValue -> mapperCalls++ },
            ).use { source ->
                JsValueTransportPacket<Int>().use { packet ->
                    jsScoped(context) {
                        listOf(
                            "({})",
                            "({ wire: 42, referenceValues: [{}] })",
                            "({ wire: null, referenceValues: [{}] })",
                            "({ wire: 'opaque' })",
                            "({ wire: 'opaque', referenceValues: null })",
                            "({ wire: 'opaque', referenceValues: {} })",
                        ).forEach { script ->
                            val encoded = assertIs<JsObject>(eval(script))
                            assertFailsWith<IllegalArgumentException> { source.pack(encoded, packet) }
                            assertFalse(encoded.isClosed)
                            assertEquals(0, mapperCalls)
                            assertEquals(0, packet.payloadCount)
                            source.pack(JsValueWire("still reusable"), JsArray(emptyList()), packet)
                            packet.reset()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rejectsForeignInputsAndScopeWithoutMappingOrConsumingPacket() {
        JsContext().use { context ->
            JsContext().use { otherContext ->
                var mapperCalls = 0
                var resolverBegins = 0
                JsValueTransportSource(
                    context = context,
                    codec = UnusedTransportCodec,
                    referenceValueMapper = { _: JsValue -> mapperCalls++ },
                ).use { source ->
                    JsValueTransportDestination(
                        context = context,
                        codec = UnusedTransportCodec,
                        referenceValueResolver =
                            object : JsValueTransportReferenceValueResolver<Int> {
                                override fun begin(payloadCount: Int) {
                                    resolverBegins++
                                }

                                context(scope: JsScope)
                                override fun resolve(payload: Int): JsValue = eval("({})")
                            },
                    ).use { destination ->
                        JsValueTransportPacket<Int>().use { packet ->
                            jsScoped(otherContext) {
                                val encoded = assertIs<JsObject>(eval("({ wire: 'opaque', referenceValues: [{}] })"))
                                val references = assertIs<JsArray>(encoded["referenceValues"])
                                assertFailsWith<IllegalArgumentException> { source.pack(encoded, packet) }
                                assertFailsWith<IllegalArgumentException> {
                                    source.pack(JsValueWire("opaque"), references, packet)
                                }
                                assertFalse(encoded.isClosed)
                                assertFalse(references.isClosed)
                            }
                            assertEquals(0, mapperCalls)
                            assertEquals(0, packet.payloadCount)
                            jsScoped(context) {
                                source.pack(JsValueWire("ready"), JsArray(emptyList()), packet)
                            }
                            jsScoped(otherContext) {
                                assertFailsWith<IllegalArgumentException> { destination.unpack(packet) }
                            }
                            assertEquals(0, resolverBegins)
                            jsScoped(context) {
                                assertEquals("ready", destination.unpack(packet)["wire"].toString())
                            }
                            assertEquals(1, resolverBegins)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun referenceTablesRequireConfiguredMapperAndResolver() {
        JsContext().use { context ->
            JsValueTransportPacket<String>().use { packet ->
                JsValueTransportSource<String>(context, UnusedTransportCodec).use { source ->
                    jsScoped(context) {
                        val encoded = assertIs<JsObject>(eval("({ wire: 'opaque', referenceValues: [{}] })"))
                        assertFailsWith<IllegalStateException> { source.pack(encoded, packet) }
                        assertEquals(0, packet.payloadCount)
                        assertFalse(encoded.isClosed)
                        source.pack(JsValueWire("empty table"), JsArray(emptyList()), packet)
                    }
                }
                JsValueTransportDestination<String>(context, UnusedTransportCodec).use { destination ->
                    jsScoped(context) {
                        assertEquals(0, assertIs<JsArray>(destination.unpack(packet)["resolvedReferenceValues"]).size)
                    }
                    packet.reset()
                    JsValueTransportSource(
                        context = context,
                        codec = UnusedTransportCodec,
                        referenceValueMapper = { _: JsValue -> "payload" },
                    ).use { source ->
                        jsScoped(context) {
                            source.pack(JsValueWire("references"), assertIs<JsArray>(eval("[{}]")), packet)
                        }
                    }
                    jsScoped(context) {
                        assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                        assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                    }
                    assertEquals(1, packet.payloadCount)
                    assertEquals("payload", packet.payload(0))
                    packet.reset()
                }
            }
        }
    }

    @Test
    fun partialPackFailureRetainsConsumerOwnedPayloadsAndAllowsReuseAfterCleanup() {
        JsContext().use { context ->
            var failMapping = true
            val mappedValues = arrayListOf<JsValue>()
            JsValueTransportSource(
                context = context,
                codec = UnusedTransportCodec,
                referenceValueMapper = { value ->
                    mappedValues += value
                    if (failMapping && mappedValues.size == 2) error("mapping failed")
                    EncodedTestPayload()
                },
            ).use { source ->
                JsValueTransportPacket<EncodedTestPayload>().use { packet ->
                    jsScoped(context) {
                        val encoded = assertIs<JsObject>(eval("({ wire: 'opaque', referenceValues: [{}, {}] })"))
                        val references = assertIs<JsArray>(encoded["referenceValues"])
                        assertFailsWith<IllegalStateException> { source.pack(encoded, packet) }
                        assertTrue(mappedValues.all { it.isClosed })
                        assertFalse(encoded.isClosed)
                        assertFalse(references.isClosed)
                        assertEquals(2, references.size)
                        assertEquals(1, packet.payloadCount)
                        val retainedPayload = packet.payload(0)
                        assertFalse(retainedPayload.closed)
                        assertFailsWith<IllegalStateException> { source.pack(encoded, packet) }
                        assertEquals(2, mappedValues.size)
                        retainedPayload.close()
                        packet.reset()
                        assertEquals(0, packet.payloadCount)

                        failMapping = false
                        source.pack(encoded, packet)
                        assertEquals(2, packet.payloadCount)
                        val payloads = List(packet.payloadCount) { packet.payload(it) }
                        packet.reset()
                        assertTrue(payloads.none { it.closed })
                        payloads.forEach { it.close() }
                    }
                }
            }
        }
    }
}

private object UnusedTransportCodec : JsValueCodec {
    override fun createEncoder(context: JsContext): JsValueEncoder = error("Packing must not create an encoder")

    override fun createDecoder(context: JsContext): JsValueDecoder = error("Unpacking must not create a decoder")
}

private class EncodedTestPayload : AutoCloseable {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
