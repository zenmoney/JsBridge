package app.zenmoney.jsbridge.consumer

import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsValueReference
import app.zenmoney.jsbridge.JsValueReferenceDispatcher
import app.zenmoney.jsbridge.id
import app.zenmoney.jsbridge.valueReferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class JsValueReferencePublicApiTest {
    @Test
    fun referenceStoreIsUsableOutsideTheLibraryPackage() {
        JsContext().use { owner ->
            JsContext().use { consumer ->
                val representedValue = owner.createObject()
                val reference: JsValueReference =
                    owner.valueReferences.createReference(
                        representedValue,
                        JsValueReferenceDispatcher { it() },
                    )
                assertEquals(owner.id, reference.contextId)
                assertNotEquals(owner.id, consumer.id)
                val representation = consumer.createObject()
                consumer.valueReferences.registerRepresentation(representation, reference)
                assertSame(representation, consumer.valueReferences.getRepresentation(reference))
                reference.release()

                val retainedReference =
                    checkNotNull(consumer.valueReferences.getForeignReference(representation)).retain()
                assertSame(representedValue, owner.valueReferences.resolve(retainedReference))

                consumer.valueReferences.disposeRepresentation(representation)
                retainedReference.release()
            }
        }
    }
}
