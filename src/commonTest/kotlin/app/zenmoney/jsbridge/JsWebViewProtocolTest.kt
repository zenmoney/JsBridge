package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JsWebViewProtocolTest {
    @Test
    fun encodesTypedCommands() {
        val command =
            JsWebViewMessage.CallFunction(
                functionHandle = 7,
                thisHandle = null,
                args =
                    listOf(
                        JsWebViewProtocolValue.String("a\nb"),
                        JsWebViewProtocolValue.Handle(9, JsWebViewProtocolHandleType.OBJECT),
                    ),
            )

        assertEquals(
            """["c",7,null,[["s","a\nb"],["h",9]]]""",
            command.value,
        )
        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["c",7,null,[["s","a\nb"],["h",9]]],5);""",
            command.toScript(5),
        )
    }

    @Test
    fun encodesCreateUint8ArrayCommand() {
        val command =
            JsWebViewMessage.CreateUint8Array(
                byteArrayOf(0, 128.toByte(), 255.toByte()),
            )

        assertEquals(
            """["y+",[0,128,255]]""",
            command.value,
        )
    }

    @Test
    fun encodesReadUint8ArrayCommand() {
        val command = JsWebViewMessage.ReadUint8Array(7)

        assertEquals(
            """["y?",7]""",
            command.value,
        )
    }

    @Test
    fun encodesNativeCallbackCommands() {
        val complete =
            JsWebViewMessage.CompleteNativeCallback(
                nativeCallbackId = 11,
                result = JsWebViewProtocolValue.Number(4),
            )
        val fail =
            JsWebViewMessage.FailNativeCallback(
                nativeCallbackId = 12,
                error = JsWebViewProtocolValue.Handle(7, JsWebViewProtocolHandleType.OBJECT),
            )

        assertEquals(
            """["+",11,["n",4.0]]""",
            complete.value,
        )
        assertEquals(
            """["-",12,["h",7]]""",
            fail.value,
        )
        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["+",11,["n",4.0]]);""",
            complete.toScript(),
        )
    }

    @Test
    fun encodesBooleansAndSpecialNumbers() {
        assertEquals("""["b",0]""", JsWebViewProtocolValue.Boolean(false).value)
        assertEquals("""["b",1]""", JsWebViewProtocolValue.Boolean(true).value)
        assertEquals("""["n","nan"]""", JsWebViewProtocolValue.Number(Double.NaN).value)
        assertEquals("""["n","+inf"]""", JsWebViewProtocolValue.Number(Double.POSITIVE_INFINITY).value)
        assertEquals("""["n","-inf"]""", JsWebViewProtocolValue.Number(Double.NEGATIVE_INFINITY).value)
        assertEquals("""["n","-0"]""", JsWebViewProtocolValue.Number(-0.0).value)

        assertEquals(false, JsWebViewProtocolValue.Boolean(false).decodeBoolean())
        assertEquals(true, JsWebViewProtocolValue.Boolean(true).decodeBoolean())
        assertTrue(JsWebViewProtocolValue.Number(Double.NaN).decodeNumber().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, JsWebViewProtocolValue.Number(Double.POSITIVE_INFINITY).decodeNumber())
        assertEquals(Double.NEGATIVE_INFINITY, JsWebViewProtocolValue.Number(Double.NEGATIVE_INFINITY).decodeNumber())
        assertEquals((-0.0).toBits(), JsWebViewProtocolValue.Number(-0.0).decodeNumber().toBits())
    }

    @Test
    fun packsHandleTypeIntoSafeInteger() {
        val handle = JsWebViewProtocolHandle.encode(42, JsWebViewProtocolHandleType.ARRAY)

        assertEquals(4294967338L, handle.encoded)
        assertEquals(42, handle.handle)
        assertEquals(JsWebViewProtocolHandleType.ARRAY, handle.type)
        assertEquals(
            """["h",4294967338]""",
            JsWebViewProtocolValue.Handle(42, JsWebViewProtocolHandleType.ARRAY).value,
        )
    }

    @Test
    fun escapesJsonControlCharacters() {
        val value = "\u0000\u0001\b\t\n\u000b\u000c\r\u001f\u2028\u2029"

        assertEquals(
            "\"\\u0000\\u0001\\b\\t\\n\\u000b\\f\\r\\u001f\\u2028\\u2029\"",
            value.toJson(),
        )
        assertEquals(
            "[\"s\",\"\\u0000\\u0001\\b\\t\\n\\u000b\\f\\r\\u001f\\u2028\\u2029\"]",
            JsWebViewProtocolValue.String(value).value,
        )
    }

    @Test
    fun decodesProtocolValuesWithoutJsContext() {
        val messages = mutableListOf<JsWebViewProtocolValue>()
        val handler =
            JsWebViewMessageHandler(
                object : JsWebViewMessageHandler.Listener {
                    override fun onSuccess(
                        requestId: Int,
                        result: JsWebViewProtocolValue,
                    ) {
                        assertEquals(3, requestId)
                        messages += result
                    }

                    override fun onFailure(
                        requestId: Int,
                        error: JsWebViewProtocolValue,
                    ) = error("Unexpected error")

                    override fun onFunction(
                        nativeCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) = error("Unexpected function")

                    override fun onPromiseExecutor(
                        executorCallbackId: Int,
                        resolve: JsWebViewProtocolValue,
                        reject: JsWebViewProtocolValue,
                    ) = error("Unexpected promise executor")

                    override fun onDeallocate(handle: Int) = error("Unexpected deallocate")
                },
            )

        handler.handle("""["r",3,["s","line\n\u263a"]]""")
        handler.handle("""["r",3,["y",[0,128,255]]]""")
        handler.handle("""["r",3,["h",4294967338]]""")
        handler.handle("""["r",3,["s","closing ] brackets"]   ]   """)

        assertEquals(JsWebViewProtocolCode.VALUE_STRING, messages[0].type)
        assertEquals("line\n☺", messages[0].decodeString())
        assertEquals(JsWebViewProtocolCode.VALUE_BYTE_ARRAY, messages[1].type)
        assertContentEquals(byteArrayOf(0, 128.toByte(), 255.toByte()), messages[1].decodeByteArray())
        assertEquals(JsWebViewProtocolCode.VALUE_HANDLE, messages[2].type)
        assertEquals(
            JsWebViewProtocolHandle.encode(42, JsWebViewProtocolHandleType.ARRAY),
            messages[2].decodeHandle(),
        )
        assertEquals("closing ] brackets", messages[3].decodeString())
    }

    @Test
    fun dispatchesFunctionMessageAsProtocolValues() {
        var receivedThis: JsWebViewProtocolValue? = null
        var receivedArgs: List<JsWebViewProtocolValue>? = null
        val handler =
            JsWebViewMessageHandler(
                object : JsWebViewMessageHandler.Listener {
                    override fun onSuccess(
                        requestId: Int,
                        result: JsWebViewProtocolValue,
                    ) = error("Unexpected result")

                    override fun onFailure(
                        requestId: Int,
                        error: JsWebViewProtocolValue,
                    ) = error("Unexpected error")

                    override fun onFunction(
                        nativeCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) {
                        assertEquals(5, nativeCallbackId)
                        assertEquals(8, callbackId)
                        receivedThis = thiz
                        receivedArgs = args
                    }

                    override fun onPromiseExecutor(
                        executorCallbackId: Int,
                        resolve: JsWebViewProtocolValue,
                        reject: JsWebViewProtocolValue,
                    ) = error("Unexpected promise executor")

                    override fun onDeallocate(handle: Int) = error("Unexpected deallocate")
                },
            )

        handler.handle(
            """["f",5,8,["h",0],[["0"],["u"],["b",1],["n",2.5]]]""",
        )

        assertEquals(
            JsWebViewProtocolHandle.encode(0, JsWebViewProtocolHandleType.OBJECT),
            receivedThis?.decodeHandle(),
        )
        assertEquals(
            listOf(
                JsWebViewProtocolCode.VALUE_NULL,
                JsWebViewProtocolCode.VALUE_UNDEFINED,
                JsWebViewProtocolCode.VALUE_BOOLEAN,
                JsWebViewProtocolCode.VALUE_NUMBER,
            ),
            receivedArgs?.map { it.type },
        )
        assertEquals(true, receivedArgs?.get(2)?.decodeBoolean())
        assertEquals(2.5, receivedArgs?.get(3)?.decodeNumber())
    }

    @Test
    fun rejectsInvalidProtocolValues() {
        val handler =
            JsWebViewMessageHandler(
                object : JsWebViewMessageHandler.Listener {
                    override fun onSuccess(
                        requestId: Int,
                        result: JsWebViewProtocolValue,
                    ) = error("Unexpected result")

                    override fun onFailure(
                        requestId: Int,
                        error: JsWebViewProtocolValue,
                    ) = error("Unexpected error")

                    override fun onFunction(
                        nativeCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) = error("Unexpected function")

                    override fun onPromiseExecutor(
                        executorCallbackId: Int,
                        resolve: JsWebViewProtocolValue,
                        reject: JsWebViewProtocolValue,
                    ) = error("Unexpected promise executor")

                    override fun onDeallocate(handle: Int) = error("Unexpected deallocate")
                },
            )

        assertFails {
            handler.handle("""["r",3,{"value":1}]""")
        }
        assertFails {
            handler.handle("""["f",5,8,["h",0],[{"value":1}]]""")
        }
        assertFails {
            handler.handle("""["r",3,42]""")
        }
        assertFails {
            handler.handle("""["r",3,["?",42]]""")
        }
        assertFails {
            handler.handle("""["r",3,["b",2]]""")
        }
        assertFails {
            handler.handle("""["r",3,["n","unknown"]]""")
        }
    }
}
