package com.example.faceaccessai

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.TextView

class FaceAccessOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: GridLayout? = null
    private var highlightView: View? = null
    private var cursorView: View? = null
    private var statusView: TextView? = null
    private var trackingIndicator: View? = null
    private var instructionView: View? = null
    private val textViews = mutableListOf<TextView>()
    private var focusedIndex = 0
    private val density = context.resources.displayMetrics.density
    
    private var isCursorAdded = false
    private var isStatusAdded = false
    private var isIndicatorAdded = false

    private var lastStatusText = ""
    private var lastStatusColor = 0
    private var lastCursorX = -1f
    private var lastCursorY = -1f
    private var lastCursorState: CursorState? = null

    private fun dpToPx(dp: Int): Int = (dp * density).toInt()

    companion object {
        private const val TAG = "FaceAccessOverlay"
    }

    // Lấy index đang được focus
    fun getFocusedIndex(): Int {
        return focusedIndex
    }

    // Thiết lập trạng thái tạm dừng cho overlay
    fun setPaused(paused: Boolean) {
        val view = overlayView ?: return
        if (paused) {
            view.alpha = 0.45f
            clearCandidate()
        } else {
            view.alpha = 1.0f
            updateFocus()
        }
    }

    // Hiển thị/Ẩn lưới điều hướng (4 ô)
    fun setGridVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // Kiểm tra overlay có thực sự được hiển thị trên màn hình hay không
    fun isShown(): Boolean {
        return overlayView?.isAttachedToWindow == true
    }

    // Kiểm tra overlay có thực sự khả dụng hay không (internal check cho actions)
    private fun isOverlayAvailable(): Boolean {
        return isShown()
    }

    // Tạo và hiển thị overlay
    fun show(): Boolean {
        // Nếu đã attached thì xem như thành công
        if (isShown()) return true

        // Nếu có view cũ nhưng chưa attached (stale), xóa đi
        if (overlayView != null) {
            overlayView = null
            textViews.clear()
        }

        // Reset trạng thái trước khi tạo mới
        focusedIndex = 0
        textViews.clear()

        val gridLayout = GridLayout(context).apply {
            columnCount = 2
            rowCount = 2
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            
            // Mặc định ẩn, AccessibilityService sẽ gọi syncOverlayGridVisibility để hiện nếu cần
            visibility = View.GONE
        }

        val itemSize = dpToPx(110) // Tăng kích thước ô một chút cho dễ nhìn
        for (i in 0..3) {
            val textView = TextView(context).apply {
                text = "Ô ${i + 1}"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                
                val params = GridLayout.LayoutParams()
                params.width = itemSize
                params.height = itemSize
                params.setMargins(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                layoutParams = params
            }
            textViews.add(textView)
            gridLayout.addView(textView)
        }

        updateFocus()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(80)
        }

        try {
            windowManager.addView(gridLayout, params)
            overlayView = gridLayout
            Log.d(TAG, "OVERLAY_SHOW_SUCCESS")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "OVERLAY_SHOW_FAILED | ${e.javaClass.simpleName}")
            // Cleanup nếu thất bại
            overlayView = null
            textViews.clear()
            return false
        }
    }

    // Reset trạng thái hiển thị khi đổi chế độ
    fun resetModeState() {
        lastCursorX = -1f
        lastCursorY = -1f
        lastCursorState = null
        lastStatusText = ""
        lastStatusColor = 0
        
        removeCursor()
        removeStatus()
        clearCandidate()
    }

    // Xóa overlay khỏi màn hình
    fun remove() {
        val viewToRemove = overlayView
        if (viewToRemove != null) {
            try {
                if (viewToRemove.isAttachedToWindow) {
                    windowManager.removeView(viewToRemove)
                    Log.d(TAG, "OVERLAY_REMOVED")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay view", e)
            }
        }
        
        clearCandidate()
        removeCursor()
        removeStatus()
        removeInstructions()
        removeTrackingIndicator()

        // Luôn làm sạch state local
        overlayView = null
        textViews.clear()
        focusedIndex = 0
        
        isCursorAdded = false
        isStatusAdded = false
        isIndicatorAdded = false
    }

    // Hiển thị trạng thái Cursor
    fun updateStatus(isEnabled: Boolean, isLocked: Boolean) {
        if (!isEnabled) {
            removeStatus()
            return
        }

        val lockText = if (isLocked) "LOCKED" else "READY"
        val lockColor = if (isLocked) Color.RED else Color.GREEN
        val textToShow = "CURSOR: ON | $lockText"

        if (isStatusAdded && textToShow == lastStatusText && lockColor == lastStatusColor) {
            return
        }

        if (isStatusAdded && (statusView == null || !statusView!!.isAttachedToWindow)) {
            isStatusAdded = false
        }

        if (!isStatusAdded) {
            createStatusView()
        }
        
        val view = statusView ?: return
        view.text = textToShow
        view.setTextColor(lockColor)
        
        lastStatusText = textToShow
        lastStatusColor = lockColor
    }

    private fun createStatusView() {
        val view = TextView(context).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(35) // Đẩy sang phải một chút để tránh đè lên indicator
            y = dpToPx(8)
        }

        try {
            windowManager.addView(view, params)
            statusView = view
            isStatusAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status view", e)
        }
    }

    fun removeStatus() {
        statusView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        statusView = null
        isStatusAdded = false
    }

    // Cập nhật chỉ báo tracking mặt
    fun updateTrackingIndicator(status: FaceLandmarkerHelper.CalibrationTrackingStatus) {
        // Fallback check: if view is gone but flag is true, reset it
        if (isIndicatorAdded && (trackingIndicator == null || !trackingIndicator!!.isAttachedToWindow)) {
            isIndicatorAdded = false
        }

        if (!isIndicatorAdded) {
            createTrackingIndicator()
        }

        val view = trackingIndicator ?: return
        val drawable = view.background as? GradientDrawable ?: return
        
        val color = when (status) {
            FaceLandmarkerHelper.CalibrationTrackingStatus.TRACKING_OK -> Color.GREEN
            else -> Color.RED 
        }
        
        drawable.setColor(color)
        
        // Phản hồi nhấp nháy nếu mất mặt
        if (status == FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND) {
            view.alpha = if (System.currentTimeMillis() % 1000 < 500) 0.3f else 1.0f
        } else {
            view.alpha = 1.0f
        }
    }

    private fun createTrackingIndicator() {
        val size = dpToPx(10)
        val view = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.GRAY)
                setStroke(dpToPx(1), Color.WHITE)
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START // Trở về góc TRÁI theo yêu cầu
            x = dpToPx(10)
            y = dpToPx(15)
        }

        try {
            windowManager.addView(view, params)
            trackingIndicator = view
            isIndicatorAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add tracking indicator", e)
        }
    }

    fun removeTrackingIndicator() {
        trackingIndicator?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        trackingIndicator = null
        isIndicatorAdded = false
    }

    // Hiển thị hướng dẫn ngắn
    fun showInstructions() {
        removeInstructions()

        val view = Column(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            
            addView(TextView(context).apply {
                text = "CURSOR MODE ON"
                setTextColor(Color.CYAN)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            
            addView(TextView(context).apply {
                text = "• Quay đầu: Di chuyển\n• Nháy mắt TRÁI: Click\n• Mở miệng 2 lần: Khóa/Mở khóa"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, dpToPx(12), 0, 0)
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(view, params)
            instructionView = view
            
            // Tự động biến mất sau 3 giây (3000ms) theo yêu cầu
            view.postDelayed({
                removeInstructions()
            }, 3000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add instruction view", e)
        }
    }

    fun removeInstructions() {
        instructionView?.let {
            try {
                // Xóa trực tiếp không cần check isAttachedToWindow để đảm bảo chắc chắn biến mất
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        instructionView = null
    }

    // Helper class for instruction view
    private class Column(context: Context) : android.widget.LinearLayout(context)

    // Hiển thị/Cập nhật con trỏ ảo
    fun updateCursor(x: Float, y: Float, state: CursorState = CursorState.NORMAL) {
        // Fallback check
        if (isCursorAdded && (cursorView == null || !cursorView!!.isAttachedToWindow)) {
            isCursorAdded = false
        }

        // Optimization: Skip if minimal movement and same state
        if (isCursorAdded && Math.abs(x - lastCursorX) < 0.5f && Math.abs(y - lastCursorY) < 0.5f && state == lastCursorState) {
            return
        }

        if (!isCursorAdded) {
            createCursor()
        }

        val view = cursorView ?: return
        
        // Cập nhật màu sắc theo trạng thái
        if (state != lastCursorState) {
            val drawable = view.background as? GradientDrawable
            when (state) {
                CursorState.NORMAL -> {
                    drawable?.setColor(Color.BLUE)
                    view.alpha = 1.0f
                }
                CursorState.HOVERING -> {
                    drawable?.setColor(Color.YELLOW)
                    view.alpha = 1.0f
                }
                CursorState.LOCKED -> {
                    drawable?.setColor(Color.parseColor("#C0C0C0")) // Màu bạc (Silver)
                    view.alpha = 0.8f // Tăng độ đậm lên một chút cho dễ nhìn
                }
            }
        }

        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = x.toInt() - params.width / 2
        params.y = y.toInt() - params.height / 2
        
        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
                lastCursorX = x
                lastCursorY = y
                lastCursorState = state
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update cursor layout", e)
        }
    }

    private fun createCursor() {
        val size = dpToPx(24)
        val view = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLUE)
                setStroke(dpToPx(2), Color.WHITE)
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
            cursorView = view
            isCursorAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add cursor view", e)
        }
    }

    fun removeCursor() {
        cursorView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        cursorView = null
        isCursorAdded = false
    }

    enum class CursorState {
        NORMAL,
        HOVERING,
        LOCKED
    }

    // Cập nhật trạng thái focus trực quan
    private fun updateFocus() {
        textViews.forEachIndexed { index, textView ->
            if (index == focusedIndex) {
                textView.setBackgroundColor(Color.BLUE)
            } else {
                textView.setBackgroundColor(Color.DKGRAY)
            }
        }
    }

    // Di chuyển sang trái
    fun moveLeft(): Boolean {
        if (!isOverlayAvailable()) return false
        clearCandidate()
        val oldIndex = focusedIndex
        when (focusedIndex) {
            1 -> focusedIndex = 0
            3 -> focusedIndex = 2
        }
        if (oldIndex != focusedIndex) {
            Log.d(TAG, "MOVE_LEFT | oldIndex=$oldIndex | newIndex=$focusedIndex")
            updateFocus()
        } else {
            Log.d(TAG, "MOVE_LEFT | at boundary | focusedIndex=$focusedIndex")
        }
        return true
    }

    // Di chuyển sang phải
    fun moveRight(): Boolean {
        if (!isOverlayAvailable()) return false
        clearCandidate()
        val oldIndex = focusedIndex
        when (focusedIndex) {
            0 -> focusedIndex = 1
            2 -> focusedIndex = 3
        }
        if (oldIndex != focusedIndex) {
            Log.d(TAG, "MOVE_RIGHT | oldIndex=$oldIndex | newIndex=$focusedIndex")
            updateFocus()
        } else {
            Log.d(TAG, "MOVE_RIGHT | at boundary | focusedIndex=$focusedIndex")
        }
        return true
    }

    // Di chuyển lên trên
    fun moveUp(): Boolean {
        if (!isOverlayAvailable()) return false
        clearCandidate()
        val oldIndex = focusedIndex
        when (focusedIndex) {
            2 -> focusedIndex = 0
            3 -> focusedIndex = 1
        }
        if (oldIndex != focusedIndex) {
            Log.d(TAG, "MOVE_UP | oldIndex=$oldIndex | newIndex=$focusedIndex")
            updateFocus()
        } else {
            Log.d(TAG, "MOVE_UP | at boundary | focusedIndex=$focusedIndex")
        }
        return true
    }

    // Di chuyển xuống dưới
    fun moveDown(): Boolean {
        if (!isOverlayAvailable()) return false
        clearCandidate()
        val oldIndex = focusedIndex
        when (focusedIndex) {
            0 -> focusedIndex = 2
            1 -> focusedIndex = 3
        }
        if (oldIndex != focusedIndex) {
            Log.d(TAG, "MOVE_DOWN | oldIndex=$oldIndex | newIndex=$focusedIndex")
            updateFocus()
        } else {
            Log.d(TAG, "MOVE_DOWN | at boundary | focusedIndex=$focusedIndex")
        }
        return true
    }

    // Xác nhận ô đang được focus
    fun confirm(): Boolean {
        if (!isOverlayAvailable()) return false
        if (focusedIndex >= textViews.size) return false
        
        val confirmedIndex = focusedIndex
        val label = textViews[confirmedIndex].text
        Log.d(TAG, "CONFIRM | focusedIndex=$confirmedIndex | label=$label")
        
        // Phản hồi trực quan đơn giản khi confirm
        textViews[confirmedIndex].setBackgroundColor(Color.GREEN)
        overlayView?.postDelayed({
            if (isOverlayAvailable() && confirmedIndex < textViews.size) {
                // Trả lại màu dựa trên trạng thái focus hiện tại
                if (confirmedIndex == focusedIndex) {
                    textViews[confirmedIndex].setBackgroundColor(Color.BLUE)
                } else {
                    textViews[confirmedIndex].setBackgroundColor(Color.DKGRAY)
                }
            }
        }, 300)
        
        return true
    }

    // Hiển thị highlight cho ứng viên tìm được
    fun showCandidate(bounds: Rect): Boolean {
        // Nếu ở mode Cursor và đang bị LOCKED, không hiện highlight theo yêu cầu
        val currentMode = FaceControlModeManager.getMode(context)
        val cursorLocked = lastCursorState == CursorState.LOCKED
        if (currentMode == FaceControlMode.CURSOR && cursorLocked) {
            clearCandidate()
            return false
        }

        // FIX 7: Harden showCandidate
        if (!isOverlayAvailable() || bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
            clearCandidate()
            return false
        }

        // Nếu đã hiện đúng vị trí rồi thì không làm gì cả
        val currentView = highlightView
        if (currentView != null && currentView.isAttachedToWindow) {
            val params = currentView.layoutParams as WindowManager.LayoutParams
            if (params.x == bounds.left && params.y == bounds.top && params.width == bounds.width() && params.height == bounds.height()) {
                return true
            }
        }

        clearCandidate()

        // Tinh chỉnh: Mở rộng vùng highlight để bao trọn ứng dụng và đẩy cao lên một chút
        val expansion = dpToPx(10) // Tăng độ bao phủ để ôm hết icon/text của ứng dụng
        val yOffset = dpToPx(15) // Đẩy lên cao hơn rõ rệt theo yêu cầu

        val view = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3300FF00")) // Nền xanh lá nhạt
                setStroke(dpToPx(2), Color.GREEN) // Viền xanh lá
                cornerRadius = dpToPx(6).toFloat()
            }
        }

        val params = WindowManager.LayoutParams(
            bounds.width() + expansion * 2,
            bounds.height() + expansion * 2,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left - expansion
            y = bounds.top - expansion - yOffset
        }

        return try {
            windowManager.addView(view, params)
            highlightView = view
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add highlight view", e)
            highlightView = null
            false
        }
    }

    // Xóa highlight ứng viên
    fun clearCandidate() {
        val view = highlightView
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove highlight view", e)
            }
        }
        highlightView = null
    }
}
