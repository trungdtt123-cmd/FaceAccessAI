package com.example.faceaccessai

import android.util.Log

class TemporalGestureDetector(

    // Thời gian nhắm mắt tối thiểu để không xem là nhiễu
    private val minimumBlinkDurationMs: Long = 80L,

    // Thời gian tối đa để một lần nhắm mắt được xem là blink tự nhiên
    private val maximumBlinkDurationMs: Long = 350L,

    // Thời gian giữ mắt nhắm để tạo CONFIRM
    private val intentionalEyeHoldDurationMs: Long = 800L,

    // Thời gian mở miệng tối thiểu để một chu kỳ có thể tạo BACK
    private val intentionalMouthOpenDurationMs: Long = 700L,

    // MAR tối thiểu của gesture BACK
    private val minimumIntentionalMouthMar: Float = 0.25f,

    // Thời gian tối thiểu để một chu kỳ được xem xét là ngáp
    private val minimumYawnDurationMs: Long = 3500L,

    // MAR rất rộng để một chu kỳ được xem xét là ngáp
    private val minimumYawnMar: Float = 0.70f,

    // EAR giảm xuống 75% baseline hoặc thấp hơn được xem là có dấu hiệu díu mắt
    private val yawnEyeSquintRatio: Float = 0.75f,

    // Hệ số cập nhật baseline EAR khi mắt đang mở bình thường
    private val openEyeBaselineSmoothing: Float = 0.10f

) {

    enum class GestureEvent {
        NONE,
        NATURAL_BLINK,
        INTENTIONAL_EYE_CLOSE,
        NATURAL_YAWN,
        INTENTIONAL_MOUTH_OPEN
    }

    data class TemporalResult(
        val event: GestureEvent,
        val eyeClosedDurationMs: Long,
        val mouthOpenDurationMs: Long,
        val eyeState: FaceStateDetector.EyeState,
        val mouthState: FaceStateDetector.MouthState,

        // MAR lớn nhất trong chu kỳ miệng
        val maxMouthMar: Float = 0f,

        // EAR nhỏ nhất trong chu kỳ miệng
        val minimumEyeEarDuringMouthCycle: Float = Float.NaN,

        // EAR mắt mở bình thường được ghi nhận trước chu kỳ
        val baselineOpenEyeEar: Float = Float.NaN,

        // Có bằng chứng mắt díu hoặc nhắm trong chu kỳ
        val yawnEyeEvidence: Boolean = false,

        // Đang trong một chu kỳ mở miệng chưa hoàn thành
        val isMouthCycleActive: Boolean = false
    )

    // Thời điểm bắt đầu nhắm mắt
    private var eyeClosedStartTimestampMs: Long? =
        null

    // Tránh phát CONFIRM nhiều lần trong cùng một lần nhắm mắt
    private var intentionalEyeEventTriggered =
        false

    // Trạng thái mắt của frame trước
    private var previousEyeState =
        FaceStateDetector.EyeState.UNKNOWN

    // Thời điểm bắt đầu một chu kỳ mở miệng
    private var mouthOpenStartTimestampMs: Long? =
        null

    // Trạng thái miệng của frame trước
    private var previousMouthState =
        FaceStateDetector.MouthState.UNKNOWN

    // MAR lớn nhất trong chu kỳ miệng hiện tại
    private var mouthCycleMaxMar =
        0f

    // EAR nhỏ nhất trong chu kỳ miệng hiện tại
    private var mouthCycleMinEar =
        Float.POSITIVE_INFINITY

    // Baseline EAR được chụp tại đầu chu kỳ miệng
    private var mouthCycleBaselineEar: Float? =
        null

    // Ghi nhận mắt đã nhắm hoàn toàn trong chu kỳ miệng
    private var mouthCycleObservedClosedEye =
        false

    // Baseline EAR mắt mở được cập nhật theo thời gian
    private var openEyeBaselineEar: Float? =
        null

    // Ngăn gesture mắt bị phát ngay sau một chu kỳ miệng
    private var suppressEyeGestureUntilOpen =
        false

    // Cập nhật trạng thái khuôn mặt và phát hiện gesture theo thời gian
    fun update(
        faceState: FaceStateDetector.FaceState,
        timestampMs: Long
    ): TemporalResult {

        val currentEyeState =
            faceState.eyeState

        val currentMouthState =
            faceState.mouthState

        val currentEar =
            faceState.averageEAR

        val currentMar =
            faceState.mar

        var detectedEvent =
            GestureEvent.NONE

        var closedDurationMs =
            0L

        var mouthOpenDurationMs =
            0L

        var completedMaxMar =
            0f

        var completedMinEar =
            Float.NaN

        var completedBaselineEar =
            Float.NaN

        var completedYawnEyeEvidence =
            false

        var mouthCycleJustEnded =
            false

        // Bắt đầu chu kỳ khi miệng chuyển sang OPEN
        if (
            currentMouthState ==
            FaceStateDetector.MouthState.OPEN &&
            previousMouthState !=
            FaceStateDetector.MouthState.OPEN
        ) {

            mouthOpenStartTimestampMs =
                timestampMs

            mouthCycleMaxMar =
                currentMar

            mouthCycleMinEar =
                currentEar

            mouthCycleBaselineEar =
                openEyeBaselineEar
                    ?: if (
                        currentEyeState ==
                        FaceStateDetector.EyeState.OPEN
                    ) {
                        currentEar
                    } else {
                        null
                    }

            mouthCycleObservedClosedEye =
                currentEyeState ==
                        FaceStateDetector.EyeState.CLOSED

            suppressEyeGestureUntilOpen =
                true

            resetEyeCycle()
        }

        // Theo dõi toàn bộ chu kỳ khi miệng đang mở
        if (
            mouthOpenStartTimestampMs !=
            null
        ) {

            val startTimestamp =
                mouthOpenStartTimestampMs

            if (
                startTimestamp != null
            ) {

                mouthOpenDurationMs =
                    timestampMs -
                            startTimestamp

                mouthCycleMaxMar =
                    maxOf(
                        mouthCycleMaxMar,
                        currentMar
                    )

                if (
                    currentEar.isFinite()
                ) {

                    mouthCycleMinEar =
                        minOf(
                            mouthCycleMinEar,
                            currentEar
                        )
                }

                if (
                    currentEyeState ==
                    FaceStateDetector.EyeState.CLOSED
                ) {

                    mouthCycleObservedClosedEye =
                        true
                }
            }
        }

        // Phân loại chu kỳ sau khi miệng đóng lại
        if (
            currentMouthState ==
            FaceStateDetector.MouthState.CLOSED &&
            previousMouthState ==
            FaceStateDetector.MouthState.OPEN
        ) {

            val startTimestamp =
                mouthOpenStartTimestampMs

            if (
                startTimestamp != null
            ) {

                mouthOpenDurationMs =
                    timestampMs -
                            startTimestamp

                completedMaxMar =
                    mouthCycleMaxMar

                completedMinEar =
                    if (
                        mouthCycleMinEar.isFinite()
                    ) {
                        mouthCycleMinEar
                    } else {
                        currentEar
                    }

                completedBaselineEar =
                    mouthCycleBaselineEar
                        ?: Float.NaN

                completedYawnEyeEvidence =
                    hasYawnEyeEvidence(
                        baselineEar =
                            mouthCycleBaselineEar,
                        minimumEar =
                            completedMinEar,
                        observedClosedEye =
                            mouthCycleObservedClosedEye
                    )

                val hasYawnMouthEvidence =
                    completedMaxMar >=
                            minimumYawnMar

                val hasYawnDurationEvidence =
                    mouthOpenDurationMs >=
                            minimumYawnDurationMs

                val isNaturalYawn =
                    hasYawnMouthEvidence &&
                            hasYawnDurationEvidence &&
                            completedYawnEyeEvidence

                val isIntentionalMouthGesture =
                    mouthOpenDurationMs >=
                            intentionalMouthOpenDurationMs &&
                            completedMaxMar >=
                            minimumIntentionalMouthMar

                detectedEvent =
                    when {

                        isNaturalYawn -> {
                            GestureEvent.NATURAL_YAWN
                        }

                        isIntentionalMouthGesture -> {
                            GestureEvent.INTENTIONAL_MOUTH_OPEN
                        }

                        else -> {
                            GestureEvent.NONE
                        }
                    }

                // Log bằng chứng của chu kỳ miệng để hiệu chỉnh detector ngáp
                Log.d(
                    TAG_YAWN_DEBUG,
                    "durationMs=$mouthOpenDurationMs | " +
                            "maxMAR=$completedMaxMar | " +
                            "minEAR=$completedMinEar | " +
                            "baselineEAR=$completedBaselineEar | " +
                            "closedEye=$mouthCycleObservedClosedEye | " +
                            "mouthEvidence=$hasYawnMouthEvidence | " +
                            "durationEvidence=$hasYawnDurationEvidence | " +
                            "eyeEvidence=$completedYawnEyeEvidence | " +
                            "result=$detectedEvent"
                )
            }

            resetMouthCycle()

            mouthCycleJustEnded =
                true

            suppressEyeGestureUntilOpen =
                currentEyeState !=
                        FaceStateDetector.EyeState.OPEN

            resetEyeCycle()
        }

        val isMouthCycleActive =
            mouthOpenStartTimestampMs !=
                    null

        // Không phát gesture mắt trong khi đang đánh giá một chu kỳ miệng
        if (
            isMouthCycleActive
        ) {

            resetEyeCycle()

        } else if (
            mouthCycleJustEnded
        ) {

            resetEyeCycle()

        } else if (
            suppressEyeGestureUntilOpen
        ) {

            resetEyeCycle()

            if (
                currentEyeState ==
                FaceStateDetector.EyeState.OPEN
            ) {

                suppressEyeGestureUntilOpen =
                    false
            }

        } else {

            // Xử lý khi mắt đang nhắm
            if (
                currentEyeState ==
                FaceStateDetector.EyeState.CLOSED
            ) {

                if (
                    previousEyeState !=
                    FaceStateDetector.EyeState.CLOSED
                ) {

                    eyeClosedStartTimestampMs =
                        timestampMs

                    intentionalEyeEventTriggered =
                        false
                }

                val startTimestamp =
                    eyeClosedStartTimestampMs

                if (
                    startTimestamp != null
                ) {

                    closedDurationMs =
                        timestampMs -
                                startTimestamp

                    if (
                        closedDurationMs >=
                        intentionalEyeHoldDurationMs &&
                        !intentionalEyeEventTriggered &&
                        detectedEvent ==
                        GestureEvent.NONE
                    ) {

                        detectedEvent =
                            GestureEvent.INTENTIONAL_EYE_CLOSE

                        intentionalEyeEventTriggered =
                            true
                    }
                }
            }

            // Xử lý khi mắt mở lại
            else if (
                currentEyeState ==
                FaceStateDetector.EyeState.OPEN
            ) {

                if (
                    previousEyeState ==
                    FaceStateDetector.EyeState.CLOSED
                ) {

                    val startTimestamp =
                        eyeClosedStartTimestampMs

                    if (
                        startTimestamp != null
                    ) {

                        val totalClosedDuration =
                            timestampMs -
                                    startTimestamp

                        if (
                            !intentionalEyeEventTriggered &&
                            totalClosedDuration >=
                            minimumBlinkDurationMs &&
                            totalClosedDuration <=
                            maximumBlinkDurationMs &&
                            detectedEvent ==
                            GestureEvent.NONE
                        ) {

                            detectedEvent =
                                GestureEvent.NATURAL_BLINK

                            closedDurationMs =
                                totalClosedDuration
                        }
                    }
                }

                resetEyeCycle()
            }
        }

        // Cập nhật baseline EAR chỉ ở trạng thái ổn định
        if (
            !isMouthCycleActive &&
            !mouthCycleJustEnded &&
            currentMouthState ==
            FaceStateDetector.MouthState.CLOSED &&
            previousMouthState ==
            FaceStateDetector.MouthState.CLOSED &&
            currentEyeState ==
            FaceStateDetector.EyeState.OPEN
        ) {

            updateOpenEyeBaseline(
                currentEar
            )
        }

        val resultMaxMar =
            if (
                completedMaxMar > 0f
            ) {

                completedMaxMar

            } else if (
                isMouthCycleActive
            ) {

                mouthCycleMaxMar

            } else {

                0f
            }

        val resultMinimumEar =
            if (
                completedMinEar.isFinite()
            ) {

                completedMinEar

            } else if (
                isMouthCycleActive &&
                mouthCycleMinEar.isFinite()
            ) {

                mouthCycleMinEar

            } else {

                Float.NaN
            }

        val resultBaselineEar =
            if (
                completedBaselineEar.isFinite()
            ) {

                completedBaselineEar

            } else if (
                isMouthCycleActive
            ) {

                mouthCycleBaselineEar
                    ?: Float.NaN

            } else {

                openEyeBaselineEar
                    ?: Float.NaN
            }

        val resultYawnEyeEvidence =
            if (
                completedMaxMar > 0f
            ) {

                completedYawnEyeEvidence

            } else if (
                isMouthCycleActive
            ) {

                hasYawnEyeEvidence(
                    baselineEar =
                        mouthCycleBaselineEar,
                    minimumEar =
                        resultMinimumEar,
                    observedClosedEye =
                        mouthCycleObservedClosedEye
                )

            } else {

                false
            }

        previousEyeState =
            currentEyeState

        previousMouthState =
            currentMouthState

        return TemporalResult(
            event = detectedEvent,
            eyeClosedDurationMs = closedDurationMs,
            mouthOpenDurationMs =
                mouthOpenDurationMs,
            eyeState = currentEyeState,
            mouthState = currentMouthState,
            maxMouthMar = resultMaxMar,
            minimumEyeEarDuringMouthCycle =
                resultMinimumEar,
            baselineOpenEyeEar =
                resultBaselineEar,
            yawnEyeEvidence =
                resultYawnEyeEvidence,
            isMouthCycleActive =
                isMouthCycleActive
        )
    }

    // Kiểm tra mắt có díu đáng kể hoặc đã nhắm trong chu kỳ miệng hay không
    private fun hasYawnEyeEvidence(
        baselineEar: Float?,
        minimumEar: Float,
        observedClosedEye: Boolean
    ): Boolean {

        if (
            observedClosedEye
        ) {

            return true
        }

        if (
            baselineEar == null ||
            !baselineEar.isFinite() ||
            baselineEar <= 0f ||
            !minimumEar.isFinite()
        ) {

            return false
        }

        val squintThreshold =
            baselineEar *
                    yawnEyeSquintRatio

        return minimumEar <=
                squintThreshold
    }

    // Cập nhật baseline EAR của mắt mở bằng exponential smoothing
    private fun updateOpenEyeBaseline(
        currentEar: Float
    ) {

        if (
            !currentEar.isFinite() ||
            currentEar <= 0f
        ) {

            return
        }

        val previousBaseline =
            openEyeBaselineEar

        if (
            previousBaseline == null
        ) {

            openEyeBaselineEar =
                currentEar

            return
        }

        val alpha =
            openEyeBaselineSmoothing
                .coerceIn(
                    0f,
                    1f
                )

        openEyeBaselineEar =
            previousBaseline *
                    (1f - alpha) +
                    currentEar *
                    alpha
    }

    // Reset trạng thái của chu kỳ mắt hiện tại
    private fun resetEyeCycle() {

        eyeClosedStartTimestampMs =
            null

        intentionalEyeEventTriggered =
            false
    }

    // Reset dữ liệu của chu kỳ miệng hiện tại
    private fun resetMouthCycle() {

        mouthOpenStartTimestampMs =
            null

        mouthCycleMaxMar =
            0f

        mouthCycleMinEar =
            Float.POSITIVE_INFINITY

        mouthCycleBaselineEar =
            null

        mouthCycleObservedClosedEye =
            false
    }

    // Reset toàn bộ detector khi mất khuôn mặt hoặc restart pipeline
    fun reset() {

        resetEyeCycle()

        previousEyeState =
            FaceStateDetector.EyeState.UNKNOWN

        resetMouthCycle()

        previousMouthState =
            FaceStateDetector.MouthState.UNKNOWN

        openEyeBaselineEar =
            null

        suppressEyeGestureUntilOpen =
            false
    }

    companion object {

        private const val TAG_YAWN_DEBUG =
            "YawnDebug"
    }
}