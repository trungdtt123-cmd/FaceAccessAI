package com.example.faceaccessai

import android.util.Log
import kotlin.math.abs
import kotlin.math.max

class HeadDirectionalCalibrationSession(
    private val onStepChanged: (Step, Set<Direction>) -> Unit,
    private val onComplete: (HeadDirectionalCalibrationProfile) -> Unit,
    private val onCancelled: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    enum class Step {
        IDLE,
        WAIT_NEUTRAL,
        LEFT,
        WAIT_CENTER_AFTER_LEFT,
        RIGHT,
        WAIT_CENTER_AFTER_RIGHT,
        UP,
        WAIT_CENTER_AFTER_UP,
        DOWN,
        COMPLETE
    }

    enum class Direction {
        LEFT, RIGHT, UP, DOWN
    }

    private var currentStep = Step.IDLE
    private val passedDirections = mutableSetOf<Direction>()
    
    // V3: Sample collections
    private val neutralYawSamples = mutableListOf<Float>()
    private val neutralPitchSamples = mutableListOf<Float>()
    private val neutralRollSamples = mutableListOf<Float>()
    private val neutralFaceScaleSamples = mutableListOf<Float>()

    private val directionalPrimaryMagnitudes = mutableListOf<Float>()
    private val directionalCrossMagnitudes = mutableListOf<Float>()

    private var centerStartTimestampMs = 0L
    private var isHoldingDirection = false
    private var validHoldDurationMs = 0L
    private var lastValidDirectionTimestampMs = 0L

    // V3 derived session thresholds
    private var horizontalActivationDeg = 19f // Initial default, overwritten by neutral
    private var verticalActivationDeg = 10f   // Initial default, overwritten by neutral

    // V3 final values for profile
    private var leftIntentYaw = 0f
    private var rightIntentYaw = 0f
    private var upIntentPitch = 0f
    private var downIntentPitch = 0f
    
    private var leftConfidence = 0f
    private var rightConfidence = 0f
    private var upConfidence = 0f
    private var downConfidence = 0f

    private var neutralYawNoise = 0f
    private var neutralPitchNoise = 0f
    private var neutralRollNoise = 0f
    private var referenceFaceScale = 1f

    // Constants
    private val neutralStableMs = 400L
    private val minNeutralSamples = 6
    
    private val directionHoldMs = 220L
    private val minDirectionalSamples = 5
    private val returnCenterMs = 250L
    
    private val centerThresholdDeg = 5f
    private val centerRollThresholdDeg = 10f
    private val retentionRatio = 0.70f
    
    private val maxYawDeg = 50f
    private val maxPitchDeg = 40f

    fun start() {
        moveToStep(Step.WAIT_NEUTRAL)
    }

    fun update(pose: HeadPoseSmoother.SmoothedHeadPose, timestampMs: Long, faceScale: Float) {
        if (!pose.yawDeg.isFinite() || !pose.pitchDeg.isFinite() || !pose.rollDeg.isFinite()) {
            pauseCurrentProgress()
            return
        }

        val isCentered = isAtCenter(pose)
        
        when (currentStep) {
            Step.WAIT_NEUTRAL -> {
                if (!faceScale.isFinite() || faceScale <= 0f) {
                    resetUnfinishedNeutral()
                    return
                }

                if (isCentered) {
                    if (centerStartTimestampMs == 0L) centerStartTimestampMs = timestampMs
                    
                    neutralYawSamples.add(pose.yawDeg)
                    neutralPitchSamples.add(pose.pitchDeg)
                    neutralRollSamples.add(pose.rollDeg)
                    neutralFaceScaleSamples.add(faceScale)

                    if (timestampMs - centerStartTimestampMs >= neutralStableMs && 
                        neutralYawSamples.size >= minNeutralSamples) {
                        
                        calculateNeutralStatistics()
                        moveToStep(Step.LEFT)
                    }
                } else {
                    resetUnfinishedNeutral()
                }
            }

            Step.LEFT -> handleDirectionStep(
                pose = pose,
                timestampMs = timestampMs,
                isCentered = isCentered,
                activationCheck = { pose.yawDeg <= -horizontalActivationDeg },
                retentionCheck = { pose.yawDeg <= -horizontalActivationDeg * retentionRatio },
                isHorizontal = true,
                onPass = { intent, conf ->
                    leftIntentYaw = intent
                    leftConfidence = conf
                    passedDirections.add(Direction.LEFT)
                    moveToStep(Step.WAIT_CENTER_AFTER_LEFT)
                }
            )

            Step.WAIT_CENTER_AFTER_LEFT -> handleWaitCenter(isCentered, timestampMs, Step.RIGHT)
            
            Step.RIGHT -> handleDirectionStep(
                pose = pose,
                timestampMs = timestampMs,
                isCentered = isCentered,
                activationCheck = { pose.yawDeg >= horizontalActivationDeg },
                retentionCheck = { pose.yawDeg >= horizontalActivationDeg * retentionRatio },
                isHorizontal = true,
                onPass = { intent, conf ->
                    rightIntentYaw = intent
                    rightConfidence = conf
                    passedDirections.add(Direction.RIGHT)
                    moveToStep(Step.WAIT_CENTER_AFTER_RIGHT)
                }
            )

            Step.WAIT_CENTER_AFTER_RIGHT -> handleWaitCenter(isCentered, timestampMs, Step.UP)

            Step.UP -> handleDirectionStep(
                pose = pose,
                timestampMs = timestampMs,
                isCentered = isCentered,
                activationCheck = { pose.pitchDeg <= -verticalActivationDeg },
                retentionCheck = { pose.pitchDeg <= -verticalActivationDeg * retentionRatio },
                isHorizontal = false,
                onPass = { intent, conf ->
                    upIntentPitch = intent
                    upConfidence = conf
                    passedDirections.add(Direction.UP)
                    moveToStep(Step.WAIT_CENTER_AFTER_UP)
                }
            )

            Step.WAIT_CENTER_AFTER_UP -> handleWaitCenter(isCentered, timestampMs, Step.DOWN)

            Step.DOWN -> handleDirectionStep(
                pose = pose,
                timestampMs = timestampMs,
                isCentered = isCentered,
                activationCheck = { pose.pitchDeg >= verticalActivationDeg },
                retentionCheck = { pose.pitchDeg >= verticalActivationDeg * retentionRatio },
                isHorizontal = false,
                onPass = { intent, conf ->
                    downIntentPitch = intent
                    downConfidence = conf
                    passedDirections.add(Direction.DOWN)
                    
                    val profile = HeadDirectionalCalibrationProfile(
                        leftIntentYawDeg = leftIntentYaw,
                        rightIntentYawDeg = rightIntentYaw,
                        upIntentPitchDeg = upIntentPitch,
                        downIntentPitchDeg = downIntentPitch,
                        neutralYawNoiseDeg = neutralYawNoise,
                        neutralPitchNoiseDeg = neutralPitchNoise,
                        neutralRollNoiseDeg = neutralRollNoise,
                        leftConfidence = leftConfidence,
                        rightConfidence = rightConfidence,
                        upConfidence = upConfidence,
                        downConfidence = downConfidence,
                        referenceFaceScale = referenceFaceScale,
                        version = 3
                    )
                    moveToStep(Step.COMPLETE)
                    onComplete(profile)
                }
            )
            else -> {}
        }
    }

    private fun handleDirectionStep(
        pose: HeadPoseSmoother.SmoothedHeadPose,
        timestampMs: Long,
        isCentered: Boolean,
        activationCheck: () -> Boolean,
        retentionCheck: () -> Boolean,
        isHorizontal: Boolean,
        onPass: (Float, Float) -> Unit
    ) {
        val primaryMag = if (isHorizontal) abs(pose.yawDeg) else abs(pose.pitchDeg)
        val crossMag = if (isHorizontal) abs(pose.pitchDeg) else abs(pose.yawDeg)
        
        // Gross primary-axis safety limit
        val grossPrimarySafe = primaryMag <= (if (isHorizontal) maxYawDeg else maxPitchDeg)
        
        val crossAxisSafe = if (isHorizontal) {
            abs(pose.pitchDeg) <= 24f && abs(pose.rollDeg) <= 18f
        } else {
            abs(pose.yawDeg) <= 24f && abs(pose.rollDeg) <= 18f
        }

        if (!isHoldingDirection) {
            if (activationCheck() && crossAxisSafe && grossPrimarySafe && !isCentered) {
                isHoldingDirection = true
                validHoldDurationMs = 0L
                lastValidDirectionTimestampMs = timestampMs
                directionalPrimaryMagnitudes.clear()
                directionalCrossMagnitudes.clear()
                
                directionalPrimaryMagnitudes.add(primaryMag)
                directionalCrossMagnitudes.add(crossMag)
            }
        } else {
            // Retention and safety check for evidence collection
            if (retentionCheck() && crossAxisSafe && grossPrimarySafe && !isCentered) {
                directionalPrimaryMagnitudes.add(primaryMag)
                directionalCrossMagnitudes.add(crossMag)
                
                if (lastValidDirectionTimestampMs != 0L) {
                    val delta = (timestampMs - lastValidDirectionTimestampMs).coerceIn(0L, 100L)
                    validHoldDurationMs += delta
                }
                lastValidDirectionTimestampMs = timestampMs

                if (validHoldDurationMs >= directionHoldMs && 
                    directionalPrimaryMagnitudes.size >= minDirectionalSamples) {
                    processDirectionResult(isHorizontal, onPass)
                }
            } else {
                // Hard reset if hold is broken or safety limit exceeded
                resetDirectionAttempt()
            }
        }
    }

    private fun handleWaitCenter(isCentered: Boolean, timestampMs: Long, nextStep: Step) {
        if (isCentered) {
            if (centerStartTimestampMs == 0L) centerStartTimestampMs = timestampMs
            if (timestampMs - centerStartTimestampMs >= returnCenterMs) {
                moveToStep(nextStep)
            }
        } else {
            centerStartTimestampMs = 0L
        }
    }

    private fun isAtCenter(pose: HeadPoseSmoother.SmoothedHeadPose): Boolean {
        return abs(pose.yawDeg) <= centerThresholdDeg && 
               abs(pose.pitchDeg) <= centerThresholdDeg &&
               abs(pose.rollDeg) <= centerRollThresholdDeg
    }

    private fun moveToStep(step: Step) {
        Log.d("HeadCalibration", "Step changed: $currentStep -> $step")
        currentStep = step
        resetDirectionAttempt()
        centerStartTimestampMs = 0L
        
        if (step == Step.WAIT_NEUTRAL) {
            resetUnfinishedNeutral()
        }
        onStepChanged(step, passedDirections.toSet())
    }

    private fun calculateNeutralStatistics() {
        neutralYawNoise = calculateRobustNoise(neutralYawSamples)
        neutralPitchNoise = calculateRobustNoise(neutralPitchSamples)
        neutralRollNoise = calculateRobustNoise(neutralRollSamples)
        referenceFaceScale = calculateMedian(neutralFaceScaleSamples)

        horizontalActivationDeg = (neutralYawNoise * 3f + 4f).coerceIn(7f, 14f)
        verticalActivationDeg = (neutralPitchNoise * 3f + 2.5f).coerceIn(4.5f, 8f)
        
        Log.d("HeadCalibration", "Neutral V3 | YawNoise=$neutralYawNoise | PitchNoise=$neutralPitchNoise | Scale=$referenceFaceScale")
        Log.d("HeadCalibration", "Derived Thresholds | H=$horizontalActivationDeg | V=$verticalActivationDeg")
    }

    private fun processDirectionResult(isHorizontal: Boolean, onPass: (Float, Float) -> Unit) {
        val primaryMedian = calculateMedian(directionalPrimaryMagnitudes)
        
        // Profile intent must align with persistence validation ranges
        val minimumIntent = if (isHorizontal) 5f else 3.5f
        val maximumIntent = if (isHorizontal) 40f else 35f
        
        if (primaryMedian > maximumIntent) {
            resetDirectionAttempt()
            return
        }

        // Stability (measured vs measured)
        val primaryMad = calculateMAD(directionalPrimaryMagnitudes, primaryMedian)
        val stability = (1f - primaryMad / max(primaryMedian, 1f)).coerceIn(0f, 1f)

        // Dominance
        val crossMedian = calculateMedian(directionalCrossMagnitudes.map { abs(it) })
        val dominance = (1f - crossMedian / max(primaryMedian, 1f)).coerceIn(0f, 1f)

        // Sample sufficiency
        val sufficiency = (directionalPrimaryMagnitudes.size / 10f).coerceIn(0f, 1f)

        val confidence = (0.45f * stability + 0.35f * dominance + 0.20f * sufficiency).coerceIn(0f, 1f)

        // Ensure intent stays within valid storage range (benefit-only clamping)
        val boundedIntent = primaryMedian.coerceAtLeast(minimumIntent)

        onPass(boundedIntent, confidence)
    }

    private fun calculateMedian(samples: List<Float>): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun calculateMAD(samples: List<Float>, median: Float): Float {
        if (samples.isEmpty()) return 0f
        val deviations = samples.map { abs(it - median) }
        return calculateMedian(deviations)
    }

    private fun calculateRobustNoise(samples: List<Float>): Float {
        val median = calculateMedian(samples)
        val mad = calculateMAD(samples, median)
        return 1.4826f * mad
    }

    private fun resetDirectionAttempt() {
        isHoldingDirection = false
        validHoldDurationMs = 0L
        lastValidDirectionTimestampMs = 0L
        directionalPrimaryMagnitudes.clear()
        directionalCrossMagnitudes.clear()
    }

    private fun resetUnfinishedNeutral() {
        centerStartTimestampMs = 0L
        neutralYawSamples.clear()
        neutralPitchSamples.clear()
        neutralRollSamples.clear()
        neutralFaceScaleSamples.clear()
    }

    fun pauseCurrentProgress() {
        lastValidDirectionTimestampMs = 0L
        
        if (currentStep == Step.WAIT_NEUTRAL) {
            resetUnfinishedNeutral()
        } else if (currentStep == Step.WAIT_CENTER_AFTER_LEFT ||
                   currentStep == Step.WAIT_CENTER_AFTER_RIGHT ||
                   currentStep == Step.WAIT_CENTER_AFTER_UP) {
            centerStartTimestampMs = 0L
        }
    }

    fun cancel() {
        moveToStep(Step.IDLE)
    }
}
