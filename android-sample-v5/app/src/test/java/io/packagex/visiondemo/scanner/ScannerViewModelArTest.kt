package io.packagex.visiondemo.scanner

import app.cash.turbine.test
import io.packagex.arcount.Bracket
import io.packagex.arcount.Command
import io.packagex.arcount.CountView
import io.packagex.arcount.Prompt
import io.packagex.arcount.SectionResult
import io.packagex.arcount.SectionState
import io.packagex.arcount.SectionStatus
import io.packagex.visiondemo.ar.AppStream
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
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AR Count in the ViewModel, against a fake session controller. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelArTest {
    @get:Rule val main = MainDispatcherRule()

    private val cam = FakeCamera()
    private var ownerAtDetach: CameraOwner? = null
    private val ar = FakeArCount(onDetach = { ownerAtDetach = cam.owner })

    private fun vm() = ScannerViewModel(
        cam, FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), ar,
    ).also { it.onAction(ScannerAction.PermissionResult(true)) }

    private val section = SectionResult(
        sectionId = "s1", labelPayload = "LBL-1", gtins = setOf("04006381333931"), status = SectionStatus.COMPLETE,
        counted = 12, manualAdded = 1, manualRemoved = 0, tentative = 0, ambiguous = 0, countLow = 13, countHigh = 13,
        breaks = emptyList(), durationMs = 42_000,
    )
    private val counting = CountView(
        SectionState.COUNTING, Prompt.SLOW_DOWN, emptyList(), emptyList(),
        Bracket(0.0, 0.0, true, "04006381333931", 13, 13, false), listOf(section),
    )

    @Test fun theCountAndTheStreamFollowTheSession() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; ar.stream.value = AppStream.UHD; advanceUntilIdle()
        assertEquals(counting, v.state.value.arCount); assertEquals(AppStream.UHD, v.state.value.arStream)
    }

    @Test fun shutterShowsTheClosedSectionsAsTheResult() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(ScanResult.Ar(listOf(section)), v.state.value.result)
    }

    @Test fun shutterWithoutAClosedSectionToasts() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting.copy(closed = emptyList()); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.Shutter)
            assertEquals(ScannerEffect.Toast("No section closed yet. Count a shelf section, then tap Finish."), awaitItem())
        }
        assertNull(v.state.value.result)
    }

    @Test fun commandsReachTheCounterOnlyFromTheLiveArCamera() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        v.onAction(ScannerAction.ArCommand(Command.AddUnit)); v.onAction(ScannerAction.ArCommand(Command.Finish))
        assertEquals(listOf(Command.AddUnit, Command.Finish), ar.commands)
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()   // the result covers the camera
        v.onAction(ScannerAction.ArCommand(Command.Restart))
        v.onAction(ScannerAction.CloseResult); v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        v.onAction(ScannerAction.ArCommand(Command.Restart))
        assertEquals(listOf(Command.AddUnit, Command.Finish), ar.commands)
    }

    @Test fun cameraPauseAndResumeKeepTheCount() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        cam.pausedFlow.value = true; advanceUntilIdle()   // heat / idle / background
        assertTrue(ar.paused); assertTrue(v.state.value.paused)
        v.onAction(ScannerAction.Resume); advanceUntilIdle()
        assertFalse(ar.paused)
        assertEquals(0, ar.resets); assertEquals(counting, v.state.value.arCount)
    }

    // A worker counting with the trigger touches nothing: the idle timeout must not pause the session under them.
    @Test fun noIdlePauseWhileArCountRuns() = runTest {
        val v = vm(); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        assertEquals(true, cam.busy.last())
        ar.count.value = counting; advanceUntilIdle()
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

    @Test fun resultPausesArCloseResumesAndNewScanStartsAFreshCount() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertTrue(ar.paused)
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertFalse(ar.paused); assertEquals(0, ar.resets); assertEquals(counting, v.state.value.arCount)
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        v.onAction(ScannerAction.ScanNext); advanceUntilIdle()   // "New Scan"
        assertFalse(ar.paused); assertEquals(1, ar.resets); assertEquals(CountView.EMPTY, v.state.value.arCount)
    }

    @Test fun pausedCameraKeepsArPausedWhenAResultCloses() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.count.value = counting; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        cam.pausedFlow.value = true; advanceUntilIdle()
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertTrue(ar.paused)
    }

    @Test fun entryClaimsArAndLeavingDetachesBeforeTheScannerClaims() = runTest {
        val v = vm(); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        assertEquals(CameraOwner.Ar, cam.owner); assertFalse(ar.paused)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertEquals(1, ar.detaches); assertEquals(CameraOwner.Ar, ownerAtDetach); assertEquals(CameraOwner.Scanner, cam.owner)
    }

    @Test fun firstEntryAsksForTheInstallAndStaysUntilInstalled() = runTest {
        ar.installed = false
        val v = vm(); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.SetMode(ScanMode.Ar))
            assertEquals(ScannerEffect.InstallArCore, awaitItem())
            assertEquals(ScanMode.Barcode, v.state.value.mode)
            v.onAction(ScannerAction.ArInstallResult(ArInstall.Unsupported))
            assertEquals(ScannerEffect.Toast("AR isn't supported on this device"), awaitItem())
            assertEquals(ScanMode.Barcode, v.state.value.mode)
            v.onAction(ScannerAction.ArInstallResult(ArInstall.Installed))
            assertEquals(ScanMode.Ar, v.state.value.mode)
            v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); v.onAction(ScannerAction.SetMode(ScanMode.Ar))
            assertEquals(ScanMode.Ar, v.state.value.mode)   // no second install prompt
            expectNoEvents()
        }
    }

    @Test fun sessionErrorsToast() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        v.effects.test {
            ar.fail("Camera not available")
            assertEquals(ScannerEffect.Toast("Camera not available"), awaitItem())
        }
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
