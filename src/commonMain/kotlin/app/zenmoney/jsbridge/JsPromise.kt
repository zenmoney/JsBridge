package app.zenmoney.jsbridge

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

expect sealed interface JsPromise : JsObject

internal fun JsPromise(
    context: JsContext,
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = context.createPromise(executor)

context(scope: JsScope)
fun JsPromise(
    executor: JsScope.(
        resolve: JsFunction,
        reject: JsFunction,
    ) -> Unit,
): JsPromise = JsPromise(scope.context, executor).autoClose()

private fun JsEventLoop?.checkNotNull(): JsEventLoop = checkNotNull(this) { "JsContext has no event loop attached" }

class JsPromiseScope internal constructor(
    context: JsContext,
) : JsScope(context),
    CoroutineScope by context.core.eventLoop.checkNotNull()

internal inline fun <T> jsPromiseScoped(
    context: JsContext,
    block: JsPromiseScope.() -> T,
): T = JsPromiseScope(context).use(block)

/**
 * Creates a promise and passes [block]'s result to its JavaScript resolve function before closing the block's scope.
 * Returning a wrapper owned by another scope leaves its lifetime unchanged.
 */
context(scope: JsScope)
fun JsPromise(
    start: CoroutineStart = CoroutineStart.DEFAULT,
    block: suspend JsPromiseScope.() -> JsValue,
): JsPromise {
    val context = scope.context
    val eventLoop = context.core.eventLoop.checkNotNull()
    lateinit var resolve: JsFunction
    lateinit var reject: JsFunction
    val promise =
        JsPromise { res, rej ->
            resolve = res.escape()
            reject = rej.escape()
        }
    eventLoop
        .launch(start = start) {
            jsPromiseScoped(context) {
                autoClose(resolve)
                autoClose(reject)
                val value =
                    try {
                        block()
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

context(scope: JsScope)
suspend fun JsValue.await(): JsValue {
    scope.requireSameContext(this)
    return awaitInScope(scope)
}

internal suspend fun JsValue.awaitInScope(scope: JsScope): JsValue =
    context.core.eventLoop
        .checkNotNull()
        .async(start = CoroutineStart.UNDISPATCHED) { runCatching { _awaitInScope(scope) } }
        .await()
        .fold({ it }, { throw it })

private fun <T> CancellableContinuation<T>.resumeOnce(result: Result<T>) {
    try {
        resumeWith(result)
    } catch (e: IllegalStateException) {
        if (!isCompleted) {
            throw e
        }
    }
}

@Suppress("FunctionName")
private suspend fun JsValue._awaitInScope(scope: JsScope): JsValue {
    val thiz = this
    return with(scope) {
        var value = thiz
        while (true) {
            val then = (value as? JsObject)?.get("then") as? JsFunction ?: break
            value =
                suspendCancellableCoroutine { cont ->
                    val closeRegistration =
                        context.invokeOnClose {
                            cont.resumeOnce(Result.failure(IllegalStateException("JsContext is closed")))
                        }
                    cont.invokeOnCancellation {
                        closeRegistration.dispose()
                    }
                    if (cont.isCompleted) {
                        closeRegistration.dispose()
                        return@suspendCancellableCoroutine
                    }
                    try {
                        then(
                            JsFunction {
                                if (cont.isCompleted) {
                                    return@JsFunction context.UNDEFINED
                                }
                                val result =
                                    runCatching {
                                        it.firstOrNull()?.escape()?.also { value -> scope.autoClose(value) } ?: context.UNDEFINED
                                    }
                                closeRegistration.dispose()
                                cont.resumeOnce(result)
                                context.UNDEFINED
                            },
                            JsFunction {
                                if (cont.isCompleted) {
                                    return@JsFunction context.UNDEFINED
                                }
                                val exception =
                                    try {
                                        it.firstOrNull()?.let { error -> JsException(error) }
                                            ?: JsException(
                                                message = "Promise rejected with no error",
                                                cause = null,
                                                data = emptyMap(),
                                            )
                                    } catch (e: Throwable) {
                                        e
                                    }
                                closeRegistration.dispose()
                                cont.resumeOnce(Result.failure(exception))
                                context.UNDEFINED
                            },
                            thiz = value,
                        )
                    } catch (e: Throwable) {
                        closeRegistration.dispose()
                        cont.resumeOnce(Result.failure(e))
                    }
                }
        }
        if (value === thiz) JsValueAlias(thiz) else value
    }
}
