package com.example.vision_sdk_android

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.customscannerview.mlkit.Environment
import com.example.customscannerview.mlkit.VisionSDK
import com.example.customscannerview.mlkit.enums.ViewType
import com.example.customscannerview.mlkit.interfaces.OCRResult
import com.example.customscannerview.mlkit.modelclasses.OCRResponse
import com.example.customscannerview.mlkit.views.Configuration
import com.example.customscannerview.mlkit.views.DetectionMode
import com.example.customscannerview.mlkit.views.ScanWindow
import com.example.customscannerview.mlkit.views.ScannerCallbacks
import com.example.customscannerview.mlkit.views.ScannerException
import com.example.customscannerview.mlkit.views.ScanningMode
import com.example.vision_sdk_android.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), ScannerCallbacks {

    lateinit var binding: ActivityMainBinding

    private var screenState = ScreenState()
    lateinit var permissionUtils: PermissionUtils
    lateinit var mediaUtils: MediaUtils

    private var isDialogShown = false
    private var barcodeList: List<Barcode> = emptyList()
    private var resetDialogFlagCallback: (() -> Unit) = {
        isDialogShown = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VisionSDK.getInstance().initialise(
            apiKey = "//TODO your api key here",
            environment = Environment.STAGING
        )
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
        setNavigationalListener()
        setRadioButtonListener()
        setCameraCaptureClickListener()
        setSettingClickListener()
        setFlashClickListener()
    }

    private fun startScanning() {

        //setting the scanning window configuration
        binding.customScannerView.startScanning(
            viewType = screenState.scanningWindow,
            scanningMode = screenState.scanningMode,
            detectionMode = screenState.detectionMode,
            scannerCallbacks = this
        )

        //Setting the Barcode and QR code scanning window sizes
        binding.customScannerView.setScanningWindowConfiguration(
            Configuration(
                barcodeWindow = ScanWindow(
                    width = ((binding.root.width * 0.9).toFloat()),
                    height = ((binding.root.width * 0.4).toFloat()),
                    radius = 10f,
                    verticalStartingPosition = (binding.root.height / 2) - ((binding.root.width * 0.4).toFloat())
                ), qrCodeWindow = ScanWindow(
                    width = ((binding.root.width * 0.7).toFloat()),
                    height = ((binding.root.width * 0.7).toFloat()),
                    radius = 10f,
                    verticalStartingPosition = (binding.root.height / 2) - ((binding.root.width * 0.5).toFloat())
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

    private fun setNavigationalListener() {
        binding.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.barCode -> {
                    screenState = screenState.copy(
                        detectionMode = DetectionMode.Barcode,
                        scanningWindow = if (screenState.scanningWindow != ViewType.FULLSCRREN) ViewType.WINDOW else ViewType.FULLSCRREN
                    )
                }

                R.id.qrCode -> {
                    screenState = screenState.copy(
                        detectionMode = DetectionMode.QR,
                        scanningWindow = if (screenState.scanningWindow != ViewType.FULLSCRREN) ViewType.WINDOW else ViewType.FULLSCRREN
                    )
                }

                R.id.ocr -> {
                    screenState = screenState.copy(
                        detectionMode = DetectionMode.OCR,
                        scanningWindow = ViewType.FULLSCRREN
                    )
                }
            }
            restartScanning()
            return@setOnItemSelectedListener true
        }
    }

    private fun setSettingClickListener() {
        binding.btnSettings.setOnClickListener {
            showSettingDialog(this) {
                if (it) {
                    screenState = screenState.copy(scanningWindow = ViewType.FULLSCRREN)
                } else {
                    when (binding.bottomNav.selectedItemId) {
                        R.id.barCode -> {
                            screenState = screenState.copy(
                                scanningWindow = ViewType.WINDOW,
                                detectionMode = DetectionMode.Barcode
                            )
                        }

                        R.id.qrCode -> {
                            screenState = screenState.copy(
                                scanningWindow = ViewType.WINDOW,
                                detectionMode = DetectionMode.QR
                            )
                        }

                        R.id.ocr -> {
                            screenState = screenState.copy(
                                detectionMode = DetectionMode.OCR,
                                scanningWindow = ViewType.WINDOW
                            )
                        }
                    }
                }
                restartScanning()
            }.show()
        }
    }


    private fun restartScanning() {
        binding.customScannerView.stopScanning()
        binding.customScannerView.startScanning(
            viewType = screenState.scanningWindow,
            scanningMode = screenState.scanningMode,
            detectionMode = screenState.detectionMode,
            this
        )


        if (screenState.scanningMode == ScanningMode.Manual || screenState.detectionMode == DetectionMode.OCR) {
            binding.camIcon.visibility = View.VISIBLE
        } else {
            binding.camIcon.visibility = View.GONE
        }

        if (screenState.detectionMode == DetectionMode.OCR ||
            screenState.detectionMode == DetectionMode.QRAndBarcode ||
            screenState.scanningWindow == ViewType.FULLSCRREN
        ) {
            binding.btnSwitch.visibility = View.GONE
        } else {
            binding.btnSwitch.visibility = View.VISIBLE
        }


    }

    private fun setRadioButtonListener() {
        binding.btnSwitch.setOnCheckedChangeListener { _, item ->
            when (item) {
                R.id.radioManual -> {
                    screenState = screenState.copy(scanningMode = ScanningMode.Manual)
                    restartScanning()
                }

                R.id.radioAuto -> {
                    screenState = screenState.copy(scanningMode = ScanningMode.Auto)
                    restartScanning()
                }
            }
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
        }
    }

    private fun setCameraCaptureClickListener() {
        binding.camIcon.setOnClickListener {
            when (screenState.detectionMode) {
                DetectionMode.QR, DetectionMode.Barcode -> {
                    captureImage()
                }

                DetectionMode.OCR -> {
                    binding.progressBar.visibility = View.VISIBLE
                    captureImage()
                }

                DetectionMode.QRAndBarcode -> {

                }
            }
        }
    }

    private fun captureImage() {
        binding.customScannerView.capture()
    }

    private fun triggerOCRCalls(bitmap: Bitmap, list: MutableList<Barcode>) {
        binding.customScannerView.makeOCRApiCall(bitmap = bitmap,
            barcodeList = list,
            onScanResult = object : OCRResult {
                override fun onOCRResponse(ocrResponse: OCRResponse?) {
                    binding.progressBar.visibility = View.GONE
                    Log.d("MainActivity", "api responded with  ${ocrResponse.toString()}")
                }

                override fun onOCRResponseFailed(throwable: Throwable?) {
                    binding.progressBar.visibility = View.GONE
                    Log.d("MainActivity", "Something went wrong ${throwable?.message}")
                }
            })
    }


    override fun onImageCaptured(bitmap: Bitmap, value: MutableList<Barcode>?) {
        triggerOCRCalls(bitmap, value ?: mutableListOf())
    }

    override fun onBarcodeDetected(barcode: Barcode) {
        showSuccessDialog(
            message = barcode.displayValue.toString(),
            context = this,
            mediaPlayer = mediaUtils.getMediaPlayer(),
            callback = resetDialogFlagCallback
        )
    }

    override fun onMultipleBarcodesDetected(barcodes: List<Barcode>) {
        barcodeList = barcodes
        if (isDialogShown.not()) {
            val filteredList = barcodeList.filter { ONE_DIMENSIONAL_FORMATS.contains(it.format) }
            if (barcodes.isNullOrEmpty().not()) {
                getMultiBarcodeFragmentInstance(
                    activity = this, barcodeList = filteredList.map {
                        BarcodeModel(
                            it.displayValue ?: ""
                        )
                    }.toMutableList()
                )
            }
        }
    }

    override fun onFailure(exception: ScannerException) {
        when (exception) {
            is ScannerException.BarCodeNotDetected -> showErrorDialog(
                viewType = screenState.detectionMode,
                this@MainActivity,
                mediaUtils.getMediaPlayer(),
                resetDialogFlagCallback
            )

            is ScannerException.QRCodeNotDetected -> showErrorDialog(
                viewType = screenState.detectionMode,
                this@MainActivity,
                mediaUtils.getMediaPlayer(),
                resetDialogFlagCallback
            )
        }
    }
}