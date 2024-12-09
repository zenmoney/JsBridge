# JsBridge

A Kotlin Multiplatform library that provides JavaScript engine integration for multiple platforms (Android, iOS, macOS, JVM).

## Features

- JavaScript engine integration using V8 (Javet) for JVM/Android platforms
- Native JavaScript engine integration for iOS/macOS platforms
- Kotlin Multiplatform API for JavaScript evaluation and bridging
- Supports Android, iOS, macOS, and JVM targets

## Usage Examples

```kotlin
val context = JsContext()
context.globalObject["sumOf"] = JsFunction(context) { args ->
    var sum = 0.0
    args.forEach {
        val n = it as JsNumber
        sum += n.toNumber().toDouble()
    }
    JsNumber(this.context, sum)
}
val sum = context.evaluateScript("sumOf(1, 2, 3, 4, 5)") as JsNumber
println("sum = $sum") // sum = 15
context.close()
```
