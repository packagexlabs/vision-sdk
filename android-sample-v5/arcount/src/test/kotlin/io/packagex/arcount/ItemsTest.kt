package io.packagex.arcount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AR Item Count (spec 5.10): the item list drives the count, in label-free sections */
class ItemsTest {
    private val home = cameraAt(0.0)

    private fun sym(text: String, x: Double, id: Int, depth: Double = 0.30) = Symbol(text, Vec3(x, 0.04, -depth), id)

    /** Three listed A units around the camera's start, 6 cm apart */
    private val threeA = listOf(sym(GTIN, -0.06, 1), sym(GTIN, 0.0, 2), sym(GTIN, 0.06, 3))

    private fun session(symbols: List<Symbol>, items: Set<String> = setOf(GTIN14), sim: (Counter) -> Sim = { Sim(symbols, it, noisePx = 1.0) }): Pair<Sim, CountingCore> {
        val core = CountingCore()
        core.setItems(items)
        return sim(CoreCounter(core)) to core
    }

    private fun counting(symbols: List<Symbol> = threeA, items: Set<String> = setOf(GTIN14)): Pair<Sim, CountingCore> {
        val (sim, core) = session(symbols, items)
        sim.run(Paths.hold(home, 5.5))
        assertEquals(SectionState.COUNTING, core.view().state)
        return sim to core
    }

    private fun item(core: CountingCore, code: String) = core.view().items.single { it.code == code }

    // matching

    @Test
    fun validGtinsMatchAsFourteenDigits() {
        assertEquals(ItemCode.key("4006381333931"), ItemCode.key("04006381333931"))
        assertEquals(ItemCode.key("036000291452"), ItemCode.key("0036000291452"))
        assertEquals(ItemCode.key("96385074"), ItemCode.key("00000096385074"))
        assertEquals(ItemCode.key("042100005264"), ItemCode.key("04252614", "UPC_E"))
    }

    @Test
    fun digitsWithoutAValidCheckDigitAndOtherTextMatchOnlyAsEqualText() {
        assertNotEquals(ItemCode.key("4006381333932"), ItemCode.key("04006381333932"))
        assertEquals(ItemCode.key("4006381333932"), ItemCode.key("4006381333932"))
        assertNotEquals(ItemCode.key("4006381333931"), ItemCode.key("4006381333932"))
        assertEquals("4260072015", ItemCode.key("4260072015"))
        assertEquals("TP6056F32", ItemCode.key("TP6056F32"))
        assertNotEquals(ItemCode.key("TP6056F32"), ItemCode.key("tp6056f32"))
    }

    @Test
    fun theCheckDigitIsTheGs1One() {
        assertTrue(Gtin.isValid("4006381333931"))
        assertTrue(Gtin.isValid("04006381333931"))
        assertTrue(Gtin.isValid("036000291452"))
        assertTrue(Gtin.isValid("96385074"))
        assertTrue(Gtin.isValid(OTHER_GTIN))
        assertFalse(Gtin.isValid("4006381333932"))
        assertFalse(Gtin.isValid("TP6056F32"))
    }

    @Test
    fun aListKeepsItsOrderAndListsAKeyOnce() {
        val list = ItemList(linkedSetOf("TP6056F32", "4006381333931", "04006381333931", "X"))
        assertEquals(listOf("TP6056F32", "4006381333931", "X"), list.entries.map { it.first })
        assertEquals(setOf("TP6056F32", GTIN14, "X"), list.keys)
    }

    // sections

    @Test
    fun aSectionOpensOnAListedReadOnlyAndAnUnlistedOneIsNeverAUnit() {
        val c = listOf(sym(OTHER_GTIN, -0.03, 11), sym(OTHER_GTIN, 0.03, 12))
        val (sim, core) = session(c)
        sim.run(Paths.hold(home, 6.0))
        assertEquals(SectionState.IDLE, core.view().state)
        assertTrue(sim.anchorRequests.isEmpty())
        assertNull(core.view().prompt)
        sim.symbols = c + threeA.map { it.copy(centre = it.centre + Vec3(0.0, -0.06, 0.0)) }
        sim.run(Paths.hold(home, 1.0))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertEquals(1, sim.anchorRequests.size)
        assertEquals(setOf(GTIN14), core.units.map { it.gtin }.toSet())
        assertEquals(3, core.units.count { it.state == UnitState.COUNTED })
        assertEquals(listOf(ItemCount(GTIN14, 3, 3, true)), core.view().items)
    }

    @Test
    fun itemModeHasNoLabelPromptAndNoTriggerOpensASection() {
        val (sim, core) = session(listOf(sym(OTHER_GTIN, 0.0, 11)))
        sim.run(Paths.hold(home, 6.0))
        sim.command(Command.TriggerLong)
        sim.command(Command.TriggerShort)
        sim.run(Paths.hold(home, 0.5))
        assertEquals(SectionState.IDLE, core.view().state)
        assertTrue(core.view().prompt != Prompt.SCAN_SHELF_LABEL)
    }

    @Test
    fun aSmallBarcodeIsAUnitNotALabel() {
        val small = listOf(-0.03, 0.03).mapIndexed { i, x -> Symbol(GTIN, Vec3(x, 0.04, -0.30), i + 1, width = 0.020, height = 0.008) }
        val (_, core) = counting(small)
        assertEquals(2, core.units.count { it.state == UnitState.COUNTED })
    }

    @Test
    fun beyondTheReachTheSectionClosesAndTheNextListedReadOpensAnother() {
        val (sim, core) = counting()
        sim.run(Paths.move(Vec3.ZERO, Vec3(1.2, 0.0, 0.0), 0.3))
        assertEquals(SectionState.CLOSED, core.view().state)
        val first = core.view().closed.single()
        assertEquals(SectionStatus.COMPLETE, first.status)
        assertEquals(3, first.counted)
        assertTrue(core.events.any { it.contains("beyond the section's reach") })
        sim.symbols = threeA.map { it.copy(centre = it.centre + Vec3(1.2, 0.0, 0.0), engineId = it.engineId + 10) }
        sim.run(Paths.hold(cameraAt(1.2), 1.0))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertEquals(2, sim.anchorRequests.size)
        assertEquals(ItemCount(GTIN14, 6, 6, true), item(core, GTIN14))
    }

    @Test
    fun theReachIsAConfigValue() {
        assertEquals(1.0, CountConfig().sectionReach, 0.0)
        assertEquals(1_000_000_000L, CountConfig().itemSeenNs)
    }

    // breaks

    @Test
    fun leavingTheViewAndSilenceDoNotFreezeAnItemSection() {
        val (sim, core) = counting()
        // 12 s on an empty stretch, within the reach: nothing in view
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.5, 0.0, 0.0), 0.3))
        sim.run(Paths.hold(cameraAt(0.5), 12.0))
        assertEquals(SectionState.COUNTING, core.view().state)
        // 12 s back over the units without a read
        sim.hidden = { true }
        sim.run(Paths.move(Vec3(0.5, 0.0, 0.0), Vec3.ZERO, 0.3))
        sim.run(Paths.hold(home, 12.0))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertTrue(core.machine.section!!.breaks.isEmpty())
        assertEquals(3, item(core, GTIN14).countLow)
    }

    @Test
    fun aTrackingLossFreezesAndTwoCountedUnitsReReadWhereTheyWereResume() {
        val (sim, core) = counting()
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        sim.tracking = Tracking.TRACKING
        assertEquals(SectionState.FROZEN, core.view().state)
        sim.hidden = { true }
        sim.run(Paths.hold(home, 0.5))
        assertEquals(Prompt.HOLD_STILL_A_MOMENT, core.view().prompt)
        sim.run(Paths.hold(home, 2.0))
        assertEquals(SectionState.FROZEN, core.view().state)
        assertEquals(Prompt.REREAD_COUNTED_ITEMS, core.view().prompt)
        assertEquals(3, item(core, GTIN14).countLow)
        sim.hidden = { false }
        sim.run(Paths.hold(home, 0.5))
        assertEquals(SectionState.COUNTING, core.view().state)
        assertEquals(2, core.machine.section!!.segment)
        assertEquals(ItemCount(GTIN14, 3, 3, true), item(core, GTIN14))
        assertTrue(core.view().closed.isEmpty())
    }

    @Test
    fun aWorldJumpAndAnAnchorNotTrackingFreezeAnItemSection() {
        val (sim, core) = counting()
        sim.hidden = { true }
        sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
        sim.step(home)
        assertEquals(SectionState.FROZEN, core.view().state)
        assertEquals(BreakReason.WORLD_JUMP, core.machine.section!!.breaks.single().second)

        val (sim2, core2) = counting()
        sim2.anchorTracking = Tracking.PAUSED
        sim2.step(home)
        assertEquals(SectionState.FROZEN, core2.view().state)
        assertEquals(BreakReason.ANCHOR_NOT_TRACKING, core2.machine.section!!.breaks.single().second)
    }

    @Test
    fun aFailedResumeClosesTheSectionWithItsRangeAndCountingGoesOnInANewOne() {
        val b = listOf(sym("TP6056F32", 0.44, 21), sym("TP6056F32", 0.50, 22), sym("TP6056F32", 0.56, 23))
        val (sim, core) = counting(threeA + b, setOf(GTIN14, "TP6056F32"))
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        sim.tracking = Tracking.TRACKING
        // the worker moves on to the B units; no counted unit is read again
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.5, 0.0, 0.0), 0.25))
        sim.run(Paths.hold(cameraAt(0.5), 4.0))
        assertEquals(SectionState.FROZEN, core.view().state)
        assertTrue(core.events.any { it.contains("resume window open") })
        sim.run(Paths.hold(cameraAt(0.5), 2.0))
        val abandoned = core.view().closed.single()
        assertEquals(SectionStatus.ABANDONED, abandoned.status)
        assertEquals(3, abandoned.countLow)
        assertEquals(SectionState.COUNTING, core.view().state)
        assertEquals(listOf(ItemCount(GTIN14, 3, 3, false), ItemCount("TP6056F32", 3, 3, true)), core.view().items)
    }

    @Test
    fun aFrozenSectionBeyondTheReachIsAbandoned() {
        val (sim, core) = counting()
        sim.tracking = Tracking.PAUSED
        sim.step(home)
        sim.tracking = Tracking.TRACKING
        sim.run(Paths.move(Vec3.ZERO, Vec3(1.2, 0.0, 0.0), 0.3))
        assertEquals(SectionStatus.ABANDONED, core.view().closed.single().status)
        assertEquals(SectionState.CLOSED, core.view().state)
        assertEquals(3, item(core, GTIN14).countLow)
    }

    @Test
    fun readsCapturedBeforeAWorldJumpCannotOpenTheResumeWindow() {
        val core = CountingCore()
        core.setItems(setOf(GTIN14))
        val sim = Sim(threeA, LateCounter(core), noisePx = 1.0, readsFirst = false)
        sim.run(Paths.hold(home, 5.5))
        assertEquals(SectionState.COUNTING, core.view().state)
        // the jump comes on the frame after a read frame: that frame's reads arrive after the jump's record
        while (sim.frame % 3 != 1) sim.step(home)
        sim.hidden = { true }
        sim.jumpCamera(Vec3(0.06, 0.0, 0.0))
        sim.run(Paths.hold(home, 1.0))
        assertEquals(SectionState.FROZEN, core.view().state)
        assertTrue(core.events.any { it.contains("captured before") })
        assertTrue(core.events.none { it.contains("resume window open") })
    }

    // the list

    @Test
    fun aCodeTakenOffTheListStopsCountingAndOneAddedCountsFromItsNextRead() {
        val b = listOf(sym("TP6056F32", 0.12, 21), sym("TP6056F32", 0.18, 22))
        val c = listOf(sym(OTHER_GTIN, -0.15, 31))
        val (sim, core) = counting(threeA + b + c, setOf(GTIN14, "TP6056F32"))
        assertEquals(2, item(core, "TP6056F32").countLow)
        val bUnits = core.units.filter { it.gtin == "TP6056F32" }
        val seen = bUnits.map { it.observations }
        core.setItems(linkedSetOf(OTHER_GTIN, GTIN))
        assertEquals(listOf(OTHER_GTIN, GTIN), core.view().items.map { it.code })
        assertEquals(ItemCount(OTHER_GTIN, 0, 0, true), item(core, OTHER_GTIN))
        sim.run(Paths.hold(home, 1.0))
        assertEquals(seen, bUnits.map { it.observations })
        assertEquals(2, core.units.count { it.gtin == "TP6056F32" })
        assertEquals(ItemCount(OTHER_GTIN, 1, 1, true), item(core, OTHER_GTIN))
        assertEquals(3, item(core, GTIN).countLow)
        sim.command(Command.Finish)
        // the section keeps the removed code's units
        assertEquals(6, core.view().closed.single().counted)
    }

    @Test
    fun anEmptyListEndsItemModeAndTheOpenSection() {
        val (_, core) = counting()
        core.setItems(emptySet())
        assertEquals(SectionStatus.COMPLETE, core.view().closed.single().status)
        assertTrue(core.view().items.isEmpty())
        assertEquals(Prompt.SCAN_SHELF_LABEL, core.view().prompt)
    }

    // the view

    @Test
    fun aCodeIsInViewWhileOneOfItsUnitsHasAMarkerOrForASecondAfterItsRead() {
        val b = listOf(sym("TP6056F32", 0.70, 21))
        val (sim, core) = counting(threeA + b, linkedSetOf(GTIN14, "TP6056F32", "NEVER"))
        assertEquals(listOf(true, false, false), core.view().items.map { it.inView })
        sim.run(Paths.move(Vec3.ZERO, Vec3(0.7, 0.0, 0.0), 0.5))
        sim.run(Paths.hold(cameraAt(0.7), 1.2))
        assertEquals(listOf(false, true, false), core.view().items.map { it.inView })
        assertEquals(listOf(3, 1, 0), core.view().items.map { it.countLow })
    }

    @Test
    fun eachSectionIsSummedWithItsOwnRange() {
        val totals = ItemTotals()
        totals.add(mapOf(GTIN14 to Counts(2, 0, 1, 0), "B" to Counts(1, 0, 0, 0)))
        totals.add(mapOf(GTIN14 to Counts(1, 0, 0, 0)))
        val view = totals.view(ItemList(linkedSetOf("B", GTIN, "C")), mapOf(GTIN14 to Counts(1, 0, 0, 2))) { it == "B" }
        assertEquals(listOf(ItemCount("B", 1, 1, true), ItemCount(GTIN, 4, 7, false), ItemCount("C", 0, 0, false)), view)
    }
}
