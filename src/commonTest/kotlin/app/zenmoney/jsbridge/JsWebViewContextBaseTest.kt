package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

abstract class JsWebViewContextBaseTest : JsContextTest() {
    @Test
    fun closesWebViewHandleAliasesIndependently() {
        jsScoped(context) {
            val arr = eval("[{value: 1}]") as JsArray
            val a1 = arr[0] as JsObject
            val a2 = arr[0] as JsObject

            assertEquals((a1 as JsWebViewObject).handle, (a2 as JsWebViewObject).handle)

            a1.close()

            assertTrue(a1.isClosed)
            assertFalse(a2.isClosed)
            assertEquals(JsNumber(context, 1), a2.getValue("value"))
        }
    }

    @Test
    fun closesCreatedWebViewValueAliasesIndependently() {
        jsScoped(context) {
            val a1 = eval("({value: 1})") as JsObject
            val a2 = JsValueAlias(a1)

            assertEquals((a1 as JsWebViewObject).handle, (a2 as JsWebViewObject).handle)

            a1.close()

            assertTrue(a1.isClosed)
            assertFalse(a2.isClosed)
            assertEquals(JsNumber(context, 1), a2.getValue("value"))
        }
    }

    @Test
    fun keepsTagsAfterNativeWrapperIsClosedWhileJsRetainsObject() {
        val nativeObject = Any()
        val a1 =
            assertIs<JsObject>(
                context.evaluateScript("globalThis.__taggedObject = {value: 1}; __taggedObject"),
            )
        val handle = (a1 as JsWebViewObject).handle
        a1.setTag("nativeObject", nativeObject)

        a1.close()
        val a2 = assertIs<JsObject>(context.evaluateScript("__taggedObject"))

        assertEquals(handle, (a2 as JsWebViewObject).handle)
        assertEquals(nativeObject, a2.getTag("nativeObject"))
    }
}
