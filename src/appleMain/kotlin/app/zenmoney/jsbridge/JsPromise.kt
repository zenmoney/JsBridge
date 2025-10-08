package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsPromise : JsObject

actual fun JsPromise(
    context: JsContext,
    executor: (JsFunction, JsFunction) -> Unit,
): JsPromise =
    JsPromiseImpl(
        context,
        context.jsPromise.constructWithArguments(
            listOf(
                (
                    JsFunction(context) { args ->
                        executor(args[0] as JsFunction, args[1] as JsFunction)
                        context.UNDEFINED
                    } as JsFunctionImpl
                ).jsValue,
            ),
        )!!,
    )

internal open class JsPromiseImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsPromise
