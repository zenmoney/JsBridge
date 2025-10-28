package app.zenmoney.jsbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

expect sealed interface JsPromise : JsObject

internal fun JsPromise(
    context: JsContext,
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = context.createPromise(executor)

fun JsScope.JsPromise(
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = JsPromise(context, executor).autoClose()

private fun JsEventLoop?.checkNotNull(): JsEventLoop = checkNotNull(this) { "JsContext has no event loop attached" }

class JsPromiseScope internal constructor(
    context: JsContext,
) : JsScope(context),
    CoroutineScope by context.core.eventLoop.checkNotNull()

internal inline fun <T> jsPromiseScope(
    context: JsContext,
    block: JsPromiseScope.() -> T,
): T = JsPromiseScope(context).use(block)

fun JsScope.JsPromise(
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend JsPromiseScope.() -> JsValue,
): JsPromise {
    lateinit var resolve: JsFunction
    lateinit var reject: JsFunction
    val promise =
        JsPromise { res, rej ->
            resolve = res.escape()
            reject = rej.escape()
        }
    val context = context
    context.core.eventLoop
        .checkNotNull()
        .launch(start = start) {
            jsPromiseScope(context) {
                autoClose(resolve)
                autoClose(reject)
                val value =
                    try {
                        block().autoClose()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reject(JsObject(e))
                        return@launch
                    }
                resolve(value)
            }
        }.invokeOnCompletion {
            resolve.close()
            reject.close()
        }
    return promise
}

suspend fun JsValue.await(): JsValue {
    val thiz = this
    return jsScope(context) {
        var value = thiz
        while (true) {
            val then = (value as? JsObject)?.get("then") as? JsFunction
            if (then == null) {
                break
            }
            value =
                suspendCancellableCoroutine { cont ->
                    then(
                        JsFunction {
                            cont.resume(it.firstOrNull()?.escape() ?: context.UNDEFINED)
                            context.UNDEFINED
                        },
                        JsFunction {
                            val exception =
                                it.firstOrNull()?.let { error -> JsException(error) }
                                    ?: JsException(
                                        message = "Promise rejected with no error",
                                        cause = null,
                                        data = emptyMap(),
                                    )
                            cont.resumeWithException(exception)
                            context.UNDEFINED
                        },
                        thiz = value,
                    )
                }.autoClose()
        }
        if (value === thiz) thiz else value.escape()
    }
}
