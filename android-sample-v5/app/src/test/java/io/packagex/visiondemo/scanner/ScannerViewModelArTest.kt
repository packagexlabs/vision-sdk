package io.packagex.visiondemo.scanner

import app.cash.turbine.test
import io.packagex.arcount.CountView
import io.packagex.arcount.ItemCount
import io.packagex.arcount.Prompt
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Prefs
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.fakes.FakeArCount
import io.packagex.visiondemo.fakes.FakeCamera
import io.packagex.visiondemo.fakes.FakeCatalog
import io.packagex.visiondemo.fakes.FakeEntitlement
import io.packagex.visiondemo.fakes.FakeExtraction
import io.packagex.visiondemo.fakes.FakeModels
import io.packagex.visiondemo.fakes.FakePreferences
import io.packagex.visiondemo.fakes.FakeReport
import io.packagex.visiondemo.fakes.MainDispatcherRule
import io.packagex.visiondemo.model.RetrievalRow
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AR Item Count (spec 5.10) in the ViewModel: the item list drives the AR session's counter, against fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelArTest {
    @get:Rule val main = MainDispatcherRule()

    private val cam = FakeCamera()
    private var ownerAtDetach: CameraOwner? = null
    private val ar = FakeArCount(onDetach = { ownerAtDetach = cam.owner })
    private val catalog = FakeCatalog(items = listOf("A", "B"))

    private fun vm(entitlement: FakeEntitlement = FakeEntitlement(true)) = ScannerViewModel(
        cam, FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), entitlement, catalog, Secrets("k", "staging"), ar,
    ).also { it.onAction(ScannerAction.PermissionResult(true)) }

    private val counting = CountView.EMPTY.copy(prompt = Prompt.SLOW_DOWN, items = listOf(ItemCount("A", 2, 2, true), ItemCount("B", 3, 4, false)))

    // --- the gate, then ARCore, then the session ---

    @Test fun theGateComesBeforeTheArSession() = runTest {
        ar.installed = false
        val v = vm(FakeEntitlement(true, delayMs = 1_000)); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceTimeBy(500)
            assertEquals(CameraOwner.Ar, cam.owner)   // the scanner camera stops; nothing runs behind the gate card
            assertTrue(v.state.value.gated); assertFalse(v.state.value.arOn)
            expectNoEvents()   // no ARCore check before the gate passes
            advanceUntilIdle()
            assertFalse(v.state.value.gated)
            assertEquals(ScannerEffect.InstallArCore, awaitItem())
            assertFalse(v.state.value.arOn)
            v.onAction(ScannerAction.ArInstallResult(ArInstall.Installed))
            assertTrue(v.state.value.arOn)
            v.onAction(ScannerAction.GoHome); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
            assertTrue(v.state.value.arOn)   // no second install prompt
            expectNoEvents()
        }
    }

    @Test fun aDeniedGateStartsNoArSession() = runTest {
        ar.installed = false
        val v = vm(FakeEntitlement(false)); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
            expectNoEvents()
        }
        assertTrue(v.state.value.gated); assertFalse(v.state.value.arOn)
    }

    @Test fun withoutArCoreTheModeLeavesWithAMessage() = runTest {
        ar.installed = false
        val v = vm(); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
            assertEquals(ScannerEffect.InstallArCore, awaitItem())
            v.onAction(ScannerAction.ArInstallResult(ArInstall.Unsupported))
            assertEquals(ScannerEffect.Toast("AR isn't supported on this device"), awaitItem())
        }
        assertTrue(v.state.value.home); assertFalse(v.state.value.arOn); assertEquals(CameraOwner.None, cam.owner)
    }

    // --- the item list feeds the counter ---

    @Test fun theListGoesToTheCounterOnModeStartOnChangeAndAfterNewScan() = runTest {
        val v = vm(); advanceUntilIdle()
        ar.calls.clear()
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        assertEquals(listOf("items A,B"), ar.calls)
        v.onAction(ScannerAction.AddItem("C")); advanceUntilIdle()
        assertEquals("items A,B,C", ar.calls.last())
        v.onAction(ScannerAction.RemoveItem("A")); advanceUntilIdle()
        assertEquals("items B,C", ar.calls.last())
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        v.onAction(ScannerAction.ScanNext); advanceUntilIdle()
        assertEquals(listOf("reset", "items B,C"), ar.calls.takeLast(2))
    }

    // --- hint, result, New Scan ---

    @Test fun theHintFollowsTheListTheCodesInViewAndTheCounter() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        ar.codesInView.value = listOf("A", "Z"); advanceUntilIdle()
        assertEquals("1 listed item in view · 0 counted", hintFor(v.state.value))
        ar.count.value = counting.copy(prompt = null); advanceUntilIdle()
        assertEquals("1 listed item in view · 5 counted", hintFor(v.state.value))
        ar.count.value = counting; advanceUntilIdle()
        assertEquals("Slow down", hintFor(v.state.value))
        v.onAction(ScannerAction.ClearItems); advanceUntilIdle()
        assertEquals("2 codes in view · add them from Item list", hintFor(v.state.value))
    }

    @Test fun theShutterShowsTheCodesInViewAndTheCountedItems() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        ar.codesInView.value = listOf("A", "Z"); ar.count.value = counting; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(
            ScanResult.Retrieval(listOf(RetrievalRow("A", true, 2, 2), RetrievalRow("Z", false, null, null), RetrievalRow("B", true, 3, 4))),
            v.state.value.result,
        )
    }

    @Test fun theShutterWithAnEmptyListAsksForItems() = runTest {
        catalog.items.value = emptyList()
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter)
        assertEquals("No items to find", v.state.value.alert?.title)
    }

    @Test fun newScanResetsTheCounterAndKeepsTheList() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        ar.count.value = counting; ar.seen.value = listOf("Z", "A"); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertTrue(ar.paused)   // the result covers the camera
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertFalse(ar.paused); assertEquals(0, ar.resets); assertEquals(counting, v.state.value.arCount)
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        v.onAction(ScannerAction.ScanNext); advanceUntilIdle()   // "New Scan"
        assertFalse(ar.paused); assertEquals(1, ar.resets)
        assertEquals(CountView.EMPTY, v.state.value.arCount); assertEquals(emptyList<String>(), v.state.value.seen)
        assertEquals(listOf("A", "B"), v.state.value.items)
    }

    @Test fun cameraPauseAndResumeKeepTheCount() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        cam.pausedFlow.value = true; advanceUntilIdle()   // heat / idle / background
        assertTrue(ar.paused); assertTrue(v.state.value.paused)
        v.onAction(ScannerAction.Resume); advanceUntilIdle()
        assertFalse(ar.paused)
        assertEquals(0, ar.resets); assertEquals(counting, v.state.value.arCount)
    }

    @Test fun pausedCameraKeepsArPausedWhenAResultCloses() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        cam.pausedFlow.value = true; advanceUntilIdle()
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertTrue(ar.paused)
    }

    // --- Add Item and the seen list ---

    @Test fun addItemAddsTheArCodesInView() = runTest {
        catalog.items.value = listOf("A")
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Point the camera at a code, then tap Add Item"), awaitItem())
            ar.codesInView.value = listOf("A", "B"); advanceUntilIdle()
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Scanned and added B"), awaitItem())
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Code already in list"), awaitItem())
        }
        assertEquals(listOf("A", "B"), v.state.value.items)
    }

    @Test fun theSeenListFollowsTheSessionAndItsAddUsesTheAddPath() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        ar.seen.value = listOf("Z", "A"); advanceUntilIdle()
        assertEquals(listOf("Z" to false, "A" to true), seenRows(v.state.value.seen, v.state.value.items))
        v.onAction(ScannerAction.AddItem("Z")); advanceUntilIdle()   // the Seen row's Add
        assertEquals(listOf("A", "B", "Z"), v.state.value.items); assertEquals("items A,B,Z", ar.calls.last())
        assertEquals(listOf("Z" to true, "A" to true), seenRows(v.state.value.seen, v.state.value.items))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle(); v.onAction(ScannerAction.ScanNext); advanceUntilIdle()
        assertEquals(emptyList<String>(), v.state.value.seen)
    }

    // --- the AR plumbing, as AR Count had it ---

    // A worker panning the shelf touches nothing: the idle timeout must not pause the session under them.
    @Test fun noIdlePauseWhileTheArSessionRuns() = runTest {
        val v = vm(); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        assertEquals(true, cam.busy.last())
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()   // the result pauses AR: the idle timeout runs again
        assertEquals(false, cam.busy.last())
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertEquals(true, cam.busy.last())
        cam.pausedFlow.value = true; advanceUntilIdle()   // heat or background still pause it
        assertEquals(false, cam.busy.last())
        v.onAction(ScannerAction.Resume); advanceUntilIdle()
        assertEquals(true, cam.busy.last())
        v.onAction(ScannerAction.GoHome); advanceUntilIdle()
        assertEquals(false, cam.busy.last())
    }

    @Test fun entryClaimsArAndLeavingDetachesBeforeTheScannerClaims() = runTest {
        val v = vm(); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        assertEquals(CameraOwner.Ar, cam.owner); assertFalse(ar.paused); assertTrue(v.state.value.arOn)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertEquals(1, ar.detaches); assertEquals(CameraOwner.Ar, ownerAtDetach); assertEquals(CameraOwner.Scanner, cam.owner)
        assertFalse(v.state.value.arOn)
    }

    @Test fun noZoomNoAutoAndNoLongPressAction() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        val prefs = v.state.value.prefs
        v.onAction(ScannerAction.ToggleAuto)   // the shutter's long press
        assertEquals(prefs, v.state.value.prefs); assertEquals(0, ar.resets)
    }

    @Test fun sessionErrorsToast() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.effects.test {
            ar.fail("Camera not available")
            assertEquals(ScannerEffect.Toast("Camera not available"), awaitItem())
        }
    }

    // Spec 6: no session can be made, or after the last stream size fails, the mode exits with a message.
    @Test fun aSessionThatCannotRunLeavesTheModeWithItsMessage() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.effects.test {
            ar.exit("AR isn't available on this device")
            assertEquals(ScannerEffect.Toast("AR isn't available on this device"), awaitItem())
        }
        assertTrue(v.state.value.home); assertEquals(1, ar.detaches); assertEquals(CameraOwner.None, cam.owner)
    }

    @Test fun anExitOnceArIsLeftIsIgnored() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        v.effects.test {
            ar.exit("AR Count could not configure the camera"); advanceUntilIdle()
            expectNoEvents()
        }
        assertFalse(v.state.value.home); assertEquals(ScanMode.Barcode, v.state.value.mode)
    }

    @Test fun tracesFollowTheSetting() = runTest {
        ar.tracing = true   // left over from the previous ViewModel
        val prefs = FakePreferences()
        val v = ScannerViewModel(cam, prefs, FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), ar)
        advanceUntilIdle(); assertFalse(ar.tracing)
        v.onAction(ScannerAction.UpdatePrefs { it.copy(arTrace = true) }); advanceUntilIdle()
        assertTrue(ar.tracing); assertTrue(v.state.value.prefs.arTrace)
        val stored = ScannerViewModel(cam, FakePreferences(Prefs(arTrace = true)), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), FakeArCount())
        advanceUntilIdle(); assertTrue((stored.ar as FakeArCount).tracing)
    }

    @Test fun newViewModelResetsSingletonCameraState() = runTest {
        val doc = io.packagex.visiondemo.fakes.FakeDocument()
        cam.front = true; doc.front = true; ar.paused = true   // left over from the previous ViewModel (Back, relaunch)
        val v = ScannerViewModel(cam, FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), ar, doc)
        advanceUntilIdle()
        assertFalse(v.state.value.frontCamera); assertFalse(cam.front); assertFalse(doc.front); assertFalse(ar.paused)
    }
}
