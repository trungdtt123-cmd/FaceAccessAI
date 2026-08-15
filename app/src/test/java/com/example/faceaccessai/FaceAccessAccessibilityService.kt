package com.example.faceaccessai

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent


class FaceAccessAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.d(
            TAG,
            "Accessibility service connected"
        )
    }


    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        // Chưa xử lý event ở milestone này
    }


    override fun onInterrupt() {

        Log.d(
            TAG,
            "Accessibility service interrupted"
        )
    }


    override fun onDestroy() {
        Log.d(
            TAG,
            "Accessibility service destroyed"
        )

        super.onDestroy()
    }


    companion object {

        private const val TAG =
            "FaceAccessService"
    }
}