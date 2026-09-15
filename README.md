# JsBridge

A Kotlin Multiplatform library that provides JavaScript engine integration for multiple platforms (Android, iOS, macOS, JVM).

## Features

- JavaScript engine integration using V8 (Javet on JVM, J2V8 on Android)
- Native JavaScript engine integration for iOS/macOS platforms
- Kotlin Multiplatform API for JavaScript evaluation and bridging
- Supports Android, iOS, macOS, and JVM targets

## WebView and Content Security Policy

`JsWebViewContext` executes scripts through the native WebView API, including on pages with `script-src 'none'` or without `unsafe-eval`. It preserves access to the page's globals and DOM, object identity, functions, promises and JavaScript exceptions. The page's CSP still applies to explicit `eval()` calls made by page or plugin code.

The bridge parses scripts with a bundled Acorn parser and captures their completion values before native execution. See [the evaluation implementation notes](docs/webview-evaluation.md) for the protocol and parser update procedure.

## BigInt values

Primitive JavaScript BigInt values are exposed as `JsNumber` with Double precision and are passed back to JavaScript as `number`. Boxed BigInt values (`Object(1n)`) remain `JsObject` instances and retain their JavaScript object identity.

On JVM, Javet 5.0.11 can truncate some BigInt values outside the signed 64-bit range before the bridge receives them; for example, `9223372036854775808n` can acquire the wrong sign. Convert such values explicitly with `Number(value)` in JavaScript to avoid this limitation. The bridge does not add JavaScript wrappers to ordinary operations to work around it.

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
