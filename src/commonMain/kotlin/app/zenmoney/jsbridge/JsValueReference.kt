@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package app.zenmoney.jsbridge

import androidx.collection.mutableIntObjectMapOf
import kotlin.concurrent.atomics.AtomicInt
import kotlin.jvm.JvmInline

/** Dispatches final reference cleanup to the thread that owns the referenced [JsContext]. */
fun interface JsValueReferenceDispatcher {
    fun dispatch(block: () -> Unit)
}

/**
 * A thread-safe, context-qualified reference to a [JsValue] retained by its owning [JsContext].
 *
 * Copying the token does not retain it. Every logical owner must call [retain] and [release] in pairs. The reference
 * returned by [JsValueReferenceStore.createReference] already has one retain owned by its caller.
 */
@JvmInline
value class JsValueReference private constructor(
    private val state: State,
) {
    val contextId: Int
        get() = state.contextId

    internal val referenceId: Int
        get() = state.referenceId

    /** Adds one independently releasable retain without creating another platform [JsValue] wrapper. */
    fun retain(): JsValueReference {
        state.retain()
        return this
    }

    /** Releases one lease. The last release schedules platform-wrapper cleanup in the owning context. */
    fun release() {
        state.release()
    }

    internal fun requireActive() {
        state.requireActive()
    }

    internal fun hasSameStateAs(other: JsValueReference): Boolean = state === other.state

    internal companion object {
        fun create(
            contextId: Int,
            referenceId: Int,
            dispatcher: JsValueReferenceDispatcher,
            release: (JsValueReference) -> Unit,
        ): JsValueReference =
            JsValueReference(
                State(
                    contextId = contextId,
                    referenceId = referenceId,
                    dispatcher = dispatcher,
                    release = release,
                ),
            )
    }

    internal fun invalidate() {
        state.invalidate()
    }

    private class State(
        val contextId: Int,
        val referenceId: Int,
        private val dispatcher: JsValueReferenceDispatcher,
        private val release: (JsValueReference) -> Unit,
    ) {
        private companion object {
            const val INVALIDATED = -1
        }

        private val leaseCount = AtomicInt(1)

        fun retain() {
            while (true) {
                val count = leaseCount.load()
                check(count > 0) { "JsValueReference is no longer active" }
                check(count < Int.MAX_VALUE) { "JsValueReference lease count overflow" }
                if (leaseCount.compareAndSet(count, count + 1)) return
            }
        }

        fun release() {
            while (true) {
                val count = leaseCount.load()
                if (count == INVALIDATED) return
                check(count > 0) { "JsValueReference lease is already released" }
                if (!leaseCount.compareAndSet(count, count - 1)) continue
                if (count == 1) {
                    dispatcher.dispatch { release(JsValueReference(this)) }
                }
                return
            }
        }

        fun requireActive() {
            check(leaseCount.load() > 0) { "JsValueReference is no longer active" }
        }

        fun invalidate() {
            leaseCount.store(INVALIDATED)
        }
    }
}

/**
 * Per-context storage for values retained behind thread-safe references and for local JavaScript representations of
 * references owned by other contexts. All map operations happen on the context thread; only
 * [JsValueReference.retain] and [JsValueReference.release] are thread-safe.
 */
class JsValueReferenceStore internal constructor(
    private val context: JsContext,
) {
    private class OwnedValue(
        val value: JsValue,
        val reference: JsValueReference,
    )

    private var nextReferenceId = 1
    private var isClosed = false
    private val valueByReferenceId = mutableIntObjectMapOf<OwnedValue>()

    private val foreignReferenceByRepresentation = mutableMapOf<JsValue, JsValueReference>()
    private val representationByForeignReference = mutableMapOf<JsValueReference, JsValue>()

    /**
     * Creates a new reference with one retain owned by the caller and takes responsibility for closing the same [value]
     * wrapper without creating an alias. The caller may continue using [value] as borrowed while it owns an active
     * reference retain, but must not close [value] directly. The store does not reuse an existing reference based on
     * JavaScript object identity: separately supplied wrappers for the same JavaScript object produce independent
     * references. [dispatcher] must execute its block on [context]'s thread and must not reject dispatch while the
     * context is active.
     */
    fun createReference(
        value: JsValue,
        dispatcher: JsValueReferenceDispatcher,
    ): JsValueReference {
        checkActive()
        require(value.context === context) { "Referenced value belongs to another JsContext" }
        check(!value.isClosed) { "Referenced JsValue is already closed" }

        val referenceId = nextReferenceId++
        val reference =
            JsValueReference.create(
                contextId = context.core.id,
                referenceId = referenceId,
                dispatcher = dispatcher,
                release = { releasedReference -> releaseOwnedValue(referenceId, releasedReference) },
            )
        value.escape()
        valueByReferenceId[referenceId] = OwnedValue(value, reference)
        return reference
    }

    /** Returns the retained wrapper as a borrowed value. The caller's active lease keeps it valid. */
    fun resolve(reference: JsValueReference): JsValue {
        checkActive()
        require(reference.contextId == context.core.id) { "JsValueReference belongs to another JsContext" }
        reference.requireActive()
        val owned = checkNotNull(valueByReferenceId[reference.referenceId]) { "Unknown JsValueReference" }
        check(reference.hasSameStateAs(owned.reference)) { "Stale JsValueReference" }
        return owned.value
    }

    /**
     * Takes ownership of [representation] and retains [foreignReference] for the new entry. The caller keeps its own
     * retain and must release it separately. The representation is a value in this context standing for a reference
     * owned by another context. It remains a lookup key according to [JsValue] equality semantics until it is disposed
     * or this context closes.
     */
    fun registerRepresentation(
        representation: JsValue,
        foreignReference: JsValueReference,
    ) {
        checkActive()
        require(representation.context === context) { "Reference representation belongs to another JsContext" }
        require(foreignReference.contextId != context.core.id) {
            "Reference representation must refer to another JsContext"
        }
        check(!representation.isClosed) { "Reference representation is already closed" }
        check(!foreignReferenceByRepresentation.containsKey(representation)) {
            "Reference representation is already registered"
        }
        check(!representationByForeignReference.containsKey(foreignReference)) {
            "JsValueReference already has a representation in this context"
        }
        val retainedReference = foreignReference.retain()
        try {
            representation.escape()
            foreignReferenceByRepresentation[representation] = retainedReference
            representationByForeignReference[retainedReference] = representation
        } catch (e: Throwable) {
            foreignReferenceByRepresentation.remove(representation)
            representationByForeignReference.remove(retainedReference)
            retainedReference.release()
            throw e
        }
    }

    /**
     * Returns the borrowed foreign reference represented by [value], or `null` when it has no live registration,
     * including after disposal. Lookup does not retain the reference. A caller that needs to outlive the representation
     * entry must call [JsValueReference.retain].
     */
    fun getForeignReference(value: JsValue): JsValueReference? {
        checkActive()
        if (value.isClosed) return null
        require(value.context === context) { "Reference representation belongs to another JsContext" }
        return foreignReferenceByRepresentation[value]?.also { it.requireActive() }
    }

    /** Returns the borrowed live representation of [foreignReference] in this context, or `null` when absent. */
    fun getRepresentation(foreignReference: JsValueReference): JsValue? {
        checkActive()
        require(foreignReference.contextId != context.core.id) {
            "Representation lookup requires a JsValueReference owned by another JsContext"
        }
        return representationByForeignReference[foreignReference]?.also { foreignReference.requireActive() }
    }

    /** Idempotently removes a representation, releases its reference lease, and closes the store-owned wrapper. */
    fun disposeRepresentation(representation: JsValue) {
        if (isClosed || representation.isClosed) return
        check(representation.context === context) { "Reference representation belongs to another JsContext" }

        val reference = foreignReferenceByRepresentation.remove(representation) ?: return
        val ownedRepresentation =
            checkNotNull(representationByForeignReference.remove(reference)) {
                "Reference representation store is inconsistent"
            }
        try {
            if (!ownedRepresentation.isClosed) ownedRepresentation.close()
        } finally {
            reference.release()
        }
    }

    /** Closes this store, invalidating owned references and releasing live representation retains. */
    internal fun close() {
        if (isClosed) return
        isClosed = true

        valueByReferenceId.forEachValue { it.reference.invalidate() }
        valueByReferenceId.clear()

        val representedReferences = foreignReferenceByRepresentation.values.toList()
        foreignReferenceByRepresentation.clear()
        representationByForeignReference.clear()
        representedReferences.forEach { it.release() }
    }

    private fun releaseOwnedValue(
        referenceId: Int,
        reference: JsValueReference,
    ) {
        if (isClosed) return
        val owned = valueByReferenceId[referenceId] ?: return
        if (!reference.hasSameStateAs(owned.reference)) return
        valueByReferenceId.remove(referenceId)
        if (!owned.value.isClosed) owned.value.close()
    }

    private fun checkActive() {
        check(!isClosed && !context.isClosed) { "JsValueReferenceStore is closed" }
    }
}

/** Returns the lazily created reference store owned by this context. */
val JsContext.valueReferences: JsValueReferenceStore
    get() = core.valueReferences
