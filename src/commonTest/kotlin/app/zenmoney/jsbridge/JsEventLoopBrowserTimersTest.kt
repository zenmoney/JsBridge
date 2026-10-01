package app.zenmoney.jsbridge

import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.KEEP
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.OBSERVE
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.SKIP
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsEventLoopBrowserTimersTest {
    @Test
    fun lockedPageShimsDoNotPreventBrowserTimerAttachment() =
        runTest {
            for (policy in listOf(JsEventLoopPolicy(OBSERVE, SKIP), JsEventLoopPolicy(KEEP, SKIP))) {
                for (process in listOf("undefined", "42", "Object.freeze({ nextTick: 43 })")) {
                    JsContext().use { context ->
                        val eventLoop = JsEventLoop(coroutineContext)
                        try {
                            context.evaluateScript(TIMER_TEST_SCRIPT).close()
                            context
                                .evaluateScript(
                                    """
                                    Object.defineProperty(globalThis, 'process', { value: $process, configurable: false, writable: false });
                                    Object.defineProperty(globalThis, 'setImmediate', { value: 44, configurable: false, writable: false });
                                    globalThis.pageProcess = globalThis.process;
                                    """.trimIndent(),
                                ).close()
                            eventLoop.attachTo(
                                context,
                                policies =
                                    JsEventLoopPolicies(
                                        timers = policy,
                                        immediate = JsEventLoopPolicy(KEEP, SKIP),
                                        nextTick = JsEventLoopPolicy(KEEP, SKIP),
                                    ),
                            )
                            assertEquals(44, context.evaluateScript("setImmediate").use { it.int })
                            assertTrue(context.evaluateScript("process === pageProcess").use { it.boolean })
                            kotlinx.coroutines.withTimeout(1_000) { eventLoop.run() }
                        } finally {
                            eventLoop.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun readOnlySurfacePlaceholdersBecomeWorkingEventLoopFunctions() =
        runTest {
            for (accessor in listOf(false, true)) {
                JsContext().use { context ->
                    val eventLoop = JsEventLoop(coroutineContext)
                    try {
                        context
                            .evaluateScript(
                                """
                                for (const name of ['process', 'setImmediate', 'clearImmediate',
                                    'setTimeout', 'setInterval', 'clearTimeout', 'clearInterval']) {
                                    Object.defineProperty(globalThis, name, $accessor
                                        ? { configurable: true, get() { return undefined; } }
                                        : { configurable: true, writable: false, value: undefined });
                                }
                                """.trimIndent(),
                            ).close()
                        eventLoop.attachTo(context)
                        context
                            .evaluateScript(
                                """
                                globalThis.calls = [];
                                clearImmediate(setImmediate(() => calls.push('cancelled')));
                                setImmediate(() => calls.push('immediate'));
                                process.nextTick(() => calls.push('nextTick'));
                                clearInterval(setTimeout(() => calls.push('cancelled'), 1));
                                clearTimeout(setInterval(() => calls.push('cancelled'), 1));
                                setTimeout(() => calls.push('timeout'), 1);
                                """.trimIndent(),
                            ).close()
                        kotlinx.coroutines.withTimeout(1_000) { eventLoop.run() }
                        assertEquals("nextTick,immediate,timeout", context.evaluateScript("calls.join(',')").use { it.string })
                    } finally {
                        eventLoop.cancel()
                    }
                }
            }
        }

    @Test
    fun browserModesDoNotCreateOrAccessNodeGlobals() =
        runTest {
            for (policy in listOf(JsEventLoopPolicy(OBSERVE, SKIP), JsEventLoopPolicy(KEEP, SKIP))) {
                for (setup in listOf("", "installPageGlobals()")) {
                    JsContext().use { context ->
                        val eventLoop = JsEventLoop(coroutineContext)
                        try {
                            context.evaluateScript(TIMER_TEST_SCRIPT).close()
                            context.evaluateScript(setup).close()
                            eventLoop.attachTo(
                                context,
                                policies =
                                    JsEventLoopPolicies(
                                        timers = policy,
                                        immediate = JsEventLoopPolicy(KEEP, SKIP),
                                        nextTick = JsEventLoopPolicy(KEEP, SKIP),
                                    ),
                            )
                            assertTrue(context.evaluateScript("nodeGlobalsWerePreserved()").use { it.boolean })
                            kotlinx.coroutines.withTimeout(1_000) { eventLoop.run() }
                        } finally {
                            eventLoop.cancel()
                        }
                    }
                }
            }
        }

    @Test
    fun preserveModeKeepsBrowserTimerFunctionsAndDescriptors() =
        runTest {
            for (locked in listOf(false, true)) {
                JsContext().use { context ->
                    val eventLoop = JsEventLoop(coroutineContext)
                    try {
                        context.evaluateScript("globalThis.lockTimerGlobals = $locked").close()
                        context.evaluateScript(TIMER_TEST_SCRIPT).close()
                        eventLoop.attachTo(
                            context,
                            policies =
                                JsEventLoopPolicies(
                                    timers = JsEventLoopPolicy(KEEP, SKIP),
                                    immediate = JsEventLoopPolicy(KEEP, SKIP),
                                    nextTick = JsEventLoopPolicy(KEEP, SKIP),
                                ),
                        )
                        assertTrue(context.evaluateScript("descriptorsWerePreserved()").use { it.boolean })
                        context.evaluateScript("clearTimeout(setTimeout(() => {}, 1000))").close()
                        assertTrue(context.evaluateScript("cancelledNativeTimer").use { it.boolean })
                    } finally {
                        eventLoop.cancel()
                    }
                }
            }
        }
}

private val TIMER_TEST_SCRIPT =
    """
    (() => {
        const token = 4242;
        globalThis.cancelledNativeTimer = false;
        function schedule() { return token; }
        function cancel(id) { cancelledNativeTimer = id === token; }
        const names = ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'];
        const timers = [schedule, cancel, schedule, cancel];
        const configurable = globalThis.lockTimerGlobals !== true;
        names.forEach((name, index) => Object.defineProperty(globalThis, name, {
            value: timers[index], writable: true, configurable, enumerable: false
        }));
        globalThis.descriptorsWerePreserved = () => names.every((name, index) => {
            const descriptor = Object.getOwnPropertyDescriptor(globalThis, name);
            return descriptor.value === timers[index] && descriptor.writable &&
                descriptor.configurable === configurable && !descriptor.enumerable && !descriptor.get;
        });
        const nodeNames = ['process', 'setImmediate', 'clearImmediate'];
        nodeNames.forEach(name => delete globalThis[name]);
        let descriptors = nodeNames.map(() => undefined);
        globalThis.installPageGlobals = () => {
            nodeNames.forEach(name => Object.defineProperty(globalThis, name, {
                configurable: true,
                get() { throw new Error('Must not read page global ' + name); },
                set() { throw new Error('Must not write page global ' + name); },
            }));
            descriptors = nodeNames.map(name => Object.getOwnPropertyDescriptor(globalThis, name));
        };
        globalThis.nodeGlobalsWerePreserved = () => nodeNames.every((name, index) => {
            const current = Object.getOwnPropertyDescriptor(globalThis, name);
            const original = descriptors[index];
            return original ? current && current.get === original.get && current.set === original.set &&
                current.configurable === original.configurable && current.enumerable === original.enumerable : !current;
        });
    })()
    """.trimIndent()
