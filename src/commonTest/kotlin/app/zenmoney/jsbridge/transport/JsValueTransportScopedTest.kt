package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.eval
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.jsScoped
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

class JsValueTransportScopedTest {
    @Test
    fun splitPackUsesCallerScopeAndRejectsForeignScopeBeforeMapping() {
        JsContext().use { context ->
            JsContext().use { otherContext ->
                val mappedValues = arrayListOf<JsValue>()
                JsValueTransportSource(
                    context = context,
                    codec = ScopedUnusedCodec,
                    referenceValueMapper = { value ->
                        mappedValues += value
                        value.toString()
                    },
                ).use { source ->
                    JsValueTransportPacket<String>().use { packet ->
                        jsScoped(context) {
                            val references = assertIs<JsArray>(eval("['first', 'second']"))
                            val wire = JsValueWire("opaque")
                            jsScoped(otherContext) {
                                assertFailsWith<IllegalArgumentException> { source.pack(wire, references, packet) }
                            }
                            assertTrue(mappedValues.isEmpty())
                            assertEquals(0, packet.payloadCount)

                            assertSame(packet, source.pack(wire, references, packet))
                            assertEquals(listOf("first", "second"), List(packet.payloadCount) { packet.payload(it) })
                            assertTrue(mappedValues.all { it in this && !it.isClosed })
                            assertFalse(references.isClosed)
                            assertTrue(references in this)
                        }
                        assertTrue(mappedValues.all { it.isClosed })
                    }
                }
            }
        }
    }

    @Test
    fun unpackCallbackReadsResolvedValuesAfterCommitWithoutCallerScope() {
        JsContext().use { context ->
            lateinit var resolverScope: JsScope
            lateinit var resolvedValue: JsValue
            lateinit var callbackTemporary: JsArray
            val events = arrayListOf<String>()
            val resolver =
                object : JsValueTransportReferenceValueResolver<String> {
                    override fun begin(payloadCount: Int) {
                        assertEquals(1, payloadCount)
                        events += "begin"
                    }

                    context(scope: JsScope)
                    override fun resolve(payload: String): JsValue {
                        resolverScope = scope
                        events += "resolve"
                        return JsString(payload).also { resolvedValue = it }
                    }

                    override fun commit() {
                        assertFalse(resolvedValue.isClosed)
                        assertSame(context, resolverScope.context)
                        events += "commit"
                    }
                }
            JsValueTransportDestination(context, ScopedUnusedCodec, resolver).use { destination ->
                JsValueTransportPacket<String>().use { packet ->
                    packet.populate { payloads ->
                        payloads.addAll(1) { "resolved" }
                        JsValueWire("opaque")
                    }
                    val result =
                        destination.unpack(packet) { wire, references ->
                            assertEquals("opaque", wire.value)
                            assertSame(resolverScope, this)
                            assertEquals(1, references.size)
                            assertSame(resolvedValue, references.single())
                            assertTrue(resolvedValue in this)
                            assertFalse(resolvedValue.isClosed)
                            assertEquals(listOf("begin", "resolve", "commit"), events)
                            events += "callback"
                            callbackTemporary = JsArray(references)
                            wire.value to callbackTemporary[0].toString()
                        }
                    assertEquals("opaque" to "resolved", result)
                    assertEquals(listOf("begin", "resolve", "commit", "callback"), events)
                    assertTrue(resolvedValue.isClosed)
                    assertTrue(callbackTemporary.isClosed)
                    assertFailsWith<IllegalStateException> { resolverScope.context }
                }
            }
        }
    }

    @Test
    fun unpackCallbackFailureClosesTemporariesWithoutRollbackAndPreservesPayloadCleanup() {
        JsContext().use { context ->
            lateinit var resolverScope: JsScope
            lateinit var resolvedValue: JsValue
            lateinit var callbackTemporary: JsValue
            val events = arrayListOf<String>()
            val resolver =
                object : JsValueTransportReferenceValueResolver<ScopedPayload> {
                    override fun begin(payloadCount: Int) {
                        events += "begin"
                    }

                    context(scope: JsScope)
                    override fun resolve(payload: ScopedPayload): JsValue {
                        assertFalse(payload.closed)
                        resolverScope = scope
                        events += "resolve"
                        return JsString("resolved").also { resolvedValue = it }
                    }

                    override fun commit() {
                        assertFalse(resolvedValue.isClosed)
                        events += "commit"
                    }

                    override fun rollback() {
                        events += "rollback"
                    }
                }
            JsValueTransportDestination(context, ScopedUnusedCodec, resolver).use { destination ->
                JsValueTransportPacket<ScopedPayload>().use { packet ->
                    val firstPayload = ScopedPayload()
                    packet.populate { payloads ->
                        payloads.addAll(1) { firstPayload }
                        JsValueWire("opaque")
                    }
                    jsScoped(context) {
                        val unrelated = eval("({})")
                        assertFailsWith<IllegalStateException> {
                            destination.unpack<Unit>(packet) { _, _ ->
                                callbackTemporary = eval("({})")
                                events += "callback"
                                error("callback failed")
                            }
                        }
                        assertEquals(listOf("begin", "resolve", "commit", "callback"), events)
                        assertTrue(resolvedValue.isClosed)
                        assertTrue(callbackTemporary.isClosed)
                        assertFailsWith<IllegalStateException> { resolverScope.context }
                        assertFalse(unrelated.isClosed)
                        assertTrue(unrelated in this)
                        assertSame(firstPayload, packet.payload(0))
                        assertFalse(firstPayload.closed)
                        assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                        firstPayload.close()
                        packet.reset()
                        assertEquals(0, packet.payloadCount)

                        val nextPayload = ScopedPayload()
                        packet.populate { payloads ->
                            payloads.addAll(1) { nextPayload }
                            JsValueWire("reused")
                        }
                        events.clear()
                        val result =
                            destination.unpack(packet) { wire, references ->
                                assertEquals("reused", wire.value)
                                JsArray(references)[0].toString()
                            }
                        assertEquals("resolved", result)
                        assertEquals(listOf("begin", "resolve", "commit"), events)
                        assertFalse(nextPayload.closed)
                        nextPayload.close()
                        packet.reset()
                    }
                }
            }
        }
    }

    @Test
    fun unpackCallbackCanReturnUnitOrNull() {
        JsContext().use { context ->
            JsValueTransportDestination<Unit>(context, ScopedUnusedCodec).use { destination ->
                JsValueTransportPacket<Unit>().use { packet ->
                    for (expected in listOf(Unit, null)) {
                        packet.populate { JsValueWire("opaque") }
                        lateinit var temporary: JsValue
                        val result =
                            destination.unpack(packet) { _, references ->
                                assertTrue(references.isEmpty())
                                temporary = JsString("temporary")
                                expected
                            }
                        assertEquals(expected, result)
                        assertTrue(temporary.isClosed)
                        packet.reset()
                    }
                }
            }
        }
    }

    @Test
    fun unpackCallbackOnlyPreservesExplicitlyEscapedResult() {
        JsContext().use { context ->
            JsValueTransportDestination<Unit>(context, ScopedUnusedCodec).use { destination ->
                JsValueTransportPacket<Unit>().use { packet ->
                    for (escapeResult in listOf(false, true)) {
                        packet.populate { JsValueWire("opaque") }
                        val result =
                            destination.unpack(packet) { _, _ ->
                                JsString("result").also { if (escapeResult) it.escape() }
                            }
                        assertEquals(!escapeResult, result.isClosed)
                        if (escapeResult) result.use { assertEquals("result", it.toString()) }
                        packet.reset()
                    }
                }
            }
        }
    }
}

private object ScopedUnusedCodec : JsValueCodec {
    override fun createEncoder(context: JsContext): JsValueEncoder = error("Packing must not create an encoder")

    override fun createDecoder(context: JsContext): JsValueDecoder = error("Unpacking must not create a decoder")
}

private class ScopedPayload : AutoCloseable {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
