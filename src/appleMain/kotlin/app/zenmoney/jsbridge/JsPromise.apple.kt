package app.zenmoney.jsbridge

import platform.JavaScriptCore.JSValue

actual sealed interface JsPromise : JsObject

internal actual fun JsPromise(
    context: JsContext,
    executor: JsScope.(JsFunction, JsFunction) -> Unit,
): JsPromise =
    JsPromiseImpl(
        context,
        context.jsPromise.constructWithArguments(
            listOf(
                (
                    JsFunction(context) { args, _ ->
                        executor(
                            this,
                            args[0] as JsFunction,
                            args[1] as JsFunction,
                        )
                        context.UNDEFINED
                    } as JsFunctionImpl
                ).jsValue,
            ),
        )!!,
    ).also { context.registerValue(it) }

internal open class JsPromiseImpl(
    context: JsContext,
    jsValue: JSValue,
) : JsObjectImpl(context, jsValue),
    JsPromise
