package com.example.faceaccessai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
    private var overlayShowCycleActive = false
    private var showAttemptCount = 0

    companion object {

        private const val TAG = "FaceAccessAccessibility"
        private const val PREFS_NAME = "overlay_prefs"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val MAX_OVERLAY_SHOW_ATTEMPTS = 8
        private const val OVERLAY_RETRY_DELAY_MS = 250L

        @Volatile
        private var instance: FaceAccessAccessibilityService? = null

        // Kiểm tra Accessibility Service có đang hoạt động
        fun isServiceRunning(): Boolean {
            return instance != null
        }

        // Persistent preference for overlay visibility
        fun isOverlayEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        }

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
            
            // Sync live service if running
            val service = instance
            if (service != null) {
                if (enabled) {
                    service.startOverlayShowCycle()
                } else {
                    service.removeOverlayAuthority()
                }
            }
        }

        fun isOverlayActuallyShown(): Boolean {
            return instance?.overlayController?.isShown() ?: false
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

        Log.d(TAG, "Accessibility Service connected")

        // 2. Synchronize overlay based on preference
        if (isOverlayEnabled(this)) {
            startOverlayShowCycle()
        } else {
            removeOverlayAuthority()
        }
    }

    private fun startOverlayShowCycle() {
        if (instance !== this) return
        
        if (!isOverlayEnabled(this)) {
            finishOverlayShowCycle()
            return
        }

        if (overlayController?.isShown() == true) {
            overlayController?.setPaused(FaceControlStateManager.isPaused())
            finishOverlayShowCycle()
            return
        }

        if (overlayShowCycleActive) return

        finishOverlayShowCycle() // Clear any stale state
        showAttemptCount = 0
        overlayShowCycleActive = true
        performOverlayShowAttempt()
    }

    private fun performOverlayShowAttempt() {
        if (instance !== this || !overlayShowCycleActive || !isOverlayEnabled(this)) {
            finishOverlayShowCycle()
            return
        }
        
        showAttemptCount++
        Log.d(TAG, "OVERLAY_SHOW_ATTEMPT | Attempt=$showAttemptCount")

        // 1. Check if already shown
        if (overlayController?.isShown() == true) {
            Log.d(TAG, "OVERLAY_SHOW_SUCCESS | Already shown")
            overlayController?.setPaused(FaceControlStateManager.isPaused())
            finishOverlayShowCycle()
            return
        }

        // 2. Ensure controller exists and is fresh if needed
        if (overlayController == null) {
            overlayController = FaceAccessOverlayController(this)
        } else if (!overlayController!!.isShown()) {
            overlayController?.remove()
            overlayController = FaceAccessOverlayController(this)
        }

        // 3. Try to show
        val controller = overlayController!!
        val actuallyAttached = controller.show() && controller.isShown()
        
        if (actuallyAttached) {
            Log.d(TAG, "OVERLAY_SHOW_SUCCESS | Attached")
            controller.setPaused(FaceControlStateManager.isPaused())
            finishOverlayShowCycle()
            return
        }

        // 4. Handle failure and schedule retry
        if (showAttemptCount < MAX_OVERLAY_SHOW_ATTEMPTS) {
            val retryRunnable = Runnable { 
                overlayRetryRunnable = null
                if (overlayShowCycleActive) {
                    performOverlayShowAttempt()
                }
            }
            overlayRetryRunnable = retryRunnable
            mainHandler.postDelayed(retryRunnable, OVERLAY_RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "OVERLAY_SHOW_FAILED | Max attempts reached")
            finishOverlayShowCycle()
        }
    }

    private fun finishOverlayShowCycle() {
        overlayRetryRunnable?.let {
            mainHandler.removeCallbacks(it)
            overlayRetryRunnable = null
        }
        overlayShowCycleActive = false
    }

    private fun removeOverlayAuthority() {
        finishOverlayShowCycle()
        overlayController?.remove()
        overlayController = null
        Log.d(TAG, "OVERLAY_REMOVED_AUTHORITATIVE")
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

    // Dùng WINDOW_STATE_CHANGED làm tín hiệu phục hồi overlay nếu bị mất
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isOverlayEnabled(this) && 
                overlayController?.isShown() != true && 
                !overlayShowCycleActive) {
                
                Log.d(TAG, "RECOVERY_SIGNAL | WINDOW_STATE_CHANGED")
                startOverlayShowCycle()
            }
        }
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
        finishOverlayShowCycle()
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
