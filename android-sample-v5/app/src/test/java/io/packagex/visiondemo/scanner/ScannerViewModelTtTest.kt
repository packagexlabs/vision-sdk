package io.packagex.visiondemo.scanner

import io.packagex.texttemplates.sdk.PXGuidance
import io.packagex.texttemplates.sdk.PXScanEvent
import io.packagex.texttemplates.sdk.PXTemplateInfo
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.camera.ScanEvent
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.data.TtPath
import io.packagex.visiondemo.data.TtState
import io.packagex.visiondemo.fakes.FakeCamera
import io.packagex.visiondemo.fakes.FakeCatalog
import io.packagex.visiondemo.fakes.FakeEntitlement
import io.packagex.visiondemo.fakes.FakeExtraction
import io.packagex.visiondemo.fakes.FakeModels
import io.packagex.visiondemo.fakes.FakePreferences
import io.packagex.visiondemo.fakes.FakeReport
import io.packagex.visiondemo.fakes.FakeTextTemplates
import io.packagex.visiondemo.fakes.MainDispatcherRule
import io.packagex.visiondemo.fakes.fakeBitmap
import io.packagex.visiondemo.fakes.ttPrediction
import io.packagex.visiondemo.model.Phase
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.model.ScanResult
import io.packagex.visiondemo.model.SheetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Text Templates mode (iOS DemoModel `.tt`): setup gate, One-Shot capture, Stream camera hand-off and events. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelTtTest {
    @get:Rule val main = MainDispatcherRule()

    private val ready = TtState(email = "me@example.com", cached = listOf(PXTemplateInfo("t1", "Shipping label"), PXTemplateInfo("t2", "Pallet")), loadedIds = listOf("t1", "t2"))

    private fun vm(tt: FakeTextTemplates) = ScannerViewModel(
        FakeCamera(), FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), FakeCatalog(),
        Secrets("k", "staging"), tt = tt,
    )

    @Test fun enteringWithoutAnEmailOpensSetup() = runTest {
        val v = vm(FakeTextTemplates())
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        assertEquals(SheetKind.TtSetup, v.state.value.sheet)
        v.onAction(ScannerAction.TtSetEmail("Me@Example.com ", fromSetup = true)); advanceUntilIdle()
        assertEquals("me@example.com", v.state.value.tt.email)
        assertNull(v.state.value.sheet)
    }

    @Test fun shutterWithNothingLoadedOffersToLoad() = runTest {
        val tt = FakeTextTemplates(ready.copy(loadedIds = emptyList()))
        val v = vm(tt)
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        val alert = v.state.value.alert!!
        assertEquals("No templates loaded", alert.title)
        v.onAction(alert.actions.first().action); advanceUntilIdle()
        assertNull(v.state.value.alert)
        assertEquals(listOf("t1", "t2"), v.state.value.tt.loadedIds)
        assertEquals(0, (v.camera as FakeCamera).captures)
    }

    @Test fun oneShotCapturesAStillAndShowsThePrediction() = runTest {
        val tt = FakeTextTemplates(ready)
        val v = vm(tt); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        assertEquals(CameraOwner.Scanner, cam.owner)
        v.onAction(ScannerAction.Shutter); runCurrent()
        assertEquals(1, cam.captures); assertEquals(Phase.Scanning, v.state.value.phase)
        tt.delayMs = 2_000
        val photo = fakeBitmap()
        cam.emit(ScanEvent.Captured(photo, emptyList(), 1f)); runCurrent()
        val pending = v.state.value.result as ScanResult.Pending   // the result screen opens on the photo at once
        assertSame(photo, pending.image); assertEquals("Predicting…", pending.subtitle); assertEquals(Phase.Processing, v.state.value.phase)
        advanceUntilIdle()
        val r = v.state.value.result as ScanResult.TextTemplate
        assertEquals("Shipping label", r.templateName); assertEquals(TtPath.OneShot, r.path); assertEquals(1, tt.predictions)
        v.onAction(ScannerAction.TtRepredict("t2")); advanceUntilIdle()
        assertEquals("Other", (v.state.value.result as ScanResult.TextTemplate).templateName)
    }

    @Test fun streamHandsTheCameraToPxScannerViewAndBack() = runTest {
        val v = vm(FakeTextTemplates(ready)); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        v.onAction(ScannerAction.TtSetPath(TtPath.Stream)); runCurrent()
        assertEquals(CameraOwner.TextTemplates, cam.owner); assertFalse(cam.running)
        assertFalse(v.state.value.ttCameraReady)   // not until the SDK camera has let go
        advanceTimeBy(301); runCurrent()
        assertTrue(v.state.value.ttCameraReady)
        v.onAction(ScannerAction.Shutter); runCurrent()
        assertEquals(0, cam.captures)   // Stream captures on its own

        v.onAction(ScannerAction.TtSetPath(TtPath.OneShot)); runCurrent()
        assertFalse(v.state.value.ttCameraReady)                     // PXScannerView unmounts first ...
        assertEquals(CameraOwner.TextTemplates, cam.owner)
        advanceTimeBy(301); runCurrent()
        assertEquals(CameraOwner.Scanner, cam.owner); assertTrue(cam.running)   // ... then the SDK camera starts
    }

    @Test fun streamEventsDriveTheHintAndShowOneResult() = runTest {
        val v = vm(FakeTextTemplates(ready.copy(path = TtPath.Stream)))
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        v.onTtEvent(PXScanEvent.Guidance(PXGuidance.Stabilizing(2, 4))); advanceUntilIdle()
        assertEquals("Hold still · 2/4", v.state.value.ttGuidance)
        v.onTtEvent(PXScanEvent.Prediction(ttPrediction("First"))); advanceUntilIdle()
        v.onTtEvent(PXScanEvent.Prediction(ttPrediction("Second"))); advanceUntilIdle()
        val r = v.state.value.result as ScanResult.TextTemplate
        assertEquals("First", r.templateName); assertEquals(TtPath.Stream, r.path)
        v.onAction(ScannerAction.GoHome); advanceUntilIdle()
        v.onTtEvent(PXScanEvent.Prediction(ttPrediction("Late"))); advanceUntilIdle()
        assertNull(v.state.value.result)
    }

    @Test fun cancelWhilePredictingClosesTheLoadingScreen() = runTest {
        val tt = FakeTextTemplates(ready).apply { delayMs = 5_000 }
        val v = vm(tt); val cam = v.camera as FakeCamera
        v.onAction(ScannerAction.SetMode(ScanMode.TextTemplates)); advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); runCurrent()
        cam.emit(ScanEvent.Captured(fakeBitmap(), emptyList(), 1f)); advanceTimeBy(1_000)
        assertTrue(v.state.value.result is ScanResult.Pending)
        v.onAction(ScannerAction.CancelProcessing); advanceUntilIdle()
        assertNull(v.state.value.result); assertEquals(Phase.Idle, v.state.value.phase)
    }
}
