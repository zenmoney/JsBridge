# JsBridge

A Kotlin Multiplatform library that provides JavaScript engine integration for multiple platforms (Android, iOS, macOS, JVM).

## Features

- JavaScript engine integration using V8 (Javet on JVM, J2V8 on Android)
- Native JavaScript engine integration for iOS/macOS platforms
- Kotlin Multiplatform API for JavaScript evaluation and bridging
- Supports Android, iOS, macOS, and JVM targets

## Usage Examples

```kotlin
JsContext().use { context ->
    val sum = jsScoped(context) {
        context.globalThis["sumOf"] = JsFunction { args ->
            JsNumber(args.sumOf { it.double })
        }
        eval("sumOf(1, 2, 3, 4, 5)").double
    }
    println("sum = $sum") // sum = 15.0
}
```
