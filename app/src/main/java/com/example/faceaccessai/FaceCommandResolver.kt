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

        // Gesture mắt có chủ ý được ưu tiên
        if (
            temporalResult?.event ==
            TemporalGestureDetector.GestureEvent.INTENTIONAL_EYE_CLOSE
        ) {

            return CommandResult(
                command =
                    FaceCommand.CONFIRM,
                source =
                    CommandSource.EYE_GESTURE
            )
        }


        // Gesture miệng có chủ ý
        if (
            temporalResult?.event ==
            TemporalGestureDetector.GestureEvent.INTENTIONAL_MOUTH_OPEN
        ) {

            return CommandResult(
                command =
                    FaceCommand.BACK,
                source =
                    CommandSource.MOUTH_GESTURE
            )
        }


        // Chớp mắt tự nhiên không tạo lệnh
        if (
            temporalResult?.event ==
            TemporalGestureDetector.GestureEvent.NATURAL_BLINK
        ) {

            return CommandResult(
                command =
                    FaceCommand.NONE,
                source =
                    CommandSource.NONE
            )
        }


        val headEvent =
            headGestureResult?.event
                ?: HeadGestureDetector.GestureEvent.NONE


        val command =
            when (headEvent) {

                HeadGestureDetector.GestureEvent.HEAD_LEFT ->
                    FaceCommand.MOVE_LEFT

                HeadGestureDetector.GestureEvent.HEAD_RIGHT ->
                    FaceCommand.MOVE_RIGHT

                HeadGestureDetector.GestureEvent.HEAD_UP ->
                    FaceCommand.MOVE_UP

                HeadGestureDetector.GestureEvent.HEAD_DOWN ->
                    FaceCommand.MOVE_DOWN

                HeadGestureDetector.GestureEvent.NONE ->
                    FaceCommand.NONE
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
}