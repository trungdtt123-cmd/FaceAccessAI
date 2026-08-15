package com.example.faceaccessai

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FaceAccessAccessibilityService : AccessibilityService() {

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
    }

    // Android gọi khi Accessibility Service được kết nối
    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

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