package com.example.faceaccessai

import android.content.Context
import android.graphics.Rect
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

class VirtualCursorEngine(private val context: Context) {

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var currentX: Float = 0f
    private var currentY: Float = 0f

    private var isLocked: Boolean = false
    
    // Configurable parameters loaded from settings
    private val settingsManager = CursorSettingsManager.getInstance(context)
    private var lastUpdateTimestamp: Long = 0
    
    // Trạng thái điều khiển "Từng nấc" (Step-by-step movement)
    private var hasMovedX = false
    private var hasMovedY = false
    private var lastYActionTimeMs = 0L
    private val autoRepeatDelayMs = 700L 
    
    private val moveStepPx = 180f // Tăng kích thước nấc để dứt khoát vượt qua độ bám của ứng dụng cũ
    private val tiltThreshold = 12f 

    // Dữ liệu hiệu chỉnh cá nhân
    private var calibrationProfile: HeadDirectionalCalibrationProfile? = null

    init {
        val metrics = context.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        // Start in center
        currentX = screenWidth / 2f
        currentY = screenHeight / 2f
    }

    fun setScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        clampToScreen()
    }

    fun getPosition(): Pair<Float, Float> {
        return Pair(currentX, currentY)
    }

    fun isLocked(): Boolean = isLocked

    fun toggleLock() {
        isLocked = !isLocked
        Log.d(TAG, "CURSOR_LOCK_TOGGLED | Locked=$isLocked")
    }

    fun setLocked(locked: Boolean) {
        isLocked = locked
    }

    fun setCalibrationProfile(profile: HeadDirectionalCalibrationProfile?) {
        this.calibrationProfile = profile
        Log.d(TAG, "Calibration profile updated in Engine")
    }

    fun update(yaw: Float, pitch: Float, timestampMs: Long) {
        if (isLocked) {
            lastUpdateTimestamp = timestampMs
            return
        }

        val profile = calibrationProfile
        
        // Tính toán ngưỡng dựa trên dữ liệu hiệu chỉnh thực tế (nếu có)
        val threshX = if (profile != null) (abs(profile.leftIntentYawDeg) + abs(profile.rightIntentYawDeg)) / 2f * 0.35f else tiltThreshold
        val threshUp = if (profile != null) abs(profile.upIntentPitchDeg) * 0.40f else tiltThreshold
        val threshDown = if (profile != null) abs(profile.downIntentPitchDeg) * 0.12f else 6f // GIẢM MẠNH ngưỡng Cúi
        
        // Đảm bảo ngưỡng không quá nhỏ để tránh nhiễu
        val finalThX = threshX.coerceAtLeast(4f)
        val finalThUp = threshUp.coerceAtLeast(4f)
        val finalThDown = threshDown.coerceAtLeast(1.5f) // Ngưỡng CỰC THẤP cho Cúi
        
        val returnX = finalThX * 0.5f
        val returnUp = finalThUp * 0.5f
        val returnDown = finalThDown * 0.85f // Cực kỳ dễ reset cho Cúi

        val sensitivity = settingsManager.getSensitivity() / 25f 
        val actualStep = moveStepPx * sensitivity
        val invertX = if (settingsManager.isInvertHorizontal()) -1f else 1f

        // --- XỬ LÝ TRỤC NGANG (X) ---
        if (abs(yaw) < returnX) {
            hasMovedX = false
        } else if (!hasMovedX && abs(yaw) > finalThX) {
            currentX += sign(yaw) * actualStep * invertX
            hasMovedX = true
        }

        // --- XỬ LÝ TRỤC DỌC (Y) ---
        // pitch < 0 là Ngẩng (Up), pitch > 0 là Cúi (Down)
        if (pitch < 0) { // Đang ngẩng lên
            if (abs(pitch) < returnUp) {
                hasMovedY = false
            } else if (!hasMovedY && abs(pitch) > finalThUp) {
                currentY -= actualStep
                hasMovedY = true
                lastYActionTimeMs = timestampMs
            } else if (hasMovedY && abs(pitch) > finalThUp && (timestampMs - lastYActionTimeMs > autoRepeatDelayMs)) {
                // Auto-repeat khi giữ
                currentY -= actualStep
                lastYActionTimeMs = timestampMs
            }
        } else { // Đang cúi xuống
            if (abs(pitch) < returnDown) {
                hasMovedY = false
            } else if (!hasMovedY && abs(pitch) > finalThDown) {
                currentY += actualStep
                hasMovedY = true
                lastYActionTimeMs = timestampMs
            } else if (hasMovedY && abs(pitch) > finalThDown && (timestampMs - lastYActionTimeMs > autoRepeatDelayMs)) {
                // Auto-repeat khi giữ
                currentY += actualStep
                lastYActionTimeMs = timestampMs
            }
        }

        lastUpdateTimestamp = timestampMs
        clampToScreen()
    }

    /**
     * Clamps cursor to screen boundaries.
     */
    private fun clampToScreen() {
        currentX = currentX.coerceIn(0f, screenWidth.toFloat())
        currentY = currentY.coerceIn(0f, screenHeight.toFloat())
    }

    fun reset() {
        currentX = screenWidth / 2f
        currentY = screenHeight / 2f
        isLocked = false
        hasMovedX = false
        hasMovedY = false
    }

    fun snapTo(targetX: Float, targetY: Float) {
        if (isLocked) return
        
        // GIẢM độ bám ứng dụng (snapping) để dễ dàng di chuyển sang ứng dụng khác
        // Giảm từ 0.20 xuống 0.08 để tránh việc con trỏ bị "hút ngược" lại mục tiêu cũ
        val snapStrength = 0.08f
        currentX += (targetX - currentX) * snapStrength
        currentY += (targetY - currentY) * snapStrength
    }

    companion object {
        private const val TAG = "VirtualCursorEngine"
    }
}
