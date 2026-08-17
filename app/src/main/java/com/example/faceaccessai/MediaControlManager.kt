package com.example.faceaccessai

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

class MediaControlManager(context: Context) {

    enum class MediaAction {
        PREVIOUS,
        PLAY_PAUSE,
        NEXT
    }

    enum class MediaControlResult {
        DISPATCHED,
        FAILED
    }

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun dispatch(action: MediaAction): MediaControlResult {
        val keyCode = when (action) {
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        }

        return try {
            // Simulate a complete media button press: DOWN then UP
            val eventTime = SystemClock.uptimeMillis()

            val downEvent = KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
            )
            audioManager.dispatchMediaKeyEvent(downEvent)

            val upEvent = KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_UP,
                keyCode,
                0
            )
            audioManager.dispatchMediaKeyEvent(upEvent)

            Log.d(TAG, "Media key dispatched: $keyCode")
            MediaControlResult.DISPATCHED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch media key: $keyCode", e)
            MediaControlResult.FAILED
        }
    }

    companion object {
        private const val TAG = "MediaControlManager"
    }
}
