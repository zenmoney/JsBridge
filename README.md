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

## WebView page globals

The WebView runtime uses a non-enumerable symbol property instead of a string-named
`window` property. Copying or deleting string properties such as `__appZenmoneyJsBridge`
does not affect its session. The runtime captures the native message channel during
initialization, so hiding its global or replacing `postMessage` afterwards does not
interrupt existing callbacks.

`JsEventLoop.attachTo` accepts a `JsEventLoopPolicies` object with three fields:
`timers` (`setTimeout`, `setInterval` and their clear functions), `immediate`
(`setImmediate` and `clearImmediate`), and `nextTick` (`process.nextTick`).
Each field is a `JsEventLoopPolicy` with two required fields:

| Field | Action | Behavior |
| --- | --- | --- |
| `ifPresent` | `KEEP` | Leave the existing functions unchanged, without awaiting their work. |
| `ifPresent` | `OBSERVE` | Wrap existing functions and await callbacks through their original scheduler. |
| `ifPresent` | `REPLACE` | Replace the group with functions scheduled by this event loop. |
| `ifMissing` | `SKIP` | Leave the entire group untouched. |
| `ifMissing` | `INSTALL` | Install the entire group with functions scheduled by this event loop. |
| `ifMissing` | `FAIL` | Fail attachment with a `JsException` identifying the missing group and function. |

The action enums are nested in `JsEventLoopPolicy`: `ExistingApiAction` and
`MissingApiAction`. A group is present only when all its members are functions.
Missing or non-function members select `ifMissing` for the whole group;
`INSTALL` also replaces any remaining members to keep scheduling and cancellation
consistent. Installing `nextTick` creates `process` when it is null or absent;
an existing object retains its other properties.

`KEEP + SKIP` leaves properties untouched without reading getters.
`REPLACE + INSTALL` installs functions without reading the originals. Other
combinations check availability and may invoke getters. Getter errors and failures
to observe or install functions fail attachment and roll back its changes; they
are not treated as missing APIs. Both replacement and installation support
configurable read-only placeholders.

Each field defaults to `REPLACE + INSTALL`, so `attachTo(context)` installs all
three groups. For a page context that should only observe browser timers:

```kotlin
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.KEEP
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.OBSERVE
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.SKIP

val untouched = JsEventLoopPolicy(ifPresent = KEEP, ifMissing = SKIP)

eventLoop.attachTo(
    context,
    policies = JsEventLoopPolicies(
        timers = JsEventLoopPolicy(
            ifPresent = OBSERVE,
            ifMissing = SKIP,
        ),
        immediate = untouched,
        nextTick = untouched,
    ),
)
```

This leaves `process`, `setImmediate` and `clearImmediate` untouched, including when
absent. Omitted fields keep their own `REPLACE + INSTALL` defaults. To observe all
available groups, pass an `OBSERVE + SKIP` policy to all three fields. Use
`OBSERVE + INSTALL` to also provide missing APIs, or `OBSERVE + FAIL` to require
existing implementations. `KEEP + INSTALL` provides only missing groups; work
through existing groups is not awaited. A `JsEventLoopPolicies` object can be reused
across contexts or adjusted with `copy(timers = ...)`, `copy(immediate = ...)`, or
`copy(nextTick = ...)`.

Observation retains the original scheduler's timing, handles, execution and error
handling. Cancellation restores the original descriptors where the page has not
replaced or locked the wrappers; scheduled work continues.
Reattaching the same context keeps its original configuration. Observation covers
only calls through the installed wrappers: earlier registrations, saved original
functions and string timer handlers are excluded, and returned callback promises
are not awaited. Observed intervals keep `run()` pending until cleared through a
wrapped clear function. Queued event-loop callbacks continue to run while the loop
awaits native Promises.

This is resilience within the page's JavaScript realm, not isolation from arbitrary
page changes. Removing the bridge's symbol property, modifying JavaScript built-ins,
or hiding the native channel before initialization can still prevent execution.
A detached context must be replaced; native WebView navigation and cookie APIs have
an independent lifetime.

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
