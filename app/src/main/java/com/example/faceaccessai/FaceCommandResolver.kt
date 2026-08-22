package com.example.faceaccessai

class FaceCommandResolver {

    enum class FaceCommand {
        NONE,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        CONFIRM,
        BACK,
        HOME,
        CLICK,
        TOGGLE_CURSOR_LOCK
    }

    data class CommandResult(
        val command: FaceCommand,
        val source: CommandSource
    )

    enum class CommandSource {
        NONE,
        HEAD_GESTURE,
        EYE_GESTURE,
        MOUTH_GESTURE,
        HOME_GESTURE
    }

    fun resolve(
        headGestureResult:
        HeadGestureDetector.HeadGestureResult?,
        temporalResult:
        TemporalGestureDetector.TemporalResult?,
        homeGestureResult:
        HomeGestureDetector.HomeGestureResult? = null
    ): CommandResult {

        // Ưu tiên HOME gesture nếu nó vừa hoàn tất
        if (
            homeGestureResult?.event ==
            HomeGestureDetector.HomeGestureEvent.HOME
        ) {

            return CommandResult(
                command =
                    FaceCommand.HOME,
                source =
                    CommandSource.HOME_GESTURE
            )
        }

        val temporalEvent =
            temporalResult?.event
                ?: TemporalGestureDetector
                    .GestureEvent.NONE

        // Mở miệng kép -> TOGGLE_CURSOR_LOCK
        if (temporalEvent == TemporalGestureDetector.GestureEvent.DOUBLE_MOUTH_OPEN) {
            return CommandResult(
                command = FaceCommand.TOGGLE_CURSOR_LOCK,
                source = CommandSource.MOUTH_GESTURE
            )
        }

        // Nhắm mắt PHẢI (Bỏ Click theo yêu cầu người dùng, chuyển sang nhắm mắt chủ ý)
        if (temporalEvent == TemporalGestureDetector.GestureEvent.RIGHT_WINK) {
            return noCommand()
        }

        // Nhắm mắt trái (dành riêng cho việc khác nếu cần, hiện tại không dùng)
        if (temporalEvent == TemporalGestureDetector.GestureEvent.LEFT_WINK) {
            return noCommand()
        }

        // Nhắm cả hai mắt (chủ đích) tạo CONFIRM (có thể dùng làm fallback hoặc action khác)
        if (
            temporalEvent ==
            TemporalGestureDetector
                .GestureEvent.INTENTIONAL_EYE_CLOSE ||
            temporalEvent ==
            TemporalGestureDetector.GestureEvent.BOTH_EYES_CLOSED
        ) {
            // Cả hai mắt nhắm lâu thì tạo CONFIRM
            return CommandResult(
                command =
                    FaceCommand.CONFIRM,
                source =
                    CommandSource.EYE_GESTURE
            )
        }

        // Chu kỳ mở miệng có chủ đích tạo BACK
        if (
            temporalEvent ==
            TemporalGestureDetector
                .GestureEvent.INTENTIONAL_MOUTH_OPEN
        ) {

            return CommandResult(
                command =
                    FaceCommand.BACK,
                source =
                    CommandSource.MOUTH_GESTURE
            )
        }

        // Blink tự nhiên không tạo command
        if (
            temporalEvent ==
            TemporalGestureDetector
                .GestureEvent.NATURAL_BLINK
        ) {

            return noCommand()
        }

        // Ngáp tự nhiên không tạo command
        if (
            temporalEvent ==
            TemporalGestureDetector
                .GestureEvent.NATURAL_YAWN
        ) {

            return noCommand()
        }

        // Trong lúc miệng đang mở, tạm khóa command đầu
        if (
            temporalResult
                ?.isMouthCycleActive ==
            true
        ) {

            return noCommand()
        }

        val headEvent =
            headGestureResult?.event
                ?: HeadGestureDetector
                    .GestureEvent.NONE

        val command =
            when (
                headEvent
            ) {

                HeadGestureDetector
                    .GestureEvent.HEAD_LEFT -> {

                    FaceCommand.MOVE_LEFT
                }

                HeadGestureDetector
                    .GestureEvent.HEAD_RIGHT -> {

                    FaceCommand.MOVE_RIGHT
                }

                HeadGestureDetector
                    .GestureEvent.HEAD_UP -> {

                    FaceCommand.MOVE_UP
                }

                HeadGestureDetector
                    .GestureEvent.HEAD_DOWN -> {

                    FaceCommand.MOVE_DOWN
                }

                HeadGestureDetector
                    .GestureEvent.NONE -> {

                    FaceCommand.NONE
                }
            }

        val source =
            if (
                command !=
                FaceCommand.NONE
            ) {

                CommandSource.HEAD_GESTURE

            } else {

                CommandSource.NONE
            }

        return CommandResult(
            command = command,
            source = source
        )
    }

    // Tạo kết quả không có command
    private fun noCommand():
            CommandResult {

        return CommandResult(
            command =
                FaceCommand.NONE,
            source =
                CommandSource.NONE
        )
    }
}