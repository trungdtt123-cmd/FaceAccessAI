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
import android.accessibilityservice.GestureDescription
import android.graphics.Path

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

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
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        }

        fun setOverlayEnabled(context: Context, enabled: Boolean) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply()
            
            // Sync live service if running
            val service = instance
            if (service != null) {
                service.mainHandler.post {
                    service.syncOverlayGridVisibility()
                }
            }
        }


        // Thực hiện thao tác Back
        fun performBack(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        // Thực hiện thao tác Home
        fun performHome(): Boolean {
            val service = instance ?: return false
            return service.performGlobalAction(GLOBAL_ACTION_HOME)
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

        // Thực hiện Cuộn/Vuốt toàn cục (không cần vị trí con trỏ cụ thể)
        fun performGlobalScroll(direction: ScrollDirection): Boolean {
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            // Vuốt tại tâm màn hình
            return performScrollAt(metrics.widthPixels / 2f, metrics.heightPixels / 2f, direction)
        }

        // Cập nhật hiển thị tạm dừng trên overlay
        fun setFaceControlPausedVisual(paused: Boolean) {
            instance?.overlayController?.setPaused(paused)
        }

        fun updateCursorPosition(x: Float, y: Float, isLocked: Boolean, isHovering: Boolean) {
            val service = instance ?: return
            val state = when {
                isLocked -> FaceAccessOverlayController.CursorState.LOCKED
                isHovering -> FaceAccessOverlayController.CursorState.HOVERING
                else -> FaceAccessOverlayController.CursorState.NORMAL
            }
            service.mainHandler.post {
                service.overlayController?.updateCursor(x, y, state)
            }
        }

        fun removeCursor() {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.removeCursor()
            }
        }

        fun updateCursorStatus(isEnabled: Boolean, isLocked: Boolean) {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.updateStatus(isEnabled, isLocked)
            }
        }

        fun updateTrackingIndicator(status: FaceLandmarkerHelper.CalibrationTrackingStatus) {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.updateTrackingIndicator(status)
            }
        }

        fun showCursorInstructions() {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.showInstructions()
            }
        }

        fun showCandidate(bounds: Rect) {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.showCandidate(bounds)
            }
        }

        fun clearCandidate() {
            val service = instance ?: return
            service.mainHandler.post {
                service.overlayController?.clearCandidate()
            }
        }

        fun onModeChanged(mode: FaceControlMode) {
            val service = instance ?: return
            
            // Đảm bảo Overlay luôn hiện khi đổi chế độ (để vẽ Cursor/Indicator)
            if (service.overlayController == null || !service.overlayController!!.isShown()) {
                service.startOverlayShowCycle()
            }

            service.mainHandler.post {
                service.overlayController?.resetModeState() // Làm sạch state cũ
                service.syncOverlayGridVisibility() // Cập nhật hiển thị lưới
                
                if (mode == FaceControlMode.CURSOR) {
                    service.overlayController?.showInstructions()
                }
            }
        }

        fun findCursorTarget(cursorX: Float, cursorY: Float): Rect? {
            val service = instance ?: return null
            val root = resolveActiveRoot() ?: return null
            val density = service.resources.displayMetrics.density
            val maxSnapDistance = 60f * density // Giảm phạm vi hút để tránh bám quá chặt vào ứng dụng cũ
            val candidate = service.scanner.findNearestActionableNode(root, cursorX, cursorY, maxSnapDistance)
            return candidate?.bounds
        }

        fun performCursorClickAt(cursorX: Float, cursorY: Float): Boolean {
            val service = instance ?: return false
            val root = resolveActiveRoot() ?: return false
            val density = service.resources.displayMetrics.density
            val maxSnapDistance = 80f * density
            val candidate = service.scanner.findNearestActionableNode(root, cursorX, cursorY, maxSnapDistance)
            if (candidate != null) {
                val node = findNodeByBounds(root, candidate.bounds)
                if (node != null) {
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "CURSOR_CLICK | Success=$result | Target=${candidate.text ?: candidate.className}")
                    return result
                }
            }
            Log.w(TAG, "CURSOR_CLICK_FAILED | No target at ($cursorX, $cursorY)")
            return false
        }

        fun performScrollAt(cursorX: Float, cursorY: Float, direction: ScrollDirection): Boolean {
            val service = instance ?: return false
            val root = resolveActiveRoot() ?: return false
            
            // Tìm node có thể cuộn tại vị trí con trỏ
            val scrollableNode = service.scanner.findScrollableNodeAt(root, cursorX, cursorY) ?: root
            
            // Mapping hướng chuẩn:
            // UP: Vuốt lên (ngón tay đi từ dưới lên) -> ACTION_SCROLL_FORWARD
            // DOWN: Vuốt xuống (ngón tay đi từ trên xuống) -> ACTION_SCROLL_BACKWARD
            val action = when (direction) {
                ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            
            val result = scrollableNode.performAction(action)
            Log.d(TAG, "CURSOR_SCROLL | Direction=$direction | Success=$result | Node=${scrollableNode.className}")
            
            // Nếu ACTION_SCROLL không thành công, thử dùng Gesture để VUỐT (Swipe)
            if (!result) {
                return performSwipeAt(cursorX, cursorY, direction)
            }
            return result
        }

        fun performSwipeAt(cursorX: Float, cursorY: Float, direction: ScrollDirection): Boolean {
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            val swipeLength = 200f * metrics.density
            
            val path = Path()
            path.moveTo(cursorX, cursorY)
            
            when (direction) {
                ScrollDirection.UP -> path.lineTo(cursorX, cursorY - swipeLength)
                ScrollDirection.DOWN -> path.lineTo(cursorX, cursorY + swipeLength)
                ScrollDirection.LEFT -> path.lineTo(cursorX - swipeLength, cursorY)
                ScrollDirection.RIGHT -> path.lineTo(cursorX + swipeLength, cursorY)
            }
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
                
            return service.dispatchGesture(gesture, null, null)
        }

        private fun findNodeByBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 600) {
                val node = queue.removeFirst()
                visited++
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b == bounds) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }

        // Thực hiện CONFIRM trên overlay và scan node
        fun performConfirm(): Boolean {
            val service = instance ?: return false
            val controller = service.overlayController ?: return false
            if (FaceControlStateManager.shouldBlockFaceCommands()) return false
            if (!controller.confirm()) {
                controller.clearCandidate()
                return false
            }
            val focusedIndex = controller.getFocusedIndex()
            val root = resolveActiveRoot()
            if (root == null) {
                controller.clearCandidate()
                Log.w(TAG, "SCAN_UNAVAILABLE | Reason=NO_ROOT")
                return true
            }
            val windowBounds = Rect()
            root.getBoundsInScreen(windowBounds)
            if (windowBounds.isEmpty) {
                val metrics = service.resources.displayMetrics
                windowBounds.set(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
            val candidate = service.scanner.scanQuadrant(root, windowBounds, focusedIndex)
            if (candidate != null) {
                if (!controller.showCandidate(candidate.bounds)) Log.e(TAG, "SCAN_HIGHLIGHT_FAILED")
            } else {
                controller.clearCandidate()
            }
            return true
        }

        fun resolveActiveRoot(): AccessibilityNodeInfo? {
            val service = instance ?: return null
            val activeRoot = service.rootInActiveWindow
            if (activeRoot != null) return activeRoot
            val windowList = service.windows
            if (windowList.isEmpty()) return null
            val activeWin = windowList.find { it.isActive }
            activeWin?.root?.let { return it }
            val focusedWin = windowList.find { it.isFocused }
            focusedWin?.root?.let { return it }
            val appWin = windowList.find { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            appWin?.root?.let { return it }
            return null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
        Log.d(TAG, "Accessibility Service connected")
        
        // Luôn bật OverlayController để xử lý Cursor/Indicator
        startOverlayShowCycle()
    }

    private fun startOverlayShowCycle() {
        if (instance !== this) return
        
        if (overlayController?.isShown() == true) {
            overlayController?.setPaused(FaceControlStateManager.isPaused())
            syncOverlayGridVisibility()
            finishOverlayShowCycle()
            return
        }
        
        if (overlayShowCycleActive) return
        finishOverlayShowCycle()
        showAttemptCount = 0
        overlayShowCycleActive = true
        performOverlayShowAttempt()
    }

    private fun performOverlayShowAttempt() {
        if (instance !== this || !overlayShowCycleActive) {
            finishOverlayShowCycle()
            return
        }
        showAttemptCount++
        
        if (overlayController == null) {
            overlayController = FaceAccessOverlayController(this)
        }
        
        val controller = overlayController!!
        val actuallyAttached = controller.show() && controller.isShown()
        
        if (actuallyAttached) {
            controller.setPaused(FaceControlStateManager.isPaused())
            syncOverlayGridVisibility()
            finishOverlayShowCycle()
            return
        }
        
        if (showAttemptCount < MAX_OVERLAY_SHOW_ATTEMPTS) {
            val retryRunnable = Runnable { 
                overlayRetryRunnable = null
                if (overlayShowCycleActive) performOverlayShowAttempt()
            }
            overlayRetryRunnable = retryRunnable
            mainHandler.postDelayed(retryRunnable, OVERLAY_RETRY_DELAY_MS)
        } else {
            finishOverlayShowCycle()
        }
    }

    /**
     * Đồng bộ hiển thị lưới điều hướng (4 ô) dựa trên Chế độ và Cài đặt
     */
    fun syncOverlayGridVisibility() {
        val service = instance ?: return
        val controller = service.overlayController ?: return
        
        val mode = FaceControlModeManager.getMode(service)
        val prefEnabled = isOverlayEnabled(service)
        
        // Lưới điều hướng chỉ hiện ở NAVIGATION_MODE và nếu được bật trong cài đặt
        val shouldShowGrid = (mode == FaceControlMode.NAVIGATION) && prefEnabled
        
        mainHandler.post {
            controller.setGridVisible(shouldShowGrid)
        }
    }

    private fun finishOverlayShowCycle() {
        overlayRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRetryRunnable = null
        overlayShowCycleActive = false
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isOverlayEnabled(this) && overlayController?.isShown() != true && !overlayShowCycleActive) {
                startOverlayShowCycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        finishOverlayShowCycle()
        overlayController?.remove()
        overlayController = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
