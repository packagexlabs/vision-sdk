package io.packagex.visiondemo.scanner

import app.cash.turbine.test
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.fakes.FakeCamera
import io.packagex.visiondemo.fakes.FakeCatalog
import io.packagex.visiondemo.fakes.FakeEntitlement
import io.packagex.visiondemo.fakes.FakeExtraction
import io.packagex.visiondemo.fakes.FakeModels
import io.packagex.visiondemo.fakes.FakePreferences
import io.packagex.visiondemo.fakes.FakeReport
import io.packagex.visiondemo.fakes.MainDispatcherRule
import io.packagex.visiondemo.fakes.code
import io.packagex.visiondemo.fakes.fakeBitmap
import io.packagex.visiondemo.model.DocType
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ScannerViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(extraction: FakeExtraction = FakeExtraction("""{"data":{"inference":{"tracking_number":"1Z"}}}""", 0)) =
        ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), extraction, FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"))

    // --- brief tests ---

    @Test fun lateResultAfterModeSwitchIsDropped() = runTest {
        val v = vm(FakeExtraction("""{"data":{"inference":{"tracking_number":"1Z"}}}""", delayMs = 5_000))
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); advanceUntilIdle()
        (v.camera as FakeCamera).emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f))
        advanceTimeBy(1_000); v.onAction(ScannerAction.SetMode(ScanMode.Barcode)); advanceUntilIdle()
        assertNull(v.state.value.result); assertEquals(Phase.Idle, v.state.value.phase)
    }

    @Test fun sheetPausesDetectionAndCloseResumes() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.OpenSheet(SheetKind.Settings)); advanceUntilIdle(); assertTrue(cam.detectionPaused)
        v.onAction(ScannerAction.DismissSheet); v.onAction(ScannerAction.SheetDismissed); advanceUntilIdle(); assertFalse(cam.detectionPaused)
    }

    @Test fun reopenLastPausesCamera() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Codes(listOf(code("123")))); advanceUntilIdle()
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle(); assertFalse(cam.detectionPaused)
        v.onAction(ScannerAction.ReopenLast); advanceUntilIdle(); assertTrue(cam.detectionPaused); assertNotNull(v.state.value.result)
    }

    @Test fun gatedModeDeniedShowsGate() = runTest {
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(false), FakeCatalog(), Secrets("k", "staging"))
        v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle(); assertTrue(v.state.value.gated)
    }

    @Test fun missingKeyIsShown() = runTest {
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("", "staging"))
        advanceUntilIdle(); assertEquals("Add STAGING_API_KEY to secrets.properties", v.state.value.missingKey)
    }

    @Test fun onDeviceWithoutModelPrompts() = runTest {
        val v = vm(); v.onAction(ScannerAction.UpdatePrefs { it.copy(processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals("On-device model not loaded", v.state.value.alert?.title)
    }

    // --- extra branches ---

    @Test fun modelPromptTextAndActionsMatchIos() = runTest {
        val models = FakeModels(mapOf((DocType.SL to ModelSize.Micro) to ModelState.Downloaded))
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), models, FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(), Secrets("k", "staging"))
        advanceUntilIdle()
        v.onAction(ScannerAction.UpdatePrefs { it.copy(processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter)
        val alert = v.state.value.alert!!
        assertEquals("Shipping label · micro is downloaded but not loaded. Load it to extract on this device.", alert.message)
        assertEquals(listOf("Load model", "Use Cloud instead", "Cancel"), alert.actions.map { it.label })
    }

    @Test fun alertActionClearsAlertAndRuns() = runTest {
        val v = vm(); v.onAction(ScannerAction.UpdatePrefs { it.copy(processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        val useCloud = v.state.value.alert!!.actions.first { it.label == "Use Cloud instead" }
        v.onAction(useCloud.action); advanceUntilIdle()
        assertNull(v.state.value.alert); assertEquals(Processing.Cloud, v.state.value.prefs.processing)
    }

    @Test fun downloadAndLoadFromPromptLoadsModel() = runTest {
        val v = vm(); v.onAction(ScannerAction.UpdatePrefs { it.copy(processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        v.onAction(v.state.value.alert!!.actions.first { it.label == "Download and load" }.action); advanceUntilIdle()
        assertEquals(ModelState.Loaded, v.state.value.models[DocType.SL to ModelSize.Micro])
    }

    @Test fun ocrCaptureShowsParsedResult() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); v.onAction(ScannerAction.Shutter)
        assertEquals(Phase.Scanning, v.state.value.phase); assertEquals(1, cam.captures)
        cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceUntilIdle()
        val r = v.state.value.result as ScanResult.Ocr
        assertEquals("1Z", r.result.primary?.value); assertEquals(Phase.Idle, v.state.value.phase); assertTrue(cam.detectionPaused)
    }

    @Test fun cancelProcessingDropsLateResult() = runTest {
        val v = vm(FakeExtraction("""{"data":{"inference":{"tracking_number":"1Z"}}}""", delayMs = 5_000)); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceTimeBy(1_000)
        assertEquals(Phase.Processing, v.state.value.phase)
        v.effects.test {
            v.onAction(ScannerAction.CancelProcessing)
            assertEquals(ScannerEffect.Toast("Cancelled"), awaitItem())
        }
        advanceUntilIdle()
        assertNull(v.state.value.result); assertEquals(Phase.Idle, v.state.value.phase)
    }

    @Test fun noCodeFailureRescansWithoutAlert() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Failure(VisionSDKException.NoBarcodeDetected)); advanceUntilIdle()
        assertNull(v.state.value.alert); assertEquals(1, cam.rescans)
    }

    @Test fun otherFailureShowsScanErrorAlert() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Failure(VisionSDKException.BlurImageDetected)); advanceUntilIdle()
        assertEquals("Image too blurry", v.state.value.alert?.title)
        assertEquals(listOf("Ok"), v.state.value.alert?.actions?.map { it.label })
    }

    @Test fun sheetSwapQueuesNextUntilDismissed() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.OpenSheet(SheetKind.Settings)); v.onAction(ScannerAction.OpenSheet(SheetKind.Models))
        assertNull(v.state.value.sheet); assertEquals(SheetKind.Models, v.state.value.pendingSheet)
        v.onAction(ScannerAction.SheetDismissed)
        assertEquals(SheetKind.Models, v.state.value.sheet); assertNull(v.state.value.pendingSheet); assertTrue(cam.detectionPaused)
    }

    @Test fun itemsSheetKeepsDetectionRunning() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.OpenSheet(SheetKind.Items)); assertFalse(cam.detectionPaused)
    }

    @Test fun entitledModeClearsGate() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval))
        assertTrue(v.state.value.gated); assertTrue(v.state.value.entitlementChecking)  // default-deny
        advanceUntilIdle()
        assertFalse(v.state.value.gated); assertFalse(v.state.value.entitlementChecking); assertEquals(CameraOwner.Scanner, cam.owner)
    }

    @Test fun arAndDocumentClaimTheirOwner() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Ar)); assertEquals(CameraOwner.Ar, cam.owner)
        v.onAction(ScannerAction.SetMode(ScanMode.DocAcq)); assertEquals(CameraOwner.Document, cam.owner)
        v.onAction(ScannerAction.SetMode(ScanMode.QR)); assertEquals(CameraOwner.Scanner, cam.owner)
    }

    @Test fun retrievalWithoutItemsPromptsForList() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter)
        assertEquals("No items to find", v.state.value.alert?.title)
        v.onAction(v.state.value.alert!!.actions.first().action)
        assertNull(v.state.value.alert); assertEquals(SheetKind.Items, v.state.value.sheet)
    }

    @Test fun retrievalSplitsFoundAndMissing() = runTest {
        val catalog = FakeCatalog(mapOf("A" to "Apple", "B" to "Banana"))
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), catalog, Secrets("k", "staging"))
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); advanceUntilIdle()
        (v.camera as FakeCamera).emit(ScanEvent.Retrieved(code("A"))); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(ScanResult.Retrieval(found = listOf("A"), missing = listOf("B")), v.state.value.result)
    }

    @Test fun pauseForcesTorchOff() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera; advanceUntilIdle()
        v.onAction(ScannerAction.ToggleTorch); assertTrue(cam.torchOn)
        cam.pausedFlow.value = true; advanceUntilIdle()
        assertTrue(v.state.value.paused); assertFalse(v.state.value.torch); assertFalse(cam.torchOn)
    }

    @Test fun resumeWhileHotToasts() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.hot = true; cam.pausedFlow.value = true; advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.Resume)
            assertEquals(ScannerEffect.Toast("Still too hot. Let the phone cool down first."), awaitItem())
        }
        assertTrue(v.state.value.paused)
    }

    @Test fun everyActionMarksUserActive() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        val before = cam.userActiveCalls
        v.onAction(ScannerAction.ToggleTorch); v.onAction(ScannerAction.CloseResult)
        assertEquals(before + 2, cam.userActiveCalls)
    }
}
