package com.dshbox.browser.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserCliWorkerPoolTest {
    @Test
    fun pendingEventWaitDoesNotBlockTheClickThatCanProduceItsEvent() {
        val workers = createBrowserCliWorkerPool()
        val waitEntered = CountDownLatch(1)
        val clickEntered = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        try {
            workers.execute {
                waitEntered.countDown()
                releaseWait.await(2, TimeUnit.SECONDS)
            }
            assertTrue("first command did not start", waitEntered.await(1, TimeUnit.SECONDS))

            workers.execute { clickEntered.countDown() }
            assertTrue("click was queued behind the pending event wait", clickEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseWait.countDown()
            workers.shutdownNow()
            workers.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
