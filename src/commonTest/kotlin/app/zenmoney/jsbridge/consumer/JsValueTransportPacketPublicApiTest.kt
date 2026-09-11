package app.zenmoney.jsbridge.consumer

import app.zenmoney.jsbridge.serialization.JsValueWire
import app.zenmoney.jsbridge.transport.JsValueTransportPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class JsValueTransportPacketPublicApiTest {
    @Test
    fun transportsOpaqueWireAndBorrowedNativePayloadsWithoutJsContext() {
        val packet = JsValueTransportPacket<NativeTransportPayload>()
        try {
            for (count in listOf(3, 1, 0)) {
                val wire = JsValueWire("custom protocol\u0000\n$count")
                val payloads = List(count) { NativeTransportPayload(it) }
                assertSame(
                    packet,
                    packet.populate { writer ->
                        writer.addAll(payloads.size) { payloads[it] }
                        wire
                    },
                )
                val consumed =
                    packet.consume { receivedWire, reader ->
                        assertEquals(wire, receivedWire)
                        assertEquals(count, reader.size)
                        arrayListOf<NativeTransportPayload>().also { received ->
                            reader.forEach { received += it }
                        }
                    }
                assertEquals(List(count) { it }, consumed.map { it.id })
                payloads.forEachIndexed { index, payload ->
                    assertSame(payload, consumed[index])
                    assertSame(payload, packet.payload(index))
                    assertFalse(payload.closed)
                }
                packet.reset()
                assertEquals(0, packet.payloadCount)
                payloads.forEach {
                    assertFalse(it.closed)
                    it.close()
                }
            }

            val retained = NativeTransportPayload(42)
            packet.populate { writer ->
                writer.addAll(1) { retained }
                JsValueWire("close without consuming")
            }
            packet.close()
            assertEquals(0, packet.payloadCount)
            assertFalse(retained.closed)
            retained.close()
        } finally {
            packet.close()
        }
    }

    @Test
    fun partialPopulateFailureRetainsPayloadsForCleanupBeforeReuse() {
        JsValueTransportPacket<NativeTransportPayload>().use { packet ->
            val first = NativeTransportPayload(0)
            var createCount = 0
            assertFailsWith<IllegalStateException> {
                packet.populate { writer ->
                    writer.addAll(3) { index ->
                        createCount++
                        if (index == 1) error("payload creation failed")
                        first
                    }
                    JsValueWire("never stored")
                }
            }
            assertEquals(2, createCount)
            assertEquals(1, packet.payloadCount)
            assertSame(first, packet.payload(0))
            assertFalse(first.closed)
            assertFailsWith<IllegalStateException> {
                packet.populate { throw AssertionError("must not enter another populate callback") }
            }
            assertFailsWith<IllegalStateException> {
                packet.consume { _, _ -> throw AssertionError("must not consume a partially populated packet") }
            }
            first.close()
            packet.reset()
            assertEquals(0, packet.payloadCount)

            assertFailsWith<IllegalArgumentException> {
                packet.populate { throw IllegalArgumentException("failed before creating any payloads") }
            }
            packet.populate { JsValueWire("reused without another reset") }
            assertEquals("reused without another reset", packet.consume { wire, _ -> wire.value })
        }
    }

    @Test
    fun consumeFailureLeavesEveryPayloadAvailableForCleanupAndReset() {
        JsValueTransportPacket<NativeTransportPayload>().use { packet ->
            val payloads = List(2) { NativeTransportPayload(it) }
            packet.populate { writer ->
                writer.addAll(payloads.size) { payloads[it] }
                JsValueWire("opaque")
            }
            var visitedCount = 0
            assertFailsWith<IllegalArgumentException> {
                packet.consume { _, reader ->
                    reader.forEach {
                        visitedCount++
                        assertSame(payloads[0], it)
                        throw IllegalArgumentException("consumer failed")
                    }
                }
            }
            assertEquals(1, visitedCount)
            assertEquals(2, packet.payloadCount)
            payloads.forEachIndexed { index, payload ->
                assertSame(payload, packet.payload(index))
                assertFalse(payload.closed)
            }
            assertFailsWith<IllegalStateException> { packet.consume { _, _ -> Unit } }
            assertFailsWith<IllegalStateException> { packet.populate { JsValueWire("blocked") } }
            payloads.forEach { it.close() }
            packet.reset()
            packet.populate { JsValueWire("reused") }
            val reusedWire =
                packet.consume { wire, reader ->
                    assertEquals(0, reader.size)
                    wire.value
                }
            assertEquals("reused", reusedWire)
        }
    }

    @Test
    fun callbackAccessorsAndReentrantOperationsRespectPacketState() {
        JsValueTransportPacket<String>().use { packet ->
            lateinit var savedWriter: JsValueTransportPacket<String>.PayloadWriter
            lateinit var savedReader: JsValueTransportPacket<String>.PayloadReader
            packet.populate { writer ->
                savedWriter = writer
                assertFailsWith<IllegalArgumentException> {
                    writer.addAll(-1) { throw AssertionError("negative count must not create payloads") }
                }
                assertEquals(0, packet.payloadCount)
                assertFailsWith<IllegalStateException> { packet.reset() }
                assertFailsWith<IllegalStateException> { packet.close() }
                assertFailsWith<IllegalStateException> { packet.populate { JsValueWire("nested") } }
                assertFailsWith<IllegalStateException> { packet.consume { _, _ -> Unit } }
                writer.addAll(1) { "payload" }
                JsValueWire("wire")
            }
            assertFailsWith<IllegalStateException> {
                savedWriter.addAll(1) { throw AssertionError("must not create a payload outside populate") }
            }
            packet.consume { wire, reader ->
                savedReader = reader
                assertEquals("wire", wire.value)
                assertEquals(1, reader.size)
                assertFailsWith<IllegalStateException> { packet.reset() }
                assertFailsWith<IllegalStateException> { packet.close() }
                assertFailsWith<IllegalStateException> { packet.populate { JsValueWire("nested") } }
                assertFailsWith<IllegalStateException> { packet.consume { _, _ -> Unit } }
                assertFailsWith<IllegalStateException> { savedWriter.addAll(0) { "unexpected" } }
                reader.forEach { assertEquals("payload", it) }
            }
            assertFailsWith<IllegalStateException> { savedReader.size }
            assertFailsWith<IllegalStateException> { savedReader.forEach {} }
            packet.reset()
            packet.populate { writer ->
                assertFailsWith<IllegalStateException> { savedReader.size }
                assertFailsWith<IllegalStateException> { savedReader.forEach {} }
                writer.addAll(0) { "unexpected" }
                JsValueWire("empty")
            }
            packet.close()
            assertFailsWith<IllegalStateException> { savedWriter.addAll(0) { "unexpected" } }
            assertFailsWith<IllegalStateException> { savedReader.size }
            assertFailsWith<IllegalStateException> { packet.reset() }
            assertFailsWith<IllegalStateException> { packet.populate { JsValueWire("closed") } }
            assertFailsWith<IllegalStateException> { packet.consume { _, _ -> Unit } }
        }
    }
}

private class NativeTransportPayload(
    val id: Int,
) : AutoCloseable {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
