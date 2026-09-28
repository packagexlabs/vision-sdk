package io.packagex.visiondemo.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AwaitDrainedTest {
    @Test fun waitsForTheInFlightTask() {
        val worker = Executors.newSingleThreadExecutor()
        val done = AtomicBoolean(false)
        worker.execute { Thread.sleep(100); done.set(true) }
        assertTrue(awaitDrained(worker, 2_000)); assertTrue(done.get())
        worker.shutdown()
    }

    @Test fun givesUpAfterTheTimeout() {
        val worker = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        worker.execute { release.await(5, TimeUnit.SECONDS) }
        val start = System.nanoTime()
        assertFalse(awaitDrained(worker, 100))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000)
        release.countDown(); worker.shutdown()
    }

    @Test fun shutDownWorkerIsDrained() {
        val worker = Executors.newSingleThreadExecutor().apply { shutdown() }
        assertTrue(awaitDrained(worker, 100))
    }
}
