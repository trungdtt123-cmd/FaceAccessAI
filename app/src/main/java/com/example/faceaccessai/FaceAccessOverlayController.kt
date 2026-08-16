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
    private val textViews = mutableListOf<TextView>()
    private var focusedIndex = 0

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
            setPadding(16, 16, 16, 16)
        }

        for (i in 0..3) {
            val textView = TextView(context).apply {
                text = "Ô ${i + 1}"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(32, 32, 32, 32)
                
                val params = GridLayout.LayoutParams()
                params.width = 250
                params.height = 250
                params.setMargins(8, 8, 8, 8)
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
            y = 100
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

        // Luôn làm sạch state local
        overlayView = null
        textViews.clear()
        focusedIndex = 0
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
        clearCandidate()

        // FIX 7: Harden showCandidate
        if (!isOverlayAvailable() || bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val view = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3300FF00")) // Nền xanh lá nhạt
                setStroke(4, Color.GREEN) // Viền xanh lá
                cornerRadius = 8f
            }
        }

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
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
