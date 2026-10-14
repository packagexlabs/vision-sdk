package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The app stream's reader closes on the engine worker, after the decode that reads its image (closeAfterDecode). */
class RunBehindTest {
    @Test fun runsOnlyAfterTheTaskInFlight() {
        val worker = Executors.newSingleThreadExecutor()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val decoding = CountDownLatch(1)
        worker.execute { decoding.await(5, TimeUnit.SECONDS); order += "decode" }
        val closed = CountDownLatch(1)
        runBehind(worker) { order += "close"; closed.countDown() }
        assertFalse(closed.await(200, TimeUnit.MILLISECONDS))   // a decode as long as you like: no timeout
        decoding.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("decode", "close"), order)
        worker.shutdown()
    }

    @Test fun runsAtOnceOnAShutDownWorker() {
        val worker = Executors.newSingleThreadExecutor().apply { shutdown() }
        var ran = false
        runBehind(worker) { ran = true }
        assertTrue(ran)
    }
}
