package app.zenmoney.jsbridge

import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Value

actual sealed interface JsFunction : JsObject

@Throws(JsException::class)
internal actual fun JsFunction.apply(
    thiz: JsValue,
    args: List<JsValue>,
): JsValue = context.callFunction((this as JsFunctionImpl), thiz, args)

@Throws(JsException::class)
internal actual fun JsFunction.applyAsConstructor(args: List<JsValue>): JsValue =
    context.callFunctionAsConstructor.apply(
        args =
            ArrayList<JsValue>(args.size + 1).apply {
                add(this@applyAsConstructor)
                addAll(args)
            },
        thiz = context.globalThis,
    )

private fun <T> V8Array.map(action: (Any?) -> T): List<T> {
    val result = arrayListOf<T>()
    var i = 0
    while (i < length()) {
        result.add(action(get(i++)))
    }
    return result
}

internal actual fun JsFunction(
    context: JsContext,
    value: JsScope.(args: List<JsValue>, thiz: JsValue) -> JsValue,
): JsFunction =
    JsFunctionImpl(
        context,
        V8Function(context.v8Runtime) { thiz, args ->
            jsScope(context) {
                (
                    try {
                        value(
                            this,
                            args?.map { JsValue(context, it).autoClose() } ?: emptyList(),
                            JsValue(context, thiz.twin()).autoClose(),
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
}
