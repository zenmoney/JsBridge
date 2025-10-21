package app.zenmoney.jsbridge

expect sealed interface JsArray : JsObject {
    val size: Int
}

internal expect fun JsArray.getValue(index: Int): JsValue

internal expect fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray

fun JsScope.JsArray(value: Iterable<JsValue>): JsArray = JsArray(context, value).autoClose()

inline fun <R, C : MutableCollection<in R>> JsArray.mapTo(
    destination: C,
    transform: JsScope.(JsValue) -> R,
): C =
    jsScope(context) {
        val n = size
        for (i in 0 until n) {
            destination.add(transform(this, get(i)))
        }
        return destination
    }

fun <T> JsArray.map(transform: JsScope.(JsValue) -> T): List<T> = mapTo(ArrayList(size), transform)

fun JsArray.toList(): List<JsValue> = map { it.escape() }

fun JsArray.forEach(action: JsScope.(JsValue) -> Unit) =
    jsScope(context) {
        val n = size
        for (i in 0 until n) {
            action(get(i))
        }
    }

fun JsArray.toPlainList(): List<Any?> = map { it.toPlainValue() }
