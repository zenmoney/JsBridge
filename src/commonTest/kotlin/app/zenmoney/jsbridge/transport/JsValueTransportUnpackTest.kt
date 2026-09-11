package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.eval
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.serialization.JsValueWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsValueTransportUnpackTest {
    @Test
    fun commitFailureClosesTemporariesAndRollsBackBeforeCallerScopeCloses() {
        JsContext().use { context ->
            lateinit var resolved: JsValue
            lateinit var resolverScope: JsScope
            var failCommit = true
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
                        return JsString(payload).also { resolved = it }
                    }

                    override fun commit() {
                        assertFalse(resolved.isClosed)
                        assertTrue(resolved in resolverScope)
                        events += "commit"
                        if (failCommit) error("commit failed")
                    }

                    override fun rollback() {
                        assertTrue(resolved.isClosed)
                        assertFailsWith<IllegalStateException> { resolverScope.context }
                        events += "rollback"
                    }
                }
            JsValueTransportDestination(context, referenceValueResolver = resolver).use { destination ->
                JsValueTransportPacket<String>().use { packet ->
                    jsScoped(context) {
                        val unrelated = eval("({})")
                        for (shouldFail in listOf(true, false)) {
                            failCommit = shouldFail
                            events.clear()
                            fillPacket(packet, "borrowed payload")
                            if (shouldFail) {
                                assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                                assertEquals(listOf("begin", "resolve", "commit", "rollback"), events)
                            } else {
                                val envelope = destination.unpack(packet)
                                assertTrue(envelope in this)
                                assertEquals("[\"x\",0]", assertIs<JsString>(envelope["wire"]).toString())
                                val references = assertIs<JsArray>(envelope["resolvedReferenceValues"])
                                assertEquals("borrowed payload", assertIs<JsString>(references[0]).toString())
                                assertEquals(listOf("begin", "resolve", "commit"), events)
                            }
                            assertFalse(unrelated.isClosed)
                            assertTrue(unrelated in this)
                            assertEquals(1, packet.payloadCount)
                            assertEquals("borrowed payload", packet.payload(0))
                            assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                            packet.reset()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun resolverFailureRollsBackAndPreservesPayloadsForCleanupAndReuse() {
        assertResolutionFailureRecovery(returnForeignValue = false)
    }

    @Test
    fun foreignResolvedValueRollsBackWithoutTakingOwnershipOfThatValue() {
        assertResolutionFailureRecovery(returnForeignValue = true)
    }

    @Test
    fun rejectsForeignCallerScopeWithoutStartingTransactionOrConsumingPacket() {
        JsContext().use { context ->
            JsContext().use { otherContext ->
                var beginCount = 0
                val resolver =
                    object : JsValueTransportReferenceValueResolver<String> {
                        override fun begin(payloadCount: Int) {
                            beginCount++
                        }

                        context(scope: JsScope)
                        override fun resolve(payload: String): JsValue = JsString(payload)
                    }
                JsValueTransportDestination(context, referenceValueResolver = resolver).use { destination ->
                    JsValueTransportPacket<String>().use { packet ->
                        fillPacket(packet, "ready")
                        jsScoped(otherContext) {
                            assertFailsWith<IllegalArgumentException> { destination.unpack(packet) }
                        }
                        assertEquals(0, beginCount)
                        jsScoped(context) {
                            val envelope = destination.unpack(packet)
                            assertTrue(envelope in this)
                            assertEquals(1, assertIs<JsArray>(envelope["resolvedReferenceValues"]).size)
                        }
                        assertEquals(1, beginCount)
                    }
                }
            }
        }
    }

    @Test
    fun rejectsNestedDecodeAndUnpackWithoutConsumingTheOtherPacket() {
        JsContext().use { context ->
            JsValueTransportPacket<String>().use { activePacket ->
                JsValueTransportPacket<String>().use { otherPacket ->
                    lateinit var destination: JsValueTransportDestination<String>
                    var resolvingActivePacket = true
                    var resolvedCount = 0
                    val resolver =
                        JsValueTransportReferenceValueResolver<String> { payload ->
                            resolvedCount++
                            if (resolvingActivePacket) {
                                assertFailsWith<IllegalStateException> { destination.unpack(otherPacket) }
                                assertFailsWith<IllegalStateException> { destination.decode(otherPacket) }
                                assertFailsWith<IllegalStateException> { destination.close() }
                                assertFailsWith<IllegalStateException> { activePacket.reset() }
                                assertFailsWith<IllegalStateException> { activePacket.close() }
                            }
                            JsString(payload)
                        }
                    destination = JsValueTransportDestination(context, referenceValueResolver = resolver)
                    destination.use {
                        fillPacket(activePacket, "active")
                        fillPacket(otherPacket, "other")
                        jsScoped(context) {
                            destination.unpack(activePacket)
                            assertEquals(1, resolvedCount)
                            resolvingActivePacket = false
                            val envelope = destination.unpack(otherPacket)
                            val references = assertIs<JsArray>(envelope["resolvedReferenceValues"])
                            assertEquals("other", assertIs<JsString>(references[0]).toString())
                            assertEquals(2, resolvedCount)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun closedDestinationDoesNotConsumePacketAndClosedPacketDoesNotStartResolution() {
        JsContext().use { context ->
            var beginCount = 0
            var closeCount = 0
            val resolver =
                object : JsValueTransportReferenceValueResolver<String> {
                    override fun begin(payloadCount: Int) {
                        beginCount++
                    }

                    context(scope: JsScope)
                    override fun resolve(payload: String): JsValue = JsString(payload)

                    override fun close() {
                        closeCount++
                    }
                }
            val destination = JsValueTransportDestination(context, referenceValueResolver = resolver)
            val closedPacket = JsValueTransportPacket<String>()
            closedPacket.close()
            jsScoped(context) {
                assertFailsWith<IllegalStateException> { destination.unpack(closedPacket) }
            }
            assertEquals(0, beginCount)
            destination.close()
            destination.close()
            assertEquals(1, closeCount)
            JsValueTransportPacket<String>().use { packet ->
                fillPacket(packet, "ready")
                jsScoped(context) {
                    assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                }
                assertEquals(0, beginCount)
                val replacementResolver = JsValueTransportReferenceValueResolver<String> { JsString(it) }
                JsValueTransportDestination(context, referenceValueResolver = replacementResolver).use { replacement ->
                    jsScoped(context) { replacement.unpack(packet) }
                }
            }
        }
    }

    private fun assertResolutionFailureRecovery(returnForeignValue: Boolean) {
        JsContext().use { context ->
            JsContext().use { otherContext ->
                otherContext.evaluateScript("({})").use { foreignValue ->
                    val temporaries = arrayListOf<JsValue>()
                    var shouldFail = true
                    var rollbackCount = 0
                    var commitCount = 0
                    val resolver =
                        object : JsValueTransportReferenceValueResolver<String> {
                            context(scope: JsScope)
                            override fun resolve(payload: String): JsValue {
                                if (shouldFail && payload == "second") {
                                    if (returnForeignValue) return foreignValue
                                    error("resolution failed")
                                }
                                return JsString(payload).also { temporaries += it }
                            }

                            override fun commit() {
                                assertFalse(temporaries.last().isClosed)
                                commitCount++
                            }

                            override fun rollback() {
                                assertTrue(temporaries.all { it.isClosed })
                                rollbackCount++
                            }
                        }
                    JsValueTransportDestination(context, referenceValueResolver = resolver).use { destination ->
                        JsValueTransportPacket<String>().use { packet ->
                            fillPacket(packet, "first", "second")
                            jsScoped(context) {
                                if (returnForeignValue) {
                                    assertFailsWith<IllegalArgumentException> { destination.unpack(packet) }
                                } else {
                                    assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                                }
                                assertEquals(1, rollbackCount)
                                assertEquals(0, commitCount)
                                assertEquals(1, temporaries.size)
                                assertFalse(foreignValue.isClosed)
                                assertEquals(2, packet.payloadCount)
                                assertEquals("first", packet.payload(0))
                                assertEquals("second", packet.payload(1))
                                assertFailsWith<IllegalStateException> { destination.unpack(packet) }
                                assertEquals(1, rollbackCount)
                                packet.reset()
                                shouldFail = false
                                fillPacket(packet, "first", "second")
                                val envelope = destination.unpack(packet)
                                val references = assertIs<JsArray>(envelope["resolvedReferenceValues"])
                                assertEquals(2, references.size)
                                assertEquals("first", assertIs<JsString>(references[0]).toString())
                                assertEquals("second", assertIs<JsString>(references[1]).toString())
                                assertEquals(1, rollbackCount)
                                assertEquals(1, commitCount)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun fillPacket(
        packet: JsValueTransportPacket<String>,
        vararg values: String,
    ) {
        packet.populate { payloads ->
            payloads.addAll(values.size) { values[it] }
            val references = values.indices.joinToString(",") { """["x",$it]""" }
            JsValueWire(if (values.size == 1) references else """["a",1,[$references]]""")
        }
    }
}
