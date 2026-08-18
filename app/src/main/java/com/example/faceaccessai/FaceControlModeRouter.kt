package com.example.faceaccessai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class FaceControlModeRouter(
    private val context: Context,
    private val mediaControlManager: MediaControlManager,
    private val actionDispatcher: FaceActionDispatcher,
    private val supportCallController: SupportCallController
) {

    enum class RoutingResult {
        NAVIGATION,
        MEDIA_DISPATCHED,
        MEDIA_FAILED,
        SUPPORT_SELECTED,
        SUPPORT_DIALER_OPENED,
        SUPPORT_FAILED,
        IGNORED,
        BLOCKED
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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

        if (mode == FaceControlMode.MEDIA) {
            return routeMediaMode(command, safetyResult)
        }

        if (mode == FaceControlMode.SUPPORT) {
            return routeSupportMode(command, safetyResult)
        }

        return RoutingResult.IGNORED
    }

    private fun routeMediaMode(
        command: FaceCommandResolver.FaceCommand,
        safetyResult: FaceCommandSafetyGate.SafetyResult
    ): RoutingResult {
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

    private fun routeSupportMode(
        command: FaceCommandResolver.FaceCommand,
        safetyResult: FaceCommandSafetyGate.SafetyResult
    ): RoutingResult {
        return when (command) {
            FaceCommandResolver.FaceCommand.MOVE_LEFT -> {
                if (FaceControlStateManager.shouldBlockFaceCommands()) return RoutingResult.BLOCKED
                val res = supportCallController.selectPrevious()
                if (res == SupportCallController.SelectionResult.SUCCESS) {
                    showSelectedContactToast()
                    RoutingResult.SUPPORT_SELECTED
                } else {
                    showNoContactsToast()
                    RoutingResult.SUPPORT_FAILED
                }
            }
            FaceCommandResolver.FaceCommand.MOVE_RIGHT -> {
                if (FaceControlStateManager.shouldBlockFaceCommands()) return RoutingResult.BLOCKED
                val res = supportCallController.selectNext()
                if (res == SupportCallController.SelectionResult.SUCCESS) {
                    showSelectedContactToast()
                    RoutingResult.SUPPORT_SELECTED
                } else {
                    showNoContactsToast()
                    RoutingResult.SUPPORT_FAILED
                }
            }
            FaceCommandResolver.FaceCommand.CONFIRM -> {
                if (FaceControlStateManager.shouldBlockFaceCommands()) return RoutingResult.BLOCKED
                val res = supportCallController.openSelectedDialer()
                when (res) {
                    SupportCallController.OpenResult.SUCCESS -> RoutingResult.SUPPORT_DIALER_OPENED
                    SupportCallController.OpenResult.NO_CONTACTS -> {
                        showNoContactsToast()
                        RoutingResult.SUPPORT_FAILED
                    }
                    SupportCallController.OpenResult.FAILED -> RoutingResult.SUPPORT_FAILED
                }
            }
            FaceCommandResolver.FaceCommand.MOVE_UP,
            FaceCommandResolver.FaceCommand.MOVE_DOWN -> {
                RoutingResult.IGNORED
            }
            FaceCommandResolver.FaceCommand.BACK,
            FaceCommandResolver.FaceCommand.HOME -> {
                val result = actionDispatcher.dispatch(safetyResult)
                RoutingResult.NAVIGATION
            }
            else -> RoutingResult.IGNORED
        }
    }

    private fun showSelectedContactToast() {
        val contact = supportCallController.getSelectedContact() ?: return
        val displayName = if (contact.name.isNotEmpty()) contact.name else contact.phone
        mainHandler.post {
            Toast.makeText(context, "Đã chọn: $displayName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNoContactsToast() {
        mainHandler.post {
            Toast.makeText(context, "Chưa có liên hệ hỗ trợ.", Toast.LENGTH_SHORT).show()
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
