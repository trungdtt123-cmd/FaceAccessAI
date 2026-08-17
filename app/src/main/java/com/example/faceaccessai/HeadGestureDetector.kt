package com.example.faceaccessai

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class HeadGestureDetector(
    private var config: HeadGestureConfig = 
        GestureSensitivityConfigProvider.headConfig(GestureSensitivity.BALANCED)
) {

    enum class GestureEvent {
        NONE,
        HEAD_LEFT,
        HEAD_RIGHT,
        HEAD_UP,
        HEAD_DOWN
    }

    enum class HeadZone {
        CENTER,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        TRANSITION,
        REJECTED
    }

    enum class DetectionPath {
        NONE,
        STRONG,
        SOFT_TREND,
        TRAVEL
    }

    private data class PitchEvidence(
        val active: Boolean,
        val zone: HeadZone,
        val path: DetectionPath,
        val magnitude: Float,
        val directionalTravel: Float,
        val consistency: Float
    )

    private data class YawEvidence(
        val active: Boolean,
        val zone: HeadZone,
        val path: DetectionPath,
        val magnitude: Float,
        val directionalTravel: Float,
        val consistency: Float,
        val dominance: Float
    )

    data class HeadGestureResult(
        val event: GestureEvent,
        val zone: HeadZone,
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
        val candidateDurationMs: Long,
        val rollGatePassed: Boolean,
        val crossAxisGatePassed: Boolean,
        val lockedUntilCenter: Boolean
    ) {
        val pitchGatePassed: Boolean
            get() = crossAxisGatePassed
    }

    private data class ClassificationResult(
        val zone: HeadZone,
        val rollGatePassed: Boolean,
        val crossAxisGatePassed: Boolean
    )

    private data class PoseSample(
        val timestampMs: Long,
        val yaw: Float,
        val pitch: Float,
        val roll: Float
    )

    private data class IntentMetrics(
        val consistency: Float,
        val rollDominance: Float,
        val crossDominance: Float,
        val confidence: Float,
        val primaryMag: Float,
        val rollPassed: Boolean,
        val crossPassed: Boolean
    )

    private val samples = java.util.ArrayDeque<PoseSample>()
    
    private var transitionGraceStartTimestampMs = 0L

    private var candidateZone: HeadZone? = null
    private var candidateStartPath: DetectionPath = DetectionPath.NONE
    private var validHoldDurationMs = 0L
    private var lastUpdateTimestampMs = 0L
    private var approachConsistency = 0f 
    private var lockedUntilCenter = false

    fun updateConfig(newConfig: HeadGestureConfig) {
        config = newConfig
        reset()
    }

    fun update(
        pose: HeadPoseSmoother.SmoothedHeadPose,
        timestampMs: Long
    ): HeadGestureResult {

        samples.addLast(PoseSample(timestampMs, pose.yawDeg, pose.pitchDeg, pose.rollDeg))
        while (samples.isNotEmpty() && timestampMs - samples.first.timestampMs > config.trendWindowMs) {
            samples.removeFirst()
        }

        val frameDeltaMs = if (lastUpdateTimestampMs == 0L) 0L else (timestampMs - lastUpdateTimestampMs).coerceAtLeast(0L)
        lastUpdateTimestampMs = timestampMs

        val rawClassify = classifyPose(
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg
        )

        if (lockedUntilCenter) {
            if (rawClassify.zone == HeadZone.CENTER) {
                lockedUntilCenter = false
                resetCandidate()
                Log.d("HeadGesture", "HEAD_UNLOCK")
            }
            return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, lockedUntilCenter)
        }

        // --- PHASE 1: Candidate Discovery (APPROACH) ---
        if (candidateZone == null) {
            val yawEv = evaluateYawEvidence(pose)
            val pitchUpEv = evaluatePitchEvidence(HeadZone.UP, pose)
            val pitchDownEv = evaluatePitchEvidence(HeadZone.DOWN, pose)
            
            // Choose the best evidence
            var bestZone = HeadZone.CENTER
            var bestPath = DetectionPath.NONE
            var bestConsistency = 0.5f

            if (yawEv.active) {
                bestZone = yawEv.zone
                bestPath = yawEv.path
                bestConsistency = yawEv.consistency
            }

            if (pitchUpEv.active) {
                // Heuristic: Pitch takes priority if yaw is weak
                if (bestPath == DetectionPath.NONE || pitchUpEv.magnitude > abs(pose.yawDeg)) {
                    bestZone = HeadZone.UP
                    bestPath = pitchUpEv.path
                    bestConsistency = pitchUpEv.consistency
                }
            }

            if (pitchDownEv.active) {
                if (bestPath == DetectionPath.NONE || pitchDownEv.magnitude > abs(pose.yawDeg)) {
                    bestZone = HeadZone.DOWN
                    bestPath = pitchDownEv.path
                    bestConsistency = pitchDownEv.consistency
                }
            }

            if (bestPath != DetectionPath.NONE) {
                candidateZone = bestZone
                candidateStartPath = bestPath
                validHoldDurationMs = 0L
                approachConsistency = bestConsistency
                transitionGraceStartTimestampMs = 0L
                Log.d("HeadGesture", "HEAD_LATCH | Direction=$candidateZone | Path=$bestPath")
            }

            if (candidateZone == null) {
                return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
            }
        }

        // --- PHASE 2: Relaxed Retention (HOLD) ---
        val isHorizontal = candidateZone == HeadZone.LEFT || candidateZone == HeadZone.RIGHT
        val metrics = calculateIntentMetrics(candidateZone!!, pose)
        val isValidHoldFrame: Boolean

        if (isHorizontal) {
            val safetyPass = abs(pose.pitchDeg) <= config.maximumTurnPitchDeg && abs(pose.rollDeg) <= config.maximumGestureRollDeg
            val retentionPass = isYawRetentionAcceptable(candidateZone!!, pose)
            isValidHoldFrame = retentionPass && safetyPass
        } else {
            val pitchEv = evaluatePitchEvidence(candidateZone!!, pose)
            val safetyPass = isPitchSafetyAcceptable(pose, pitchEv)
            val retentionPass = isPitchRetentionAcceptable(candidateZone!!, pose)
            // Pitch multi-path logic in hold: either current pitch active path OR stable relaxed retention
            isValidHoldFrame = (pitchEv.active || retentionPass) && safetyPass
        }

        if (isValidHoldFrame) {
            validHoldDurationMs += frameDeltaMs
            transitionGraceStartTimestampMs = 0L
        } else {
            // Check for reset triggers
            if (rawClassify.zone == HeadZone.CENTER) {
                // Pitch candidates use grace on center, horizontal ones reset sooner if clear center
                if (isHorizontal) {
                   Log.d("HeadGesture", "HEAD_RESET | Reason=CENTER | Direction=$candidateZone")
                   resetCandidate()
                   return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
                }
            }
            
            // Strong opposite movement resets immediately
            val isStrongOpposite = if (candidateZone == HeadZone.UP) pose.pitchDeg >= config.downPitchThresholdDeg 
                                   else if (candidateZone == HeadZone.DOWN) pose.pitchDeg <= config.upPitchThresholdDeg
                                   else if (candidateZone == HeadZone.LEFT) pose.yawDeg >= config.rightYawThresholdDeg
                                   else if (candidateZone == HeadZone.RIGHT) pose.yawDeg <= config.leftYawThresholdDeg
                                   else false
            
            if (isStrongOpposite) {
                Log.d("HeadGesture", "HEAD_RESET | Reason=OPPOSITE | Direction=$candidateZone")
                resetCandidate()
                return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
            }

            // Otherwise use grace period
            if (transitionGraceStartTimestampMs == 0L) transitionGraceStartTimestampMs = timestampMs
            if (timestampMs - transitionGraceStartTimestampMs > config.candidateGracePeriodMs) {
                Log.d("HeadGesture", "HEAD_RESET | Reason=GRACE_EXPIRED | Direction=$candidateZone")
                resetCandidate()
                return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
            }
        }

        // --- PHASE 3: Confirmation (ACCEPT) ---
        val requiredHold = if (isHorizontal) config.horizontalHoldDurationMs else if (candidateStartPath == DetectionPath.STRONG) config.strongHoldDurationMs else config.minimumHoldDurationMs
        
        if (validHoldDurationMs >= requiredHold && isValidHoldFrame) {
            val event = eventFromZone(candidateZone!!)
            Log.d("HeadGesture", "HEAD_ACCEPT | Direction=$candidateZone | Path=$candidateStartPath | Hold=${validHoldDurationMs}ms")
            
            val finalValidDuration = validHoldDurationMs
            val zoneToReport = candidateZone!!
            resetCandidate()
            lockedUntilCenter = true
            return createResult(event, zoneToReport, pose, finalValidDuration, metrics.rollPassed, metrics.crossPassed, true)
        }

        return createResult(GestureEvent.NONE, candidateZone!!, pose, validHoldDurationMs, metrics.rollPassed, metrics.crossPassed, false)
    }

    private fun evaluateYawEvidence(pose: HeadPoseSmoother.SmoothedHeadPose): YawEvidence {
        val side = if (pose.yawDeg < 0) HeadZone.LEFT else HeadZone.RIGHT
        val mag = abs(pose.yawDeg)
        
        val sampleList = samples.toList()
        var directionalTravel = 0f
        if (sampleList.size >= 2) {
            val first = sampleList.first().yaw
            val last = sampleList.last().yaw
            directionalTravel = if (side == HeadZone.LEFT) (first - last).coerceAtLeast(0f) else (last - first).coerceAtLeast(0f)
        }

        val consistency = calculateYawConsistency(side)
        
        val rollMag = abs(pose.rollDeg)
        val pitchMag = abs(pose.pitchDeg)
        val dominance = mag / (mag + rollMag * config.rollWeight + pitchMag * 0.5f + 0.001f)

        val strongThreshold = if (side == HeadZone.LEFT) abs(config.leftYawThresholdDeg) else config.rightYawThresholdDeg
        val softThreshold = if (side == HeadZone.LEFT) config.softLeftYawThresholdDeg else config.softRightYawThresholdDeg

        val isStrong = mag >= strongThreshold && dominance >= 0.5f
        val isSoft = mag >= softThreshold && directionalTravel >= config.yawSoftTravelThresholdDeg && consistency >= config.yawSoftConsistency && dominance >= 0.52f
        val isTravel = directionalTravel >= config.yawTravelThresholdDeg && mag >= config.yawTravelMinMagnitudeDeg && dominance >= 0.45f

        val path = when {
            isStrong -> DetectionPath.STRONG
            isSoft -> DetectionPath.SOFT_TREND
            isTravel -> DetectionPath.TRAVEL
            else -> DetectionPath.NONE
        }

        return YawEvidence(path != DetectionPath.NONE, side, path, mag, directionalTravel, consistency, dominance)
    }

    private fun evaluatePitchEvidence(zone: HeadZone, pose: HeadPoseSmoother.SmoothedHeadPose): PitchEvidence {
        val mag = if (zone == HeadZone.UP) -pose.pitchDeg else pose.pitchDeg
        val sampleList = samples.toList()
        
        var directionalTravel = 0f
        if (sampleList.size >= 2) {
            val first = sampleList.first().pitch
            val last = sampleList.last().pitch
            directionalTravel = if (zone == HeadZone.UP) (first - last).coerceAtLeast(0f) else (last - first).coerceAtLeast(0f)
        }

        val consistency = calculatePitchConsistency(zone)
        
        val strongThreshold = if (zone == HeadZone.UP) abs(config.upPitchThresholdDeg) else config.downPitchThresholdDeg
        val softThreshold = if (zone == HeadZone.UP) config.softUpPitchThresholdDeg else config.softDownPitchThresholdDeg

        val isStrong = mag >= strongThreshold
        val isSoft = mag >= softThreshold && directionalTravel >= config.softPitchTravelThresholdDeg && consistency >= config.softPitchConsistency
        val isTravel = directionalTravel >= config.pitchTravelThresholdDeg && mag >= config.pitchTravelMinMagnitudeDeg

        val path = when {
            isStrong -> DetectionPath.STRONG
            isSoft -> DetectionPath.SOFT_TREND
            isTravel -> DetectionPath.TRAVEL
            else -> DetectionPath.NONE
        }

        return PitchEvidence(path != DetectionPath.NONE, zone, path, mag, directionalTravel, consistency)
    }

    private fun isYawRetentionAcceptable(zone: HeadZone, pose: HeadPoseSmoother.SmoothedHeadPose): Boolean {
        val mag = abs(pose.yawDeg)
        
        val requiredMagnitude = when (candidateStartPath) {
            DetectionPath.STRONG -> {
                val threshold = if (zone == HeadZone.LEFT) abs(config.leftYawThresholdDeg) else config.rightYawThresholdDeg
                threshold * config.retentionRatio
            }
            DetectionPath.SOFT_TREND -> {
                val threshold = if (zone == HeadZone.LEFT) config.softLeftYawThresholdDeg else config.softRightYawThresholdDeg
                threshold * config.retentionRatio
            }
            DetectionPath.TRAVEL -> {
                config.yawTravelMinMagnitudeDeg * 0.80f
            }
            else -> {
                val threshold = if (zone == HeadZone.LEFT) abs(config.leftYawThresholdDeg) else config.rightYawThresholdDeg
                threshold * config.retentionRatio
            }
        }
        
        return mag >= requiredMagnitude
    }

    private fun isPitchRetentionAcceptable(zone: HeadZone, pose: HeadPoseSmoother.SmoothedHeadPose): Boolean {
        val mag = if (zone == HeadZone.UP) -pose.pitchDeg else pose.pitchDeg
        
        val requiredMagnitude = when (candidateStartPath) {
            DetectionPath.STRONG -> {
                val threshold = if (zone == HeadZone.UP) abs(config.upPitchThresholdDeg) else config.downPitchThresholdDeg
                threshold * config.retentionRatio
            }
            DetectionPath.SOFT_TREND -> {
                val threshold = if (zone == HeadZone.UP) config.softUpPitchThresholdDeg else config.softDownPitchThresholdDeg
                threshold * config.retentionRatio
            }
            DetectionPath.TRAVEL -> {
                config.pitchTravelMinMagnitudeDeg * 0.80f
            }
            else -> {
                val threshold = if (zone == HeadZone.UP) abs(config.upPitchThresholdDeg) else config.downPitchThresholdDeg
                threshold * config.retentionRatio
            }
        }
        
        return mag >= requiredMagnitude
    }

    private fun isPitchSafetyAcceptable(pose: HeadPoseSmoother.SmoothedHeadPose, pitchEv: PitchEvidence): Boolean {
        // Gross conflict checks
        if (abs(pose.yawDeg) > 35f || abs(pose.rollDeg) > 30f) return false
        
        val isExpandedCoupling = pitchEv.path == DetectionPath.STRONG || pitchEv.path == DetectionPath.TRAVEL
        
        // If strong or clear travel evidence, allow more coupling
        val yawLimit = if (isExpandedCoupling) 30f else 24f
        val rollLimit = if (isExpandedCoupling) (if (pitchEv.path == DetectionPath.TRAVEL) 24f else 22f) else 18f
        
        return abs(pose.yawDeg) <= yawLimit && abs(pose.rollDeg) <= rollLimit
    }

    private fun calculateYawConsistency(zone: HeadZone): Float {
        var matching = 0
        var total = 0
        val list = samples.toList()
        for (i in 1 until list.size) {
            val d = list[i].yaw - list[i-1].yaw
            if (abs(d) > config.minimumSignificantDeltaDeg) {
                total++
                val matches = if (zone == HeadZone.LEFT) d < 0 else d > 0
                if (matches) matching++
            }
        }
        return if (total > 0) matching.toFloat() / total else 0.5f
    }

    private fun calculatePitchConsistency(zone: HeadZone): Float {
        var matching = 0
        var total = 0
        val list = samples.toList()
        for (i in 1 until list.size) {
            val d = list[i].pitch - list[i-1].pitch
            if (abs(d) > config.minimumSignificantDeltaDeg) {
                total++
                val matches = if (zone == HeadZone.UP) d < 0 else d > 0
                if (matches) matching++
            }
        }
        return if (total > 0) matching.toFloat() / total else 0.5f
    }

    private fun calculateIntentMetrics(zone: HeadZone, pose: HeadPoseSmoother.SmoothedHeadPose): IntentMetrics {
        val isHorizontal = zone == HeadZone.LEFT || zone == HeadZone.RIGHT
        val primaryMag = if (isHorizontal) abs(pose.yawDeg) else abs(pose.pitchDeg)
        val crossMag = if (isHorizontal) abs(pose.pitchDeg) else abs(pose.yawDeg)
        val rollMag = abs(pose.rollDeg)

        val rollDom = primaryMag / (primaryMag + rollMag * config.rollWeight + 0.001f)
        val crossDom = primaryMag / (primaryMag + crossMag + 0.001f)
        
        val rollPassed = rollDom > 0.5f
        val crossPassed = crossDom > 0.5f
        
        val dominance = min(rollDom, crossDom)

        var matchingDeltas = 0
        var significantDeltas = 0
        val sampleList = samples.toList()
        
        for (i in 1 until sampleList.size) {
            val p1 = sampleList[i-1]
            val p2 = sampleList[i]
            val delta = if (isHorizontal) p2.yaw - p1.yaw else p2.pitch - p1.pitch
            if (abs(delta) > config.minimumSignificantDeltaDeg) {
                significantDeltas++
                val matches = when (zone) {
                    HeadZone.LEFT -> delta < 0
                    HeadZone.RIGHT -> delta > 0
                    HeadZone.UP -> delta < 0
                    HeadZone.DOWN -> delta > 0
                    else -> false
                }
                if (matches) matchingDeltas++
            }
        }
        
        val consistency = if (significantDeltas > 0) matchingDeltas.toFloat() / significantDeltas else 0.5f
        val effectiveConsistency = if (candidateZone != null) max(consistency, 0.6f) else consistency

        val threshold = when (zone) {
            HeadZone.LEFT -> abs(config.leftYawThresholdDeg)
            HeadZone.RIGHT -> abs(config.rightYawThresholdDeg)
            HeadZone.UP -> abs(config.upPitchThresholdDeg)
            HeadZone.DOWN -> config.downPitchThresholdDeg
            else -> 25f
        }
        val amplitudeScore = (primaryMag / threshold).coerceIn(0f, 1f)
        val confidence = effectiveConsistency * 0.50f + dominance * 0.30f + amplitudeScore * 0.20f

        return IntentMetrics(consistency, rollDom, crossDom, confidence, primaryMag, rollPassed, crossPassed)
    }

    private fun classifyPose(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ): ClassificationResult {

        val retention = 1.0f

        val isCenter = abs(yawDeg) <= config.centerYawThresholdDeg * retention &&
                       abs(pitchDeg) <= config.centerPitchThresholdDeg * retention &&
                       abs(rollDeg) <= config.centerRollThresholdDeg * retention

        if (isCenter) return ClassificationResult(HeadZone.CENTER, true, true)

        val normYaw = if (yawDeg <= config.leftYawThresholdDeg * retention) abs(yawDeg) / abs(config.leftYawThresholdDeg)
                      else if (yawDeg >= config.rightYawThresholdDeg * retention) abs(yawDeg) / config.rightYawThresholdDeg
                      else 0f

        val normPitch = if (pitchDeg <= config.upPitchThresholdDeg * retention) abs(pitchDeg) / abs(config.upPitchThresholdDeg)
                        else if (pitchDeg >= config.downPitchThresholdDeg * retention) abs(pitchDeg) / config.downPitchThresholdDeg
                        else 0f

        if (normYaw <= 0.001f && normPitch <= 0.001f) return ClassificationResult(HeadZone.TRANSITION, true, true)

        val useYaw: Boolean = if (abs(normYaw - normPitch) > config.minimumAxisDominanceMargin) {
            normYaw > normPitch
        } else {
            val yawConsistency = calculateRawConsistency(isHorizontal = true)
            val pitchConsistency = calculateRawConsistency(isHorizontal = false)
            if (abs(yawConsistency - pitchConsistency) > 0.1f) yawConsistency > pitchConsistency
            else return ClassificationResult(HeadZone.TRANSITION, true, true)
        }

        return if (useYaw) {
            val zone = if (yawDeg < 0) HeadZone.LEFT else HeadZone.RIGHT
            ClassificationResult(zone, abs(rollDeg) <= config.maximumGestureRollDeg, abs(pitchDeg) <= config.maximumTurnPitchDeg)
        } else {
            val zone = if (pitchDeg < 0) HeadZone.UP else HeadZone.DOWN
            ClassificationResult(zone, abs(rollDeg) <= config.maximumGestureRollDeg, abs(yawDeg) <= config.maximumNodYawDeg)
        }
    }

    private fun calculateRawConsistency(isHorizontal: Boolean): Float {
        var posDeltas = 0
        var negDeltas = 0
        var sigCount = 0
        val list = samples.toList()
        for (i in 1 until list.size) {
            val d = if (isHorizontal) list[i].yaw - list[i-1].yaw else list[i].pitch - list[i-1].pitch
            if (abs(d) > config.minimumSignificantDeltaDeg) {
                sigCount++
                if (d > 0) posDeltas++ else negDeltas++
            }
        }
        return if (sigCount > 0) max(posDeltas.toFloat(), negDeltas.toFloat()) / sigCount else 0f
    }

    private fun eventFromZone(zone: HeadZone): GestureEvent = when (zone) {
        HeadZone.LEFT -> GestureEvent.HEAD_LEFT
        HeadZone.RIGHT -> GestureEvent.HEAD_RIGHT
        HeadZone.UP -> GestureEvent.HEAD_UP
        HeadZone.DOWN -> GestureEvent.HEAD_DOWN
        else -> GestureEvent.NONE
    }

    private fun createResult(
        event: GestureEvent,
        zone: HeadZone,
        pose: HeadPoseSmoother.SmoothedHeadPose,
        candidateDurationMs: Long,
        rollGatePassed: Boolean,
        crossAxisGatePassed: Boolean,
        lockedUntilCenter: Boolean
    ): HeadGestureResult {
        return HeadGestureResult(event, zone, pose.yawDeg, pose.pitchDeg, pose.rollDeg, candidateDurationMs, rollGatePassed, crossAxisGatePassed, lockedUntilCenter)
    }

    fun interrupt() {
        resetCandidate()
        samples.clear()
        transitionGraceStartTimestampMs = 0L
    }

    private fun resetCandidate() {
        candidateZone = null
        candidateStartPath = DetectionPath.NONE
        validHoldDurationMs = 0L
        lastUpdateTimestampMs = 0L
        approachConsistency = 0f
        transitionGraceStartTimestampMs = 0L
    }

    fun reset() {
        resetCandidate()
        lockedUntilCenter = false
        samples.clear()
    }
}
