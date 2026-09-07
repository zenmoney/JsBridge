package app.zenmoney.jsbridge

import kotlin.test.Test
import kotlin.test.assertEquals

class JsWebViewRuntimeTest {
    @Test
    fun callbackRetainsThisAndRepeatedArgumentsUntilBothOwnersRelease() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                for (const completion of ["+", "-"]) {
                    for (const nativeFirst of [true, false]) {
                        globalThis.__value = {};
                        __callback.call(__value, __value, null, __value).catch(() => {});
                        const callback = messages.pop();
                        const encoded = callback[3];
                        const handle = encoded[1] % 4294967296;
                        const isRetained = () => {
                            bridge.dispatch(["s", 0, "__probe", encoded], 3);
                            return __probe === __value;
                        };
                        const release = () => bridge.dispatch(["r*", [handle, -1]]);
                        const complete = () => bridge.dispatch([completion, callback[1], encoded]);
                        check(isRetained(), "callback arguments must be retained");
                        if (nativeFirst) release(); else complete();
                        check(isRetained(), "the remaining owner must keep this and repeated arguments alive");
                        if (nativeFirst) complete(); else release();
                        check(!isRetained(), "the last release must remove the handle");
                    }
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun overlappingCallbacksKeepIndependentRetainsWhenNativeReacquiresHandle() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                for (const reacquire of [false, true]) {
                    globalThis.__value = {};
                    __callback(__value).catch(() => {});
                    const first = messages.pop();
                    __callback(__value).catch(() => {});
                    const second = messages.pop();
                    const encoded = first[4][0];
                    const handle = encoded[1] % 4294967296;
                    const isRetained = () => {
                        bridge.dispatch(["s", 0, "__probe", encoded], 3);
                        return __probe === __value;
                    };
                    bridge.dispatch(["r*", [handle, -1]]);
                    if (reacquire) {
                        bridge.dispatch(["e", "__value"], 4);
                        check(messages.pop()[2][1] === encoded[1], "re-export must preserve the handle");
                        bridge.dispatch(["r*", [handle, 1]]);
                    }
                    bridge.dispatch(["-", first[1], "failure"]);
                    check(isRetained(), "the second callback must retain its arguments");
                    bridge.dispatch(["+", second[1], encoded]);
                    check(isRetained() === reacquire, "native ownership must be independent of callback retains");
                    if (reacquire) bridge.dispatch(["r*", [handle, -1]]);
                    check(!isRetained(), "all owners have released the object");
                }
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun nativeRetainSurvivesAnOlderNativeReleaseInFlight() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                globalThis.__value = {};
                bridge.dispatch(["e", "__value"], 3);
                const encoded = messages.pop()[2];
                const handle = encoded[1] % 4294967296;
                __callback(__value).catch(() => {});
                const callback = messages.pop();
                // The previous wrapper closes before native receives the already posted callback.
                bridge.dispatch(["r*", [handle, -1]]);
                bridge.dispatch(["r*", [handle, 1]]);
                // Native escapes the new argument wrapper and completes its callback.
                bridge.dispatch(["+", callback[1], ["u"]]);
                bridge.dispatch(["s", 0, "__probe", encoded], 4);
                check(__probe === __value, "escaped native wrapper must survive callback completion");
                bridge.dispatch(["r*", [handle, -1]]);
                bridge.dispatch(["s", 0, "__probe", encoded], 5);
                check(__probe === undefined, "closing the escaped wrapper must remove the handle");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun reexportDoesNotAddReferencesAndGlobalHandleNeedsNoRetain() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                globalThis.__value = {};
                bridge.dispatch(["e", "__value"], 1);
                const encoded = messages.pop()[2];
                const handle = encoded[1] % 4294967296;
                bridge.dispatch(["e", "__value"], 2);
                check(messages.pop()[2][1] === encoded[1], "aliases must share a handle");
                bridge.dispatch(["r*", [handle, -1]]);
                bridge.dispatch(["s", 0, "__probe", encoded], 3);
                check(__probe === undefined, "re-export must not add another implicit retain");
                bridge.dispatch(["r*", [0, 1]]);
                bridge.dispatch(["r*", [0, -1]]);
                bridge.dispatch(["s", 0, "__probe", ["h", 0]], 4);
                check(__probe === globalThis, "global handle must remain available");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun zeroRefCountBatchPreservesOwnedValuesAndReleasesUnownedTransitValues() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                globalThis.__value = {};
                bridge.dispatch(["e", "__value"], 1);
                const encoded = messages.pop()[2];
                const handle = encoded[1] % 4294967296;
                bridge.dispatch(["r*", [handle, 0]]);
                bridge.dispatch(["s", 0, "__probe", encoded], 2);
                check(__probe === __value, "cancelled release/retain must preserve native ownership");

                bridge.dispatch(["r*", [handle, -1]]);
                bridge.dispatch(["e", "__value"], 3);
                check(messages.pop()[2][1] === encoded[1], "re-export must preserve the handle");
                bridge.dispatch(["s", 0, "__probe", encoded], 4);
                check(__probe === __value, "re-export must hold the value in transit before native responds");
                // Native reacquires and immediately closes the wrapper before sending another request.
                bridge.dispatch(["r*", [handle, 0]]);
                bridge.dispatch(["s", 0, "__probe", encoded], 5);
                check(__probe === undefined, "cancelled retain/release must clean up the unowned transit value");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun evalRestoresErrorStateAfterNestedCallsAndSyntaxErrors() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                const evaluate = (script, id) => {
                    bridge.dispatch(["e", script], id);
                    return messages.pop();
                };
                const assertNoErrorSlot = () => check(
                    !Object.prototype.hasOwnProperty.call(globalThis, "__appZenmoneyEvalError"),
                    "eval must remove its temporary error slot"
                );
                check(evaluate("throw null", 1)[2] === null, "null must remain a thrown value");
                assertNoErrorSlot();
                check(evaluate("throw undefined", 2)[0] === "e", "undefined must remain an error");
                assertNoErrorSlot();
                check(evaluate("let = ;", 3)[0] === "e", "syntax errors must be reported");
                assertNoErrorSlot();
                check(evaluate("var __globalEvalValue = 41; __globalEvalValue + 1", 4)[2] === 42, "global eval semantics");
                check(__globalEvalValue === 41, "var must remain global");

                // The inner request posts its error before the outer request completes successfully.
                const nested = "globalThis.$JS_WEB_VIEW_BRIDGE_OBJECT.dispatch(['e', 'throw 7'], 6); 42";
                check(evaluate(nested, 5)[2] === 42, "inner failure must not fail the outer eval");
                check(messages.pop()[2] === 7, "inner failure must retain its own value");
                assertNoErrorSlot();

                // Re-enter after the outer evaluation has stored its thrown value.
                const originalEval = globalThis.eval;
                let reenter = true;
                globalThis.eval = function (source) {
                    const value = (0, originalEval)(source);
                    if (reenter) {
                        reenter = false;
                        bridge.dispatch(["e", "throw 'inner'"], 8);
                    }
                    return value;
                };
                try {
                    check(evaluate("throw 'outer'", 7)[2] === "outer", "nested eval must restore the outer error");
                    check(messages.pop()[2] === "inner", "nested error value");
                } finally {
                    globalThis.eval = originalEval;
                }
                assertNoErrorSlot();
                globalThis.__appZenmoneyEvalError = undefined;
                check(evaluate("42", 9)[2] === 42, "stale error state must not affect success");
                check(Object.prototype.hasOwnProperty.call(globalThis, "__appZenmoneyEvalError"), "preserve existing slot");
                return "ok";
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun failedCallbackEncodingAndPostingRollBackPendingCallbacksAndHandles() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                messages.length = 0;
                const { pendingJsCallbacks, objectByHandle, refCountByHandle, unpublishedHandles } = __diagnostics;
                const baselineObjects = objectByHandle.size;
                const baselineRefs = refCountByHandle.size;
                const checkClean = () => {
                    check(pendingJsCallbacks.size === 0, "failed callback must not remain pending");
                    check(objectByHandle.size === baselineObjects, "failed transfer must not retain objects");
                    check(refCountByHandle.size === baselineRefs, "failed transfer must not retain counts");
                    check(unpublishedHandles.size === 0, "failed transfer must clear handles pending send");
                };
                for (let i = 0; i < 1000; i++) {
                    __callback(Symbol("unsupported")).catch(() => {});
                    __callback.call({}, {}, {}, Symbol("unsupported")).catch(() => {});
                }
                check(messages.length === 0, "encoding failure must not post a callback");
                checkClean();

                const value = {};
                __callback(value, Symbol("unsupported")).catch(() => {});
                __callback(value).catch(() => {});
                const callback = messages.pop();
                const handle = callback[4][0][1] % 4294967296;
                check(refCountByHandle.get(handle) === 2, "retry needs the implicit native and temporary callback references");
                bridge.dispatch(["+", callback[1], ["u"]]);
                bridge.dispatch(["r*", [handle, -1]]);
                checkClean();

                // Previously exported handles keep their identity after a failed re-export.
                globalThis.__value = value;
                bridge.dispatch(["e", "__value"], 3);
                const reexported = messages.pop()[2][1] % 4294967296;
                check(reexported === handle, "published handle identity must survive native release");
                bridge.dispatch(["r*", [handle, 0]]);
                __callback(value, Symbol("unsupported")).catch(() => {});
                checkClean();

                const native = globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE;
                const originalPost = native.postMessage;
                native.postMessage = () => { throw new Error("post failed"); };
                try {
                    __callback.call({}, {}, {}).catch(() => {});
                    checkClean();
                    try { bridge.dispatch(["e", "({})"], 4); } catch (_) {}
                    checkClean();
                } finally {
                    native.postMessage = originalPost;
                }
                return "ok";
                """.trimIndent(),
                inspectHandles = true,
            ),
        )
    }

    @Test
    fun failedOuterEncodingPreservesHandlesPublishedByReentrantCallback() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                messages.length = 0;
                let entered = false;
                const value = { get then() {
                    if (!entered) {
                        entered = true;
                        __callback(value).catch(() => {});
                    }
                }};
                __callback(value, Symbol("unsupported")).catch(() => {});
                check(messages.length === 1, "only the inner callback should be posted");
                const callback = messages.pop();
                const handle = callback[4][0][1] % 4294967296;
                const state = __diagnostics;
                check(state.pendingJsCallbacks.size === 1, "only the inner callback should remain pending");
                check(state.unpublishedHandles.size === 0, "inner post must mark the shared handle as sent");
                check(state.refCountByHandle.get(handle) === 2, "outer failure must preserve inner callback and native refs");
                bridge.dispatch(["r*", [handle, -1]]);
                // A malformed completion rejects the callback and still releases its temporary reference.
                bridge.dispatch(["+", callback[1], ["h", "invalid"]]);
                check(state.pendingJsCallbacks.size === 0, "malformed completion must remove the callback");
                check(!state.objectByHandle.has(handle), "malformed completion must release the final reference");
                check(!state.refCountByHandle.has(handle), "malformed completion must remove the count");
                return "ok";
                """.trimIndent(),
                inspectHandles = true,
            ),
        )
    }

    @Test
    fun failedOuterEncodingPreservesUncountedReexportPublishedByNestedRequest() {
        assertEquals(
            "ok",
            evaluateRuntime(
                """
                const bridge = $JS_WEB_VIEW_BRIDGE_OBJECT;
                bridge.dispatch(["f", 1], 1);
                bridge.dispatch(["s", 0, "__callback", messages.pop()[2]], 2);
                let reenter = false;
                globalThis.__value = { get then() {
                    if (reenter) {
                        reenter = false;
                        bridge.dispatch(["e", "__value"], 10);
                    }
                }};
                bridge.dispatch(["e", "__value"], 3);
                const handle = messages.pop()[2][1] % 4294967296;
                bridge.dispatch(["r*", [handle, -1]]);
                messages.length = 0;
                reenter = true;
                __callback(__value, Symbol("unsupported")).catch(() => {});
                const state = __diagnostics;
                check(messages.length === 1 && messages[0][1] === 10, "nested request must send the re-exported handle");
                check(state.pendingJsCallbacks.size === 0, "failed outer callback must not remain pending");
                check(state.unpublishedHandles.size === 0, "nested post must mark the re-exported handle as sent");
                check(!state.refCountByHandle.has(handle), "native retain has not arrived yet");
                check(state.objectByHandle.get(handle) === __value, "outer rollback must preserve the value in transit");
                bridge.dispatch(["r*", [handle, 1]]);
                bridge.dispatch(["s", 0, "__probe", messages[0][2]], 4);
                check(__probe === __value, "nested native owner must be able to use the reacquired handle");
                bridge.dispatch(["r*", [handle, -1]]);
                check(!state.objectByHandle.has(handle), "last native release must remove the handle");
                return "ok";
                """.trimIndent(),
                inspectHandles = true,
            ),
        )
    }

    private fun evaluateRuntime(
        body: String,
        inspectHandles: Boolean = false,
    ): String =
        JsContext().use { context ->
            val runtime =
                if (inspectHandles) {
                    jsWebViewRuntimeScript.replace(
                        "const unpublishedHandles = new Set();",
                        "const unpublishedHandles = new Set(); " +
                            "globalThis.__diagnostics = { pendingJsCallbacks, objectByHandle, refCountByHandle, unpublishedHandles };",
                    )
                } else {
                    jsWebViewRuntimeScript
                }
            (
                context.evaluateScript(
                    """
                    (() => {
                        globalThis.window = globalThis;
                        const messages = [];
                        globalThis.$JS_WEB_VIEW_ANDROID_INTERFACE = {
                            postMessage(message) { messages.push(JSON.parse(message)); }
                        };
                        $runtime
                        const check = (condition, message) => { if (!condition) throw new Error(message); };
                        $body
                    })()
                    """.trimIndent(),
                ) as JsString
            ).use { it.toString() }
        }
}
