package com.example.faceaccessai

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FaceAccessAccessibilityService : AccessibilityService() {

    private var overlayController: FaceAccessOverlayController? = null

    companion object {

        private const val TAG = "FaceAccessAccessibility"

        @Volatile
        private var instance: FaceAccessAccessibilityService? = null

        // Kiểm tra Accessibility Service có đang hoạt động
        fun isServiceRunning(): Boolean {
            return instance != null
        }

        // Thực hiện thao tác Back
        fun performBack(): Boolean {
            val service = instance ?: return false

            return service.performGlobalAction(
                GLOBAL_ACTION_BACK
            )
        }

        // Thực hiện thao tác Home
        fun performHome(): Boolean {
            val service = instance ?: return false

            return service.performGlobalAction(
                GLOBAL_ACTION_HOME
            )
        }

        // Thực hiện MOVE_LEFT trên overlay
        fun performMoveLeft(): Boolean {
            val service = instance ?: return false
            return service.overlayController?.moveLeft() ?: false
        }

        // Thực hiện MOVE_RIGHT trên overlay
        fun performMoveRight(): Boolean {
            val service = instance ?: return false
            return service.overlayController?.moveRight() ?: false
        }

        // Thực hiện MOVE_UP trên overlay
        fun performMoveUp(): Boolean {
            val service = instance ?: return false
            return service.overlayController?.moveUp() ?: false
        }

        // Thực hiện MOVE_DOWN trên overlay
        fun performMoveDown(): Boolean {
            val service = instance ?: return false
            return service.overlayController?.moveDown() ?: false
        }

        // Thực hiện CONFIRM trên overlay
        fun performConfirm(): Boolean {
            val service = instance ?: return false
            return service.overlayController?.confirm() ?: false
        }
    }

    // Android gọi khi Accessibility Service được kết nối
    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        overlayController = FaceAccessOverlayController(this)
        overlayController?.show()

        Log.d(
            TAG,
            "Accessibility Service connected"
        )
    }

    // Không đọc AccessibilityEvent từ ứng dụng khác
    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
    }

    // Android gọi khi Accessibility Service bị gián đoạn
    override fun onInterrupt() {
        Log.w(
            TAG,
            "Accessibility Service interrupted"
        )
    }

    // Xóa instance khi service bị hủy
    override fun onDestroy() {

        overlayController?.remove()
        overlayController = null

        if (instance === this) {
            instance = null
        }

        Log.d(
            TAG,
            "Accessibility Service destroyed"
        )

        super.onDestroy()
    }
}