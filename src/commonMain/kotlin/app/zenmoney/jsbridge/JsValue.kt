package app.zenmoney.jsbridge

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

expect sealed interface JsValue : AutoCloseable {
    val context: JsContext
}

fun JsValue.toJson(): String =
    context.evaluateScript("JSON.stringify").use { stringify ->
        (stringify as JsFunction).apply(context.globalObject, listOf(this)).use {
            it.toString()
        }
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
    return get("then").use { then ->
        if (then !is JsFunction) {
            return this
        }
        suspendCancellableCoroutine { cont ->
            then.apply(
                thiz = this,
                args =
                    listOf(
                        JsFunction(context) { args ->
                            this.close()
                            args.forEachIndexed { index, it -> if (index != 0) it.close() }
                            cont.resume(args.firstOrNull() ?: context.UNDEFINED)
                            this.context.UNDEFINED
                        },
                        JsFunction(context) { args ->
                            this.close()
                            args.forEachIndexed { index, it -> if (index != 0) it.close() }
                            val exception =
                                args.firstOrNull().use { error ->
                                    JsException(
                                        message = error?.toString() ?: "Unknown error",
                                        cause = null,
                                        data = (error as? JsObject)?.toPlainMap() ?: emptyMap(),
                                    )
                                }
                            cont.resumeWithException(exception)
                            this.context.UNDEFINED
                        },
                    ),
            )
        }
    }
}
