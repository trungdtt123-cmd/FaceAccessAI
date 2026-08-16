package com.example.faceaccessai

import android.os.SystemClock
import android.util.Log

object FaceControlStateManager {

    private const val TAG = "FaceControlState"
    private const val RESUME_GRACE_MS = 1200L

    @Volatile
    private var paused = false

    @Volatile
    private var resumeGraceUntilElapsedMs = 0L

    // Kiểm tra xem điều khiển khuôn mặt có đang bị tạm dừng không
    fun isPaused(): Boolean {
        return paused
    }

    // Thiết lập trạng thái tạm dừng
    @Synchronized
    fun setPaused(shouldPause: Boolean) {
        if (paused == shouldPause) return

        paused = shouldPause
        if (!shouldPause) {
            // Khi bắt đầu tiếp tục (Resume), thiết lập thời gian ân hạn
            resumeGraceUntilElapsedMs = SystemClock.elapsedRealtime() + RESUME_GRACE_MS
            Log.d(TAG, "PAUSE_CHANGED | Paused=false | ResumeGraceMs=$RESUME_GRACE_MS")
        } else {
            resumeGraceUntilElapsedMs = 0L
            Log.d(TAG, "PAUSE_CHANGED | Paused=true")
        }
    }

    // Chuyển đổi trạng thái tạm dừng
    @Synchronized
    fun togglePaused(): Boolean {
        val newState = !paused
        setPaused(newState)
        return newState
    }

    // Kiểm tra xem có nên chặn các lệnh điều khiển khuôn mặt hay không
    fun shouldBlockFaceCommands(): Boolean {
        if (paused) return true

        val graceUntil = resumeGraceUntilElapsedMs
        if (graceUntil > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now < graceUntil) {
                return true
            } else {
                // Grace period đã kết thúc
                resumeGraceUntilElapsedMs = 0L
            }
        }

        return false
    }

    // Reset trạng thái về ACTIVE (dùng khi service dừng)
    @Synchronized
    fun resetToActive() {
        paused = false
        resumeGraceUntilElapsedMs = 0L
        Log.d(TAG, "STATE_RESET | Active")
    }
}
