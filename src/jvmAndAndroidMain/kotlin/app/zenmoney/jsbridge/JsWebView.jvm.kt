package app.zenmoney.jsbridge

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal actual fun createJsWebView(): JsWebView = throw UnsupportedOperationException("JsWebViewContext is not available on JVM")

internal actual class JsWebViewBlockingRequest<T> {
    private val latch = CountDownLatch(1)
    private var result: Result<T>? = null

    actual fun complete(result: Result<T>) {
        this.result = result
        latch.countDown()
    }

    actual fun await(debug: String): T {
        check(latch.await(10, TimeUnit.SECONDS)) {
            "Timed out executing JsWebViewContext.$debug"
        }
        return checkNotNull(result).getOrThrow()
    }
}
