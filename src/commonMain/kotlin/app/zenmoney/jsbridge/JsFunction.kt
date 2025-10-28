package app.zenmoney.jsbridge

expect sealed interface JsFunction : JsObject

class JsFunctionScope internal constructor(
    context: JsContext,
) : JsScope(context) {
    @Suppress("PropertyName")
    internal var _thiz: JsValue? = null
    val thiz: JsValue
        get() = checkNotNull(_thiz) { "JsFunctionScope is already closed" }

    override fun close() {
        _thiz = null
        super.close()
    }
}

internal inline fun <T> jsFunctionScope(
    context: JsContext,
    block: JsFunctionScope.() -> T,
): T = JsFunctionScope(context).use(block)

@Throws(JsException::class)
internal fun JsFunction.call(
    args: List<JsValue> = emptyList(),
    thiz: JsValue = context.globalThis,
): JsValue = context.callFunction(this, args, thiz)

@Throws(JsException::class)
internal fun JsFunction.callAsConstructor(args: List<JsValue> = emptyList()): JsValue = context.callFunctionAsConstructor(this, args)

internal fun JsFunction(
    context: JsContext,
    value: JsFunctionScope.(args: List<JsValue>) -> JsValue,
): JsFunction = context.createFunction(value)

fun JsScope.JsFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction = JsFunction(context, value).autoClose()
