package io.packagex.visiondemo.scanner

import app.cash.turbine.test
import io.packagex.visiondemo.ar.ArRow
import io.packagex.visiondemo.ar.PayloadCount
import io.packagex.visiondemo.camera.CameraOwner
import io.packagex.visiondemo.data.Secrets
import io.packagex.visiondemo.fakes.FakeAr
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
import io.packagex.visiondemo.model.SheetKind
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

/** AR Barcode in the ViewModel (iOS DemoModel's AR parts), against a fake AR controller. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerViewModelArTest {
    @get:Rule val main = MainDispatcherRule()

    private val cam = FakeCamera()
    private var ownerAtDetach: CameraOwner? = null
    private val ar = FakeAr(onDetach = { ownerAtDetach = cam.owner })
    private val catalog = FakeCatalog(initial = mapOf("A1" to "Apple"))

    private fun vm() = ScannerViewModel(
        cam, FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), catalog, Secrets("k", "staging"), ar,
    ).also { it.onAction(ScannerAction.PermissionResult(true)) }

    private val twoCodes = listOf(PayloadCount("A1", "EAN-13", 1), PayloadCount("B2", "QR", 1))

    @Test fun shutterShowsPayloadRowsWithCatalogNames() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.counts.value = twoCodes; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertEquals(ScanResult.Ar(listOf(ArRow("A1", "EAN-13", 1, "Apple"), ArRow("B2", "QR", 1, null))), v.state.value.result)
    }

    @Test fun shutterWithoutMarkersToasts() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        v.effects.test {
            v.onAction(ScannerAction.Shutter)
            assertEquals(ScannerEffect.Toast("No markers yet. Point at barcodes first."), awaitItem())
        }
        assertNull(v.state.value.result)
    }

    @Test fun namingItemsWhileArRunsUnderTheItemsSheet() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.counts.value = twoCodes; advanceUntilIdle()
        val clears = ar.clears
        v.onAction(ScannerAction.OpenSheet(SheetKind.ArItems)); advanceUntilIdle()
        v.onAction(ScannerAction.NameArItem(" B2 ", " Banana ")); advanceUntilIdle()
        assertEquals(mapOf("B2" to "Banana", "A1" to "Apple"), v.state.value.itemNames)   // newest first (iOS)
        assertEquals(v.state.value.itemNames, ar.catalog)                                  // the renderer sees it at once
        assertEquals("Banana", catalog.names.value["B2"])
        v.onAction(ScannerAction.NameArItem("C3", "  ")); advanceUntilIdle()             // blank name ignored
        assertFalse("C3" in v.state.value.itemNames)
        v.onAction(ScannerAction.RemoveArItem("A1")); advanceUntilIdle()
        assertEquals(mapOf("B2" to "Banana"), ar.catalog)
        assertFalse(ar.paused); assertEquals(clears, ar.clears); assertEquals(twoCodes, v.state.value.arCounts)
    }

    @Test fun cameraPauseAndResumeKeepCounts() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.counts.value = twoCodes; advanceUntilIdle()
        val clears = ar.clears
        cam.pausedFlow.value = true; advanceUntilIdle()   // heat / idle / background
        assertTrue(ar.paused); assertTrue(v.state.value.paused)
        v.onAction(ScannerAction.Resume); advanceUntilIdle()
        assertFalse(ar.paused)
        assertEquals(clears, ar.clears); assertEquals(twoCodes, v.state.value.arCounts)
    }

    @Test fun resultPausesArCloseResumesAndNewScanClears() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.counts.value = twoCodes; advanceUntilIdle()
        val clears = ar.clears
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        assertTrue(ar.paused)
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertFalse(ar.paused); assertEquals(clears, ar.clears); assertEquals(twoCodes, v.state.value.arCounts)
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        v.onAction(ScannerAction.ScanNext); advanceUntilIdle()   // "New Scan"
        assertFalse(ar.paused); assertEquals(clears + 1, ar.clears); assertEquals(emptyList<PayloadCount>(), v.state.value.arCounts)
    }

    @Test fun pausedCameraKeepsArPausedWhenAResultCloses() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        ar.counts.value = twoCodes; advanceUntilIdle()
        v.onAction(ScannerAction.Shutter); advanceUntilIdle()
        cam.pausedFlow.value = true; advanceUntilIdle()
        v.onAction(ScannerAction.CloseResult); advanceUntilIdle()
        assertTrue(ar.paused)
    }

    @Test fun entryClaimsArAndLeavingDetachesBeforeTheScannerClaims() = runTest {
        val v = vm(); advanceUntilIdle()
        v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        assertEquals(CameraOwner.Ar, cam.owner); assertEquals(1, ar.clears); assertFalse(ar.paused)
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

    @Test fun sessionErrorsToastAndTheCatalogReachesAr() = runTest {
        val v = vm(); v.onAction(ScannerAction.SetMode(ScanMode.Ar)); advanceUntilIdle()
        assertEquals(mapOf("A1" to "Apple"), ar.catalog)
        v.effects.test {
            ar.fail("Camera not available")
            assertEquals(ScannerEffect.Toast("Camera not available"), awaitItem())
        }
    }

    @Test fun newViewModelResetsSingletonCameraState() = runTest {
        val doc = io.packagex.visiondemo.fakes.FakeDocument()
        cam.front = true; doc.front = true; ar.paused = true   // left over from the previous ViewModel (Back, relaunch)
        val v = ScannerViewModel(cam, FakePreferences(), FakeModels(), FakeExtraction("{}", 0), FakeReport(), FakeEntitlement(true), catalog, Secrets("k", "staging"), ar, doc)
        advanceUntilIdle()
        assertFalse(v.state.value.frontCamera); assertFalse(cam.front); assertFalse(doc.front); assertFalse(ar.paused)
    }
}
