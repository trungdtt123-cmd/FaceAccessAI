package com.example.faceaccessai

class FaceCommandResolver {

    enum class FaceCommand {
        NONE,
        MOVE_LEFT,
        MOVE_RIGHT,
        MOVE_UP,
        MOVE_DOWN,
        CONFIRM,
        BACK
    }

    data class CommandResult(
        val command: FaceCommand,
        val source: CommandSource
    )

    enum class CommandSource {
        NONE,
        HEAD_GESTURE,
        EYE_GESTURE,
        MOUTH_GESTURE
    }

    fun resolve(
        headGestureResult:
        HeadGestureDetector.HeadGestureResult?,
        temporalResult:
        TemporalGestureDetector.TemporalResult?
    ): CommandResult {

        val temporalEvent =
            temporalResult?.event
                ?: TemporalGestureDetector
                    .GestureEvent.NONE

        // Nhắm mắt có chủ đích tạo CONFIRM
        if (
            temporalEvent ==
            TemporalGestureDetector
                .GestureEvent.INTENTIONAL_EYE_CLOSE
        ) {

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