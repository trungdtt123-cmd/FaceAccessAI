package com.example.faceaccessai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class FaceAccessAccessibilityService : AccessibilityService() {

    private var overlayController: FaceAccessOverlayController? = null
    private val scanner = AccessibilityNodeScanner()
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayRetryRunnable: Runnable? = null

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
            if (FaceControlStateManager.shouldBlockFaceCommands()) return false
            return service.overlayController?.moveLeft() ?: false
        }

        // Thực hiện MOVE_RIGHT trên overlay
        fun performMoveRight(): Boolean {
            val service = instance ?: return false
            if (FaceControlStateManager.shouldBlockFaceCommands()) return false
            return service.overlayController?.moveRight() ?: false
        }

        // Thực hiện MOVE_UP trên overlay
        fun performMoveUp(): Boolean {
            val service = instance ?: return false
            if (FaceControlStateManager.shouldBlockFaceCommands()) return false
            return service.overlayController?.moveUp() ?: false
        }

        // Thực hiện MOVE_DOWN trên overlay
        fun performMoveDown(): Boolean {
            val service = instance ?: return false
            if (FaceControlStateManager.shouldBlockFaceCommands()) return false
            return service.overlayController?.moveDown() ?: false
        }

        // Cập nhật hiển thị tạm dừng trên overlay
        fun setFaceControlPausedVisual(paused: Boolean) {
            instance?.overlayController?.setPaused(paused)
        }

        // Thực hiện CONFIRM trên overlay và scan node
        fun performConfirm(): Boolean {
            val service = instance ?: return false
            val controller = service.overlayController ?: return false

            // Không thực hiện nếu đang bị chặn
            if (FaceControlStateManager.shouldBlockFaceCommands()) {
                return false
            }

            // Kiểm tra overlay confirm
            if (!controller.confirm()) {
                controller.clearCandidate()
                return false
            }

            // Discovery: Scan target quadrant
            val focusedIndex = controller.getFocusedIndex()
            
            // Sử dụng helper để lấy root an toàn
            val root = service.resolveActiveRoot()

            // Clear stale highlight nếu không lấy được root
            if (root == null) {
                controller.clearCandidate()
                Log.w(TAG, "SCAN_UNAVAILABLE | Reason=NO_ROOT")
                return true
            }

            val windowBounds = Rect()
            root.getBoundsInScreen(windowBounds)

            if (windowBounds.isEmpty) {
                val metrics = service.resources.displayMetrics
                windowBounds.set(
                    0,
                    0,
                    metrics.widthPixels,
                    metrics.heightPixels
                )
            }

            val candidate = service.scanner.scanQuadrant(
                root,
                windowBounds,
                focusedIndex
            )

            if (candidate != null) {
                if (!controller.showCandidate(candidate.bounds)) {
                    Log.e(TAG, "SCAN_HIGHLIGHT_FAILED")
                }
            } else {
                controller.clearCandidate()
            }

            return true
        }
    }

    // Android gọi khi Accessibility Service được kết nối
    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        // 1. Cấu hình Capability an toàn
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info

        // 2. Diagnostic log
        val appliedInfo = serviceInfo
        val canRetrieve = (appliedInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        val hasInteractiveFlag = (appliedInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0

        Log.d(
            TAG,
            "ACCESSIBILITY_DIAGNOSTIC | " +
                    "Capabilities=${appliedInfo.capabilities} | " +
                    "CanRetrieveWindowContent=$canRetrieve | " +
                    "Flags=${appliedInfo.flags} | " +
                    "RetrieveInteractiveWindowsFlag=$hasInteractiveFlag | " +
                    "EventTypes=${appliedInfo.eventTypes}"
        )

        // 3. Khởi tạo Overlay an toàn
        Log.d(TAG, "OVERLAY_INIT_START")
        
        cancelOverlayRetry()
        overlayController?.remove()
        
        val controller = FaceAccessOverlayController(this)
        overlayController = controller
        
        val firstShowSucceeded = controller.show()
        val isAttached = controller.isShown()

        Log.d(TAG, "OVERLAY_INIT_FIRST | ShowReturned=$firstShowSucceeded | Attached=$isAttached")

        // Áp dụng trạng thái pause hiện tại nếu có
        controller.setPaused(FaceControlStateManager.isPaused())

        // 4. Retry đúng một lần nếu chưa hiển thị (chống race condition khi lần đầu bật service)
        if (!firstShowSucceeded || !isAttached) {
            val retryRunnable = Runnable {
                if (instance === this && overlayController === controller) {
                    if (controller.isShown()) {
                        Log.d(TAG, "OVERLAY_RETRY_SKIPPED_ALREADY_ATTACHED")
                        controller.setPaused(FaceControlStateManager.isPaused())
                    } else {
                        Log.d(TAG, "OVERLAY_RETRY_START")
                        val retrySucceeded = controller.show()
                        controller.setPaused(FaceControlStateManager.isPaused())
                        Log.d(TAG, "OVERLAY_RETRY_RESULT | ShowReturned=$retrySucceeded | Attached=${controller.isShown()}")
                    }
                }
            }
            overlayRetryRunnable = retryRunnable
            mainHandler.postDelayed(retryRunnable, 250)
        }

        Log.d(
            TAG,
            "Accessibility Service connected"
        )
    }

    private fun cancelOverlayRetry() {
        overlayRetryRunnable?.let {
            mainHandler.removeCallbacks(it)
            overlayRetryRunnable = null
        }
    }

    // Tìm kiếm root node từ cửa sổ đang hoạt động hoặc fallback
    private fun resolveActiveRoot(): AccessibilityNodeInfo? {
        // A. Thử rootInActiveWindow trước
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            Log.d(TAG, "ROOT_SOURCE | Source=ROOT_IN_ACTIVE_WINDOW")
            return activeRoot
        }

        // B. Fallback dùng windows list nếu rootInActiveWindow null
        val windowList = windows
        Log.d(TAG, "ROOT_DIAGNOSTIC | RootInActiveWindow=false | WindowCount=${windowList.size}")
        
        if (windowList.isEmpty()) {
            Log.w(TAG, "ROOT_DIAGNOSTIC | Result=NO_ROOT_AVAILABLE")
            return null
        }

        // 1. Tìm active window có root
        val activeWin = windowList.find { it.isActive }
        activeWin?.root?.let {
            Log.d(TAG, "ROOT_SOURCE | Source=ACTIVE_WINDOW")
            return it
        }

        // 2. Tìm focused window có root
        val focusedWin = windowList.find { it.isFocused }
        focusedWin?.root?.let {
            Log.d(TAG, "ROOT_SOURCE | Source=FOCUSED_WINDOW")
            return it
        }

        // 3. Fallback window ứng dụng đầu tiên (không phải accessibility overlay nếu có thể)
        val appWin = windowList.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        appWin?.root?.let {
            Log.d(TAG, "ROOT_SOURCE | Source=APPLICATION_WINDOW_FALLBACK")
            return it
        }

        Log.w(TAG, "ROOT_DIAGNOSTIC | Result=NO_ROOT_AVAILABLE")
        return null
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

        cancelOverlayRetry()
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
