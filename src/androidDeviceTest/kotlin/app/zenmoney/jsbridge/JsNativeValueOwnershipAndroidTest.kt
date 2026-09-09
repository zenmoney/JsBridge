package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JsNativeValueOwnershipAndroidTest {
    @Test
    fun failedObjectClassificationReleasesIncomingNativeValues() {
        JsEngineContext().use { context ->
            val runtime = nativeRuntime(context)
            val baseline = runtime.objectReferenceCount
            for (script in listOf(
                "({get then(){throw new Error('getter failed')}})",
                "new Proxy({}, {getPrototypeOf(){throw new Error('prototype failed')}})",
                "(()=>{const n=new Number(1); n.toString=()=> 'not-a-number'; return n})()",
            )) {
                repeat(25) {
                    assertFailsWith<Exception> { context.evaluateScript(script).close() }
                }
                assertEquals(baseline, runtime.objectReferenceCount, script)
            }
        }
    }

    @Test
    fun failedPropertyClassificationReleasesIncomingNativeValues() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val runtime = nativeRuntime(context)
                val parent = assertIs<JsObject>(eval("({child: {get then(){throw new Error('getter failed')}}})"))
                val baseline = runtime.objectReferenceCount
                repeat(25) {
                    assertFailsWith<Exception> { parent["child"] }
                }
                assertEquals(baseline, runtime.objectReferenceCount)
            }
        }
    }

    @Test
    fun failedArrayPopulationReleasesTheNewNativeArray() {
        JsEngineContext().use { context ->
            jsScoped(context) {
                val runtime = nativeRuntime(context)
                val number = JsNumber(1)
                val values =
                    sequence {
                        yield(number)
                        throw IllegalArgumentException("iterator failed")
                    }.asIterable()
                val baseline = runtime.objectReferenceCount
                repeat(25) {
                    assertFailsWith<IllegalArgumentException> { context.createArray(values).close() }
                }
                assertEquals(baseline, runtime.objectReferenceCount)
            }
        }
    }

    private fun nativeRuntime(context: JsEngineContext): V8 =
        JsEngineContext::class.java
            .getDeclaredField("v8Runtime")
            .apply { isAccessible = true }
            .get(context) as V8
}
