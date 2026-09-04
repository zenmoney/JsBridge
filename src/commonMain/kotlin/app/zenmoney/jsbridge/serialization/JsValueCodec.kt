package app.zenmoney.jsbridge.serialization

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsValue
import kotlin.jvm.JvmInline

/**
 * Trusted opaque representation produced by a [JsValueEncoder] and passed to a compatible [JsValueDecoder]. The value
 * is not sanitized before decoding and must not be constructed from untrusted input.
 */
@JvmInline
value class JsValueWire(
    val value: String,
)

/**
 * A context-bound `JsValue -> wire` encoder prepared by a [JsValueCodec].
 *
 * [referenceValues] belongs to [context] and is valid only during [block]. A caller that transfers an entry outside
 * the callback must explicitly escape or otherwise adopt that entry according to its own ownership policy.
 */
interface JsValueEncoder : AutoCloseable {
    val context: JsContext

    /** Invokes [block] exactly once on success. */
    fun <R> encode(
        value: JsValue,
        block: JsScope.(wire: JsValueWire, referenceValues: JsArray) -> R,
    ): R
}

/** A context-bound wire decoder prepared by a [JsValueCodec]. */
interface JsValueDecoder : AutoCloseable {
    val context: JsContext

    /** Returns a caller-owned value in [context]. */
    fun decode(
        wire: JsValueWire,
        resolvedReferenceValues: List<JsValue>,
    ): JsValue
}

/**
 * Serialization strategy reusable across [JsContext] instances. A created encoder or decoder is bound to the context
 * passed to its factory method and owns every resource it retains until closed.
 */
interface JsValueCodec {
    fun createEncoder(context: JsContext): JsValueEncoder

    fun createDecoder(context: JsContext): JsValueDecoder
}
