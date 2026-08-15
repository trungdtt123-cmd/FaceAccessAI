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

            FaceCommandResolver.FaceCommand.MOVE_LEFT,
            FaceCommandResolver.FaceCommand.MOVE_RIGHT,
            FaceCommandResolver.FaceCommand.MOVE_UP,
            FaceCommandResolver.FaceCommand.MOVE_DOWN,
            FaceCommandResolver.FaceCommand.CONFIRM -> {
                DispatchResult.UNSUPPORTED_COMMAND
            }
        }
    }
}