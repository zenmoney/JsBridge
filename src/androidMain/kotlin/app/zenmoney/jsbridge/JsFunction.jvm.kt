package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Value

actual sealed interface JsFunction : JsObject {
    @Throws(JsException::class)
    actual fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue

    @Throws(JsException::class)
    actual fun applyAsConstructor(args: List<JsValue>): JsValue
}

private fun <T> V8Array.map(action: (Any?) -> T): List<T> {
    val result = arrayListOf<T>()
    var i = 0
    while (i < length()) {
        result.add(action(get(i++)))
    }
    return result
}

actual fun JsFunction(
    context: JsContext,
    value: JsValue.(args: List<JsValue>) -> JsValue,
): JsFunction =
    JsFunctionImpl(
        context,
        V8Function(context.v8Runtime) { thiz, args ->
            (
                try {
                    value(
                        JsValue(context, thiz.twin()),
                        args?.map { JsValue(context, it) } ?: emptyList(),
                    )
                } catch (e: Exception) {
                    context.throwExceptionToJs(e)
                } as JsValueImpl
            ).v8Value.let {
                if (it is V8Value) {
                    it.twin()
                } else {
                    it
                }
            }
        },
    ).also { context.registerValue(it) }

internal class JsFunctionImpl(
    context: JsContext,
    v8Function: V8Function,
) : JsObjectImpl(context, v8Function),
    JsFunction {
    val v8Function: V8Function
        get() = v8Value as V8Function

    @Throws(JsException::class)
    override fun apply(
        thiz: JsValue,
        args: List<JsValue>,
    ): JsValue = context.callFunction(this, thiz, args)

    @Throws(JsException::class)
    override fun applyAsConstructor(args: List<JsValue>): JsValue =
        context.callFunctionAsConstructor(
            ArrayList<JsValue>(args.size + 1).apply {
                add(this@JsFunctionImpl)
                addAll(args)
            },
        )
}
