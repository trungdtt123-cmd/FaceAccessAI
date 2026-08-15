package com.example.faceaccessai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class FaceCommandSafetyGateTest {

    @Test
    fun secondCommandInsideCooldownIsBlocked() {

        val safetyGate =
            FaceCommandSafetyGate(
                minimumCommandIntervalMs = 700L
            )


        val firstCommand =
            FaceCommandResolver.CommandResult(
                command =
                    FaceCommandResolver.FaceCommand.MOVE_LEFT,
                source =
                    FaceCommandResolver.CommandSource.HEAD_GESTURE
            )


        val secondCommand =
            FaceCommandResolver.CommandResult(
                command =
                    FaceCommandResolver.FaceCommand.MOVE_RIGHT,
                source =
                    FaceCommandResolver.CommandSource.HEAD_GESTURE
            )


        val firstResult =
            safetyGate.evaluate(
                commandResult = firstCommand,
                isFrameSafe = true,
                timestampMs = 1000L
            )


        assertEquals(
            FaceCommandSafetyGate.Decision.ALLOWED,
            firstResult.decision
        )

        assertTrue(
            firstResult.isAllowed
        )


        val blockedResult =
            safetyGate.evaluate(
                commandResult = secondCommand,
                isFrameSafe = true,
                timestampMs = 1500L
            )


        assertEquals(
            FaceCommandSafetyGate.Decision.BLOCKED_COOLDOWN,
            blockedResult.decision
        )

        assertFalse(
            blockedResult.isAllowed
        )

        assertEquals(
            200L,
            blockedResult.cooldownRemainingMs
        )


        val allowedAfterCooldown =
            safetyGate.evaluate(
                commandResult = secondCommand,
                isFrameSafe = true,
                timestampMs = 1700L
            )


        assertEquals(
            FaceCommandSafetyGate.Decision.ALLOWED,
            allowedAfterCooldown.decision
        )

        assertTrue(
            allowedAfterCooldown.isAllowed
        )
    }
}