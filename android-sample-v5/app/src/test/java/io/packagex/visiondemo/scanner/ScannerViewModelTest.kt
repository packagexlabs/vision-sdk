package io.packagex.visiondemo.scanner

import app.cash.turbine.test
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.ItemLabelFeedback
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
import io.packagex.visiondemo.model.Feedback
import io.packagex.visiondemo.model.ModelSize
import io.packagex.visiondemo.model.ModelState
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.Processing
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import android.graphics.Rect
import io.packagex.visionsdk.core.pricetag.PriceTagData
import io.packagex.visionsdk.exceptions.VisionSDKException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    @Test fun retrievalReportsCodesInViewFlaggedByList() = runTest {
        val catalog = FakeCatalog(items = listOf("A", "B"))
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), catalog, Secrets("k", "staging"))
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); runCurrent()
        val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Retrieved(code("A"))); cam.emit(ScanEvent.Retrieved(code("Z"))); runCurrent()
        assertEquals(listOf("A", "Z"), v.state.value.codesInView)
        v.onAction(ScannerAction.Shutter); advanceTimeBy(400)
        assertEquals(ScanResult.Retrieval(listOf("A" to true, "Z" to false)), v.state.value.result)
    }

    @Test fun codesLeaveViewAfterASecondWithoutReports() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); runCurrent()
        (v.camera as FakeCamera).emit(ScanEvent.Retrieved(code("A"))); runCurrent()
        advanceTimeBy(900); assertEquals(listOf("A"), v.state.value.codesInView)
        (v.camera as FakeCamera).emit(ScanEvent.Retrieved(code("A"))); runCurrent()
        advanceTimeBy(900); assertEquals(listOf("A"), v.state.value.codesInView)   // refreshed
        advanceTimeBy(200); assertEquals(emptyList<String>(), v.state.value.codesInView)
    }

    // --- fix round 1 ---

    @Test fun wildCardSkipsModelPrompt() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.UpdatePrefs { it.copy(wildCard = true, processing = Processing.Device) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertNull(v.state.value.alert); assertEquals(1, cam.captures)
    }

    @Test fun wildCardResultUsesRoutedType() = runTest {
        val v = vm(FakeExtraction("""{"data":{"inference":{"item_name":"Bolt"}}}""", routedType = DocType.IL)); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.UpdatePrefs { it.copy(wildCard = true) }); v.onAction(ScannerAction.SetMode(ScanMode.Ocr))
        cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceUntilIdle()
        val r = v.state.value.result as ScanResult.Ocr
        assertEquals(DocType.IL, r.result.docType); assertEquals("Item label (wild card)", r.title)
        assertTrue(r.subtitle, r.subtitle.startsWith("On-device · large · "))
    }

    @Test fun cloudResultTitleAndSubtitle() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceUntilIdle()
        val r = v.state.value.result as ScanResult.Ocr
        assertEquals("Shipping label", r.title); assertTrue(r.subtitle, r.subtitle.startsWith("Cloud · "))
    }

    @Test fun cloudFailureOffersOnDevice() = runTest {
        val x = FakeExtraction("{}", error = IllegalStateException("boom")); val v = vm(x); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceUntilIdle()
        val alert = v.state.value.alert!!
        assertEquals("Cloud request failed", alert.title); assertEquals("boom", alert.message)
        assertEquals(listOf("Try again", "Use On-device", "Cancel"), alert.actions.map { it.label })
        v.onAction(ScannerAction.Retry); advanceUntilIdle()
        assertEquals(2, x.calls)
    }

    @Test fun priceTagsCollectUniqueAndShowOnShutter() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle()
        cam.emit(ScanEvent.PriceTag(PriceTagData("14438-01", "$28.99", Rect())))
        cam.emit(ScanEvent.PriceTag(PriceTagData("14438", "$28.99", Rect())))
        cam.emit(ScanEvent.PriceTag(PriceTagData("999", "$1.00", Rect()))); advanceUntilIdle()
        assertNull(v.state.value.result)   // no drawer on the event
        assertEquals(2, v.state.value.tags.size)
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        val tags = (v.state.value.result as ScanResult.Price).tags
        assertEquals(listOf("14438" to true, "999" to false), tags.map { it.sku to it.valid })
        assertEquals(listOf("$28.99", "Not Found"), tags.map { it.expected })
        v.onAction(ScannerAction.CloseResult); assertTrue(v.state.value.tags.isEmpty())
    }

    @Test fun priceShutterWithNoTagsShowsEmptyDrawer() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(ScanResult.Price(emptyList()), v.state.value.result)
    }

    @Test fun shutterCaptureWithNoCodeExplains() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.Shutter)
        cam.emit(ScanEvent.Failure(VisionSDKException.NoBarcodeDetected)); runCurrent()
        val alert = v.state.value.alert!!
        assertEquals("No Barcode Found", alert.title)
        assertEquals("Move closer so the code fills the frame, then try again.", alert.message)
        assertEquals(listOf("Try again", "Turn on torch and retry", "Cancel"), alert.actions.map { it.label })
        v.onAction(alert.actions[1].action)
        assertTrue(v.state.value.torch); assertTrue(cam.torchOn); assertNull(v.state.value.alert)
    }

    @Test fun shutterRetryCapturesAgain() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.QR)); v.onAction(ScannerAction.Shutter)
        cam.emit(ScanEvent.Failure(VisionSDKException.NoQRCodeDetected)); runCurrent()
        assertEquals("No QR Code Found", v.state.value.alert?.title)
        v.onAction(ScannerAction.Retry)
        assertEquals(2, cam.captures); assertEquals(Phase.Scanning, v.state.value.phase)
    }

    @Test fun feedbackSuccessUntilPresented() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Codes(listOf(code("1")))); runCurrent()
        assertEquals(Feedback.Success, v.state.value.feedback); assertNull(v.state.value.result)
        advanceTimeBy(400); assertNull(v.state.value.feedback); assertNotNull(v.state.value.result)
    }

    @Test fun feedbackErrorClearsAfter1200ms() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Failure(VisionSDKException.BlurImageDetected)); runCurrent()
        assertEquals(Feedback.Error, v.state.value.feedback)
        advanceTimeBy(1_100); assertEquals(Feedback.Error, v.state.value.feedback)
        advanceTimeBy(200); assertNull(v.state.value.feedback)
    }

    @Test fun passedRecheckRescans() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle()
        val before = cam.rescans
        cam.emit(ScanEvent.Failure(VisionSDKException.PriceTagNotEligible())); advanceUntilIdle()
        assertFalse(v.state.value.gated); assertEquals(before + 1, cam.rescans)
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
    // --- UI action surface ---

    @Test fun itemListAddRemoveClearPersists() = runTest {
        val catalog = FakeCatalog()
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), catalog, Secrets("k", "staging"))
        v.onAction(ScannerAction.AddItem("A")); v.onAction(ScannerAction.AddItem("B")); advanceUntilIdle()
        assertEquals(listOf("A", "B"), v.state.value.items); assertEquals(listOf("A", "B"), catalog.items.value)
        v.effects.test {
            v.onAction(ScannerAction.AddItem("A"))
            assertEquals(ScannerEffect.Toast("Code already in list"), awaitItem())
        }
        v.onAction(ScannerAction.RemoveItem("A")); advanceUntilIdle(); assertEquals(listOf("B"), catalog.items.value)
        v.onAction(ScannerAction.ClearItems); advanceUntilIdle(); assertEquals(emptyList<String>(), catalog.items.value)
    }

    @Test fun addItemsInViewAddsNewCodesWithIosToasts() = runTest {
        val v = ScannerViewModel(FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(items = listOf("A")), Secrets("k", "staging"))
        v.onAction(ScannerAction.SetMode(ScanMode.Retrieval)); runCurrent()
        v.effects.test {
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Point the camera at a code, then tap Add Item"), awaitItem())
            val cam = v.camera as FakeCamera
            cam.emit(ScanEvent.Retrieved(code("A"))); cam.emit(ScanEvent.Retrieved(code("B"))); runCurrent()
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Scanned and added B"), awaitItem())
            v.onAction(ScannerAction.AddItemsInView)
            assertEquals(ScannerEffect.Toast("Code already in list"), awaitItem())
        }
        assertEquals(listOf("A", "B"), v.state.value.items)
    }

    @Test fun toggleExpandedResetsOnPresentAndClose() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Codes(listOf(code("1")))); advanceUntilIdle()
        v.onAction(ScannerAction.ToggleExpanded); assertTrue(v.state.value.resultExpanded)
        v.onAction(ScannerAction.ToggleExpanded); v.onAction(ScannerAction.ToggleExpanded); assertTrue(v.state.value.resultExpanded)
        v.onAction(ScannerAction.CloseResult); assertFalse(v.state.value.resultExpanded)
        v.onAction(ScannerAction.ToggleExpanded); v.onAction(ScannerAction.ReopenLast); assertFalse(v.state.value.resultExpanded)
    }

    @Test fun scanNextClosesAndRescans() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        cam.emit(ScanEvent.Codes(listOf(code("1")))); advanceUntilIdle()
        val before = cam.rescans
        v.onAction(ScannerAction.ScanNext)
        assertNull(v.state.value.result); assertFalse(cam.detectionPaused); assertEquals(before + 1, cam.rescans)
    }

    @Test fun copyEmitsClipboardEffectAndToast() = runTest {
        val v = vm()
        v.effects.test {
            v.onAction(ScannerAction.Copy("Tracking No.", "1Z9"))
            assertEquals(ScannerEffect.Copy("1Z9"), awaitItem())
            assertEquals(ScannerEffect.Toast("Copied Tracking No."), awaitItem())
        }
    }

    @Test fun sendFeedbackSubmitsShownResultAndToasts() = runTest {
        val v = vm(); val cam = v.camera as FakeCamera
        var sent: Map<String, ItemLabelFeedback.Entry>? = null
        v.submitFeedback = { _, _, entries, _ -> sent = entries; "Feedback sent · 1 entities" }
        v.onAction(ScannerAction.SetMode(ScanMode.Ocr)); cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceUntilIdle()
        val entries = mapOf("f" to ItemLabelFeedback.Entry("x", true))
        v.effects.test {
            assertEquals(ScannerEffect.Haptic, awaitItem())   // from the result's success flash
            v.onAction(ScannerAction.SendFeedback(entries))
            assertEquals(ScannerEffect.Toast("Feedback sent · 1 entities"), awaitItem())
        }
        assertEquals(entries, sent)
    }

    @Test fun zoomAppliesAndResetsOnModeSwitch() = runTest {
        val v = vm()
        v.onAction(ScannerAction.Zoom(2f)); assertEquals(2f, v.state.value.zoom)
        v.onAction(ScannerAction.SetMode(ScanMode.QR)); assertEquals(1f, v.state.value.zoom)
    }

    @Test fun clearTagsEmptiesPriceList() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Price)); advanceUntilIdle()
        (v.camera as FakeCamera).emit(ScanEvent.PriceTag(PriceTagData("14438", "$28.99", Rect()))); advanceUntilIdle()
        v.onAction(ScannerAction.ClearTags); assertTrue(v.state.value.tags.isEmpty())
    }
}
