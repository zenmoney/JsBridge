package app.zenmoney.jsbridge

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class JsEventLoopBrowserTimersTest {
    @Test
    fun timerCreatedDuringAttachmentCanBeCancelledAfterAttachment() =
        runTest {
            val context = JsContext()
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                jsScoped(context) { eval(TIMER_TEST_SCRIPT) }
                eventLoop.attachTo(context, timerMode = JsTimerMode.PRESERVE)
                jsScoped(context) {
                    assertEquals("true", eval("timersStayedNative").toString())
                    eval("clearTimeout(timerCreatedDuringAttachment)")
                    assertEquals("true", eval("cancelledNativeTimer").toString())
                    assertEquals("true", eval("descriptorsWerePreserved()").toString())
                }
            } finally {
                eventLoop.cancel()
                context.close()
            }
        }

    @Test
    fun nonConfigurableWritableTimersStayNativeThroughoutAttachment() =
        runTest {
            val context = JsContext()
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                jsScoped(context) {
                    eval("globalThis.lockTimerGlobals = true")
                    eval(TIMER_TEST_SCRIPT)
                }
                eventLoop.attachTo(context, timerMode = JsTimerMode.PRESERVE)
                jsScoped(context) {
                    assertEquals("true", eval("timersStayedNative").toString())
                    eval("clearTimeout(timerCreatedDuringAttachment)")
                    assertEquals("true", eval("cancelledNativeTimer").toString())
                    assertEquals("true", eval("descriptorsWerePreserved()").toString())
                }
            } finally {
                eventLoop.cancel()
                context.close()
            }
        }

    @Test
    fun failedAttachmentPreservesTimerDescriptors() =
        runTest {
            val context = JsContext()
            val eventLoop = JsEventLoop(coroutineContext)
            try {
                jsScoped(context) {
                    eval(TIMER_TEST_SCRIPT)
                    eval("Object.defineProperty(globalThis, 'process', { get() { throw new Error('attachment interrupted'); } })")
                }
                assertFails { eventLoop.attachTo(context, timerMode = JsTimerMode.PRESERVE) }
                jsScoped(context) { assertEquals("true", eval("descriptorsWerePreserved()").toString()) }
            } finally {
                eventLoop.cancel()
                context.close()
            }
        }
}

// A page accessor runs synchronously during attachment, deterministically exercising the interval
// in which other renderer tasks can run between the native bridge's individual RPCs.
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
        const process = {};
        Object.defineProperty(globalThis, 'process', {
            configurable: true,
            get() {
                globalThis.timersStayedNative = names.every((name, index) => globalThis[name] === timers[index]);
                globalThis.timerCreatedDuringAttachment = setTimeout(() => {}, 1000);
                return process;
            },
            set() {}
        });
    })()
    """.trimIndent()
