package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.serialization.ExpressionValueCodec
import app.zenmoney.jsbridge.serialization.JsValueCodec
import app.zenmoney.jsbridge.serialization.JsValueDecoder
import app.zenmoney.jsbridge.serialization.JsValueEncoder
import app.zenmoney.jsbridge.serialization.JsValueWire

/**
 * Resolves consumer-defined [JsValueTransportPacket] payloads into destination-context entries of a codec
 * reference-value table.
 *
 * A resolver is confined to the destination context's thread. The lifecycle callbacks describe one destination decode
 * transaction and let a stateful resolver roll back side effects without allocating a result wrapper for every
 * payload. The destination owns the resolver and closes it together with its decoder.
 */
fun interface JsValueTransportReferenceValueResolver<in P : Any> : AutoCloseable {
    /** [scope] owns resolver temporaries and is closed before [commit] or [rollback]. */
    context(scope: JsScope)
    fun resolve(payload: P): JsValue

    fun begin(payloadCount: Int) {}

    fun commit() {}

    fun rollback() {}

    override fun close() {}
}

/**
 * A reusable boundary object between source processing and destination processing.
 *
 * The consumer allocates one packet per concurrently in-flight transfer, or pools them. [JsValueTransportSource]
 * fills an empty packet and [JsValueTransportDestination] borrows its contents. The packet retains high-water
 * storage for later calls and never closes, releases, or otherwise interprets payloads. The consumer owns every payload
 * returned by its reference-value mapper, releases it according to its own contract, and then calls [reset]. A packet
 * may cross threads only when its payload type permits that and the handoff safely publishes it. Ownership must be
 * sequential: encoding, decoding, payload cleanup, [reset], and [close] must never run concurrently.
 */
class JsValueTransportPacket<P : Any> : AutoCloseable {
    internal companion object {
        const val EMPTY = 0
        const val ENCODING = 1
        const val READY = 2
        const val DECODING = 3
        const val RESET_REQUIRED = 4
        const val CLOSED = 5
    }

    private var state = EMPTY
    private var wireValue: String? = null
    private var payloads = arrayListOf<P?>()
    private val payloadWriter = PayloadWriter()
    private val payloadReader = PayloadReader()

    /** Number of consumer-owned payloads currently stored in this packet. */
    var payloadCount: Int = 0
        private set

    /** Returns one borrowed payload. Its ownership remains defined entirely by the consumer. */
    fun payload(index: Int): P {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        require(index in 0 until payloadCount) { "JsValueTransportPacket payload index is out of bounds" }
        return checkNotNull(payloads[index])
    }

    internal inline fun populate(block: (payloads: PayloadWriter) -> JsValueWire): JsValueTransportPacket<P> {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state == EMPTY) { "JsValueTransportPacket is not empty" }
        state = ENCODING
        try {
            wireValue = block(payloadWriter).value
            state = READY
            return this
        } catch (e: Throwable) {
            state = if (payloadCount == 0) EMPTY else RESET_REQUIRED
            throw e
        }
    }

    private fun addPayload(payload: P) {
        if (payloadCount < payloads.size) {
            payloads[payloadCount] = payload
        } else {
            payloads.add(payload)
        }
        payloadCount++
    }

    internal inner class PayloadWriter {
        inline fun addAll(
            count: Int,
            createPayload: (index: Int) -> P,
        ) {
            check(state == ENCODING) { "JsValueTransportPacket is not being populated" }
            repeat(count) { index -> addPayload(createPayload(index)) }
        }
    }

    internal inline fun <R> consume(block: (wire: JsValueWire, payloads: PayloadReader) -> R): R {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state == READY) { "JsValueTransportPacket is not ready for consumption" }
        state = DECODING
        try {
            return block(JsValueWire(checkNotNull(wireValue)), payloadReader)
        } finally {
            state = RESET_REQUIRED
        }
    }

    internal inner class PayloadReader {
        val size: Int
            get() {
                check(state == DECODING) { "JsValueTransportPacket is not being consumed" }
                return payloadCount
            }

        inline fun forEach(block: (P) -> Unit) {
            check(state == DECODING) { "JsValueTransportPacket is not being consumed" }
            repeat(payloadCount) { index -> block(checkNotNull(payloads[index])) }
        }
    }

    /**
     * Forgets the wire and payload references and returns this packet to its empty reusable state. This method never
     * invokes a payload cleanup operation; the consumer must release owned payload resources before calling it.
     */
    fun reset() {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state != ENCODING && state != DECODING) { "JsValueTransportPacket is in use" }
        clear(dropStorage = false)
        state = EMPTY
    }

    /**
     * Permanently clears this packet, drops its retained high-water storage, and prevents further reuse. This method
     * never releases consumer-owned payload resources; the consumer must release them before calling it.
     */
    override fun close() {
        if (state == CLOSED) return
        check(state != ENCODING && state != DECODING) { "JsValueTransportPacket is in use" }
        clear(dropStorage = true)
        state = CLOSED
    }

    private fun clear(dropStorage: Boolean) {
        if (dropStorage) {
            payloads = arrayListOf()
        } else {
            for (index in 0 until payloadCount) {
                payloads[index] = null
            }
        }
        payloadCount = 0
        wireValue = null
    }
}

/**
 * The source-context half of one transfer direction. It never accesses or retains a destination [JsContext].
 *
 * [referenceValueMapper] receives every operation-owned entry emitted by the configured encoder in its source
 * reference-value table. It may return any non-null consumer payload and need not preserve JavaScript identity. The
 * mapper decides whether to borrow, copy, escape, close, or replace the source wrapper; neither this source nor its
 * packet knows the payload's ownership semantics. A null mapper accepts only wires without reference entries.
 */
class JsValueTransportSource<P : Any>(
    private val context: JsContext,
    codec: JsValueCodec = ExpressionValueCodec,
    private val referenceValueMapper: ((JsValue) -> P)? = null,
) : AutoCloseable {
    private val encoder = createEncoder(context, codec)
    private var isClosed = false

    /** Encodes [value] into caller-owned [packet] and finishes all source-context work before returning it. */
    fun encode(
        value: JsValue,
        packet: JsValueTransportPacket<P>,
    ): JsValueTransportPacket<P> {
        check(!isClosed) { "JsValueTransportSource is closed" }
        require(value.context === context) { "JsValueTransportSource cannot encode a JsValue from another JsContext" }
        return packet.populate { payloads ->
            encoder.encode(value) { wire, referenceValues ->
                if (referenceValues.size > 0) {
                    val configuredMapper =
                        checkNotNull(referenceValueMapper) {
                            "JsValueTransportSource has no reference-value mapper"
                        }
                    payloads.addAll(referenceValues.size) { index -> configuredMapper(referenceValues[index]) }
                }
                wire
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        encoder.close()
    }

    companion object
}

/**
 * The destination-context half of one transfer direction. It never accesses or retains a source [JsContext].
 *
 * [decode] must be called after the consumer has dispatched to [context]'s thread. It borrows packet payloads and
 * resolves them through [referenceValueResolver] into the codec's destination reference-value table. Success or failure
 * leaves the packet populated so its consumer can perform payload-specific cleanup and call
 * [JsValueTransportPacket.reset].
 */
class JsValueTransportDestination<P : Any>(
    private val context: JsContext,
    codec: JsValueCodec = ExpressionValueCodec,
    private val referenceValueResolver: JsValueTransportReferenceValueResolver<P>? = null,
) : AutoCloseable {
    private var isDecoding = false
    private var isClosed = false
    private val decoder = createDecoder(context, codec)
    private val resolvedPayloadValues = arrayListOf<JsValue>()

    /**
     * Resolves [packet] without taking ownership of its payloads and returns a value owned by [scope].
     * [scope] must belong to this destination's [context].
     */
    context(scope: JsScope)
    fun decode(packet: JsValueTransportPacket<P>): JsValue {
        check(!isClosed) { "JsValueTransportDestination is closed" }
        check(!isDecoding) { "JsValueTransportDestination is already decoding another packet" }
        require(scope.context === context) {
            "JsValueTransportDestination cannot decode in a JsScope from another JsContext"
        }
        return packet.consume { wire, payloads ->
            isDecoding = true
            val resolver = referenceValueResolver
            var committed = false
            var result: JsValue? = null
            try {
                resolver?.begin(payloads.size)
                result =
                    jsScoped(context) {
                        payloads.forEach { payload ->
                            val configuredResolver =
                                checkNotNull(resolver) {
                                    "JsValueTransportDestination has no reference-value resolver"
                                }
                            val resolvedValue = configuredResolver.resolve(payload)
                            require(resolvedValue.context === context) {
                                "JsValueTransportReferenceValueResolver returned a JsValue from another JsContext"
                            }
                            resolvedPayloadValues += resolvedValue
                        }

                        val decodedValue = decoder.decode(wire, resolvedPayloadValues)
                        result = decodedValue
                        require(decodedValue.context === context) {
                            "JsValueDecoder returned a JsValue from another JsContext"
                        }
                        decodedValue.also { escape(it) }
                    }
                resolver?.commit()
                val decodedValue = checkNotNull(result).also { scope.autoClose(it) }
                committed = true
                decodedValue
            } finally {
                try {
                    if (!committed) {
                        try {
                            result?.close()
                        } finally {
                            resolver?.rollback()
                        }
                    }
                } finally {
                    resolvedPayloadValues.clear()
                    isDecoding = false
                }
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        var exception: Throwable? = null
        try {
            referenceValueResolver?.close()
        } catch (e: Throwable) {
            exception = e
        }
        try {
            decoder.close()
        } catch (e: Throwable) {
            exception = exception ?: e
        }
        exception?.let { throw it }
    }

    companion object
}

private fun createEncoder(
    context: JsContext,
    codec: JsValueCodec,
): JsValueEncoder {
    val encoder = codec.createEncoder(context)
    try {
        require(encoder.context === context) { "JsValueCodec created a JsValueEncoder for another JsContext" }
        return encoder
    } catch (e: Throwable) {
        runCatching { encoder.close() }
        throw e
    }
}

private fun createDecoder(
    context: JsContext,
    codec: JsValueCodec,
): JsValueDecoder {
    val decoder = codec.createDecoder(context)
    try {
        require(decoder.context === context) { "JsValueCodec created a JsValueDecoder for another JsContext" }
        return decoder
    } catch (e: Throwable) {
        runCatching { decoder.close() }
        throw e
    }
}
