package com.example.faceaccessai


class FaceCommandSafetyGate(
    private val minimumCommandIntervalMs: Long = 700L
) {

    enum class Decision {
        NO_COMMAND,
        ALLOWED,
        BLOCKED_UNSAFE_FRAME,
        BLOCKED_COOLDOWN
    }


    data class SafetyResult(
        val command: FaceCommandResolver.FaceCommand,
        val source: FaceCommandResolver.CommandSource,
        val decision: Decision,
        val cooldownRemainingMs: Long
    ) {

        val isAllowed: Boolean
            get() = decision == Decision.ALLOWED
    }


    private var lastAcceptedTimestampMs: Long? = null


    fun evaluate(
        commandResult: FaceCommandResolver.CommandResult,
        isFrameSafe: Boolean,
        timestampMs: Long
    ): SafetyResult {

        if (
            commandResult.command ==
            FaceCommandResolver.FaceCommand.NONE
        ) {

            return createResult(
                commandResult = commandResult,
                decision = Decision.NO_COMMAND,
                cooldownRemainingMs = 0L
            )
        }


        if (!isFrameSafe) {

            return createResult(
                commandResult = commandResult,
                decision = Decision.BLOCKED_UNSAFE_FRAME,
                cooldownRemainingMs = 0L
            )
        }


        val lastTimestamp =
            lastAcceptedTimestampMs


        if (lastTimestamp != null) {

            val elapsedMs =
                (timestampMs - lastTimestamp)
                    .coerceAtLeast(0L)


            if (
                elapsedMs <
                minimumCommandIntervalMs
            ) {

                return createResult(
                    commandResult = commandResult,
                    decision = Decision.BLOCKED_COOLDOWN,
                    cooldownRemainingMs =
                        minimumCommandIntervalMs -
                                elapsedMs
                )
            }
        }


        lastAcceptedTimestampMs =
            timestampMs


        return createResult(
            commandResult = commandResult,
            decision = Decision.ALLOWED,
            cooldownRemainingMs = 0L
        )
    }


    fun reset() {

        lastAcceptedTimestampMs = null
    }


    private fun createResult(
        commandResult: FaceCommandResolver.CommandResult,
        decision: Decision,
        cooldownRemainingMs: Long
    ): SafetyResult {

        return SafetyResult(
            command = commandResult.command,
            source = commandResult.source,
            decision = decision,
            cooldownRemainingMs = cooldownRemainingMs
        )
    }
}