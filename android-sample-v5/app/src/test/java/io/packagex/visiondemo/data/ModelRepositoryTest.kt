package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.ModelState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ModelRepositoryTest {

    // ifStillDownloading: the cancel/fail race guard (iOS DemoModel.swift:739).

    @Test fun stillDownloadingIsReplaced() {
        assertEquals(
            ModelState.Failed,
            ifStillDownloading(ModelState.Downloading(0.3f), ModelState.Failed),
        )
        assertEquals(
            ModelState.Downloading(0.6f),
            ifStillDownloading(ModelState.Downloading(0.3f), ModelState.Downloading(0.6f)),
        )
    }

    @Test fun noLongerDownloadingIsNotClobbered() {
        // A concurrent cancel() already moved the row to NotDownloaded -- a late failure/progress
        // callback must not stomp on that.
        assertEquals(
            ModelState.NotDownloaded,
            ifStillDownloading(ModelState.NotDownloaded, ModelState.Failed),
        )
        assertEquals(
            ModelState.Loaded,
            ifStillDownloading(ModelState.Loaded, ModelState.Failed),
        )
    }

    // refreshedState: refresh() skips requerying the SDK for a row mid-download (iOS DemoModel.swift:721).

    @Test fun refreshSkipsDownloadingRowsWithoutQueryingTheSdk() = runTest {
        val result = refreshedState(
            current = ModelState.Downloading(0.4f),
            isLoaded = { fail("must not query isLoaded for a downloading row"); false },
            isDownloaded = { fail("must not query isDownloaded for a downloading row"); false },
        )
        assertEquals(ModelState.Downloading(0.4f), result)
    }

    @Test fun refreshReportsLoadedOverDownloaded() = runTest {
        assertEquals(
            ModelState.Loaded,
            refreshedState(ModelState.NotDownloaded, isLoaded = { true }, isDownloaded = { true }),
        )
    }

    @Test fun refreshReportsDownloadedWhenNotLoaded() = runTest {
        assertEquals(
            ModelState.Downloaded,
            refreshedState(ModelState.NotDownloaded, isLoaded = { false }, isDownloaded = { true }),
        )
    }

    @Test fun refreshReportsNotDownloadedWhenNeither() = runTest {
        assertEquals(
            ModelState.NotDownloaded,
            refreshedState(ModelState.Loaded, isLoaded = { false }, isDownloaded = { false }),
        )
    }

    // mergeRefreshed: refresh() folds its probe into the rows' current states atomically.

    @Test fun refreshKeepsADownloadThatStartedDuringTheProbe() =
        assertEquals(ModelState.Downloading(0.1f), mergeRefreshed(ModelState.Downloading(0.1f), ModelState.NotDownloaded))

    @Test fun refreshKeepsADownloadThatFinishedDuringTheProbe() =
        assertEquals(ModelState.Downloaded, mergeRefreshed(ModelState.Downloaded, ModelState.Downloading(0.9f)))

    @Test fun refreshOtherwiseTakesTheProbe() =
        assertEquals(ModelState.Loaded, mergeRefreshed(ModelState.NotDownloaded, ModelState.Loaded))
}
