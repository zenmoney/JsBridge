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

internal inline fun <T> jsFunctionScoped(
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

/**
 * Creates a function that passes the callback's result to JavaScript before closing the callback's scope.
 * Returning a wrapper owned by another scope leaves its lifetime unchanged.
 */
context(scope: JsScope)
fun JsFunction(value: JsFunctionScope.(args: List<JsValue>) -> JsValue): JsFunction = JsFunction(scope.context, value).autoClose()

@Throws(JsException::class)
context(scope: JsScope)
operator fun JsFunction.invoke(
    args: List<JsValue> = emptyList(),
    thiz: JsValue = scope.context.globalThis,
): JsValue {
    scope.requireSameContext(this)
    return call(args, thiz).autoClose()
}

@Throws(JsException::class)
context(scope: JsScope)
operator fun JsFunction.invoke(
    vararg args: JsValue,
    thiz: JsValue = scope.context.globalThis,
): JsValue = invoke(args.asList(), thiz)

@Throws(JsException::class)
context(scope: JsScope)
fun JsFunction.invokeAsConstructor(args: List<JsValue> = emptyList()): JsValue {
    scope.requireSameContext(this)
    return callAsConstructor(args).autoClose()
}

@Throws(JsException::class)
context(scope: JsScope)
fun JsFunction.invokeAsConstructor(vararg args: JsValue): JsValue = invokeAsConstructor(args.asList())
