package io.packagex.visiondemo.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The engine's intake: one image decoded, the newest one waiting. */
class LatestWinsTest {
    private class Item : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun theNewestWaitsAndGoesNextTheOneItReplacedIsClosedAndCounted() {
        val slot = LatestWins<Item>()
        val (a, b, c) = List(3) { Item() }
        assertTrue(slot.offer(a))    // idle: decoded now
        assertFalse(slot.offer(b))   // busy: waits
        assertFalse(slot.offer(c))   // newer: b is closed unread
        assertEquals(1, b.closes.get()); assertEquals(0, c.closes.get()); assertEquals(1L, slot.dropped)
        assertSame(c, slot.next())   // a done: c at once
        assertNull(slot.next())      // c done: idle
        assertTrue(slot.offer(Item()))
    }

    @Test fun teardownClosesTheWaitingOneWithoutCountingIt() {
        val slot = LatestWins<Item>()
        val waiting = Item()
        slot.offer(Item()); slot.offer(waiting)
        slot.clear()
        assertEquals(1, waiting.closes.get()); assertEquals(0L, slot.dropped)
        assertNull(slot.next())      // the decode in flight ends: nothing follows
        assertTrue(slot.offer(Item()))
    }

    // The camera thread offers while the worker takes: no image is lost (left neither decoded nor closed) or closed
    // twice, and the slot ends idle.
    @Test fun underLoadEveryItemIsDecodedOrDroppedOnce() {
        val slot = LatestWins<Item>()
        val worker = Executors.newSingleThreadExecutor()
        val decoded = AtomicInteger()
        fun decode(item: Item) {
            worker.execute {
                if (decoded.incrementAndGet() % 7 == 0) Thread.sleep(1)
                item.close()
                slot.next()?.let(::decode)
            }
        }
        val items = List(20_000) { Item() }
        items.forEach { if (slot.offer(it)) decode(it) }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (decoded.get() + slot.dropped < items.size && System.nanoTime() < until) Thread.sleep(2)
        worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(items.size.toLong(), decoded.get() + slot.dropped)
        assertTrue(items.all { it.closes.get() == 1 })
        assertTrue(slot.offer(Item()))   // idle again
    }
}
