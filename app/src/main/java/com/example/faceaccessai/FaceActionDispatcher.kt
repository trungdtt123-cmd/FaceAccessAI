package com.example.faceaccessai

import android.util.Log

class FaceActionDispatcher {

    enum class DispatchResult {
        EXECUTED,
        SERVICE_UNAVAILABLE,
        UNSUPPORTED_COMMAND,
        BLOCKED_BY_SAFETY_GATE,
        BLOCKED_BY_FACE_CONTROL_STATE,
        NO_COMMAND
    }

    // Thực thi command đã vượt qua lớp an toàn
    fun dispatch(
        safetyResult: FaceCommandSafetyGate.SafetyResult
    ): DispatchResult {

        if (!safetyResult.isAllowed) {
            return DispatchResult.BLOCKED_BY_SAFETY_GATE
        }

        val command = safetyResult.command

        if (command == FaceCommandResolver.FaceCommand.NONE) {
            return DispatchResult.NO_COMMAND
        }

        // Kiểm tra trạng thái tạm dừng trước khi thực thi
        if (FaceControlStateManager.shouldBlockFaceCommands()) {
            Log.d(
                "FaceControlState",
                "COMMAND_BLOCKED | Command=$command | " +
                        "Reason=${if (FaceControlStateManager.isPaused()) "PAUSED" else "RESUME_GRACE"}"
            )
            return DispatchResult.BLOCKED_BY_FACE_CONTROL_STATE
        }

        return when (command) {

            FaceCommandResolver.FaceCommand.BACK -> {
                if (FaceAccessAccessibilityService.performBack()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.MOVE_LEFT -> {
                if (FaceAccessAccessibilityService.performMoveLeft()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.MOVE_RIGHT -> {
                if (FaceAccessAccessibilityService.performMoveRight()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.MOVE_UP -> {
                // Mặc định thực hiện Cuộn/Vuốt toàn cục (Dùng cho MEDIA/SUPPORT)
                if (FaceAccessAccessibilityService.performGlobalScroll(ScrollDirection.UP)) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.MOVE_DOWN -> {
                if (FaceAccessAccessibilityService.performGlobalScroll(ScrollDirection.DOWN)) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.CONFIRM -> {
                if (FaceAccessAccessibilityService.performConfirm()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.HOME -> {
                if (FaceAccessAccessibilityService.performHome()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.CLICK -> {
                if (FaceAccessCameraService.performCursorClick()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.TOGGLE_CURSOR_LOCK -> {
                FaceAccessCameraService.toggleCursorLock()
                DispatchResult.EXECUTED
            }

            else -> {
                DispatchResult.NO_COMMAND
            }
        }
    }
    
    // Thêm hàm dispatchFocusMove cho chế độ NAVIGATION
    fun dispatchFocusMove(safetyResult: FaceCommandSafetyGate.SafetyResult): DispatchResult {
        if (!safetyResult.isAllowed) return DispatchResult.BLOCKED_BY_SAFETY_GATE
        val command = safetyResult.command
        
        return when (command) {
            FaceCommandResolver.FaceCommand.MOVE_UP -> {
                if (FaceAccessAccessibilityService.performMoveUp()) DispatchResult.EXECUTED else DispatchResult.SERVICE_UNAVAILABLE
            }
            FaceCommandResolver.FaceCommand.MOVE_DOWN -> {
                if (FaceAccessAccessibilityService.performMoveDown()) DispatchResult.EXECUTED else DispatchResult.SERVICE_UNAVAILABLE
            }
            FaceCommandResolver.FaceCommand.MOVE_LEFT -> {
                if (FaceAccessAccessibilityService.performMoveLeft()) DispatchResult.EXECUTED else DispatchResult.SERVICE_UNAVAILABLE
            }
            FaceCommandResolver.FaceCommand.MOVE_RIGHT -> {
                if (FaceAccessAccessibilityService.performMoveRight()) DispatchResult.EXECUTED else DispatchResult.SERVICE_UNAVAILABLE
            }
            else -> dispatch(safetyResult)
        }
    }
}
