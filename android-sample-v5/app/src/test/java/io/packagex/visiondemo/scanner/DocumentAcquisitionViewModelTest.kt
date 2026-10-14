package io.packagex.visiondemo.scanner

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.document.CaptureStart
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
    private val v by lazy { ScannerViewModel(cam, FakePreferences(), FakeModels(), FakeExtraction("{}"), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"), document = doc) }

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

    @Test fun leavingReleasesTheDocumentCameraBeforeTheNextOwnerClaims() = runTest {
        doc.ownerProbe = { cam.owner }
        enterDocAcq(); assertEquals(emptyList<CameraOwner?>(), doc.releasedUnder)
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()   // AR Item Count: the AR session's camera
        assertEquals(listOf<CameraOwner?>(CameraOwner.Document), doc.releasedUnder); assertEquals(CameraOwner.Ar, cam.owner)
        v.onAction(ScannerAction.SetMode(ScanMode.DocAcq)); advanceUntilIdle(); assertEquals(CameraOwner.Document, cam.owner)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertEquals(listOf<CameraOwner?>(CameraOwner.Document, CameraOwner.Document), doc.releasedUnder); assertEquals(CameraOwner.Scanner, cam.owner)
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
        enterDocAcq(); doc.captureStart = CaptureStart.NoPage
        v.effects.test {
            v.onAction(ScannerAction.Shutter); advanceUntilIdle()
            assertEquals(ScannerEffect.Toast("Fit the whole page in view"), awaitItem())
        }
        assertEquals(Phase.Idle, v.state.value.phase)
    }

    @Test fun shutterDuringAnAutoCaptureIsSilent() = runTest {
        enterDocAcq(); doc.captureStart = CaptureStart.Busy
        v.effects.test {
            v.onAction(ScannerAction.Shutter); advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(Phase.Idle, v.state.value.phase)
        assertEquals(listOf(1), capturePage())   // the auto capture's page still arrives
    }

    @Test fun torchFlipAndFocusDriveTheDocumentCamera() = runTest {
        enterDocAcq()
        v.onAction(ScannerAction.ToggleTorch); advanceUntilIdle(); assertTrue(doc.torchOn); assertFalse(cam.torchOn)
        v.onAction(ScannerAction.FlipCamera); advanceUntilIdle()
        assertTrue(doc.front); assertFalse(cam.front); assertFalse(doc.torchOn); assertTrue(v.state.value.frontCamera)
        v.onAction(ScannerAction.Focus(0.25f, 0.5f)); advanceUntilIdle()
        assertEquals(0.25f to 0.5f, doc.focusPoint); assertNull(cam.focusPoint); assertEquals(0.25f, v.state.value.focus?.x)
        v.onAction(ScannerAction.ToggleTorch); v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertFalse(doc.torchOn); assertFalse(v.state.value.frontCamera)   // the SDK camera keeps its own (back) lens
    }

    @Test fun exportUsesTheShownPages() = runTest {
        enterDocAcq(); capturePage()
        v.onAction(ScannerAction.RescanDocument(dropLast = false)); capturePage()
        v.onAction(ScannerAction.RescanDocument(dropLast = true)); advanceUntilIdle()
        v.onAction(ScannerAction.ReopenLast); advanceUntilIdle()
        v.onAction(ScannerAction.ExportPdf(enhanced = true)); advanceUntilIdle()
        assertEquals(listOf(1, 2), doc.exportedPages.map { it.index })
        v.onAction(ScannerAction.CloseResult)
        v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); v.onAction(ScannerAction.SetMode(ScanMode.DocAcq)); advanceUntilIdle()
        v.onAction(ScannerAction.ReopenLast); advanceUntilIdle()
        v.onAction(ScannerAction.ExportPdf(enhanced = true)); advanceUntilIdle()
        assertEquals(2, doc.exports); assertEquals(listOf(1, 2), doc.exportedPages.map { it.index })
    }

    @Test fun oneExportAtATime() = runTest {
        enterDocAcq(); capturePage(); doc.exportDelayMs = 1_000
        v.onAction(ScannerAction.ExportPdf(enhanced = true)); v.onAction(ScannerAction.ExportPdf(enhanced = true)); advanceUntilIdle()
        assertEquals(1, doc.exports)
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
