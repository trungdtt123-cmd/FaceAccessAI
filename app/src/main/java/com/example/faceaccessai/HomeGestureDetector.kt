package com.example.faceaccessai

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HomeGestureDetector(
    private var config: HomeGestureConfig = 
        GestureSensitivityConfigProvider.homeConfig(GestureSensitivity.BALANCED)
) {

    enum class HomeGestureEvent {
        NONE,
        HOME
    }

    private enum class State {
        IDLE,
        FIRST_TILT_CANDIDATE,
        WAIT_CENTER_CROSSING,
        WAIT_SECOND_TILT,
        SECOND_TILT_CANDIDATE,
        LOCKED_AFTER_HOME
    }

    private enum class Side {
        NONE,
        NEGATIVE, 
        POSITIVE  
    }

    private enum class TiltPath {
        NONE,
        STRONG,
        SOFT_DOMINANT,
        TRAVEL,
        TREND
    }

    private data class TiltEvidence(
        val active: Boolean,
        val side: Side,
        val path: TiltPath,
        val magnitude: Float,
        val directionalTravel: Float,
        val consistency: Float,
        val rollDominance: Float,
        val weight: Float
    )

    private data class PoseSample(
        val timestamp: Long,
        val yaw: Float,
        val pitch: Float,
        val roll: Float
    )

    // --- State Variables ---
    private var state = State.IDLE
    private var firstTiltSide = Side.NONE
    private var firstPeakRoll = 0f
    private var sequenceStartTimestampMs = 0L
    private var lastUpdateTimestampMs = 0L
    
    private var candidateEvidenceAccumulatedMs = 0L
    private var graceStartTimestampMs = 0L
    private var neutralStartTimestampMs = 0L
    
    private val samples = java.util.ArrayDeque<PoseSample>()

    data class HomeGestureResult(
        val event: HomeGestureEvent,
        val state: String
    )

    fun updateConfig(newConfig: HomeGestureConfig) {
        config = newConfig
        reset()
    }

    fun update(
        yaw: Float,
        pitch: Float,
        roll: Float,
        timestampMs: Long
    ): HomeGestureResult {

        // 1. Update Sample Window
        samples.addLast(PoseSample(timestampMs, yaw, pitch, roll))
        while (samples.isNotEmpty() && timestampMs - samples.first.timestamp > config.trendWindowMs) {
            samples.removeFirst()
        }

        val frameDeltaMs = if (lastUpdateTimestampMs == 0L) 0L else (timestampMs - lastUpdateTimestampMs).coerceAtLeast(0L)
        lastUpdateTimestampMs = timestampMs

        // 2. Global Safety & Timeout
        if (state != State.IDLE && state != State.LOCKED_AFTER_HOME) {
            if (timestampMs - sequenceStartTimestampMs > config.sequenceTimeoutMs) {
                Log.d("HomeGesture", "HOME_RESET | Reason=TIMEOUT")
                reset()
            }
            if (isGrossConflict(yaw, pitch)) {
                Log.d("HomeGesture", "HOME_RESET | Reason=GROSS_CONFLICT | Yaw=${String.format("%.1f", yaw)} | Pitch=${String.format("%.1f", pitch)}")
                reset()
            }
        }

        var event = HomeGestureEvent.NONE

        // 3. State Machine
        when (state) {
            State.IDLE -> {
                if (!isGrossConflict(yaw, pitch)) {
                    val evidence = evaluateTiltEvidence(roll)
                    if (evidence.active) {
                        state = State.FIRST_TILT_CANDIDATE
                        firstTiltSide = evidence.side
                        sequenceStartTimestampMs = timestampMs
                        candidateEvidenceAccumulatedMs = 0L
                        graceStartTimestampMs = 0L
                        firstPeakRoll = roll
                        
                        Log.d("HomeGesture", "TILT_CANDIDATE | Stage=FIRST | Side=$firstTiltSide | Path=${evidence.path} | Roll=${String.format("%.1f", roll)}")
                    }
                }
            }

            State.FIRST_TILT_CANDIDATE -> {
                val evidence = evaluateTiltEvidence(roll)
                if (evidence.active && evidence.side == firstTiltSide) {
                    candidateEvidenceAccumulatedMs += (frameDeltaMs * evidence.weight).toLong()
                    graceStartTimestampMs = 0L
                    
                    // Track peak
                    if (firstTiltSide == Side.NEGATIVE) firstPeakRoll = min(firstPeakRoll, roll)
                    else firstPeakRoll = max(firstPeakRoll, roll)

                    if (candidateEvidenceAccumulatedMs >= config.firstEvidenceTargetMs) {
                        state = State.WAIT_CENTER_CROSSING
                        Log.d("HomeGesture", "FIRST_TILT_CONFIRMED | Side=$firstTiltSide | Peak=${String.format("%.1f", firstPeakRoll)}")
                    }
                } else {
                    if (graceStartTimestampMs == 0L) graceStartTimestampMs = timestampMs
                    if (timestampMs - graceStartTimestampMs > config.candidateGraceMs) {
                        reset()
                    }
                }
            }

            State.WAIT_CENTER_CROSSING -> {
                if (detectCenterCrossing(roll)) {
                    state = State.WAIT_SECOND_TILT
                    resetStageSamples(timestampMs, yaw, pitch, roll)
                    // Reset temporal evidence for second side to avoid contamination
                    candidateEvidenceAccumulatedMs = 0L
                    graceStartTimestampMs = 0L
                }
                
                if (calculateClearYawTurn()) {
                    Log.d("HomeGesture", "HOME_RESET | Reason=CLEAR_YAW_TURN_DURING_CENTER")
                    reset()
                }
            }

            State.WAIT_SECOND_TILT -> {
                val evidence = evaluateTiltEvidence(roll)
                val targetSide = if (firstTiltSide == Side.NEGATIVE) Side.POSITIVE else Side.NEGATIVE
                
                if (evidence.active && evidence.side == targetSide) {
                    state = State.SECOND_TILT_CANDIDATE
                    // Credit the detecting frame evidence immediately
                    candidateEvidenceAccumulatedMs = (frameDeltaMs * evidence.weight).toLong()
                    graceStartTimestampMs = 0L
                    Log.d("HomeGesture", "SECOND_TILT_START | Side=$targetSide | Path=${evidence.path}")
                    
                    if (candidateEvidenceAccumulatedMs >= getRequiredSecondEvidenceMs(evidence.path)) {
                        event = triggerHomeAcceptance()
                    }
                } else if (evidence.active && evidence.side == firstTiltSide) {
                    reset()
                }
                
                if (calculateClearYawTurn()) {
                    Log.d("HomeGesture", "HOME_RESET | Reason=CLEAR_YAW_TURN_WAIT_SECOND")
                    reset()
                }
            }

            State.SECOND_TILT_CANDIDATE -> {
                val evidence = evaluateTiltEvidence(roll)
                val targetSide = if (firstTiltSide == Side.NEGATIVE) Side.POSITIVE else Side.NEGATIVE

                if (evidence.active && evidence.side == targetSide) {
                    candidateEvidenceAccumulatedMs += (frameDeltaMs * evidence.weight).toLong()
                    graceStartTimestampMs = 0L

                    if (candidateEvidenceAccumulatedMs >= getRequiredSecondEvidenceMs(evidence.path)) {
                        event = triggerHomeAcceptance()
                    }
                } else {
                    if (graceStartTimestampMs == 0L) graceStartTimestampMs = timestampMs
                    if (timestampMs - graceStartTimestampMs > config.candidateGraceMs) {
                        reset()
                    }
                }
            }

            State.LOCKED_AFTER_HOME -> {
                val isNeutral = abs(roll) <= 6.0f && abs(yaw) <= 24f && abs(pitch) <= 24f
                if (isNeutral) {
                    if (neutralStartTimestampMs == 0L) neutralStartTimestampMs = timestampMs
                    if (timestampMs - neutralStartTimestampMs >= config.unlockNeutralHoldMs) {
                        Log.d("HomeGesture", "HOME_UNLOCK")
                        reset()
                    }
                } else {
                    neutralStartTimestampMs = 0L
                }
            }
        }

        return HomeGestureResult(event, state.name)
    }

    private fun evaluateTiltEvidence(roll: Float): TiltEvidence {
        if (samples.size < 2) return TiltEvidence(false, Side.NONE, TiltPath.NONE, 0f, 0f, 0f, 0f, 0f)

        if (calculateClearYawTurn()) {
            return TiltEvidence(false, Side.NONE, TiltPath.NONE, abs(roll), 0f, 0f, 0f, 0f)
        }

        val first = samples.first()
        val last = samples.last()
        
        val rollTravel = abs(last.roll - first.roll)
        val yawTravel = abs(last.yaw - first.yaw)
        val pitchTravel = abs(last.pitch - first.pitch)
        
        val negRollTravel = (first.roll - last.roll).coerceAtLeast(0f)
        val posRollTravel = (last.roll - first.roll).coerceAtLeast(0f)
        
        val side = if (roll < 0) Side.NEGATIVE else Side.POSITIVE
        val directionalTravel = if (side == Side.NEGATIVE) negRollTravel else posRollTravel
        
        val rollConsistency = calculateConsistency(isHorizontal = false, targetSide = side)
        val rollDom = rollTravel / (rollTravel + 0.85f * yawTravel + 0.35f * pitchTravel + 0.001f)
        
        val mag = abs(roll)
        
        if (mag >= config.strongRollThreshold) {
            return TiltEvidence(true, side, TiltPath.STRONG, mag, directionalTravel, rollConsistency, rollDom, 1.0f)
        }
        
        if (mag >= config.softRollThreshold && directionalTravel >= config.softTravelThreshold && rollDom >= config.rollDominanceSoft && rollConsistency >= config.rollConsistencySoft) {
            return TiltEvidence(true, side, TiltPath.SOFT_DOMINANT, mag, directionalTravel, rollConsistency, rollDom, 0.85f)
        }
        
        if (directionalTravel >= config.travelRollThreshold && mag >= config.travelMinCurrentRoll && rollDom >= 0.45f) {
            return TiltEvidence(true, side, TiltPath.TRAVEL, mag, directionalTravel, rollConsistency, rollDom, 0.90f)
        }
        
        if (mag >= config.travelMinCurrentRoll && directionalTravel >= 4.0f && rollConsistency >= config.rollConsistencyTrend) {
            return TiltEvidence(true, side, TiltPath.TREND, mag, directionalTravel, rollConsistency, rollDom, 0.80f)
        }

        return TiltEvidence(false, Side.NONE, TiltPath.NONE, mag, directionalTravel, rollConsistency, rollDom, 0f)
    }

    private fun detectCenterCrossing(roll: Float): Boolean {
        val peakMag = abs(firstPeakRoll)
        val currentMag = abs(roll)
        val returnTravel = peakMag - currentMag
        val isTowardNeutral = returnTravel > 0 && currentMag < peakMag

        if (currentMag <= config.centerBandThreshold && returnTravel >= config.minimumCenterReturnTravel && isTowardNeutral) {
            Log.d("HomeGesture", "CENTER_PASSED | Path=CENTER_BAND_WITH_RETURN")
            return true
        }
        
        if (firstTiltSide == Side.NEGATIVE && roll > 0.5f) {
            Log.d("HomeGesture", "CENTER_PASSED | Path=SIGN_CROSSING")
            return true
        }
        if (firstTiltSide == Side.POSITIVE && roll < -0.5f) {
            Log.d("HomeGesture", "CENTER_PASSED | Path=SIGN_CROSSING")
            return true
        }
        
        if (peakMag > config.centerBandThreshold + 2.0f) {
            if (returnTravel >= peakMag * config.centerReturnRatio) {
                if (currentMag < peakMag * 0.4f) {
                   Log.d("HomeGesture", "CENTER_PASSED | Path=RETURN_TRAVEL")
                   return true
                }
            }
        }
        
        return false
    }

    private fun calculateClearYawTurn(): Boolean {
        if (samples.size < 2) return false
        val first = samples.first()
        val last = samples.last()
        val yawTravel = abs(last.yaw - first.yaw)
        val rollTravel = abs(last.roll - first.roll)
        val pitchTravel = abs(last.pitch - first.pitch)
        
        val yawSide = if (last.yaw < 0) Side.NEGATIVE else Side.POSITIVE
        val consistency = calculateConsistency(isHorizontal = true, targetSide = yawSide)
        val yawDom = yawTravel / (yawTravel + rollTravel + 0.35f * pitchTravel + 0.001f)
        
        return yawTravel >= config.clearYawTravelThreshold && 
               consistency >= config.clearYawConsistency && 
               yawDom >= config.clearYawDominance &&
               yawTravel > rollTravel + 2.0f
    }

    private fun calculateConsistency(isHorizontal: Boolean, targetSide: Side): Float {
        var matching = 0
        var total = 0
        val list = samples.toList()
        for (i in 1 until list.size) {
            val d = if (isHorizontal) list[i].yaw - list[i-1].yaw else list[i].roll - list[i-1].roll
            if (abs(d) > config.significantDeltaDeg) {
                total++
                val matches = if (targetSide == Side.NEGATIVE) d < 0 else d > 0
                if (matches) matching++
            }
        }
        return if (total > 0) matching.toFloat() / total else 0.5f
    }

    private fun isGrossConflict(yaw: Float, pitch: Float): Boolean {
        return abs(yaw) > config.grossYawLimit || abs(pitch) > config.grossPitchLimit
    }

    private fun resetStageSamples(timestampMs: Long, yaw: Float, pitch: Float, roll: Float) {
        samples.clear()
        samples.addLast(PoseSample(timestampMs, yaw, pitch, roll))
    }

    private fun getRequiredSecondEvidenceMs(path: TiltPath): Long {
        return when (path) {
            TiltPath.STRONG -> config.secondStrongEvidenceMs
            TiltPath.TRAVEL -> config.secondTravelEvidenceMs
            TiltPath.SOFT_DOMINANT -> config.secondSoftDominantEvidenceMs
            TiltPath.TREND -> config.secondTrendEvidenceMs
            else -> 1000L // Effectively fallback
        }
    }

    private fun triggerHomeAcceptance(): HomeGestureEvent {
        state = State.LOCKED_AFTER_HOME
        neutralStartTimestampMs = 0L
        Log.d("HomeGesture", "HOME_ACCEPT")
        return HomeGestureEvent.HOME
    }

    fun reset() {
        state = State.IDLE
        firstTiltSide = Side.NONE
        firstPeakRoll = 0f
        sequenceStartTimestampMs = 0L
        candidateEvidenceAccumulatedMs = 0L
        graceStartTimestampMs = 0L
        neutralStartTimestampMs = 0L
        samples.clear()
    }
}
