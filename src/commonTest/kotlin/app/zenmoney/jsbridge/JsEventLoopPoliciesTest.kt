package app.zenmoney.jsbridge

import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.KEEP
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.OBSERVE
import app.zenmoney.jsbridge.JsEventLoopPolicy.ExistingApiAction.REPLACE
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.FAIL
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.INSTALL
import app.zenmoney.jsbridge.JsEventLoopPolicy.MissingApiAction.SKIP
import app.zenmoney.jsbridge.TestApiGroup.IMMEDIATE
import app.zenmoney.jsbridge.TestApiGroup.NEXT_TICK
import app.zenmoney.jsbridge.TestApiGroup.TIMERS
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsEventLoopPoliciesTest {
    private val keepPolicy = JsEventLoopPolicy(KEEP, SKIP)
    private val observePolicy = JsEventLoopPolicy(OBSERVE, SKIP)
    private val replacePolicy = JsEventLoopPolicy(REPLACE, INSTALL)
    private val keepPolicies = JsEventLoopPolicies(timers = keepPolicy, immediate = keepPolicy, nextTick = keepPolicy)
    private val observePolicies = JsEventLoopPolicies(timers = observePolicy, immediate = observePolicy, nextTick = observePolicy)

    @Test
    fun policiesComposeIndependentlyForAllApis() =
        runTest {
            for (ifMissing in MissingApiAction.entries) {
                for (timers in ExistingApiAction.entries) {
                    for (immediate in ExistingApiAction.entries) {
                        for (nextTick in ExistingApiAction.entries) {
                            withHost { context, loop ->
                                val actions = listOf(timers, immediate, nextTick)
                                val policies = actions.map { JsEventLoopPolicy(it, ifMissing) }
                                loop.attachTo(
                                    context,
                                    policies = JsEventLoopPolicies(timers = policies[0], immediate = policies[1], nextTick = policies[2]),
                                )
                                for ((index, policy) in policies.withIndex()) {
                                    assertEquals(policy.ifPresent == KEEP, context.bool("__host.isOriginal($index)"))
                                }
                                context.eval(
                                    """
                                    globalThis.calls = [];
                                    setTimeout(() => calls.push('timer'), 1);
                                    clearTimeout(setInterval(() => calls.push('cancelled'), 1));
                                    setImmediate(() => calls.push('immediate'));
                                    clearImmediate(setImmediate(() => calls.push('cancelled')));
                                    process.nextTick(() => calls.push('nextTick'));
                                    """.trimIndent(),
                                )
                                val running = async { loop.run() }
                                testScheduler.advanceTimeBy(1)
                                testScheduler.runCurrent()
                                assertEquals(actions.count { it == REPLACE }, context.int("calls.length"), "Policies: $policies")
                                assertEquals(OBSERVE !in actions, running.isCompleted)
                                for ((index, policy) in policies.withIndex()) {
                                    if (policy.ifPresent != REPLACE) context.eval("__host.fire($index)")
                                    testScheduler.runCurrent()
                                    assertEquals(OBSERVE !in actions.drop(index + 1), running.isCompleted)
                                }
                                withTimeout(1_000) { running.await() }
                                assertEquals("immediate,nextTick,timer", context.string("calls.sort().join(',')"))
                                loop.cancel()
                                withTimeout(1_000) { loop.run() }
                                for ((index, policy) in policies.withIndex()) {
                                    assertEquals(policy.ifPresent != REPLACE, context.bool("__host.isOriginal($index)"))
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun unspecifiedApisDefaultToReplaceAndReattachmentKeepsTheConfiguration() =
        runTest {
            withHost { context, loop ->
                loop.attachTo(context, policies = JsEventLoopPolicies(timers = keepPolicy))
                loop.attachTo(context, policies = observePolicies)
                assertTrue(context.bool("__host.isOriginal(0)"))
                assertFalse(context.bool("__host.isOriginal(1)"))
                assertFalse(context.bool("__host.isOriginal(2)"))
                context.eval(
                    "globalThis.calls = []; process.nextTick(() => calls.push('tick')); setImmediate(() => calls.push('immediate'))",
                )
                withTimeout(1_000) { loop.run() }
                assertEquals("tick,immediate", context.string("calls.join(',')"))
            }
        }

    @Test
    fun keepAndSkipDoesNotReadAnyApiGetters() =
        runTest {
            withHost { context, loop ->
                context.eval(
                    """
                    for (const name of ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval',
                        'setImmediate', 'clearImmediate', 'process']) {
                        Object.defineProperty(globalThis, name, {
                            configurable: false,
                            get() { throw new Error('Must not read ' + name); },
                        });
                    }
                    """.trimIndent(),
                )
                loop.attachTo(context, policies = keepPolicies)
                withTimeout(1_000) { loop.run() }
            }
        }

    @Test
    fun immediateObservationPreservesOpaqueHandlesReceiversArgumentsAndErrors() =
        runTest {
            withHost { context, loop ->
                loop.attachTo(context, policies = keepPolicies.copy(immediate = observePolicy))
                context.eval(
                    """
                    globalThis.expectedError = new Error('callback failed');
                    globalThis.argument = {};
                    globalThis.cancelled = setImmediate(() => { throw new Error('cancelled callback'); });
                    globalThis.handle = setImmediate.call(argument, function (first, second) {
                        globalThis.callbackThis = this === handle;
                        globalThis.callbackArguments = first === argument && second === 42;
                        throw expectedError;
                    }, argument, 42);
                    globalThis.receiverPreserved = __host.lastReceiver === argument;
                    globalThis.returnPreserved = clearImmediate.call(argument, cancelled) === __host.clearResult;
                    globalThis.clearReceiverPreserved = __host.lastReceiver === argument;
                    """.trimIndent(),
                )
                val running = async { loop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                assertTrue(context.bool("receiverPreserved && returnPreserved && clearReceiverPreserved"))
                context.eval(
                    """
                    globalThis.errorPreserved = false;
                    try { __host.fire(1); } catch (error) { errorPreserved = error === expectedError; }
                    """.trimIndent(),
                )
                withTimeout(1_000) { running.await() }
                assertTrue(context.bool("callbackThis && callbackArguments && errorPreserved"))
                assertEquals(0, context.int("__host.pendingCount(1)"))
            }
        }

    @Test
    fun nextTickObservationTracksCallbacksWithNoHandleAndNestedWork() =
        runTest {
            withHost { context, loop ->
                loop.attachTo(context, policies = observePolicies)
                context.eval(
                    """
                    globalThis.calls = [];
                    globalThis.argument = {};
                    globalThis.firstReturn = process.nextTick.call(argument, function (value) {
                        if (this !== __host.tickReceiver || value !== argument) throw new Error('Callback context changed');
                        calls.push('first');
                        setImmediate(() => calls.push('nested'));
                    }, argument);
                    globalThis.receiverPreserved = __host.lastReceiver === argument;
                    globalThis.secondReturn = process.nextTick(() => { calls.push('second'); return new Promise(() => {}); });
                    """.trimIndent(),
                )
                assertTrue(context.bool("receiverPreserved && firstReturn === undefined && secondReturn === undefined"))
                val running = async { loop.run() }
                testScheduler.runCurrent()
                context.eval("__host.fire(2)")
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                context.eval("__host.fire(2)")
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                context.eval("__host.fire(1)")
                withTimeout(1_000) { running.await() }
                assertEquals("first,second,nested", context.string("calls.join(',')"))
            }
        }

    @Test
    fun cancelledObservationRestoresNodeApisAndCapturedWrappersStillDelegate() =
        runTest {
            withHost { context, loop ->
                loop.attachTo(
                    context,
                    policies = JsEventLoopPolicies(timers = keepPolicy, immediate = observePolicy, nextTick = observePolicy),
                )
                context.eval(
                    """
                    globalThis.calls = 0;
                    globalThis.capturedImmediate = setImmediate;
                    globalThis.capturedNextTick = process.nextTick;
                    capturedImmediate(() => calls++);
                    capturedNextTick(() => calls++);
                    """.trimIndent(),
                )
                val running = async { loop.run() }
                testScheduler.runCurrent()
                assertFalse(running.isCompleted)
                loop.cancel()
                withTimeout(1_000) { running.await() }
                assertTrue(context.bool("__host.isOriginal(1) && __host.isOriginal(2)"))
                context.eval(
                    """
                    capturedImmediate(() => calls++);
                    capturedNextTick(() => calls++);
                    __host.fire(1); __host.fire(1); __host.fire(2); __host.fire(2);
                    """.trimIndent(),
                )
                assertEquals(4, context.int("calls"))
            }
        }

    @Test
    fun observeSkipsMissingApis() =
        runTest {
            withHost { context, loop ->
                context.eval(
                    """
                    globalThis.apiNames = ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval',
                        'setImmediate', 'clearImmediate', 'process'];
                    apiNames.forEach(name => { delete globalThis[name]; });
                    """.trimIndent(),
                )
                loop.attachTo(context, policies = observePolicies)
                assertEquals(loop, context.eventLoop)
                withTimeout(1_000) { loop.run() }
                assertTrue(context.bool("apiNames.every(name => !(name in globalThis))"))
                loop.cancel()
                withTimeout(1_000) { loop.run() }
                assertTrue(context.bool("apiNames.every(name => !(name in globalThis))"))
            }
        }

    @Test
    fun observeSkipsIncompleteGroupsWithoutChangingDescriptors() =
        runTest {
            val groups =
                mapOf(
                    TIMERS to listOf("setTimeout", "setInterval", "clearTimeout", "clearInterval"),
                    IMMEDIATE to listOf("setImmediate", "clearImmediate"),
                    NEXT_TICK to listOf("nextTick"),
                )
            for ((api, names) in groups) {
                for (missingName in names) {
                    for (placeholder in listOf(false, true)) {
                        withHost { context, loop ->
                            context.eval(
                                """
                                (() => {
                                    const target = ${if (api == NEXT_TICK) "process" else "globalThis"};
                                    const names = [${names.joinToString { "'$it'" }}];
                                    if ($placeholder) {
                                        Object.defineProperty(target, '$missingName', {
                                            value: 42, writable: false, configurable: false,
                                        });
                                    } else {
                                        delete target['$missingName'];
                                    }
                                    const originals = names.map(name => Object.getOwnPropertyDescriptor(target, name));
                                    globalThis.groupIsUnchanged = () => names.every((name, index) => {
                                        const original = originals[index];
                                        const current = Object.getOwnPropertyDescriptor(target, name);
                                        if (!original || !current) return original === current;
                                        return ['value', 'writable', 'configurable', 'enumerable', 'get', 'set']
                                            .every(key => original[key] === current[key]);
                                    });
                                })();
                                """.trimIndent(),
                            )
                            loop.attachTo(context, policies = policiesFor(api, observePolicy))
                            assertTrue(context.bool("groupIsUnchanged()"), "$api without $missingName")
                            withTimeout(1_000) { loop.run() }
                            loop.cancel()
                            withTimeout(1_000) { loop.run() }
                            assertTrue(context.bool("groupIsUnchanged()"), "$api without $missingName after cancellation")
                        }
                    }
                }
            }
        }

    @Test
    fun defaultObserveAwaitsTimersWithoutNodeApis() =
        runTest {
            val processSetups = listOf("delete globalThis.process", "process = undefined", "process = null", "process = 42", "process = {}")
            for (processSetup in processSetups) {
                withHost { context, loop ->
                    context.eval(
                        """
                        delete globalThis.setImmediate;
                        delete globalThis.clearImmediate;
                        $processSetup;
                        globalThis.originalProcess = globalThis.process;
                        globalThis.hadProcess = Object.prototype.hasOwnProperty.call(globalThis, 'process');
                        """.trimIndent(),
                    )
                    loop.attachTo(context, policies = observePolicies)
                    context.eval("globalThis.called = false; setTimeout(() => { called = true; }, 1)")
                    val running = async { loop.run() }
                    testScheduler.runCurrent()
                    assertFalse(running.isCompleted)
                    context.eval("__host.fire(0)")
                    withTimeout(1_000) { running.await() }
                    assertTrue(context.bool("called"))
                    assertTrue(
                        context.bool(
                            """
                            !('setImmediate' in globalThis) && !('clearImmediate' in globalThis) &&
                                globalThis.process === originalProcess &&
                                Object.prototype.hasOwnProperty.call(globalThis, 'process') === hadProcess &&
                                (typeof process !== 'object' || process === null || !('nextTick' in process))
                            """.trimIndent(),
                        ),
                    )
                }
            }
        }

    @Test
    fun missingActionsApplyToAbsentIncompleteAndNonFunctionGroups() =
        runTest {
            val setups =
                listOf(
                    """
                    for (const name of ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval',
                        'setImmediate', 'clearImmediate', 'process']) delete globalThis[name];
                    """.trimIndent(),
                    "delete globalThis.clearInterval; delete globalThis.clearImmediate; delete process.nextTick;",
                    "globalThis.clearInterval = 42; globalThis.clearImmediate = null; process.nextTick = undefined;",
                )
            for (setup in setups) {
                for (ifPresent in ExistingApiAction.entries) {
                    for (ifMissing in MissingApiAction.entries) {
                        withHost { context, loop ->
                            context.eval(setup)
                            context.eval(
                                """
                                globalThis.snapshot = () => [globalThis.setTimeout, globalThis.setInterval,
                                    globalThis.clearTimeout, globalThis.clearInterval, globalThis.setImmediate,
                                    globalThis.clearImmediate, globalThis.process, globalThis.process && process.nextTick];
                                globalThis.originals = snapshot();
                                """.trimIndent(),
                            )
                            val policy = JsEventLoopPolicy(ifPresent, ifMissing)
                            if (ifMissing == FAIL) {
                                val error =
                                    assertFailsWith<JsException> {
                                        loop.attachTo(
                                            context,
                                            policies = JsEventLoopPolicies(timers = policy, immediate = policy, nextTick = policy),
                                        )
                                    }
                                assertTrue(error.message.orEmpty().contains("TIMERS"), error.message)
                                assertNull(context.eventLoop)
                            } else {
                                loop.attachTo(
                                    context,
                                    policies = JsEventLoopPolicies(timers = policy, immediate = policy, nextTick = policy),
                                )
                                assertEquals(loop, context.eventLoop)
                            }
                            if (ifMissing == INSTALL) {
                                context.eval(
                                    """
                                    globalThis.calls = [];
                                    setTimeout(() => calls.push('timer'), 1);
                                    clearTimeout(setInterval(() => calls.push('cancelled'), 1));
                                    setImmediate(() => calls.push('immediate'));
                                    clearImmediate(setImmediate(() => calls.push('cancelled')));
                                    process.nextTick(() => calls.push('tick'));
                                    """.trimIndent(),
                                )
                                withTimeout(1_000) { loop.run() }
                                assertEquals("tick,immediate,timer", context.string("calls.join(',')"), "$policy with $setup")
                                assertTrue(context.bool("[0, 1, 2].every(group => __host.pendingCount(group) === 0)"))
                            } else {
                                withTimeout(1_000) { loop.run() }
                                assertTrue(context.bool("snapshot().every((value, index) => value === originals[index])"))
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun mixedPresentAndMissingGroupsKeepObserveOrReplaceIndependently() =
        runTest {
            for (ifPresent in ExistingApiAction.entries) {
                for (missingGroup in TestApiGroup.entries) {
                    withHost { context, loop ->
                        val remove =
                            when (missingGroup) {
                                TIMERS -> "delete globalThis.clearInterval"
                                IMMEDIATE -> "delete globalThis.clearImmediate"
                                NEXT_TICK -> "delete globalThis.process"
                            }
                        context.eval(remove)
                        loop.attachTo(
                            context,
                            policies =
                                JsEventLoopPolicies(
                                    timers = JsEventLoopPolicy(ifPresent, INSTALL),
                                    immediate = JsEventLoopPolicy(ifPresent, INSTALL),
                                    nextTick = JsEventLoopPolicy(ifPresent, INSTALL),
                                ),
                        )
                        context.eval(
                            """
                            globalThis.calls = [];
                            setTimeout(() => calls.push('timer'), 1);
                            setImmediate(() => calls.push('immediate'));
                            process.nextTick(() => calls.push('tick'));
                            """.trimIndent(),
                        )
                        val running = async { loop.run() }
                        testScheduler.advanceTimeBy(1)
                        testScheduler.runCurrent()
                        assertEquals(if (ifPresent == REPLACE) 3 else 1, context.int("calls.length"))
                        assertEquals(ifPresent != OBSERVE, running.isCompleted)
                        for (group in TestApiGroup.entries) {
                            if (group != missingGroup && ifPresent != REPLACE) context.eval("__host.fire(${group.ordinal})")
                        }
                        withTimeout(1_000) { running.await() }
                        assertEquals("immediate,tick,timer", context.string("calls.sort().join(',')"))
                        loop.cancel()
                        withTimeout(1_000) { loop.run() }
                        for (group in TestApiGroup.entries) {
                            if (group != missingGroup) {
                                assertEquals(ifPresent != REPLACE, context.bool("__host.isOriginal(${group.ordinal})"))
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun missingFailureRollsBackEarlierGroupsAndAllowsRetry() =
        runTest {
            for (earlierPolicy in listOf(observePolicy, replacePolicy)) {
                for (ifPresent in ExistingApiAction.entries) {
                    withHost { context, loop ->
                        context.eval("delete process.nextTick; globalThis.originalProcess = process")
                        val error =
                            assertFailsWith<JsException> {
                                loop.attachTo(
                                    context,
                                    policies =
                                        JsEventLoopPolicies(
                                            timers = earlierPolicy,
                                            immediate = earlierPolicy,
                                            nextTick = JsEventLoopPolicy(ifPresent, FAIL),
                                        ),
                                )
                            }
                        assertTrue(error.message.orEmpty().contains("NEXT_TICK"), error.message)
                        assertTrue(error.message.orEmpty().contains("nextTick"), error.message)
                        assertNull(context.eventLoop)
                        assertTrue(context.bool("__host.isOriginal(0) && __host.isOriginal(1)"))
                        assertTrue(context.bool("process === originalProcess && !('nextTick' in process)"))
                        loop.attachTo(
                            context,
                            policies =
                                JsEventLoopPolicies(
                                    timers = JsEventLoopPolicy(OBSERVE, INSTALL),
                                    immediate = JsEventLoopPolicy(OBSERVE, INSTALL),
                                    nextTick = JsEventLoopPolicy(OBSERVE, INSTALL),
                                ),
                        )
                        context.eval("globalThis.called = false; process.nextTick(() => { called = true; })")
                        withTimeout(1_000) { loop.run() }
                        assertTrue(context.bool("called"))
                    }
                }
            }
        }

    @Test
    fun availabilityChecksReadGettersOnceAndDoNotHideTheirErrors() =
        runTest {
            for (ifPresent in ExistingApiAction.entries) {
                for (ifMissing in MissingApiAction.entries) {
                    val needsCheck = !((ifPresent == KEEP && ifMissing == SKIP) || (ifPresent == REPLACE && ifMissing == INSTALL))
                    if (!needsCheck) continue
                    for (throws in listOf(false, true)) {
                        withHost { context, loop ->
                            context.eval(
                                """
                                globalThis.reads = 0;
                                const originalNextTick = process.nextTick;
                                Object.defineProperty(process, 'nextTick', {
                                    configurable: true,
                                    get() {
                                        reads++;
                                        if ($throws) throw new Error('availability check failed');
                                        return originalNextTick;
                                    },
                                });
                                """.trimIndent(),
                            )
                            val policies = keepPolicies.copy(nextTick = JsEventLoopPolicy(ifPresent, ifMissing))
                            if (throws) {
                                val error =
                                    assertFailsWith<JsException> {
                                        loop.attachTo(context, policies = policies)
                                    }
                                assertTrue(error.message.orEmpty().contains("availability check failed"), error.message)
                                assertNull(context.eventLoop)
                                assertTrue(context.bool("typeof Object.getOwnPropertyDescriptor(process, 'nextTick').get === 'function'"))
                            } else {
                                loop.attachTo(context, policies = policies)
                            }
                            assertEquals(1, context.int("reads"))
                        }
                    }
                }
            }
        }

    @Test
    fun observeRejectsUnreplaceableFunctionsAndRollsBack() =
        runTest {
            for ((target, name) in listOf("globalThis" to "clearInterval", "globalThis" to "clearImmediate", "process" to "nextTick")) {
                for (ifMissing in MissingApiAction.entries) {
                    withHost { context, loop ->
                        context.eval("Object.defineProperty($target, '$name', { writable: false, configurable: false })")
                        assertFailsWith<JsException> {
                            loop.attachTo(
                                context,
                                policies =
                                    JsEventLoopPolicies(
                                        timers = JsEventLoopPolicy(OBSERVE, ifMissing),
                                        immediate = JsEventLoopPolicy(OBSERVE, ifMissing),
                                        nextTick = JsEventLoopPolicy(OBSERVE, ifMissing),
                                    ),
                            )
                        }
                        assertNull(context.eventLoop)
                        assertTrue(context.bool("[0, 1, 2].every(__host.isOriginal)"))
                    }
                }
            }
        }

    @Test
    fun failedAttachmentRollsBackReplacementsAndObserversAndAllowsRetry() =
        runTest {
            for (policy in listOf(observePolicy, replacePolicy)) {
                withHost { context, loop ->
                    context.eval("Object.defineProperty(process, 'nextTick', { writable: false, configurable: false })")
                    assertFailsWith<JsException> { loop.attachTo(context, policies = JsEventLoopPolicies(immediate = policy)) }
                    assertNull(context.eventLoop)
                    assertTrue(context.bool("[0, 1, 2].every(__host.isOriginal)"))
                    loop.attachTo(context, policies = JsEventLoopPolicies(nextTick = keepPolicy))
                    withTimeout(1_000) { loop.run() }
                }
            }
        }

    @Test
    fun replaceNextTickHandlesReadOnlyDescriptorsAndKeepsTheProcessObject() =
        runTest {
            for (accessor in listOf(false, true)) {
                withHost { context, loop ->
                    context.eval(
                        """
                        globalThis.originalProcess = process;
                        Object.defineProperty(globalThis, 'process', { writable: false, configurable: false });
                        Object.defineProperty(process, 'nextTick', $accessor
                            ? { configurable: true, get() { throw new Error('Must not read nextTick'); } }
                            : { configurable: true, writable: false, value: undefined });
                        """.trimIndent(),
                    )
                    loop.attachTo(context, policies = keepPolicies.copy(nextTick = replacePolicy))
                    context.eval("globalThis.called = false; process.nextTick(() => { called = true; })")
                    withTimeout(1_000) { loop.run() }
                    assertTrue(context.bool("called && process === originalProcess"))
                }
            }
        }

    private fun policiesFor(
        group: TestApiGroup,
        policy: JsEventLoopPolicy,
    ): JsEventLoopPolicies =
        when (group) {
            TIMERS -> keepPolicies.copy(timers = policy)
            IMMEDIATE -> keepPolicies.copy(immediate = policy)
            NEXT_TICK -> keepPolicies.copy(nextTick = policy)
        }

    private suspend fun TestScope.withHost(block: suspend (JsEngineContext, JsEventLoop) -> Unit) {
        JsEngineContext().use { context ->
            context.eval(HOST_SCHEDULERS)
            val loop = JsEventLoop(coroutineContext)
            try {
                block(context, loop)
            } finally {
                loop.cancel()
                withTimeout(1_000) { loop.run() }
            }
        }
    }
}

private enum class TestApiGroup { TIMERS, IMMEDIATE, NEXT_TICK }

private fun JsContext.eval(script: String) = evaluateScript(script).close()

private fun JsContext.bool(script: String) = evaluateScript(script).use { it.boolean }

private fun JsContext.int(script: String) = evaluateScript(script).use { it.int }

private fun JsContext.string(script: String) = evaluateScript(script).use { it.string }

private val HOST_SCHEDULERS =
    """
    (() => {
        const queues = [new Map(), new Map(), new Map()];
        let nextId = 1;
        const tickReceiver = {};
        const clearResult = {};
        function schedule(group, callback, args, repeat, receiver) {
            if (typeof callback !== 'function') throw new TypeError('Expected a callback');
            __host.lastReceiver = receiver;
            const handle = group === 0 ? nextId++ : { valueOf() { throw new Error('Must not coerce an opaque handle'); } };
            queues[group].set(handle, { callback, args, repeat });
            return group === 2 ? undefined : handle;
        }
        globalThis.setTimeout = function (callback, delay, ...args) { return schedule(0, callback, args, false, this); };
        globalThis.setInterval = function (callback, delay, ...args) { return schedule(0, callback, args, true, this); };
        globalThis.clearTimeout = globalThis.clearInterval = function (id) { queues[0].delete((+id) | 0); };
        globalThis.setImmediate = function (callback, ...args) { return schedule(1, callback, args, false, this); };
        globalThis.clearImmediate = function (id) {
            __host.lastReceiver = this;
            queues[1].delete(id);
            return clearResult;
        };
        globalThis.process = { nextTick(callback, ...args) { return schedule(2, callback, args, false, this); } };
        const groups = [
            [globalThis, ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval']],
            [globalThis, ['setImmediate', 'clearImmediate']],
            [process, ['nextTick']],
        ];
        const originals = groups.map(([target, names]) => names.map(name => target[name]));
        globalThis.__host = {
            tickReceiver,
            clearResult,
            isOriginal(group) {
                const [target, names] = groups[group];
                return names.every((name, index) => target[name] === originals[group][index]);
            },
            pendingCount(group) { return queues[group].size; },
            fire(group) {
                const entry = queues[group].entries().next().value;
                if (!entry) throw new Error('No scheduled work in group ' + group);
                const [handle, item] = entry;
                if (!item.repeat) queues[group].delete(handle);
                return item.callback.apply(group === 2 ? tickReceiver : handle, item.args);
            },
        };
    })();
    """.trimIndent()
