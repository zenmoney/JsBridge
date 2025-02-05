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
        destination.add(transform(get(i)))
    }
    return destination
}

fun <T> JsArray.map(transform: (JsValue) -> T): List<T> = mapTo(ArrayList(size), transform)

fun JsArray.toList(): List<JsValue> = map { it }

fun JsArray.forEach(action: (JsValue) -> Unit) {
    val n = size
    for (i in 0 until n) {
        action(get(i))
    }
}

fun JsArray.toPlainList(): List<Any?> =
    map {
        val value = it.toPlainValue()
        it.close()
        value
    }
