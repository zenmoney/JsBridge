# JsBridge

A Kotlin Multiplatform library that provides JavaScript engine integration for multiple platforms (Android, iOS, macOS, JVM).

## Features

- JavaScript engine integration using V8 (Javet) for JVM/Android platforms
- Native JavaScript engine integration for iOS/macOS platforms
- Kotlin Multiplatform API for JavaScript evaluation and bridging
- Supports Android, iOS, macOS, and JVM targets

## Usage Examples

```kotlin
JsContext().use { context ->
    val sum = jsScope(context) {
        context.globalThis["sumOf"] = JsFunction { args, _ ->
            JsNumber(args.sumOf { (it as JsNumber).toNumber().toDouble() })
        }
        (eval("sumOf(1, 2, 3, 4, 5)") as JsNumber).toNumber()
    }
    println("sum = $sum") // sum = 15.0
}
```
