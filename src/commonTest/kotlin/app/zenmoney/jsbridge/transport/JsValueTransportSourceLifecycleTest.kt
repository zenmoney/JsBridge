package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsObject
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.eval
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueCodec
import app.zenmoney.jsbridge.serialization.JsValueDecoder
import app.zenmoney.jsbridge.serialization.JsValueEncoder
import app.zenmoney.jsbridge.serialization.JsValueWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JsValueTransportSourceLifecycleTest {
    @Test
    fun rejectsReentrantOperationsDuringEncoderInitializationAndClosesInitializedEncoder() {
        JsContext().use { context ->
            jsScoped(context) {
                val value = eval("'value'")
                val encoded = assertIs<JsObject>(eval("({ wire: 'null', referenceValues: [] })"))
                val references = assertIs<JsArray>(encoded["referenceValues"])
                val wire = JsValueWire("null")
                var createCount = 0
                var encodeCount = 0
                var closeCount = 0
                lateinit var source: JsValueTransportSource<Unit>
                JsValueTransportPacket<Unit>().use { otherPacket ->
                    val codec =
                        object : JsValueCodec {
                            override fun createEncoder(context: JsContext): JsValueEncoder {
                                createCount++
                                assertFailsWith<IllegalStateException> { source.close() }
                                assertFailsWith<IllegalStateException> { source.encode(value, otherPacket) }
                                assertFailsWith<IllegalStateException> { source.pack(encoded, otherPacket) }
                                assertFailsWith<IllegalStateException> { source.pack(wire, references, otherPacket) }
                                val delegate = ExpressionValueCodec.createEncoder(context)
                                return object : JsValueEncoder {
                                    override val context: JsContext = delegate.context

                                    override fun <R> encode(
                                        value: JsValue,
                                        block: JsScope.(wire: JsValueWire, referenceValues: JsArray) -> R,
                                    ): R {
                                        encodeCount++
                                        return delegate.encode(value, block)
                                    }

                                    override fun close() {
                                        closeCount++
                                        delegate.close()
                                    }
                                }
                            }

                            override fun createDecoder(context: JsContext): JsValueDecoder = error("Unexpected decoder")
                        }
                    source = JsValueTransportSource(context, codec)
                    source.use {
                        assertEquals(0, createCount)
                        JsValueTransportPacket<Unit>().use { packet -> source.encode(value, packet) }
                        assertEquals(1, createCount)
                        assertEquals(1, encodeCount)
                        assertEquals(0, closeCount)
                        source.encode(value, otherPacket)
                        assertEquals(1, createCount)
                        assertEquals(2, encodeCount)
                        source.close()
                        assertEquals(1, closeCount)
                    }
                    assertEquals(1, closeCount)
                }
            }
        }
    }
}
