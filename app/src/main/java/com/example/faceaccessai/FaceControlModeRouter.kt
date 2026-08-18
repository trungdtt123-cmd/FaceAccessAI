package com.example.faceaccessai

import android.content.Context
import android.util.Log

class FaceControlModeRouter(
    private val context: Context,
    private val mediaControlManager: MediaControlManager,
    private val actionDispatcher: FaceActionDispatcher
) {

    enum class RoutingResult {
        NAVIGATION,
        MEDIA_DISPATCHED,
        MEDIA_FAILED,
        IGNORED,
        BLOCKED
    }

    fun route(safetyResult: FaceCommandSafetyGate.SafetyResult): RoutingResult {
        if (!safetyResult.isAllowed) {
            return RoutingResult.BLOCKED
        }

        val mode = FaceControlModeManager.getMode(context)
        val command = safetyResult.command

        if (mode == FaceControlMode.NAVIGATION) {
            val result = actionDispatcher.dispatch(safetyResult)
            Log.d(TAG, "NAVIGATION_MODE | Command=$command | Result=$result")
            return RoutingResult.NAVIGATION
        }

        // MEDIA MODE logic
        return when (command) {
            FaceCommandResolver.FaceCommand.MOVE_LEFT -> {
                dispatchMedia(MediaControlManager.MediaAction.PREVIOUS)
            }
            FaceCommandResolver.FaceCommand.MOVE_RIGHT -> {
                dispatchMedia(MediaControlManager.MediaAction.NEXT)
            }
            FaceCommandResolver.FaceCommand.CONFIRM -> {
                dispatchMedia(MediaControlManager.MediaAction.PLAY_PAUSE)
            }
            FaceCommandResolver.FaceCommand.MOVE_UP,
            FaceCommandResolver.FaceCommand.MOVE_DOWN -> {
                Log.d(TAG, "MEDIA_MODE | Command=$command | Ignored")
                RoutingResult.IGNORED
            }
            FaceCommandResolver.FaceCommand.BACK,
            FaceCommandResolver.FaceCommand.HOME -> {
                val result = actionDispatcher.dispatch(safetyResult)
                Log.d(TAG, "MEDIA_MODE_GLOBAL | Command=$command | Result=$result")
                RoutingResult.NAVIGATION
            }
            else -> RoutingResult.IGNORED
        }
    }

    private fun dispatchMedia(action: MediaControlManager.MediaAction): RoutingResult {
        if (FaceControlStateManager.shouldBlockFaceCommands()) {
            Log.d(TAG, "MEDIA_ACTION_BLOCKED | Action=$action")
            return RoutingResult.BLOCKED
        }

        val result = mediaControlManager.dispatch(action)
        Log.d(TAG, "MEDIA_ACTION_DISPATCHED | Action=$action | Result=$result")

        return if (result == MediaControlManager.MediaControlResult.DISPATCHED) {
            RoutingResult.MEDIA_DISPATCHED
        } else {
            RoutingResult.MEDIA_FAILED
        }
    }

    companion object {
        private const val TAG = "FaceControlRouter"
    }
}
