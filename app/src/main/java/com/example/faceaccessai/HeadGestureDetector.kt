package com.example.faceaccessai

import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class HeadGestureDetector(
    private val leftYawThresholdDeg: Float = -25f,
    private val rightYawThresholdDeg: Float = 25f,
    private val upPitchThresholdDeg: Float = -25f,
    private val downPitchThresholdDeg: Float = 25f,
    private val centerYawThresholdDeg: Float = 10f,
    private val centerPitchThresholdDeg: Float = 12f,
    private val centerRollThresholdDeg: Float = 10f,
    private val maximumTurnPitchDeg: Float = 20f,
    private val maximumNodYawDeg: Float = 20f,
    private val maximumGestureRollDeg: Float = 15f,
    private val minimumHoldDurationMs: Long = 300L
) {

    // --- Intent V1.1 Constants ---
    private val trendWindowMs = 450L
    private val minimumSignificantDeltaDeg = 0.35f
    private val rollWeight = 0.75f
    private val minimumDirectionalConfidence = 0.60f
    private val candidateGracePeriodMs = 120L
    private val minimumDirectionalConsistency = 0.60f
    private val minimumAxisDominanceMargin = 0.15f
    private val holdRetentionRatio = 0.80f // 80% của threshold chính dùng để duy trì HOLD

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
    private var confidenceDropStartTimestampMs = 0L
    private var conflictStartTimestampMs = 0L

    private var candidateZone: HeadZone? = null
    private var validHoldDurationMs = 0L
    private var lastUpdateTimestampMs = 0L
    private var approachConsistency = 0f 
    private var lockedUntilCenter = false

    // Cập nhật trạng thái head gesture
    fun update(
        pose: HeadPoseSmoother.SmoothedHeadPose,
        timestampMs: Long
    ): HeadGestureResult {

        // Thêm sample mới và dọn dẹp cửa sổ thời gian
        samples.addLast(PoseSample(timestampMs, pose.yawDeg, pose.pitchDeg, pose.rollDeg))
        while (samples.isNotEmpty() && timestampMs - samples.first.timestampMs > trendWindowMs) {
            samples.removeFirst()
        }

        // Phân loại thô dùng threshold kích hoạt (25°)
        val rawClassify = classifyPose(
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg,
            useRetention = false
        )

        // Phân loại dùng threshold duy trì (20°)
        val retentionClassify = classifyPose(
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg,
            useRetention = true
        )

        val actualRetentionZone = retentionClassify.zone

        // Return-to-center lock check first
        if (lockedUntilCenter) {
            if (rawClassify.zone == HeadZone.CENTER) {
                lockedUntilCenter = false
                resetCandidate()
            }

            return createResult(
                event = GestureEvent.NONE,
                zone = rawClassify.zone,
                pose = pose,
                candidateDurationMs = 0L,
                rollGatePassed = rawClassify.rollGatePassed,
                crossAxisGatePassed = rawClassify.crossAxisGatePassed,
                lockedUntilCenter = lockedUntilCenter
            )
        }

        // Giai đoạn xác nhận APPROACH (Ý định bắt đầu)
        if (candidateZone == null) {
            val rawZone = rawClassify.zone
            if (isGestureZone(rawZone)) {
                val metrics = calculateIntentMetrics(rawZone, pose)
                // APPROACH chỉ được xác nhận nếu: confidence, consistency, rollPassed, crossPassed đạt chuẩn
                if (metrics.confidence >= minimumDirectionalConfidence && 
                    metrics.consistency >= minimumDirectionalConsistency &&
                    metrics.rollPassed && metrics.crossPassed) {
                    
                    candidateZone = rawZone
                    validHoldDurationMs = 0L
                    lastUpdateTimestampMs = timestampMs
                    approachConsistency = metrics.consistency
                    
                    // Reset các timer
                    confidenceDropStartTimestampMs = 0L
                    transitionGraceStartTimestampMs = 0L
                    conflictStartTimestampMs = 0L
                    
                    Log.d("HeadGestureIntent", "Decision=APPROACH_CONFIRMED | Direction=$candidateZone | Consistency=${String.format(Locale.US, "%.2f", metrics.consistency)}")
                    Log.d("HeadGestureIntent", "Decision=HOLD_START | Direction=$candidateZone")
                }
            }
            
            if (candidateZone == null) {
                return createResult(
                    event = GestureEvent.NONE,
                    zone = rawClassify.zone,
                    pose = pose,
                    candidateDurationMs = 0L,
                    rollGatePassed = rawClassify.rollGatePassed,
                    crossAxisGatePassed = rawClassify.crossAxisGatePassed,
                    lockedUntilCenter = false
                )
            }
        }

        // Giai đoạn duy trì HOLD (Đã có candidateZone)
        val frameDeltaMs = (timestampMs - lastUpdateTimestampMs).coerceAtLeast(0L)
        lastUpdateTimestampMs = timestampMs

        val metrics = calculateIntentMetrics(candidateZone!!, pose)
        val holdConfidence = metrics.confidence 

        // Định nghĩa Valid Hold Frame
        val isRetentionCorrect = actualRetentionZone == candidateZone
        val isConfidenceOk = holdConfidence >= minimumDirectionalConfidence
        val isNoConflict = metrics.rollPassed && metrics.crossPassed
        val isNotTransition = actualRetentionZone != HeadZone.TRANSITION && actualRetentionZone != HeadZone.REJECTED

        val isValidHoldFrame = isRetentionCorrect && isConfidenceOk && isNoConflict && isNotTransition

        if (isValidHoldFrame) {
            validHoldDurationMs += frameDeltaMs
            // Xóa grace timers khi frame hợp lệ
            transitionGraceStartTimestampMs = 0L
            confidenceDropStartTimestampMs = 0L
            conflictStartTimestampMs = 0L
        } else {
            // Xử lý Invalidity / Grace
            
            // 1. Resets ngay lập tức
            if (rawClassify.zone == HeadZone.CENTER) {
                Log.d("HeadGestureIntent", "Decision=RESET | Phase=HOLD | Reason=RETURNED_CENTER")
                resetCandidate()
                return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
            }
            if (isGestureZone(actualRetentionZone) && actualRetentionZone != candidateZone) {
                Log.d("HeadGestureIntent", "Decision=RESET | Phase=HOLD | Reason=DIRECTION_CHANGE")
                resetCandidate()
                return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
            }

            // 2. Grace Timers (Không cộng validHoldDurationMs)
            
            // Transition/Rejected Grace
            if (!isNotTransition) {
                if (transitionGraceStartTimestampMs == 0L) transitionGraceStartTimestampMs = timestampMs
                if (timestampMs - transitionGraceStartTimestampMs > candidateGracePeriodMs) {
                    Log.d("HeadGestureIntent", "Decision=RESET | Phase=HOLD | Reason=TRANSITION_TIMEOUT")
                    resetCandidate()
                    return createResult(GestureEvent.NONE, rawClassify.zone, pose, 0L, rawClassify.rollGatePassed, rawClassify.crossAxisGatePassed, false)
                }
            } else {
                transitionGraceStartTimestampMs = 0L
            }

            // Confidence Grace
            if (!isConfidenceOk) {
                if (confidenceDropStartTimestampMs == 0L) confidenceDropStartTimestampMs = timestampMs
                if (timestampMs - confidenceDropStartTimestampMs > candidateGracePeriodMs) {
                    Log.d("HeadGestureIntent", "Decision=RESET | Phase=HOLD | Reason=LOW_HOLD_CONFIDENCE")
                    resetCandidate()
                    return createResult(GestureEvent.NONE, HeadZone.REJECTED, pose, 0L, metrics.rollPassed, metrics.crossPassed, false)
                }
            } else {
                confidenceDropStartTimestampMs = 0L
            }

            // Conflict Grace (Roll/Cross-Axis)
            if (!isNoConflict) {
                if (conflictStartTimestampMs == 0L) conflictStartTimestampMs = timestampMs
                if (timestampMs - conflictStartTimestampMs > candidateGracePeriodMs) {
                    Log.d("HeadGestureIntent", "Decision=RESET | Phase=HOLD | Reason=SEVERE_CONFLICT")
                    resetCandidate()
                    return createResult(GestureEvent.NONE, HeadZone.REJECTED, pose, 0L, metrics.rollPassed, metrics.crossPassed, false)
                }
            } else {
                conflictStartTimestampMs = 0L
            }
        }

        // Phát event khi đủ thời gian hold hợp lệ và frame hiện tại phải hợp lệ
        if (validHoldDurationMs >= minimumHoldDurationMs && isValidHoldFrame) {
            
            val event = eventFromZone(candidateZone!!)
            val finalValidDuration = validHoldDurationMs

            Log.d("HeadGestureIntent", "Decision=ACCEPTED | Direction=$candidateZone | " +
                "Primary=${String.format(Locale.US, "%.1f", metrics.primaryMag)} | " +
                "ApproachConsistency=${String.format(Locale.US, "%.2f", approachConsistency)} | " +
                "ValidHoldDuration=${finalValidDuration}ms")

            val zoneToReport = candidateZone!!
            resetCandidate()
            // Sau khi resetCandidate vẫn phải giữ lock
            lockedUntilCenter = true

            return createResult(
                event = event,
                zone = zoneToReport,
                pose = pose,
                candidateDurationMs = finalValidDuration,
                rollGatePassed = metrics.rollPassed,
                crossAxisGatePassed = metrics.crossPassed,
                lockedUntilCenter = true
            )
        }

        return createResult(
            event = GestureEvent.NONE,
            zone = candidateZone ?: rawClassify.zone,
            pose = pose,
            candidateDurationMs = validHoldDurationMs,
            rollGatePassed = metrics.rollPassed,
            crossAxisGatePassed = metrics.crossPassed,
            lockedUntilCenter = false
        )
    }

    private fun calculateIntentMetrics(zone: HeadZone, pose: HeadPoseSmoother.SmoothedHeadPose): IntentMetrics {
        val isHorizontal = zone == HeadZone.LEFT || zone == HeadZone.RIGHT
        val primaryMag = if (isHorizontal) abs(pose.yawDeg) else abs(pose.pitchDeg)
        val crossMag = if (isHorizontal) abs(pose.pitchDeg) else abs(pose.yawDeg)
        val rollMag = abs(pose.rollDeg)

        // Tính dominance riêng cho Roll và Cross-Axis
        val rollDom = primaryMag / (primaryMag + rollMag * rollWeight + 0.001f)
        val crossDom = primaryMag / (primaryMag + crossMag + 0.001f)
        
        val rollPassed = rollDom > 0.5f
        val crossPassed = crossDom > 0.5f
        
        // Confidence dùng dominance thấp nhất làm penalty
        val dominance = min(rollDom, crossDom)

        // Temporal directional consistency
        var matchingDeltas = 0
        var significantDeltas = 0
        val sampleList = samples.toList()
        
        for (i in 1 until sampleList.size) {
            val p1 = sampleList[i-1]
            val p2 = sampleList[i]
            
            val delta = if (isHorizontal) p2.yaw - p1.yaw else p2.pitch - p1.pitch
            
            if (abs(delta) > minimumSignificantDeltaDeg) {
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
        
        val consistency = if (significantDeltas > 0) {
            matchingDeltas.toFloat() / significantDeltas
        } else {
            0.5f
        }

        val threshold = when (zone) {
            HeadZone.LEFT -> abs(leftYawThresholdDeg)
            HeadZone.RIGHT -> abs(rightYawThresholdDeg)
            HeadZone.UP -> abs(upPitchThresholdDeg)
            HeadZone.DOWN -> abs(downPitchThresholdDeg)
            else -> 25f
        }
        val amplitudeScore = (primaryMag / threshold).coerceIn(0f, 1f)

        // Trong HOLD, nếu người dùng đứng yên, consistency không còn là điều kiện bắt buộc để duy trì confidence
        val effectiveConsistency = if (candidateZone != null) {
            max(consistency, 0.6f) 
        } else {
            consistency
        }

        val confidence = effectiveConsistency * 0.50f + dominance * 0.30f + amplitudeScore * 0.20f

        return IntentMetrics(consistency, rollDom, crossDom, confidence, primaryMag, rollPassed, crossPassed)
    }

    // Phân loại tư thế đầu (V1.3: Xử lý axis ambiguity + Retention)
    private fun classifyPose(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        useRetention: Boolean = false
    ): ClassificationResult {

        val retention = if (useRetention) holdRetentionRatio else 1.0f

        val isCenter = abs(yawDeg) <= centerYawThresholdDeg * retention &&
                       abs(pitchDeg) <= centerPitchThresholdDeg * retention &&
                       abs(rollDeg) <= centerRollThresholdDeg * retention

        if (isCenter) {
            return ClassificationResult(HeadZone.CENTER, true, true)
        }

        // Tính normalized activation strength
        val normYaw = if (yawDeg <= leftYawThresholdDeg * retention) abs(yawDeg) / abs(leftYawThresholdDeg)
                      else if (yawDeg >= rightYawThresholdDeg * retention) abs(yawDeg) / rightYawThresholdDeg
                      else 0f

        val normPitch = if (pitchDeg <= upPitchThresholdDeg * retention) abs(pitchDeg) / abs(upPitchThresholdDeg)
                        else if (pitchDeg >= downPitchThresholdDeg * retention) abs(pitchDeg) / downPitchThresholdDeg
                        else 0f

        // Nếu không có trục nào vượt ngưỡng rõ rệt
        if (normYaw <= 0.001f && normPitch <= 0.001f) {
            return ClassificationResult(HeadZone.TRANSITION, true, true)
        }

        // Chọn trục chi phối
        val useYaw: Boolean = if (abs(normYaw - normPitch) > minimumAxisDominanceMargin) {
            normYaw > normPitch
        } else {
            // Trường hợp quá gần nhau, dùng temporal evidence
            val yawConsistency = calculateRawConsistency(isHorizontal = true)
            val pitchConsistency = calculateRawConsistency(isHorizontal = false)
            
            if (abs(yawConsistency - pitchConsistency) > 0.1f) {
                yawConsistency > pitchConsistency
            } else {
                // Vẫn không rõ -> TRANSITION
                return ClassificationResult(HeadZone.TRANSITION, true, true)
            }
        }

        return if (useYaw) {
            val zone = if (yawDeg < 0) HeadZone.LEFT else HeadZone.RIGHT
            ClassificationResult(
                zone = zone,
                rollGatePassed = abs(rollDeg) <= maximumGestureRollDeg,
                crossAxisGatePassed = abs(pitchDeg) <= maximumTurnPitchDeg
            )
        } else {
            val zone = if (pitchDeg < 0) HeadZone.UP else HeadZone.DOWN
            ClassificationResult(
                zone = zone,
                rollGatePassed = abs(rollDeg) <= maximumGestureRollDeg,
                crossAxisGatePassed = abs(yawDeg) <= maximumNodYawDeg
            )
        }
    }

    private fun calculateRawConsistency(isHorizontal: Boolean): Float {
        var posDeltas = 0
        var negDeltas = 0
        var sigCount = 0
        val list = samples.toList()
        
        for (i in 1 until list.size) {
            val d = if (isHorizontal) list[i].yaw - list[i-1].yaw else list[i].pitch - list[i-1].pitch
            if (abs(d) > minimumSignificantDeltaDeg) {
                sigCount++
                if (d > 0) posDeltas++ else negDeltas++
            }
        }
        return if (sigCount > 0) max(posDeltas, negDeltas).toFloat() / sigCount else 0f
    }

    private fun eventFromZone(zone: HeadZone): GestureEvent = when (zone) {
        HeadZone.LEFT -> GestureEvent.HEAD_LEFT
        HeadZone.RIGHT -> GestureEvent.HEAD_RIGHT
        HeadZone.UP -> GestureEvent.HEAD_UP
        HeadZone.DOWN -> GestureEvent.HEAD_DOWN
        else -> GestureEvent.NONE
    }

    private fun isGestureZone(zone: HeadZone): Boolean = 
        zone == HeadZone.LEFT || zone == HeadZone.RIGHT || zone == HeadZone.UP || zone == HeadZone.DOWN

    private fun createResult(
        event: GestureEvent,
        zone: HeadZone,
        pose: HeadPoseSmoother.SmoothedHeadPose,
        candidateDurationMs: Long,
        rollGatePassed: Boolean,
        crossAxisGatePassed: Boolean,
        lockedUntilCenter: Boolean
    ): HeadGestureResult {

        return HeadGestureResult(
            event = event,
            zone = zone,
            yawDeg = pose.yawDeg,
            pitchDeg = pose.pitchDeg,
            rollDeg = pose.rollDeg,
            candidateDurationMs = candidateDurationMs,
            rollGatePassed = rollGatePassed,
            crossAxisGatePassed = crossAxisGatePassed,
            lockedUntilCenter = lockedUntilCenter
        )
    }

    // Hủy candidate nhưng giữ khóa chống lặp
    fun interrupt() {
        resetCandidate()
        samples.clear()
        transitionGraceStartTimestampMs = 0L
        confidenceDropStartTimestampMs = 0L
        conflictStartTimestampMs = 0L
    }

    private fun resetCandidate() {
        candidateZone = null
        validHoldDurationMs = 0L
        lastUpdateTimestampMs = 0L
        approachConsistency = 0f
        transitionGraceStartTimestampMs = 0L
        confidenceDropStartTimestampMs = 0L
        conflictStartTimestampMs = 0L
    }

    // Reset toàn bộ detector
    fun reset() {
        resetCandidate()
        lockedUntilCenter = false
        samples.clear()
    }
}
