package app.zenmoney.jsbridge

/**
 * A JavaScript Number. Primitive BigInt values are exposed as [JsNumber] with Double precision
 * and are passed back to JavaScript as Number. Boxed BigInt values (`Object(1n)`) remain [JsObject]s.
 *
 * On JVM, Javet 5.0.11 can narrow a BigInt to a signed Long before the bridge receives it,
 * so some values outside the Long range may have an incorrect sign or magnitude.
 * Explicitly evaluating `Number(value)` in JavaScript avoids that upstream limitation.
 */
expect sealed interface JsNumber : JsValue {
    fun toNumber(): Number
}

expect sealed interface JsNumberObject :
    JsObject,
    JsNumber

internal fun JsNumber(
    context: JsContext,
    value: Number,
): JsNumber = context.createNumber(value)

internal fun JsNumberObject(
    context: JsContext,
    value: Number,
): JsNumberObject = context.createNumberObject(value)

context(scope: JsScope)
fun JsNumber(value: Number): JsNumber = JsNumber(scope.context, value).autoClose()

context(scope: JsScope)
fun JsNumberObject(value: Number): JsNumberObject = JsNumberObject(scope.context, value).autoClose()
