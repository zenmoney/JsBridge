package app.zenmoney.jsbridge

expect sealed interface JsArray : JsObject {
    val size: Int

    operator fun get(index: Int): JsValue
}

expect fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray

inline fun <R, C : MutableCollection<in R>> JsArray.mapTo(
    destination: C,
    transform: (JsValue) -> R,
): C {
    val n = size
    for (i in 0 until n) {
        val value = get(i)
        val transformedValue =
            try {
                transform(value)
            } catch (e: Throwable) {
                try {
                    value.close()
                } catch (_: Throwable) {
                }
                throw e
            }
        if (transformedValue !== value) {
            value.close()
        }
        destination.add(transformedValue)
    }
    return destination
}

fun <T> JsArray.map(transform: (JsValue) -> T): List<T> = mapTo(ArrayList(size), transform)

fun JsArray.toList(): List<JsValue> = map { it }

fun JsArray.forEach(action: (JsValue) -> Unit) {
    val n = size
    for (i in 0 until n) {
        get(i).use(action)
    }
}

fun JsArray.toPlainList(): List<Any?> = map { it.toPlainValue() }
