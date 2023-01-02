package com.example.vision_sdk_android

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.customscannerview.mlkit.enums.ViewType
import com.example.customscannerview.mlkit.interfaces.OCRResult
import com.example.customscannerview.mlkit.modelclasses.OCRResponseParent
import com.example.customscannerview.mlkit.views.CaptureCallback
import com.example.customscannerview.mlkit.views.Configuration
import com.example.customscannerview.mlkit.views.ScanWindow
import com.example.vision_sdk_android.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding

    private var screenState = ScreenState()
    lateinit var permissionUtils: PermissionUtils
    lateinit var mediaUtils: MediaUtils

    private var isDialogShown = false
    private var isManualDetection = false
    private var barcodeList: List<Barcode> = emptyList()
    private var resetDialogFlagCallback: (() -> Unit) = {
        isDialogShown = false
        isManualDetection = false
    }
    var showErrorDialogJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionUtils = PermissionUtils(this)
        mediaUtils = MediaUtils(this)

        if (permissionUtils.allRuntimePermissionsGranted().not()) {
            permissionUtils.getRuntimePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        startScanning()
        setBarcodeAndTextIndicatorObservers()
        setBarcodeResultListener()
        setNavigationalListener()
        setRadioButtonListener()
        setCameraCaptureClickListener()
        setSettingClickListener()
        setMultipleBarcodeListener()
        setFlashClickListener()
    }

    private fun startScanning() {

        //setting the scanning window configuration
        binding.customScannerView.startScanning(viewType = ViewType.RECTANGLE)

        //Setting the Barcode and QR code scanning window sizes
        binding.customScannerView.setScanningWindowConfiguration(
            Configuration(
                barcodeWindow = ScanWindow(
                    width = ((binding.root.width * 0.9).toFloat()),
                    height = ((binding.root.width * 0.4).toFloat()),
                    radius = 100f
                ), qrCodeWindow = ScanWindow(
                    width = ((binding.root.width * 0.7).toFloat()),
                    height = ((binding.root.width * 0.7).toFloat()),
                    radius = 20f
                )
            )
        )
    }

    private fun setBarcodeAndTextIndicatorObservers() {
        binding.customScannerView.barcodeIndicators.observe(this) { barcodeList ->
            if (barcodeList.isEmpty()) {
                lifecycleScope.launch {
                    delay(2000)
                    binding.qrCodeDetector.setImageResource(R.drawable.ic_qr_inactive)
                    binding.barCodeDetector.setImageResource(R.drawable.ic_br_inactive)
                }
            } else {
                barcodeList.forEach {
                    if (TWO_DIMENSIONAL_FORMATS.contains(it.format)) {
                        binding.qrCodeDetector.setImageResource(R.drawable.ic_qr_active)
                    }
                    if (ONE_DIMENSIONAL_FORMATS.contains(it.format)) {
                        binding.barCodeDetector.setImageResource(R.drawable.ic_br_active)

                    }
                }
            }
        }

        binding.customScannerView.textIndicator.observe(this) { text ->
            lifecycleScope.launch {
                delay(2000)
                binding.textDetector.setImageResource(R.drawable.ic_text_inactive)
            }
            if (text.textBlocks.isEmpty()) {
                binding.textDetector.setImageResource(R.drawable.ic_text_inactive)
            } else {
                binding.textDetector.setImageResource(R.drawable.ic_text_active)
            }
        }
    }

    private fun setBarcodeResultListener() {
        binding.customScannerView.barcodeResultSingle.observe(this) {
            Log.d("BarCd", "BarCode detected, ${it.displayValue}")
            if (isDialogShown.not() && screenState.windowSize != WindowSize.FullScreen) {
                when (screenState.scanningMode) {
                    ScanningMode.Auto -> {
                        isDialogShown = true
                        when (screenState.selectedMode) {
                            SelectedMode.Barcode -> {
                                if (ONE_DIMENSIONAL_FORMATS.contains(it.format)) {
                                    showSuccessDialog(
                                        message = it.displayValue.toString(),
                                        context = this,
                                        mediaPlayer = mediaUtils.getMediaPlayer(),
                                        callback = resetDialogFlagCallback
                                    )
                                }
                            }

                            SelectedMode.QRCode -> {
                                if (TWO_DIMENSIONAL_FORMATS.contains(it.format)) {
                                    showSuccessDialog(
                                        message = it.displayValue.toString(),
                                        context = this,
                                        mediaPlayer = mediaUtils.getMediaPlayer(),
                                        callback = resetDialogFlagCallback
                                    )
                                }
                            }

                            SelectedMode.OCR -> {}
                        }
                    }

                    ScanningMode.Manual -> {
                        if (isManualDetection) {
                            when (screenState.selectedMode) {
                                SelectedMode.Barcode -> {
                                    if (ONE_DIMENSIONAL_FORMATS.contains(it.format)) {
                                        isManualDetection = false
                                        showErrorDialogJob?.cancel()
                                        showSuccessDialog(
                                            message = it.displayValue.toString(),
                                            context = this,
                                            mediaPlayer = mediaUtils.getMediaPlayer(),
                                            callback = resetDialogFlagCallback
                                        )
                                    }
                                }

                                SelectedMode.QRCode -> {
                                    if (TWO_DIMENSIONAL_FORMATS.contains(it.format)) {
                                        isManualDetection = false
                                        showErrorDialogJob?.cancel()
                                        showSuccessDialog(
                                            message = it.displayValue.toString(),
                                            context = this,
                                            mediaPlayer = mediaUtils.getMediaPlayer(),
                                            callback = resetDialogFlagCallback
                                        )
                                    }
                                }

                                SelectedMode.OCR -> {}
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    private fun setMultipleBarcodeListener() {
        binding.customScannerView.multipleBarcodes.observe(this) {
            barcodeList = it
            if (isDialogShown.not()) {
                if (screenState.scanningMode == ScanningMode.Manual && screenState.windowSize == WindowSize.FullScreen) {
                    if (isManualDetection) {
                        when (screenState.selectedMode) {
                            SelectedMode.Barcode -> {
                                val barcodes =
                                    it.filter { ONE_DIMENSIONAL_FORMATS.contains(it.format) }
                                if (barcodes.isNullOrEmpty().not()) {
                                    isManualDetection = false
                                    showErrorDialogJob?.cancel()
                                    getMultiBarcodeFragmentInstance(
                                        activity = this, barcodeList = barcodes.map {
                                            BarcodeModel(
                                                it.displayValue ?: ""
                                            )
                                        }.toMutableList()
                                    )
                                }
                            }

                            SelectedMode.QRCode -> {
                                val barcodes =
                                    it.filter { TWO_DIMENSIONAL_FORMATS.contains(it.format) }
                                if (barcodes.isNullOrEmpty().not()) {
                                    isManualDetection = false
                                    showErrorDialogJob?.cancel()
                                    getMultiBarcodeFragmentInstance(
                                        activity = this, barcodeList = barcodes.map {
                                            BarcodeModel(
                                                it.displayValue ?: ""
                                            )
                                        }.toMutableList()
                                    )
                                }
                            }

                            SelectedMode.OCR -> {}
                        }
                    }
                }
            }
        }
    }

    private fun setNavigationalListener() {
        binding.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.barCode -> {
                    if (screenState.windowSize == WindowSize.FullScreen) {
                        binding.btnSwitch.visibility = View.GONE
                    } else {
                        binding.btnSwitch.visibility = View.VISIBLE
                    }
                    screenState = screenState.copy(selectedMode = SelectedMode.Barcode)
                }

                R.id.qrCode -> {
                    if (screenState.windowSize == WindowSize.FullScreen) {
                        binding.btnSwitch.visibility = View.GONE
                    } else {
                        binding.btnSwitch.visibility = View.VISIBLE
                    }
                    screenState = screenState.copy(selectedMode = SelectedMode.QRCode)
                }

                R.id.ocr -> {
                    binding.btnSwitch.visibility = View.GONE
                    screenState = screenState.copy(
                        selectedMode = SelectedMode.OCR
                    )
                }
            }
            renderState()

            return@setOnItemSelectedListener true
        }
    }

    private fun renderState() {
        restartScanning()
    }

    private fun restartScanning() {
        binding.customScannerView.stopScanning()
        when (screenState.windowSize) {
            WindowSize.Window -> {
                when (screenState.selectedMode) {
                    SelectedMode.Barcode -> binding.customScannerView.startScanning(ViewType.RECTANGLE)
                    SelectedMode.QRCode -> binding.customScannerView.startScanning(ViewType.SQUARE)
                    SelectedMode.OCR -> binding.customScannerView.startScanning(ViewType.FULLSCRREN)
                }
            }

            WindowSize.FullScreen -> {
                binding.customScannerView.startScanning(ViewType.FULLSCRREN)
            }
        }

    }

    private fun setRadioButtonListener() {
        binding.btnSwitch.setOnCheckedChangeListener { _, item ->
            when (item) {
                R.id.radioManual -> {
                    binding.camIcon.visibility = View.VISIBLE
                    screenState = screenState.copy(scanningMode = ScanningMode.Manual)
                    restartScanning()
                }

                R.id.radioAuto -> {
                    binding.camIcon.visibility = View.GONE
                    screenState = screenState.copy(scanningMode = ScanningMode.Auto)
                    restartScanning()
                }
            }
        }
    }

    private fun setSettingClickListener() {
        binding.btnSettings.setOnClickListener {
            showSettingDialog(this) {
                if (it) {
                    binding.btnSwitch.visibility = View.GONE
                    screenState = screenState.copy(
                        windowSize = WindowSize.FullScreen,
                    )
                } else {

                    binding.btnSwitch.visibility = View.VISIBLE
                    when (binding.bottomNav.selectedItemId) {
                        R.id.barCode -> {
                            screenState = screenState.copy(
                                selectedMode = SelectedMode.Barcode,
                                windowSize = WindowSize.Window,
                                scanningMode = ScanningMode.Manual
                            )
                        }

                        R.id.qrCode -> {
                            screenState = screenState.copy(
                                selectedMode = SelectedMode.QRCode,
                                windowSize = WindowSize.Window,
                                scanningMode = ScanningMode.Manual
                            )
                        }

                        R.id.ocr -> {
                            screenState = screenState.copy(
                                selectedMode = SelectedMode.OCR,
                                windowSize = WindowSize.Window,
                                scanningMode = ScanningMode.Manual

                            )
                        }
                    }
                }
                renderState()
            }.show()
        }
    }

    private fun setFlashClickListener() {
        binding.flashIcon.setOnClickListener {
            screenState = screenState.copy(flashStatus = screenState.flashStatus.not())

            if (screenState.flashStatus) {
                binding.flashIcon.setImageResource(R.drawable.ic_flash_active)
                binding.customScannerView.enableTorch()

            } else {
                binding.flashIcon.setImageResource(R.drawable.ic_flash_inactive)
                binding.customScannerView.disableTorch()
            }

            binding.flashIcon.setImageResource(R.drawable.ic_flash_inactive)
            binding.customScannerView.disableTorch()
            false
        }
    }

    private fun setCameraCaptureClickListener() {
        binding.camIcon.setOnClickListener {
            when (screenState.selectedMode) {
                SelectedMode.QRCode, SelectedMode.Barcode -> {
                    isManualDetection = true
                    showErrorDialogJob = showManualFailureDetectionDialog()
                }

                SelectedMode.OCR -> {
                    binding.progressBarWithDimBg.visibility = View.VISIBLE
                    captureImage()
                }
            }
        }
    }

    private fun captureImage() {
        binding.customScannerView.captureImage(object : CaptureCallback {
            override fun onImageCaptured(bitmap: Bitmap, value: MutableList<Barcode>?) {
                triggerOCRCalls(bitmap, value ?: mutableListOf())
            }

        })
    }

    private fun triggerOCRCalls(bitmap: Bitmap, list: MutableList<Barcode>) {
        binding.customScannerView.makeOCRApiCall(bitmap = bitmap,
            barcodeList = list,
            onScanResult = object : OCRResult {
                override fun onOCRResponse(ocrResponse: OCRResponseParent?) {
                    binding.progressBarWithDimBg.visibility = View.GONE
                    Log.d("MainActivity", "api responded with  ${ocrResponse.toString()}")
                }

                override fun onOCRResponseFailed(throwable: Throwable?) {
                    binding.progressBarWithDimBg.visibility = View.GONE
                    Log.d("MainActivity", "Something went wrong ${throwable?.message}")
                }
            })
    }


    private fun showManualFailureDetectionDialog(): Job {
        return lifecycleScope.launch {
            launch {
                delay(1500)
                isDialogShown = true
                showErrorDialog(
                    viewType = screenState.selectedMode,
                    this@MainActivity,
                    mediaUtils.getMediaPlayer(),
                    resetDialogFlagCallback
                )
            }
        }
    }
}