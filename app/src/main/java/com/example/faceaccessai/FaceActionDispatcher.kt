package com.example.faceaccessai

class FaceActionDispatcher {

    enum class DispatchResult {
        EXECUTED,
        SERVICE_UNAVAILABLE,
        UNSUPPORTED_COMMAND,
        BLOCKED_BY_SAFETY_GATE,
        NO_COMMAND
    }

    // Thực thi command đã vượt qua lớp an toàn
    fun dispatch(
        safetyResult: FaceCommandSafetyGate.SafetyResult
    ): DispatchResult {

        if (!safetyResult.isAllowed) {
            return DispatchResult.BLOCKED_BY_SAFETY_GATE
        }

        return when (safetyResult.command) {

            FaceCommandResolver.FaceCommand.BACK -> {
                if (FaceAccessAccessibilityService.performBack()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.NONE -> {
                DispatchResult.NO_COMMAND
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
                if (FaceAccessAccessibilityService.performMoveUp()) {
                    DispatchResult.EXECUTED
                } else {
                    DispatchResult.SERVICE_UNAVAILABLE
                }
            }

            FaceCommandResolver.FaceCommand.MOVE_DOWN -> {
                if (FaceAccessAccessibilityService.performMoveDown()) {
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
        }
    }
}