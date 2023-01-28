package com.example.visionsdkandroid

import android.content.Context
import android.media.MediaPlayer


class MediaUtils(private val context: Context) {
    private val player: MediaPlayer = MediaPlayer.create(context, R.raw.beep_sound)

    fun getMediaPlayer(): MediaPlayer {
        return player
    }
}