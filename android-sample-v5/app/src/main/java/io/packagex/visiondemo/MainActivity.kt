package io.packagex.visiondemo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.packagex.visiondemo.ar.ArSessionController
import io.packagex.visiondemo.designsystem.VisionTheme
import io.packagex.visiondemo.model.ScanMode
import io.packagex.visiondemo.scanner.ScannerAction
import io.packagex.visiondemo.scanner.ScannerRoute
import io.packagex.visiondemo.scanner.ScannerViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var arSession: ArSessionController

    /** The screen's own: [ScannerRoute]'s hiltViewModel() is this activity's */
    private val scanner: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VisionTheme { ScannerRoute() }
        }
        replayFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        replayFrom(intent)
    }

    /**
     * `adb shell am start -S -n io.vision_sdk_android/io.packagex.visiondemo.MainActivity --es arReplay <recording.mp4>`:
     * AR Item Count opens on the recording in place of the camera ([ArSessionController.replay]), once
     */
    private fun replayFrom(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_REPLAY) ?: return
        intent.removeExtra(EXTRA_REPLAY)
        arSession.replay(path)
        scanner.onAction(ScannerAction.GoHome) // AR Item Count already open: left, so the next open attaches the replay
        scanner.onAction(ScannerAction.SetMode(ScanMode.Retrieval))
    }

    private companion object {
        const val EXTRA_REPLAY = "arReplay"
    }
}
