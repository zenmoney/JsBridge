package app.zenmoney.jsbridge.consumer

import app.zenmoney.jsbridge.JsArray
import app.zenmoney.jsbridge.JsBoolean
import app.zenmoney.jsbridge.JsBooleanObject
import app.zenmoney.jsbridge.JsContext
import app.zenmoney.jsbridge.JsDate
import app.zenmoney.jsbridge.JsEventLoop
import app.zenmoney.jsbridge.JsFunction
import app.zenmoney.jsbridge.JsNull
import app.zenmoney.jsbridge.JsNumber
import app.zenmoney.jsbridge.JsNumberObject
import app.zenmoney.jsbridge.JsObject
import app.zenmoney.jsbridge.JsPromise
import app.zenmoney.jsbridge.JsScope
import app.zenmoney.jsbridge.JsString
import app.zenmoney.jsbridge.JsStringObject
import app.zenmoney.jsbridge.JsUint8Array
import app.zenmoney.jsbridge.JsUndefined
import app.zenmoney.jsbridge.JsValue
import app.zenmoney.jsbridge.JsValueAlias
import app.zenmoney.jsbridge.autoClose
import app.zenmoney.jsbridge.await
import app.zenmoney.jsbridge.escape
import app.zenmoney.jsbridge.eval
import app.zenmoney.jsbridge.evalBlockScoped
import app.zenmoney.jsbridge.get
import app.zenmoney.jsbridge.int
import app.zenmoney.jsbridge.invoke
import app.zenmoney.jsbridge.invokeAsConstructor
import app.zenmoney.jsbridge.isClosed
import app.zenmoney.jsbridge.isScoped
import app.zenmoney.jsbridge.jsScoped
import app.zenmoney.jsbridge.string
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class JsScopeContextParametersTest {
    @Test
    fun explicitScopeCanEscapeAndAdoptCollections() {
        JsContext().use { context ->
            val values =
                JsScope(context).use { scope ->
                    with(scope) { listOf(JsObject(), JsString("escaped")) }.also { values ->
                        scope.escape(values)
                        values.forEach {
                            assertFalse(it in scope)
                            assertFalse(it.isScoped)
                        }
                    }
                }
            values.forEach { assertFalse(it.isClosed) }
            JsScope(context).use { scope ->
                scope.autoClose(values)
                values.forEach { assertTrue(it in scope) }
            }
            values.forEach { assertTrue(it.isClosed) }
        }
    }

    @Test
    fun operationsWorkWithOnlyAContextParameterAndCloseWithTheirScope() {
        JsContext().use { context ->
            val values = JsScope(context).use { scope -> with(scope) { exerciseOperations() } }
            values.forEach { assertTrue(it.isClosed) }
        }
    }

    @Test
    fun promiseFactoriesAndAwaitUseTheProvidedScope() =
        runTest {
            JsContext().use { context ->
                val eventLoop = JsEventLoop(coroutineContext).apply { attachTo(context) }
                val consumer = eventLoop.async { jsScoped(context) { exercisePromises() } }
                val runner = launch { eventLoop.runAndComplete() }
                val values = consumer.await()
                runner.join()
                values.forEach { assertTrue(it.isClosed) }
            }
        }
}

context(scope: JsScope)
private fun exerciseOperations(): List<JsValue> {
    val text = JsString("value")
    val obj = JsObject().apply { this["name"] = text }
    val array = JsArray(listOf(text))
    val objectEntry = obj["name"]
    val arrayEntry = array[0]
    val alias = JsValueAlias(text)
    assertEquals("value", objectEntry.string)
    assertEquals("value", arrayEntry.string)
    assertNotSame(text, alias)

    val function = eval("(function (value) { return value; })") as JsFunction
    val fromVarargs = function(text)
    val fromList = function(listOf(text))
    assertEquals("value", fromVarargs.string)
    assertEquals("value", fromList.string)

    val constructor = eval("(function Box(value) { this.value = value; })") as JsFunction
    val constructedFromVarargs = constructor.invokeAsConstructor(text) as JsObject
    val constructedFromList = constructor.invokeAsConstructor(listOf(text)) as JsObject
    assertEquals("value", constructedFromVarargs["value"].string)
    assertEquals("value", constructedFromList["value"].string)

    lateinit var callbackValue: JsValue
    val callback =
        JsFunction { args ->
            JsString(args[0].string + "!").also {
                callbackValue = it
                assertTrue(it in this)
                assertFalse(it in scope)
            }
        }
    val callbackResult = callback(text)
    assertEquals("value!", callbackResult.string)
    assertTrue(callbackValue.isClosed)

    val innerValue =
        jsScoped(scope.context) {
            JsString("inner").also {
                assertTrue(it in this)
                assertFalse(it in scope)
            }
        }
    assertTrue(innerValue.isClosed)
    assertFalse(text.isClosed)

    val adopted = JsString("adopted").escape().autoClose()
    val adoptedList = listOf(JsString("first"), JsString("second")).escape().autoClose()
    val blockValue = evalBlockScoped("name;", "name" to text)
    assertEquals("value", blockValue.string)
    assertEquals(eval("null"), JsNull())
    assertEquals(eval("undefined"), JsUndefined())

    return (
        listOf(
            text,
            obj,
            array,
            objectEntry,
            arrayEntry,
            alias,
            function,
            fromVarargs,
            fromList,
            constructor,
            constructedFromVarargs,
            constructedFromList,
            callback,
            callbackResult,
            adopted,
            blockValue,
            JsStringObject("boxed"),
            JsNumber(7),
            JsNumberObject(7),
            JsBoolean(true),
            JsBooleanObject(true),
            JsDate(1234),
            JsUint8Array(byteArrayOf(1, 2)),
            JsObject(IllegalStateException("failure")),
            with(scope) { eval("({ explicitScope: true })") },
        ) + adoptedList
    ).also { values -> values.forEach { assertTrue(it in scope) } }
}

context(scope: JsScope)
private suspend fun exercisePromises(): List<JsValue> {
    val immediate = JsPromise { resolve, _ -> resolve(JsNumber(21)) }
    lateinit var produced: JsValue
    val asynchronous =
        JsPromise {
            JsString("async").also {
                produced = it
                assertTrue(it in this)
                assertFalse(it in scope)
            }
        }
    val immediateResult = immediate.await()
    val asynchronousResult = asynchronous.await()
    assertEquals(21, immediateResult.int)
    assertEquals("async", asynchronousResult.string)
    assertTrue(produced.isClosed)

    val plain = JsNumber(42)
    val plainResult = plain.await()
    assertEquals(42, plainResult.int)
    assertNotSame(plain, plainResult)
    assertFalse(plain.isClosed)
    return listOf(immediate, asynchronous, immediateResult, asynchronousResult, plain, plainResult).also { values ->
        values.forEach { assertTrue(it in scope) }
    }
}
