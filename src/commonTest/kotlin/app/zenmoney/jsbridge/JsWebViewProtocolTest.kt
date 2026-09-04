package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JsWebViewProtocolTest {
    @Test
    fun webViewRuntimeEmbedsOnlyMinimalExpressionCore() {
        assertTrue(jsWebViewRuntimeScript.length < 36_000)
        assertTrue("function encodeObject(" !in jsWebViewRuntimeScript)
        assertTrue("function encodeDate(" !in jsWebViewRuntimeScript)
        assertTrue("typedArrayDefinitions" !in jsWebViewRuntimeScript)
    }

    @Test
    fun webViewRuntimeScriptInstallsWithExpressionCodec() {
        JsContext().use { context ->
            context
                .evaluateScript(
                    "globalThis.window = globalThis; delete globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT; null",
                ).use { }
            context
                .evaluateScript(
                    """
                    globalThis.__runtimeError = null;
                    try {
                        $jsWebViewRuntimeScript
                    } catch (error) {
                        globalThis.__runtimeError = { message: error.message, stack: error.stack };
                    }
                    """.trimIndent(),
                ).use { }
            val runtimeError = (context.evaluateScript("JSON.stringify(globalThis.__runtimeError)") as JsString).use { it.toString() }
            assertEquals("null", runtimeError)

            assertTrue(
                (context.evaluateScript("typeof globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT === 'object'") as JsBoolean)
                    .use { it.toBoolean() },
            )
        }
    }

    @Test
    fun webViewRuntimeUsesExpressionEncodingForValues() {
        JsContext().use { context ->
            val messages =
                (
                    context.evaluateScript(
                        """
                        globalThis.window = globalThis;
                        globalThis.__webViewProtocolMessages = [];
                        globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {
                            postMessage(message) {
                                globalThis.__webViewProtocolMessages.push(message);
                            }
                        };
                        delete globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT;
                        $jsWebViewRuntimeScript
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "null"], 1);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "false"], 2);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "true"], 3);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "2.5"], 4);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "'line\\u2028separator'"], 5);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "undefined"], 6);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "NaN"], 7);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "Infinity"], 8);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "-Infinity"], 9);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "-0"], 10);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "9007199254740993n"], 11);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["s", 0, "__decodedValue", true], 12);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "globalThis.__decodedValue"], 13);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["s", 0, "__decodedValue", ["n", "-0"]], 14);
                        $JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(["e", "Object.is(globalThis.__decodedValue, -0)"], 15);
                        globalThis.__webViewProtocolMessages.join("\n");
                        """.trimIndent(),
                    ) as JsString
                ).use { it.toString() }

            assertEquals(
                """
                ["r",1,null]
                ["r",2,false]
                ["r",3,true]
                ["r",4,2.5]
                ["r",5,"line\u2028separator"]
                ["r",6,["u"]]
                ["r",7,["n","nan"]]
                ["r",8,["n","+inf"]]
                ["r",9,["n","-inf"]]
                ["r",10,["n","-0"]]
                ["r",11,["i","9007199254740993"]]
                ["r",12,["u"]]
                ["r",13,true]
                ["r",14,["u"]]
                ["r",15,true]
                """.trimIndent(),
                messages,
            )
        }
    }

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
            """["c",7,null,["a\nb",["h",9]]]""",
            command.value,
        )
        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["c",7,null,["a\nb",["h",9]]],5);""",
            command.toScript(5),
        )
    }

    @Test
    fun embedsExpressionWireDirectlyInTaggedDecodeCommand() {
        val command =
            JsWebViewMessage.DecodeExpression(
                decoderHandle = 7,
                expression = """["o",1,{"name":"Ada","self":["r",1]}]""",
                resolvedReferenceValues =
                    listOf(
                        JsWebViewProtocolValue.String("external"),
                        JsWebViewProtocolValue.Handle(9, JsWebViewProtocolHandleType.OBJECT),
                    ),
            )

        assertEquals(
            """["v",7,["o",1,{"name":"Ada","self":["r",1]}],["external",["h",9]]]""",
            command.value,
        )
        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["v",7,["o",1,{"name":"Ada","self":["r",1]}],["external",["h",9]]],5);""",
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
            """["y+",["ui8",1,"AID/"]]""",
            command.value,
        )
    }

    @Test
    fun decodesCanonicalBase64WithoutIntermediateString() {
        for (size in 0..64) {
            val value = ByteArray(size) { (it * 31 + size).toByte() }
            assertContentEquals(value, JsWebViewProtocolValue.Uint8Array(value).decodeUint8Array())
        }
        assertContentEquals(
            byteArrayOf(0, 128.toByte(), 255.toByte()),
            JsWebViewProtocolValue.fromEncoded("""["ui8",1,"AID\/"]""").decodeUint8Array(),
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
    fun encodesFireAndForgetCommand() {
        val command = JsWebViewMessage.Release(7)

        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["r",7]);""",
            command.toScript(),
        )
    }

    @Test
    fun encodesNativeCallbackCommands() {
        val complete =
            JsWebViewMessage.CompleteNativeCallback(
                jsCallbackId = 11,
                result = JsWebViewProtocolValue.Number(4),
            )
        val fail =
            JsWebViewMessage.FailNativeCallback(
                jsCallbackId = 12,
                error = JsWebViewProtocolValue.Handle(7, JsWebViewProtocolHandleType.OBJECT),
            )

        assertEquals(
            """["+",11,4.0]""",
            complete.value,
        )
        assertEquals(
            """["-",12,["h",7]]""",
            fail.value,
        )
        assertEquals(
            """__appZenmoneyJsBridge.dispatch(["+",11,4.0]);""",
            complete.toScript(),
        )
    }

    @Test
    fun usesExpressionEncodingForPrimitiveValues() {
        assertEquals("null", JsWebViewProtocolValue.Null().value)
        assertEquals("[\"u\"]", JsWebViewProtocolValue.Undefined().value)
        assertEquals("false", JsWebViewProtocolValue.Boolean(false).value)
        assertEquals("true", JsWebViewProtocolValue.Boolean(true).value)
        assertEquals("4.0", JsWebViewProtocolValue.Number(4).value)
        assertEquals("""["n","nan"]""", JsWebViewProtocolValue.Number(Double.NaN).value)
        assertEquals("""["n","+inf"]""", JsWebViewProtocolValue.Number(Double.POSITIVE_INFINITY).value)
        assertEquals("""["n","-inf"]""", JsWebViewProtocolValue.Number(Double.NEGATIVE_INFINITY).value)
        assertEquals("""["n","-0"]""", JsWebViewProtocolValue.Number(-0.0).value)
        assertEquals("\"value\"", JsWebViewProtocolValue.String("value").value)

        assertEquals(false, JsWebViewProtocolValue.Boolean(false).decodeBoolean())
        assertEquals(true, JsWebViewProtocolValue.Boolean(true).decodeBoolean())
        assertEquals(4.0, JsWebViewProtocolValue.Number(4).decodeNumber())
        assertTrue(JsWebViewProtocolValue.Number(Double.NaN).decodeNumber().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, JsWebViewProtocolValue.Number(Double.POSITIVE_INFINITY).decodeNumber())
        assertEquals(Double.NEGATIVE_INFINITY, JsWebViewProtocolValue.Number(Double.NEGATIVE_INFINITY).decodeNumber())
        assertEquals((-0.0).toBits(), JsWebViewProtocolValue.Number(-0.0).decodeNumber().toBits())
        assertFails { JsWebViewProtocolValue.BigInt("01") }
        assertFails { JsWebViewProtocolValue.fromEncoded("""["i","-0"]""").decodeBigIntString() }
    }

    @Test
    fun encodesBigInts() {
        val value = JsWebViewProtocolValue.BigInt("9007199254740993")

        assertEquals("""["i","9007199254740993"]""", value.value)
        assertEquals(9007199254740992.0, value.decodeBigInt())
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
            "\"\\u0000\\u0001\\b\\t\\n\\u000b\\f\\r\\u001f\\u2028\\u2029\"",
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
                        jsCallbackId: Int,
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

        handler.handle("""["r",3,"line\n\u263a"]""")
        handler.handle("""["r",3,["ui8",1,"AID/"]]""")
        handler.handle("""["r",3,["h",4294967338]]""")
        handler.handle("""["r",3,["i","9007199254740993"]]""")
        handler.handle("""["r",3,"closing ] brackets"   ]   """)
        handler.handle("""["r",3,false]""")
        handler.handle("""["r",3,true]""")
        handler.handle("""["r",3,null]""")
        handler.handle("""["r",3,["u"]]""")
        handler.handle("""["r",3,2.5]""")
        handler.handle("""["r",3,["n","nan"]]""")
        handler.handle("""["r",3,["n","+inf"]]""")
        handler.handle("""["r",3,["n","-inf"]]""")
        handler.handle("""["r",3,["n","-0"]]""")

        assertEquals(JsWebViewProtocolValueType.STRING, messages[0].type)
        assertEquals("line\n☺", messages[0].decodeString())
        assertEquals(""""line\n\u263a"""", messages[0].value)
        assertEquals(JsWebViewProtocolValueType.UINT8_ARRAY, messages[1].type)
        assertContentEquals(byteArrayOf(0, 128.toByte(), 255.toByte()), messages[1].decodeUint8Array())
        assertEquals(JsWebViewProtocolValueType.HANDLE, messages[2].type)
        assertEquals(
            JsWebViewProtocolHandle.encode(42, JsWebViewProtocolHandleType.ARRAY),
            messages[2].decodeHandle(),
        )
        assertEquals(JsWebViewProtocolValueType.BIGINT, messages[3].type)
        assertEquals(9007199254740992.0, messages[3].decodeBigInt())
        assertEquals("closing ] brackets", messages[4].decodeString())
        assertEquals(false, messages[5].decodeBoolean())
        assertEquals(true, messages[6].decodeBoolean())
        assertEquals(JsWebViewProtocolValueType.NULL, messages[7].type)
        assertEquals(JsWebViewProtocolValueType.UNDEFINED, messages[8].type)
        assertEquals(2.5, messages[9].decodeNumber())
        assertTrue(messages[10].decodeNumber().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, messages[11].decodeNumber())
        assertEquals(Double.NEGATIVE_INFINITY, messages[12].decodeNumber())
        assertEquals((-0.0).toBits(), messages[13].decodeNumber().toBits())
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
                        jsCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) {
                        assertEquals(5, jsCallbackId)
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
            """["f",5,8,["h",0],[null,["u"],true,2.5,["i","9007199254740993"]]]""",
        )

        assertEquals(
            JsWebViewProtocolHandle.encode(0, JsWebViewProtocolHandleType.OBJECT),
            receivedThis?.decodeHandle(),
        )
        assertEquals(
            listOf(
                JsWebViewProtocolValueType.NULL,
                JsWebViewProtocolValueType.UNDEFINED,
                JsWebViewProtocolValueType.BOOLEAN,
                JsWebViewProtocolValueType.NUMBER,
                JsWebViewProtocolValueType.BIGINT,
            ),
            receivedArgs?.map { it.type },
        )
        assertEquals(true, receivedArgs?.get(2)?.decodeBoolean())
        assertEquals(2.5, receivedArgs?.get(3)?.decodeNumber())
        assertEquals(9007199254740992.0, receivedArgs?.get(4)?.decodeBigInt())
    }

    @Test
    fun dispatchesErrorPromiseAndDeallocateMessages() {
        var failedRequestId = 0
        var failure: JsWebViewProtocolValue? = null
        var promiseCallbackId = 0
        var receivedResolve: JsWebViewProtocolValue? = null
        var receivedReject: JsWebViewProtocolValue? = null
        var deallocatedHandle = 0
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
                    ) {
                        failedRequestId = requestId
                        failure = error
                    }

                    override fun onFunction(
                        jsCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) = error("Unexpected function")

                    override fun onPromiseExecutor(
                        executorCallbackId: Int,
                        resolve: JsWebViewProtocolValue,
                        reject: JsWebViewProtocolValue,
                    ) {
                        promiseCallbackId = executorCallbackId
                        receivedResolve = resolve
                        receivedReject = reject
                    }

                    override fun onDeallocate(handle: Int) {
                        deallocatedHandle = handle
                    }
                },
            )

        handler.handle("""["e",4,"failure"]""")
        handler.handle("""["p",5,"resolve","reject"]""")
        handler.handle("""["d",7]""")

        assertEquals(4, failedRequestId)
        assertEquals("failure", failure?.decodeString())
        assertEquals(5, promiseCallbackId)
        assertEquals("resolve", receivedResolve?.decodeString())
        assertEquals("reject", receivedReject?.decodeString())
        assertEquals(7, deallocatedHandle)
    }

    @Test
    fun validatesCompleteCallbackBeforeDispatch() {
        var dispatchCount = 0
        val handler =
            JsWebViewMessageHandler(
                object : JsWebViewMessageHandler.Listener {
                    override fun onSuccess(
                        requestId: Int,
                        result: JsWebViewProtocolValue,
                    ) {
                        dispatchCount++
                    }

                    override fun onFailure(
                        requestId: Int,
                        error: JsWebViewProtocolValue,
                    ) {
                        dispatchCount++
                    }

                    override fun onFunction(
                        jsCallbackId: Int,
                        callbackId: Int,
                        thiz: JsWebViewProtocolValue,
                        args: List<JsWebViewProtocolValue>,
                    ) {
                        dispatchCount++
                    }

                    override fun onPromiseExecutor(
                        executorCallbackId: Int,
                        resolve: JsWebViewProtocolValue,
                        reject: JsWebViewProtocolValue,
                    ) {
                        dispatchCount++
                    }

                    override fun onDeallocate(handle: Int) {
                        dispatchCount++
                    }
                },
            )

        listOf(
            """["r",3,7] trailing""",
            """["r",18446744073709551617,7]""",
            """["r",3,["h",18446744073709551617]]""",
        ).forEach { message ->
            assertFails { handler.handle(message) }
        }
        assertEquals(0, dispatchCount)
        assertFails {
            JsWebViewProtocolValue.fromEncoded("""["h",18446744073709551617]""").decodeHandle()
        }
        assertFails {
            JsWebViewProtocolValue.fromEncoded("""["ui8",18446744073709551617,""]""").decodeUint8Array()
        }
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
                        jsCallbackId: Int,
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
            handler.handle("""["r",3,["?",42]]""")
        }
        assertFails {
            handler.handle("""["r",3,["0"]]""")
        }
        assertFails {
            handler.handle("""["r",3,["b",2]]""")
        }
        assertFails {
            handler.handle("""["r",3,["n",42]]""")
        }
        assertFails {
            handler.handle("""["r",3,["s","value"]]""")
        }
        assertFails {
            handler.handle("""["r",3,["n","unknown"]]""")
        }
        assertFails {
            handler.handle("""["r",3,["i",42]]""")
        }
        assertFails {
            handler.handle("[\"r\",3,\"line\nbreak\"]")
        }
        assertFails {
            handler.handle("""["r",3,"invalid\xescape"]""")
        }
        assertFails {
            JsWebViewProtocolValue.fromEncoded("""["ui8",1,"AA"]""").decodeUint8Array()
        }
        assertFails {
            JsWebViewProtocolValue.fromEncoded("""["ui8",1,"AB=="]""").decodeUint8Array()
        }
    }
}
