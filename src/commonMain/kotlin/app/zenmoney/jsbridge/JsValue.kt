package app.zenmoney.jsbridge

import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

expect sealed interface JsValue : AutoCloseable {
    val context: JsContext
}

internal expect val JsValue.core: JsValueCore

internal class JsValueCore(
    context: JsContext,
) : JsScopeItem() {
    @Suppress("PropertyName")
    internal var _context: JsContext? = context
    val context: JsContext
        get() = checkNotNull(_context) { "JsValue is already closed" }

    fun close(value: JsValue) {
        _context?.closeValue(value)
        _context = null
    }
}

@Suppress("FunctionName")
fun <T : JsValue> JsScope.JsValueAlias(value: T): T = context.createValueAlias(value).autoClose()

fun JsValue.toJson(): String =
    jsScope(context) {
        val stringify = eval("JSON.stringify") as JsFunction
        stringify(this@toJson).toString()
    }

fun JsValue.toPlainValue(): Any? = context.getPlainValueOf(this)

internal fun JsValue.toBasicPlainValue(): Any? {
    return when (this) {
        context.NULL,
        context.UNDEFINED,
        -> null
        is JsBoolean -> return toBoolean()
        is JsNumber -> return toNumber()
        is JsString -> return toString()
        is JsDate -> return toMillis()
        is JsUint8Array -> return toByteArray()
        is JsArray -> return toPlainList()
        is JsObject -> return toPlainMap()
        else -> toString()
    }
}

suspend fun JsValue.await(): JsValue {
    if (this !is JsObject) {
        return this
    }
    return jsScope(context) {
        val thiz = this@await
        val then = (get("then") as? JsFunction)?.escape() ?: return@jsScope thiz
        coroutineContext.job.invokeOnCompletion {
            then.close()
        }
        suspendCancellableCoroutine<JsValue> { cont ->
            jsScope(context) {
                then.autoClose()(
                    JsFunction {
                        cont.resume(it.firstOrNull()?.escape() ?: context.UNDEFINED)
                        context.UNDEFINED
                    },
                    JsFunction {
                        val exception =
                            it.firstOrNull()?.let { JsException(it) }
                                ?: JsException(
                                    message = "Promise rejected with no error",
                                    cause = null,
                                    data = emptyMap(),
                                )
                        cont.resumeWithException(exception)
                        context.UNDEFINED
                    },
                    thiz = thiz,
                ).escape()
            }
        }
    }
}

fun JsValue.isNullOrUndefined(): Boolean = this == context.NULL || this == context.UNDEFINED
