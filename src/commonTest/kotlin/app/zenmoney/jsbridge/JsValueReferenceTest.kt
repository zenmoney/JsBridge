package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JsValueReferenceTest {
    private val immediateDispatcher = JsValueReferenceDispatcher { it() }

    @Test
    fun retainsOwnedValueUntilLastLeaseIsReleased() {
        JsContext().use { context ->
            val value = context.createObject()
            val reference = context.core.valueReferences.createReference(value, immediateDispatcher)
            val secondLease = reference.retain()

            assertSame(value, context.core.valueReferences.resolve(reference))
            reference.release()
            assertFalse(value.isClosed)

            secondLease.release()
            assertTrue(value.isClosed)
            assertFailsWith<IllegalStateException> {
                context.core.valueReferences.resolve(reference)
            }
        }
    }

    @Test
    fun finalReleaseUsesOwningContextDispatcher() {
        JsContext().use { context ->
            var cleanup: (() -> Unit)? = null
            val dispatcher = JsValueReferenceDispatcher { cleanup = it }
            val value = context.createObject()
            val reference = context.core.valueReferences.createReference(value, dispatcher)

            reference.release()

            assertFalse(value.isClosed)
            assertFailsWith<IllegalStateException> {
                context.core.valueReferences.resolve(reference)
            }
            checkNotNull(cleanup).invoke()
            assertTrue(value.isClosed)
        }
    }

    @Test
    fun resolvesOnlyInOwningContext() {
        JsContext().use { owner ->
            JsContext().use { other ->
                val reference = owner.core.valueReferences.createReference(owner.createObject(), immediateDispatcher)

                assertFailsWith<IllegalArgumentException> {
                    other.core.valueReferences.resolve(reference)
                }

                reference.release()
            }
        }
    }

    @Test
    fun separatelyWrappedSameObjectHasIndependentReferences() {
        JsContext().use { context ->
            val firstWrapper = context.createObject()
            val secondWrapper = context.createValueAlias(firstWrapper)
            val firstReference = context.core.valueReferences.createReference(firstWrapper, immediateDispatcher)
            val secondReference = context.core.valueReferences.createReference(secondWrapper, immediateDispatcher)

            firstReference.release()

            assertTrue(firstWrapper.isClosed)
            assertFalse(secondWrapper.isClosed)
            assertSame(secondWrapper, context.core.valueReferences.resolve(secondReference))

            secondReference.release()
            assertTrue(secondWrapper.isClosed)
        }
    }

    @Test
    fun foreignReferenceRepresentationOwnsLeaseUntilDisposed() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
                val representation = consumer.createObject()
                consumer.core.valueReferences.registerRepresentation(representation, reference)
                assertSame(representation, consumer.core.valueReferences.getRepresentation(reference))
                reference.release()
                val equivalentRepresentation = consumer.createValueAlias(representation)

                val operationLease =
                    checkNotNull(
                        consumer.core.valueReferences.getForeignReference(equivalentRepresentation),
                    ).retain()
                consumer.core.valueReferences.disposeRepresentation(equivalentRepresentation)
                assertNull(consumer.core.valueReferences.getRepresentation(operationLease))
                assertFalse(representedValue.isClosed)
                assertNull(consumer.core.valueReferences.getForeignReference(equivalentRepresentation))
                assertTrue(representation.isClosed)
                assertNull(consumer.core.valueReferences.getForeignReference(representation))
                consumer.core.valueReferences.disposeRepresentation(representation)

                operationLease.release()
                assertTrue(representedValue.isClosed)
                equivalentRepresentation.close()
            }
        }
    }

    @Test
    fun foreignReferenceHasAtMostOneRepresentationPerContext() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
                val firstRepresentation = consumer.createObject()
                consumer.core.valueReferences.registerRepresentation(firstRepresentation, reference)
                val secondRepresentation = consumer.createObject()

                assertFailsWith<IllegalStateException> {
                    consumer.core.valueReferences.registerRepresentation(secondRepresentation, reference)
                }
                assertSame(firstRepresentation, consumer.core.valueReferences.getRepresentation(reference))

                consumer.core.valueReferences.disposeRepresentation(firstRepresentation)
                assertNull(consumer.core.valueReferences.getRepresentation(reference))
                reference.release()
                assertTrue(representedValue.isClosed)
            }
        }
    }

    @Test
    fun foreignReferenceLookupDoesNotRetain() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
                val representation = consumer.createObject()
                consumer.core.valueReferences.registerRepresentation(representation, reference)
                reference.release()

                consumer.core.valueReferences.getForeignReference(representation)
                assertFalse(representedValue.isClosed)
                consumer.core.valueReferences.disposeRepresentation(representation)

                assertTrue(representedValue.isClosed)
            }
        }
    }

    @Test
    fun primitiveValueCanRepresentForeignReference() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
                val representation = consumer.createString("reference:1")
                consumer.core.valueReferences.registerRepresentation(representation, reference)
                reference.release()

                val equivalentRepresentation = consumer.createString("reference:1")
                val representedReference =
                    checkNotNull(
                        consumer.core.valueReferences.getForeignReference(equivalentRepresentation),
                    )
                assertSame(representedValue, owner.core.valueReferences.resolve(representedReference))

                consumer.core.valueReferences.disposeRepresentation(equivalentRepresentation)
                assertTrue(representedValue.isClosed)
                equivalentRepresentation.close()
            }
        }
    }

    @Test
    fun disposingUnknownRepresentationIsNoOp() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
                val representation = consumer.createObject()
                val equivalentRepresentation = consumer.createValueAlias(representation)

                consumer.core.valueReferences.disposeRepresentation(representation)
                assertFalse(representation.isClosed)
                assertNull(consumer.core.valueReferences.getForeignReference(representation))

                consumer.core.valueReferences.registerRepresentation(representation, reference)
                reference.release()
                consumer.core.valueReferences.disposeRepresentation(equivalentRepresentation)
                consumer.core.valueReferences.disposeRepresentation(equivalentRepresentation)
                assertTrue(representedValue.isClosed)
                assertTrue(representation.isClosed)
                equivalentRepresentation.close()
            }
        }
    }

    @Test
    fun closingConsumerContextReleasesItsReferenceLeases() {
        JsContext().use { owner ->
            val representedValue = owner.createObject()
            val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)
            val consumer = JsContext()
            val store = consumer.core.valueReferences
            val representation = consumer.createObject()
            store.registerRepresentation(representation, reference)
            reference.release()

            consumer.close()

            assertTrue(representedValue.isClosed)
            store.disposeRepresentation(representation)
        }
    }

    @Test
    fun closingOwnerContextInvalidatesOutstandingReferences() {
        val owner = JsContext()
        val representedValue = owner.createObject()
        val reference = owner.core.valueReferences.createReference(representedValue, immediateDispatcher)

        owner.close()

        assertTrue(representedValue.isClosed)
        assertFailsWith<IllegalStateException> { reference.retain() }
        reference.release()
    }
}
