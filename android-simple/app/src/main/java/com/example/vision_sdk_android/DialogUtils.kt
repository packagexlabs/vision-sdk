package com.example.vision_sdk_android

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import com.example.customscannerview.mlkit.enums.ViewType


fun showSuccessDialog(
    message: String,
    context: Context,
    mediaPlayer: MediaPlayer,
    callback: () -> Unit,
    isSoundEnabled: Boolean = true,
    vibrationEnabled: Boolean = true
) {
    val resultDialog =
        AlertDialog.Builder(context).setCancelable(false).setTitle("SCAN RESULT").create()
    resultDialog.setMessage(message)
    resultDialog.setButton(
        AlertDialog.BUTTON_POSITIVE, "Copy"
    ) { dialog, _ ->
        dialog?.dismiss()
        callback()
    }
    resultDialog.setButton(
        AlertDialog.BUTTON_NEGATIVE, "Cancel"
    ) { dialog, _ ->
        dialog?.dismiss()
        callback()
    }
    resultDialog.show()

    if (isSoundEnabled) {
        mediaPlayer.start()

    } else if (vibrationEnabled) {
        val vibrator = context.getSystemService(AppCompatActivity.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    400, VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            vibrator.vibrate(400)
        }
    }
}


fun showErrorDialog(
    viewType: ViewType, context: Context, mediaPlayer: MediaPlayer, callback: () -> Unit,
) {
    val failureDialog =
        AlertDialog.Builder(context).setCancelable(false).setTitle("SCAN FAILED").create()

    var name = when (viewType) {
        ViewType.SQUARE -> " QR Code"
        ViewType.RECTANGLE -> "Barcode"
        ViewType.FULLSCRREN -> "Something"
        else -> {
            "Somethinh"
        }
    }

    val subView =
        (context as Activity).layoutInflater.inflate(R.layout.barcode_not_found_view, null, false)

    failureDialog.setView(subView)
    failureDialog.setTitle("No $name Found")
    failureDialog.setMessage("Please capture photo when $name indicator is active or manually enter the code")
    failureDialog.setButton(
        AlertDialog.BUTTON_NEGATIVE, "OK"
    ) { dialog, _ ->
        val code = subView.findViewById<EditText>(R.id.inputValue).text.toString()
        context.copyToClipboard(code)
        callback.invoke()
        dialog?.dismiss()
    }
    failureDialog.setCanceledOnTouchOutside(true)
    failureDialog.setOnCancelListener {
        callback.invoke()
        it?.dismiss()
    }
    failureDialog.show()
    mediaPlayer.start()
}

fun Context.copyToClipboard(text: CharSequence) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("label", text)
    clipboard.setPrimaryClip(clip)
}

fun showSettingDialog(context: Context, callback: (Boolean) -> Unit): Dialog {
    val settingsFragment = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)

    val v = LayoutInflater.from(context).inflate(R.layout.setting_sheet_view, null, false)
    settingsFragment.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
    settingsFragment.setCancelable(false)
    settingsFragment.setCanceledOnTouchOutside(true)
    settingsFragment.setContentView(v)

    settingsFragment.findViewById<ImageView>(R.id.btnDownSetting).setOnClickListener {
        settingsFragment.dismiss()
    }
    settingsFragment.findViewById<SwitchCompat>(R.id.btnSwitchSetting)
        .setOnCheckedChangeListener { _, isChecked ->
            callback.invoke(isChecked)
        }
    return settingsFragment
}


fun getMultiBarcodeFragmentInstance(
    activity: FragmentActivity, barcodeList: MutableList<BarcodeModel>
) {
    val multiBarcodesDialog = MultiBarcodeFragment.newInstance("DialogFragment")
    multiBarcodesDialog.barcodesList = barcodeList
    multiBarcodesDialog.show(activity.supportFragmentManager, "DialogCustom")
}



