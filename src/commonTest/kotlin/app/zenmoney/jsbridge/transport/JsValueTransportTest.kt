package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsBoolean
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsFunction
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.boolean
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.map
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueCodec
import app.zenmoney.jsbridge.serialization.JsValueDecoder
import app.zenmoney.jsbridge.serialization.JsValueEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs

class JsValueTransportTest {
    @Test
    fun usesConfiguredCodecThroughPublicInterface() {
        val codec =
            object : JsValueCodec {
                var createdEncoderCount = 0
                var createdDecoderCount = 0

                override fun createEncoder(context: JsContext): JsValueEncoder {
                    createdEncoderCount++
                    return ExpressionValueCodec.createEncoder(context)
                }

                override fun createDecoder(context: JsContext): JsValueDecoder {
                    createdDecoderCount++
                    return ExpressionValueCodec.createDecoder(context)
                }
            }

        JsContext().use { sourceContext ->
            JsContext().use { destinationContext ->
                JsValueTransportSource<Unit>(sourceContext, codec).use { source ->
                    JsValueTransportDestination<Unit>(destinationContext, codec).use { destination ->
                        assertEquals(1, codec.createdEncoderCount)
                        assertEquals(1, codec.createdDecoderCount)
                        val packet = JsValueTransportPacket<Unit>()
                        sourceContext.evaluateScript("'custom codec'").use { value ->
                            source.encode(value, packet)
                        }
                        try {
                            destination.decode(packet).use { value ->
                                assertEquals("custom codec", assertIs<JsString>(value).toString())
                            }
                        } finally {
                            packet.reset()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun transfersStructuredGraphBetweenIndependentContexts() {
        JsContext().use { source ->
            JsContext().use { destination ->
                JsValueTransportSource<Unit>(source).use { transportSource ->
                    JsValueTransportDestination<Unit>(destination).use { transportDestination ->
                        val packet = JsValueTransportPacket<Unit>()
                        source
                            .evaluateScript(
                                """
                                (() => {
                                    const bytes = Uint8Array.from([0, 128, 255]);
                                    const value = {
                                        text: "value",
                                        number: -0,
                                        sparse: new Array(3),
                                        bytes: bytes,
                                        sameBytes: bytes,
                                    };
                                    value.sparse[1] = undefined;
                                    value.self = value;
                                    return value;
                                })()
                                """.trimIndent(),
                            ).use { sourceValue ->
                                transportSource.encode(sourceValue, packet)
                                try {
                                    transportDestination.decode(packet).use { destinationValue ->
                                        destination.globalThis["transported"] = destinationValue
                                        assertEquals(
                                            listOf(true, true, true, true, true, true),
                                            assertIs<JsArray>(
                                                destination.evaluateScript(
                                                    """
                                                    [
                                                        transported.text === "value",
                                                        Object.is(transported.number, -0),
                                                        transported.self === transported,
                                                        transported.sameBytes === transported.bytes,
                                                        Array.from(transported.bytes).join(",") === "0,128,255",
                                                        transported.sparse.length === 3 &&
                                                            !(0 in transported.sparse) &&
                                                            1 in transported.sparse &&
                                                            transported.sparse[1] === undefined &&
                                                            !(2 in transported.sparse),
                                                    ]
                                                    """.trimIndent(),
                                                ),
                                            ).map { it.boolean },
                                        )
                                    }
                                } finally {
                                    packet.reset()
                                }
                            }
                    }
                }
            }
        }
    }

    @Test
    fun packetIsReusableAfterResetAndDecode() {
        JsContext().use { sourceContext ->
            JsContext().use { destinationContext ->
                JsValueTransportSource<Unit>(sourceContext).use { source ->
                    JsValueTransportDestination<Unit>(destinationContext).use { destination ->
                        val packet = JsValueTransportPacket<Unit>()
                        sourceContext.evaluateScript("'discarded'").use { value ->
                            source.encode(value, packet)
                        }
                        packet.reset()

                        listOf("first", "second").forEach { expected ->
                            sourceContext.evaluateScript("'$expected'").use { value ->
                                source.encode(value, packet)
                            }
                            try {
                                destination.decode(packet).use { value ->
                                    assertEquals(expected, assertIs<JsString>(value).toString())
                                }
                            } finally {
                                packet.reset()
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun sourceFailureRetainsMappedPayloadsUntilReset() {
        JsContext().use { context ->
            val matches =
                assertIs<JsFunction>(
                    context.evaluateScript("value => value !== null && value.__packetReference === true"),
                )
            try {
                val codec = createReferenceCodec(matches)
                var mappedPayloadCount = 0
                JsValueTransportSource(
                    context = context,
                    codec = codec,
                    referenceValueMapper = { _: JsValue ->
                        if (mappedPayloadCount++ == 0) "first payload" else error("mapping failed")
                    },
                ).use { source ->
                    val packet = JsValueTransportPacket<String>()
                    context
                        .evaluateScript(
                            "[{ __packetReference: true }, { __packetReference: true }]",
                        ).use { value ->
                            assertFails { source.encode(value, packet) }
                        }

                    assertEquals(1, packet.payloadCount)
                    assertEquals("first payload", packet.payload(0))
                    context.evaluateScript("'blocked until reset'").use { value ->
                        assertFails { source.encode(value, packet) }
                    }

                    packet.reset()
                    assertEquals(0, packet.payloadCount)
                    context.evaluateScript("'reusable after reset'").use { value ->
                        source.encode(value, packet)
                    }
                    packet.reset()
                }
            } finally {
                matches.close()
            }
        }
    }

    @Test
    fun sourceFailureWithoutMappedPayloadsKeepsPacketReusable() {
        JsContext().use { context ->
            val codec =
                ExpressionValueCodec {
                    eval(
                        """
                        [{
                            tag: "failing",
                            matches() { throw new Error("encoding failed"); },
                            encode() { return []; },
                        }]
                        """.trimIndent(),
                    )
                }
            JsValueTransportSource<Unit>(context, codec).use { source ->
                val packet = JsValueTransportPacket<Unit>()
                context.evaluateScript("() => {}").use { value ->
                    assertFails { source.encode(value, packet) }
                }

                assertEquals(0, packet.payloadCount)
                context.evaluateScript("'reused without reset'").use { value ->
                    source.encode(value, packet)
                }
                packet.reset()
            }
        }
    }

    @Test
    fun closeClearsPacketAndPermanentlyPreventsReuse() {
        JsContext().use { context ->
            val matches =
                assertIs<JsFunction>(
                    context.evaluateScript("value => value !== null && value.__packetReference === true"),
                )
            try {
                val codec = createReferenceCodec(matches)
                JsValueTransportSource(
                    context = context,
                    codec = codec,
                    referenceValueMapper = { _: JsValue -> "owned payload" },
                ).use { source ->
                    val packet = JsValueTransportPacket<String>()
                    context.evaluateScript("({ __packetReference: true })").use { value ->
                        source.encode(value, packet)
                    }
                    assertEquals(1, packet.payloadCount)

                    packet.close()
                    packet.close()

                    assertEquals(0, packet.payloadCount)
                    assertFails { packet.payload(0) }
                    assertFails { packet.reset() }
                    context.evaluateScript("'closed packet'").use { value ->
                        assertFails { source.encode(value, packet) }
                    }
                }
            } finally {
                matches.close()
            }
        }
    }

    @Test
    fun genericPayloadCanCarryDirectValueAndConsumerMetadata() {
        JsContext().use { destinationContext ->
            JsContext().use { sourceContext ->
                val matches =
                    assertIs<JsFunction>(
                        sourceContext.evaluateScript("value => value !== null && value.__exported === true"),
                    )
                val createHandle =
                    assertIs<JsFunction>(
                        destinationContext.evaluateScript(
                            """
                            () => ({ __handle: true })
                            """.trimIndent(),
                        ),
                    )
                try {
                    run {
                        val codec = createReferenceCodec(matches)
                        val source =
                            JsValueTransportSource<MetadataPayload>(
                                context = sourceContext,
                                codec = codec,
                                referenceValueMapper = { value ->
                                    MetadataPayload(value.escape(), "source metadata")
                                },
                            )
                        var receivedMetadata: String? = null
                        val destination =
                            JsValueTransportDestination(
                                context = destinationContext,
                                referenceValueResolver =
                                    JsValueTransportReferenceValueResolver<MetadataPayload> { scope, payload ->
                                        receivedMetadata = payload.metadata
                                        with(scope) { createHandle() }
                                    },
                            )
                        source.use {
                            destination.use {
                                val packet = JsValueTransportPacket<MetadataPayload>()
                                sourceContext.evaluateScript("({ __exported: true })").use { value ->
                                    source.encode(value, packet)
                                    try {
                                        destination.decode(packet).use { handle ->
                                            destinationContext.globalThis["genericPayloadHandle"] = handle
                                            assertEquals("source metadata", receivedMetadata)
                                            assertEquals(
                                                true,
                                                assertIs<JsBoolean>(
                                                    destinationContext.evaluateScript(
                                                        "genericPayloadHandle.__handle === true",
                                                    ),
                                                ).boolean,
                                            )
                                        }
                                        assertEquals(1, packet.payloadCount)
                                        val payload = packet.payload(0)
                                        packet.reset()
                                        assertFalse(payload.value.isClosed)
                                        payload.value.close()
                                    } finally {
                                        repeat(packet.payloadCount) { packet.payload(it).value.close() }
                                        packet.reset()
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    createHandle.close()
                    matches.close()
                }
            }
        }
    }

    private fun createReferenceCodec(matches: JsFunction): JsValueCodec =
        ExpressionValueCodec {
            JsArray(listOf(matches))
        }
}

private class MetadataPayload(
    val value: JsValue,
    val metadata: String,
)
