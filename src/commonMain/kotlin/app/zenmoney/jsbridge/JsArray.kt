package app.zenmoney.jsbridge

expect sealed interface JsArray : JsObject {
    val size: Int
}

internal expect fun JsArray.getValue(index: Int): JsValue

internal fun JsArray(
    context: JsContext,
    value: Iterable<JsValue>,
): JsArray = context.createArray(value)

fun JsScope.JsArray(value: Iterable<JsValue>): JsArray = JsArray(context, value).autoClose()

inline fun <R, C : MutableCollection<in R>> JsArray.mapTo(
    destination: C,
    transform: JsScope.(JsValue) -> R,
): C =
    jsScoped(context) {
        val n = size
        for (i in 0 until n) {
            destination.add(transform(this, get(i)))
        }
        return destination
    }

inline fun <T> JsArray.map(transform: JsScope.(JsValue) -> T): List<T> = mapTo(ArrayList(size), transform)

inline fun JsArray.forEach(action: JsScope.(JsValue) -> Unit) =
    jsScoped(context) {
        val n = size
        for (i in 0 until n) {
            action(get(i))
        }
    }

fun JsArray.toPlainList(): List<Any?> = map { it.toPlainValue() }
