package com.example.vision_sdk_android

import com.google.mlkit.vision.barcode.common.Barcode

val TWO_DIMENSIONAL_FORMATS = mutableListOf(
    Barcode.FORMAT_QR_CODE
)
val ONE_DIMENSIONAL_FORMATS = mutableListOf(
    Barcode.FORMAT_CODABAR,
    Barcode.FORMAT_CODE_39,
    Barcode.FORMAT_CODE_93,
    Barcode.FORMAT_CODE_128,
    Barcode.FORMAT_EAN_8,
    Barcode.FORMAT_EAN_13,
    Barcode.FORMAT_ITF,
    Barcode.FORMAT_DATA_MATRIX,
    Barcode.FORMAT_AZTEC,
    Barcode.FORMAT_PDF417,
    Barcode.FORMAT_UPC_A,
    Barcode.FORMAT_UPC_E
)