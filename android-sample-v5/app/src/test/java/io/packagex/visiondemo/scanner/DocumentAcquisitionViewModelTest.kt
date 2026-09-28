package io.packagex.visiondemo.scanner

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.fakes.FakeCamera
import io.packagex.visiondemo.fakes.FakeCatalog
import io.packagex.visiondemo.fakes.FakeDocument
import io.packagex.visiondemo.fakes.FakeEntitlement
import io.packagex.visiondemo.fakes.FakeExtraction
import io.packagex.visiondemo.fakes.FakeModels
import io.packagex.visiondemo.fakes.FakePreferences
import io.packagex.visiondemo.fakes.FakeReport
import io.packagex.visiondemo.fakes.MainDispatcherRule
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentAcquisitionViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val doc = FakeDocument()
    private val cam = FakeCamera()
    private val v by lazy { ScannerViewModel(cam, FakePreferences(), FakeModels(), FakeExtraction("{}"), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), doc) }

    private fun TestScope.enterDocAcq() {
        v.onAction(ScannerAction.PermissionResult(true)); v.onAction(ScannerAction.SetMode(ScanMode.DocAcq)); advanceUntilIdle()
    }

    /** A captured page reaches the drawer; returns the page indexes shown. */
    private fun TestScope.capturePage(): List<Int> {
        doc.still(); advanceUntilIdle()
        return (v.state.value.result as ScanResult.Document).pages.map { it.index }
    }

    /** The next effect that isn't the capture's haptic. */
    private suspend fun ReceiveTurbine<ScannerEffect>.next(): ScannerEffect {
        var e = awaitItem()
        while (e == ScannerEffect.Haptic) e = awaitItem()
        return e
    }

    @Test fun enteringClaimsTheCameraAndLeavingHandsItBack() = runTest {
        enterDocAcq(); assertEquals(CameraOwner.Document, cam.owner); assertTrue(doc.detecting)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertEquals(CameraOwner.Scanner, cam.owner); assertFalse(doc.detecting)
    }

    @Test fun autoFollowsTheCapturePill() = runTest {
        enterDocAcq(); assertFalse(doc.auto)
        v.onAction(ScannerAction.ToggleAuto); advanceUntilIdle(); assertTrue(doc.auto)
        v.onAction(ScannerAction.ToggleAuto); advanceUntilIdle(); assertFalse(doc.auto)
    }

    @Test fun manualShutterCapturesAndShowsThePage() = runTest {
        enterDocAcq()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(1, doc.captures); assertEquals(Phase.Scanning, v.state.value.phase); assertFalse(doc.detecting)
        assertEquals(listOf(1), capturePage())
        assertEquals(Phase.Idle, v.state.value.phase); assertFalse(doc.detecting)   // nothing to detect under the drawer
    }

    @Test fun shutterWithoutAPageToasts() = runTest {
        enterDocAcq(); doc.pageInView = false
        v.effects.test {
            v.onAction(ScannerAction.Shutter); advanceUntilIdle()
            assertEquals(ScannerEffect.Toast("Fit the page inside the frame"), awaitItem())
        }
        assertEquals(Phase.Idle, v.state.value.phase)
    }

    @Test fun failedCaptureReturnsToIdle() = runTest {
        enterDocAcq(); v.onAction(ScannerAction.Shutter); doc.still(null); advanceUntilIdle()
        assertEquals(Phase.Idle, v.state.value.phase); assertNull(v.state.value.result); assertTrue(doc.detecting)
    }

    @Test fun addPageKeepsPagesAndRetakeDropsTheLast() = runTest {
        enterDocAcq()
        assertEquals(listOf(1), capturePage())
        v.onAction(ScannerAction.RescanDocument(dropLast = false)); advanceUntilIdle()
        assertNull(v.state.value.result); assertTrue(doc.detecting)
        assertEquals(listOf(1, 2), capturePage())
        v.onAction(ScannerAction.RescanDocument(dropLast = true)); advanceUntilIdle()
        assertEquals(listOf(1, 3), capturePage())
    }

    @Test fun exportSharesAndTheNextCaptureStartsANewDocument() = runTest {
        enterDocAcq(); capturePage()
        v.effects.test {
            v.onAction(ScannerAction.ExportPdf(enhanced = true)); advanceUntilIdle()
            assertEquals(ScannerEffect.Toast("Preparing searchable PDF…"), next())
            assertTrue(next() is ScannerEffect.SharePdf)
        }
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertEquals(listOf(2), capturePage())
    }

    @Test fun retakeAfterExportKeepsThePages() = runTest {
        enterDocAcq(); capturePage()
        v.onAction(ScannerAction.RescanDocument(dropLast = false)); capturePage()
        v.onAction(ScannerAction.ExportPdf(enhanced = false)); advanceUntilIdle()
        v.onAction(ScannerAction.RescanDocument(dropLast = true)); advanceUntilIdle()
        assertEquals(listOf(1, 3), capturePage())
    }

    @Test fun failedExportToastsAndKeepsTheDocument() = runTest {
        enterDocAcq(); capturePage(); doc.exportFails = true
        v.effects.test {
            v.onAction(ScannerAction.ExportPdf(enhanced = true)); advanceUntilIdle()
            assertEquals(ScannerEffect.Toast("Preparing searchable PDF…"), next())
            assertEquals(ScannerEffect.Toast("PDF export failed"), next())
        }
        v.onAction(ScannerAction.RescanDocument(dropLast = false)); advanceUntilIdle()
        assertEquals(listOf(1, 2), capturePage())
    }

    @Test fun pagesAreClearedOnModeSwitch() = runTest {
        enterDocAcq(); capturePage()
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); v.onAction(ScannerAction.SetMode(ScanMode.DocAcq)); advanceUntilIdle()
        assertEquals(listOf(2), capturePage())
    }

    @Test fun lateProcessingAfterModeSwitchIsDropped() = runTest {
        enterDocAcq(); doc.still()
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertNull(v.state.value.result); assertEquals(Phase.Idle, v.state.value.phase)
    }

    @Test fun detectionStopsUnderSheetsAndWhilePaused() = runTest {
        enterDocAcq()
        v.onAction(ScannerAction.OpenSheet(SheetKind.Settings)); advanceUntilIdle(); assertFalse(doc.detecting)
        v.onAction(ScannerAction.DismissSheet); v.onAction(ScannerAction.SheetDismissed); advanceUntilIdle(); assertTrue(doc.detecting)
        cam.pausedFlow.value = true; advanceUntilIdle(); assertFalse(doc.detecting)
        v.onAction(ScannerAction.Resume); advanceUntilIdle(); assertTrue(doc.detecting)
    }

    @Test fun zoomGoesToTheDocumentCamera() = runTest {
        enterDocAcq(); v.onAction(ScannerAction.Zoom(2f)); advanceUntilIdle()
        assertEquals(2f, doc.zoomRatio); assertEquals(1f, cam.zoomRatio)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertEquals(1f, v.state.value.zoom)
    }

    @Test fun livePageDrivesTheHint() = runTest {
        enterDocAcq()
        doc.seePage(true); advanceUntilIdle(); assertTrue(v.state.value.codeInFrame)
        doc.seePage(false); advanceUntilIdle(); assertFalse(v.state.value.codeInFrame)
    }
}
