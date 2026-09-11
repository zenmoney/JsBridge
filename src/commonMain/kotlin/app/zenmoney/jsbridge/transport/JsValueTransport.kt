package app.zenmoney.jsbridge.transport

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsObject
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
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
 * A resolver is confined to the destination context's thread. Its transaction ends once all packet payloads are
 * resolved, before the unpack callback or decoder runs. Later processing failures do not roll back resolved references.
 * The destination owns the resolver and closes it together with its decoder.
 */
fun interface JsValueTransportReferenceValueResolver<in P : Any> : AutoCloseable {
    /** [scope] owns resolver temporaries until the unpack callback finishes, unless ownership is explicitly transferred. */
    context(scope: JsScope)
    fun resolve(payload: P): JsValue

    fun begin(payloadCount: Int) {}

    /** Commits resolved references while their scope is still open. They must remain valid for the unpack callback. */
    fun commit() {}

    /** Rolls back failed resolution (including [begin] or [commit] failure), after the temporary scope has closed. */
    fun rollback() {}

    override fun close() {}
}

/**
 * A reusable boundary object carrying an opaque wire and consumer-owned reference payloads between contexts.
 *
 * The consumer allocates one packet per concurrently in-flight transfer, or pools them. It can call [populate] and
 * [consume] directly, or use [JsValueTransportSource] and [JsValueTransportDestination] to map JavaScript references.
 * The packet retains high-water storage for later calls and never closes, releases, or otherwise interprets payloads.
 * The consumer owns every stored payload, releases it according to its own contract, and then calls [reset]. A packet
 * may cross threads only when its payload type permits that and the handoff safely publishes it. Ownership must be
 * sequential: filling, consuming, payload cleanup, [reset], and [close] must never run concurrently. The packet stores
 * no context-bound JavaScript wrappers except those explicitly retained by consumer payloads.
 */
class JsValueTransportPacket<P : Any> : AutoCloseable {
    internal companion object {
        const val EMPTY = 0
        const val POPULATING = 1
        const val READY = 2
        const val CONSUMING = 3
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

    /**
     * Fills this empty packet with an opaque wire returned by [block] and any payloads added through its writer.
     * The writer is borrowed only for the duration of [block]. If [block] fails after adding payloads, they remain
     * available through [payload] for consumer cleanup, and [reset] is required before reuse. Failure without added
     * payloads leaves the packet empty and reusable immediately.
     */
    fun populate(block: (payloads: PayloadWriter) -> JsValueWire): JsValueTransportPacket<P> {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state == EMPTY) { "JsValueTransportPacket is not empty" }
        state = POPULATING
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

    /** A borrowed writer valid only inside the [populate] callback. Do not retain it after the callback returns. */
    inner class PayloadWriter internal constructor() {
        /** Appends payloads in index order, retaining every successfully created payload if a later call fails. */
        fun addAll(
            count: Int,
            createPayload: (index: Int) -> P,
        ) {
            check(state == POPULATING) { "JsValueTransportPacket is not being populated" }
            require(count >= 0) { "JsValueTransportPacket payload count must not be negative" }
            repeat(count) { index -> addPayload(createPayload(index)) }
        }
    }

    /**
     * Borrows this populated packet's wire and payload reader for one callback. Success or failure requires [reset]
     * before another population or consumption. Payloads remain available through [payload] for consumer cleanup;
     * this method never takes ownership of them. The reader must not be retained after [block] returns.
     */
    fun <R> consume(block: (wire: JsValueWire, payloads: PayloadReader) -> R): R {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state == READY) { "JsValueTransportPacket is not ready for consumption" }
        state = CONSUMING
        try {
            return block(JsValueWire(checkNotNull(wireValue)), payloadReader)
        } finally {
            state = RESET_REQUIRED
        }
    }

    /** A borrowed reader valid only inside the [consume] callback. Do not retain it after the callback returns. */
    inner class PayloadReader internal constructor() {
        /** Number of payloads available to this callback. */
        val size: Int
            get() {
                check(state == CONSUMING) { "JsValueTransportPacket is not being consumed" }
                return payloadCount
            }

        /** Visits borrowed payloads in their original insertion order. */
        fun forEach(block: (P) -> Unit) {
            check(state == CONSUMING) { "JsValueTransportPacket is not being consumed" }
            repeat(payloadCount) { index -> block(checkNotNull(payloads[index])) }
        }
    }

    /**
     * Forgets the wire and payload references and returns this packet to its empty reusable state. This method never
     * invokes a payload cleanup operation; the consumer must release owned payload resources before calling it.
     */
    fun reset() {
        check(state != CLOSED) { "JsValueTransportPacket is closed" }
        check(state != POPULATING && state != CONSUMING) { "JsValueTransportPacket is in use" }
        clear(dropStorage = false)
        state = EMPTY
    }

    /**
     * Permanently clears this packet, drops its retained high-water storage, and prevents further reuse. This method
     * never releases consumer-owned payload resources; the consumer must release them before calling it.
     */
    override fun close() {
        if (state == CLOSED) return
        check(state != POPULATING && state != CONSUMING) { "JsValueTransportPacket is in use" }
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
 * [referenceValueMapper] receives a scoped wrapper for every entry in the source reference-value table,
 * whether produced by [encode] or supplied to [pack]. It may return any non-null consumer payload and need not preserve
 * JavaScript identity. The mapper decides whether to borrow, copy, escape, close, or replace the source wrapper;
 * neither this source nor its packet knows the payload's ownership semantics. A null mapper accepts only wires
 * without reference entries.
 * The configured codec's encoder is created on the first [encode] call; [pack] does not use it.
 */
class JsValueTransportSource<P : Any>(
    private val context: JsContext,
    codec: JsValueCodec = ExpressionValueCodec,
    private val referenceValueMapper: ((JsValue) -> P)? = null,
) : AutoCloseable {
    private val encoder = lazy(LazyThreadSafetyMode.NONE) { createEncoder(context, codec) }
    private var isPopulating = false
    private var isClosed = false

    /** Encodes [value] into caller-owned [packet] and finishes all source-context work before returning it. */
    fun encode(
        value: JsValue,
        packet: JsValueTransportPacket<P>,
    ): JsValueTransportPacket<P> {
        check(!isClosed) { "JsValueTransportSource is closed" }
        require(value.context === context) { "JsValueTransportSource cannot encode a JsValue from another JsContext" }
        return populate(packet) { payloads ->
            encoder.value.encode(value) { wire, referenceValues ->
                mapReferenceValues(referenceValues, payloads)
                wire
            }
        }
    }

    /**
     * Packs a trusted JavaScript `{ wire, referenceValues }` envelope without invoking the configured encoder.
     * [encoded] is borrowed and must belong to this source's context. Its `wire` must be a string produced by a codec
     * compatible with the destination decoder, and `referenceValues` must be an array. The wire remains opaque and
     * is not validated or sanitized by transport.
     */
    fun pack(
        encoded: JsObject,
        packet: JsValueTransportPacket<P>,
    ): JsValueTransportPacket<P> {
        check(!isClosed) { "JsValueTransportSource is closed" }
        check(!isPopulating) { "JsValueTransportSource is already populating another packet" }
        require(encoded.context === context) { "JsValueTransportSource cannot pack a JsObject from another JsContext" }
        return jsScoped(context) {
            val wireValue = encoded["wire"]
            require(wireValue is JsString) { "JsValueTransportSource encoded wire must be a string" }
            val wire = JsValueWire(wireValue.toString())
            val referenceValues = encoded["referenceValues"]
            require(referenceValues is JsArray) { "JsValueTransportSource referenceValues must be an array" }
            pack(wire, referenceValues, packet)
        }
    }

    /**
     * Packs an already encoded [wire] and borrowed source-context [referenceValues]. This can be called directly from
     * a [JsValueEncoder.encode] callback. Entry wrappers created for the mapper belong to [scope] and remain valid
     * until it closes unless the mapper changes their ownership. The supplied array retains its existing ownership.
     * [scope] must belong to this source's context.
     */
    context(scope: JsScope)
    fun pack(
        wire: JsValueWire,
        referenceValues: JsArray,
        packet: JsValueTransportPacket<P>,
    ): JsValueTransportPacket<P> {
        check(!isClosed) { "JsValueTransportSource is closed" }
        require(scope.context === context) { "JsValueTransportSource cannot pack in a JsScope from another JsContext" }
        require(referenceValues.context === context) {
            "JsValueTransportSource cannot pack reference values from another JsContext"
        }
        return populate(packet) { payloads ->
            mapReferenceValues(referenceValues, payloads)
            wire
        }
    }

    private fun populate(
        packet: JsValueTransportPacket<P>,
        block: (JsValueTransportPacket<P>.PayloadWriter) -> JsValueWire,
    ): JsValueTransportPacket<P> {
        check(!isPopulating) { "JsValueTransportSource is already populating another packet" }
        isPopulating = true
        try {
            return packet.populate(block)
        } finally {
            isPopulating = false
        }
    }

    context(scope: JsScope)
    private fun mapReferenceValues(
        referenceValues: JsArray,
        payloads: JsValueTransportPacket<P>.PayloadWriter,
    ) {
        val count = referenceValues.size
        if (count == 0) return
        val configuredMapper =
            checkNotNull(referenceValueMapper) {
                "JsValueTransportSource has no reference-value mapper"
            }
        payloads.addAll(count) { index -> configuredMapper(referenceValues[index]) }
    }

    override fun close() {
        if (isClosed) return
        check(!isPopulating) { "JsValueTransportSource is populating a packet" }
        isClosed = true
        if (encoder.isInitialized()) encoder.value.close()
    }

    companion object
}

/**
 * The destination-context half of one transfer direction. It never accesses or retains a source [JsContext].
 *
 * [decode] and [unpack] must be called after the consumer has dispatched to [context]'s thread. They borrow packet
 * payloads and resolve them through [referenceValueResolver] into the destination reference-value table. Success or
 * failure leaves the packet populated so its consumer can perform payload-specific cleanup and call
 * [JsValueTransportPacket.reset]. The configured codec's decoder is created on the first [decode] call; [unpack] does
 * not use it.
 */
class JsValueTransportDestination<P : Any>(
    private val context: JsContext,
    codec: JsValueCodec = ExpressionValueCodec,
    private val referenceValueResolver: JsValueTransportReferenceValueResolver<P>? = null,
) : AutoCloseable {
    private var isConsuming = false
    private var isClosed = false
    private val decoder = lazy(LazyThreadSafetyMode.NONE) { createDecoder(context, codec) }
    private val resolvedPayloadValues = arrayListOf<JsValue>()

    /**
     * Resolves [packet] without taking ownership of its payloads and returns a value owned by [scope].
     * [scope] must belong to this destination's [context].
     */
    context(scope: JsScope)
    fun decode(packet: JsValueTransportPacket<P>): JsValue {
        require(scope.context === context) {
            "JsValueTransportDestination cannot consume in a JsScope from another JsContext"
        }
        return unpack(packet) { wire, resolvedReferenceValues ->
            decoder.value.decode(wire, resolvedReferenceValues).also { escape(it) }
        }.also { scope.autoClose(it) }
    }

    /**
     * Resolves [packet] into a JavaScript `{ wire, resolvedReferenceValues }` envelope owned by [scope], without
     * invoking the configured decoder. The array retains the resolved JavaScript values after resolver temporaries
     * are closed. [scope] must belong to this destination's context.
     *
     * Resolver changes are committed before the envelope is built. Building or decoding the envelope does not roll
     * back resolved references.
     */
    context(scope: JsScope)
    fun unpack(packet: JsValueTransportPacket<P>): JsObject {
        require(scope.context === context) {
            "JsValueTransportDestination cannot consume in a JsScope from another JsContext"
        }
        return unpack(packet) { wire, resolvedReferenceValues ->
            JsObject()
                .apply {
                    this["wire"] = JsString(wire.value)
                    this["resolvedReferenceValues"] = JsArray(resolvedReferenceValues)
                }.also { escape(it) }
        }.also { scope.autoClose(it) }
    }

    /**
     * Resolves [packet] and invokes [block] with its wire and borrowed reference values, without creating an envelope
     * or invoking the configured decoder. Resolution is committed before [block] runs; callback failure does not cause
     * resolver rollback. The callback's receiver owns resolver and callback temporaries and closes on success or failure.
     * The reference list is reused and must not be retained after [block] returns.
     *
     * The result has no implicit ownership handling. A callback that transfers a [JsValue] outside its scope must
     * explicitly escape or otherwise adopt it. Payload ownership and the requirement to reset [packet] after
     * consumption are unchanged.
     */
    fun <R> unpack(
        packet: JsValueTransportPacket<P>,
        block: JsScope.(wire: JsValueWire, resolvedReferenceValues: List<JsValue>) -> R,
    ): R {
        check(!isClosed) { "JsValueTransportDestination is closed" }
        check(!isConsuming) { "JsValueTransportDestination is already consuming another packet" }
        return packet.consume { wire, payloads ->
            isConsuming = true
            val resolver = referenceValueResolver
            var committed = false
            try {
                jsScoped(context) {
                    resolver?.begin(payloads.size)
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
                    resolver?.commit()
                    committed = true
                    block(wire, resolvedPayloadValues)
                }
            } finally {
                try {
                    if (!committed) resolver?.rollback()
                } finally {
                    resolvedPayloadValues.clear()
                    isConsuming = false
                }
            }
        }
    }

    override fun close() {
        if (isClosed) return
        check(!isConsuming) { "JsValueTransportDestination is consuming a packet" }
        isClosed = true
        var exception: Throwable? = null
        try {
            referenceValueResolver?.close()
        } catch (e: Throwable) {
            exception = e
        }
        try {
            if (decoder.isInitialized()) decoder.value.close()
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
