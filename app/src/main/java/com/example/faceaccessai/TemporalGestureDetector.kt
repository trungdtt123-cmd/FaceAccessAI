package com.example.faceaccessai

import android.util.Log

class TemporalGestureDetector(

    // Thời gian nhắm mắt tối thiểu để không xem là nhiễu
    private val minimumBlinkDurationMs: Long = 80L,

    // Thời gian tối đa để một lần nhắm mắt được xem là blink tự nhiên
    private val maximumBlinkDurationMs: Long = 350L,

    // Thời gian giữ mắt nhắm để tạo CONFIRM hoặc nhắm mắt chủ ý
    // Tăng lên 1.5s theo yêu cầu để đảm bảo tính chủ đích cao
    private val intentionalEyeHoldDurationMs: Long = 1500L,

    // Thời gian mở miệng tối thiểu để một chu kỳ có thể tạo BACK
    // Giảm xuống 500ms để nhạy hơn
    private val intentionalMouthOpenDurationMs: Long = 500L,

    // MAR tối thiểu của gesture BACK (phù hợp với detector mới)
    private val minimumIntentionalMouthMar: Float = 0.16f,

    // Thời gian tối thiểu để một chu kỳ được xem xét là ngáp
    private val minimumYawnDurationMs: Long = 3500L,

    // MAR rất rộng để một chu kỳ được xem xét là ngáp
    private val minimumYawnMar: Float = 0.70f,

    // EAR giảm xuống 75% baseline hoặc thấp hơn được xem là có dấu hiệu díu mắt
    private val yawnEyeSquintRatio: Float = 0.75f,

    // Hệ số cập nhật baseline EAR khi mắt đang mở bình thường
    private val openEyeBaselineSmoothing: Float = 0.10f,

    // Tỉ lệ EAR giữa hai mắt để phân biệt Wink (nhắm một bên) vs Blink (nhắm cả hai)
    // Tăng lên 0.85: Chỉ cần mắt phải nhỏ hơn mắt trái 15% là nhận lệnh (cực nhạy)
    private val winkEarRatioThreshold: Float = 0.85f

) {

    enum class GestureEvent {
        NONE,
        NATURAL_BLINK,
        INTENTIONAL_EYE_CLOSE,
        NATURAL_YAWN,
        INTENTIONAL_MOUTH_OPEN,
        LEFT_WINK,
        RIGHT_WINK,
        BOTH_EYES_CLOSED,
        DOUBLE_MOUTH_OPEN
    }

    data class TemporalResult(
        val event: GestureEvent,
        val eyeClosedDurationMs: Long,
        val mouthOpenDurationMs: Long,
        val eyeState: FaceStateDetector.EyeState,
        val mouthState: FaceStateDetector.MouthState,
        val leftEyeState: FaceStateDetector.EyeState = FaceStateDetector.EyeState.UNKNOWN,
        val rightEyeState: FaceStateDetector.EyeState = FaceStateDetector.EyeState.UNKNOWN,

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

    private var previousLeftEyeState =
        FaceStateDetector.EyeState.UNKNOWN

    private var previousRightEyeState =
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

    // Gesture cụ thể cho từng mắt
    private var leftEyeClosedStartMs: Long? = null
    private var rightEyeClosedStartMs: Long? = null
    private var leftWinkTriggered = false
    private var bothEyesClosedTriggered = false

    // Theo dõi mở miệng kép
    private var lastMouthOpenRisingEdgeMs: Long = 0
    // Cờ bỏ qua chu kỳ miệng hiện tại (nếu đã kích hoạt DOUBLE_MOUTH_OPEN)
    private var ignoreCurrentMouthCycle = false

    // Ngăn gesture mắt bị phát ngay sau một chu kỳ miệng
    private var suppressEyeGestureUntilOpen =
        false

    // Theo dõi thời gian nhắm mắt của bên còn lại để phân biệt Wink
    private var otherEyeClosedDurationDuringCycle: Long = 0
    private var lastOtherEyeClosedTimestampDuringCycle: Long? = null
    private var minLeftEarDuringCycle: Float = 1.0f
    private var minRightEarDuringCycle: Float = 1.0f

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

                val isIntentionalMouthGesture = !ignoreCurrentMouthCycle &&
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

        // --- NHẬN DIỆN MỞ MIỆNG KÉP (Rising Edge) ---
        if (currentMouthState == FaceStateDetector.MouthState.OPEN && 
            previousMouthState == FaceStateDetector.MouthState.CLOSED) {
            
            val now = timestampMs
            val delta = now - lastMouthOpenRisingEdgeMs
            Log.d("TemporalGesture", "MOUTH_RISING_EDGE | delta=$delta")
            
            if (delta in 200..1200) { // Tăng nhẹ cửa sổ thời gian lên 1.2s
                detectedEvent = GestureEvent.DOUBLE_MOUTH_OPEN
                lastMouthOpenRisingEdgeMs = 0
                ignoreCurrentMouthCycle = true // Đã khóa/mở khóa rồi thì thôi không trigger BACK nữa
                Log.d("TemporalGesture", "DOUBLE_MOUTH_OPEN_DETECTED")
            } else {
                lastMouthOpenRisingEdgeMs = now
                ignoreCurrentMouthCycle = false
            }
        }

        // --- XỬ LÝ GESTURE MẮT ---
        // Cho phép nhận diện mắt độc lập với chu kỳ miệng để tăng độ nhạy
        if (
            suppressEyeGestureUntilOpen
        ) {
            resetEyeCycle()
            if (currentEyeState == FaceStateDetector.EyeState.OPEN) {
                suppressEyeGestureUntilOpen = false
            }
        } else {

            // --- LOGIC MẮT CẢI TIẾN (WINK & BOTH EYES CLOSED) ---
            val leftClosed = faceState.leftEyeState == FaceStateDetector.EyeState.CLOSED
            val rightClosed = faceState.rightEyeState == FaceStateDetector.EyeState.CLOSED

            // 1. Cập nhật timestamp bắt đầu nhắm cho từng mắt
            if (leftClosed && leftEyeClosedStartMs == null) {
                leftEyeClosedStartMs = timestampMs
                minLeftEarDuringCycle = faceState.leftEAR
                if (!rightClosed) {
                    otherEyeClosedDurationDuringCycle = 0
                    lastOtherEyeClosedTimestampDuringCycle = null
                }
            }
            if (rightClosed && rightEyeClosedStartMs == null) {
                rightEyeClosedStartMs = timestampMs
                minRightEarDuringCycle = faceState.rightEAR
                if (!leftClosed) {
                    otherEyeClosedDurationDuringCycle = 0
                    lastOtherEyeClosedTimestampDuringCycle = null
                }
            }
            
            // Cập nhật EAR thấp nhất và thời gian nhắm chéo
            if (leftEyeClosedStartMs != null) {
                minLeftEarDuringCycle = minOf(minLeftEarDuringCycle, faceState.leftEAR)
                if (rightClosed) {
                    val last = lastOtherEyeClosedTimestampDuringCycle ?: timestampMs
                    otherEyeClosedDurationDuringCycle += (timestampMs - last)
                    lastOtherEyeClosedTimestampDuringCycle = timestampMs
                } else {
                    lastOtherEyeClosedTimestampDuringCycle = null
                }
            }
            if (rightEyeClosedStartMs != null) {
                minRightEarDuringCycle = minOf(minRightEarDuringCycle, faceState.rightEAR)
                if (leftClosed) {
                    val last = lastOtherEyeClosedTimestampDuringCycle ?: timestampMs
                    otherEyeClosedDurationDuringCycle += (timestampMs - last)
                    lastOtherEyeClosedTimestampDuringCycle = timestampMs
                } else {
                    lastOtherEyeClosedTimestampDuringCycle = null
                }
            }

            // 2. Nhận diện NHẮM CẢ HAI MẮT (Liên tục)
            if (leftClosed && rightClosed) {
                val leftDur = timestampMs - (leftEyeClosedStartMs ?: timestampMs)
                val rightDur = timestampMs - (rightEyeClosedStartMs ?: timestampMs)

                if (leftDur >= intentionalEyeHoldDurationMs && rightDur >= intentionalEyeHoldDurationMs) {
                    if (!bothEyesClosedTriggered) {
                        detectedEvent = GestureEvent.BOTH_EYES_CLOSED
                        bothEyesClosedTriggered = true
                        intentionalEyeEventTriggered = true
                        Log.d("TemporalGesture", "BOTH_EYES_CLOSED_DETECTED")
                    }
                }
            }

            // 3. Nhận diện NHẮM MẮT PHẢI (RIGHT_WINK) - Kích hoạt khi NHẢ MẮT (OPEN-CLOSED-OPEN)
            // Yêu cầu: Mắt TRÁI phần lớn thời gian phải MỞ
            // HOẶC mắt PHẢI nhắm sâu hơn mắt trái đáng kể (hỗ trợ người mắt nhỏ)
            if (!rightClosed && previousRightEyeState == FaceStateDetector.EyeState.CLOSED && !leftClosed) {
                val startTimestamp = rightEyeClosedStartMs
                if (startTimestamp != null) {
                    val duration = timestampMs - startTimestamp
                    val otherClosedRatio = if (duration > 0) otherEyeClosedDurationDuringCycle.toFloat() / duration else 0f
                    
                    // So sánh trực tiếp EAR tối thiểu trong chu kỳ
                    val earRatio = if (minLeftEarDuringCycle > 0) minRightEarDuringCycle / minLeftEarDuringCycle else 1f
                    
                    Log.d("TemporalGesture", "RIGHT_WINK_CANDIDATE | dur=$duration | leftRatio=$otherClosedRatio | earRatio=$earRatio")
                    
                    val isDurationValid = duration in minimumBlinkDurationMs..intentionalEyeHoldDurationMs
                    val isTrueWink = (otherClosedRatio < 0.5f) || (earRatio < winkEarRatioThreshold)
                    
                    if (isDurationValid && isTrueWink) {
                        if (!bothEyesClosedTriggered && !intentionalEyeEventTriggered) {
                            detectedEvent = GestureEvent.RIGHT_WINK
                            Log.d("TemporalGesture", "RIGHT_WINK_DETECTED | reason=${if (otherClosedRatio < 0.5f) "time" else "ratio"}")
                            resetEyeCycle() // Reset ngay để chuẩn bị cho lần nháy tiếp theo
                        }
                    }
                }
            }

            // 4. Nhận diện NHẮM MẮT TRÁI (LEFT_WINK) - Fallback hoặc dùng cho việc khác
            if (!leftClosed && previousLeftEyeState == FaceStateDetector.EyeState.CLOSED && !rightClosed) {
                val startTimestamp = leftEyeClosedStartMs
                if (startTimestamp != null) {
                    val duration = timestampMs - startTimestamp
                    val otherClosedRatio = if (duration > 0) otherEyeClosedDurationDuringCycle.toFloat() / duration else 0f
                    val earDiff = minRightEarDuringCycle - minLeftEarDuringCycle
                    
                    val isDurationValid = duration in minimumBlinkDurationMs..intentionalEyeHoldDurationMs
                    if (isDurationValid && (otherClosedRatio < 0.5f || earDiff > 0.05f)) {
                        if (!bothEyesClosedTriggered && !intentionalEyeEventTriggered) {
                            detectedEvent = GestureEvent.LEFT_WINK
                            Log.d("TemporalGesture", "LEFT_WINK_DETECTED")
                            resetEyeCycle()
                        }
                    }
                }
            }

            // Reset trạng thái khi mắt mở
            if (!leftClosed) {
                leftEyeClosedStartMs = null
                leftWinkTriggered = false
                if (!rightClosed) {
                    otherEyeClosedDurationDuringCycle = 0
                    lastOtherEyeClosedTimestampDuringCycle = null
                    minLeftEarDuringCycle = 1.0f
                }
            }
            if (!rightClosed) {
                rightEyeClosedStartMs = null
                bothEyesClosedTriggered = false
                if (!leftClosed) {
                    otherEyeClosedDurationDuringCycle = 0
                    lastOtherEyeClosedTimestampDuringCycle = null
                    minRightEarDuringCycle = 1.0f
                }
            }

            // Fallback logic cho nhắm mắt bình thường (BOTH_EYES_CLOSED long hold)
            if (detectedEvent == GestureEvent.NONE && !intentionalEyeEventTriggered) {
                // Sử dụng ngưỡng linh hoạt kết hợp Baseline (hỗ trợ tối đa cho người mắt nhỏ)
                val baseline = openEyeBaselineEar ?: 0.25f
                val relativeThreshold = baseline * 0.75f // Chỉ cần giảm 25% EAR là bắt đầu tính
                
                // Mắt nhắm nếu EAR thấp hơn tuyệt đối (0.18) HOẶC thấp hơn tương đối so với baseline (0.24)
                val isEffectivelyClosed = currentEyeState == FaceStateDetector.EyeState.CLOSED || 
                                         (faceState.averageEAR < relativeThreshold && faceState.averageEAR < 0.24f)
                
                if (isEffectivelyClosed) {
                    if (eyeClosedStartTimestampMs == null) {
                        eyeClosedStartTimestampMs = timestampMs
                    }
                    val startTimestamp = eyeClosedStartTimestampMs
                    if (startTimestamp != null) {
                        closedDurationMs = timestampMs - startTimestamp
                        if (closedDurationMs >= intentionalEyeHoldDurationMs) {
                            detectedEvent = GestureEvent.INTENTIONAL_EYE_CLOSE
                            intentionalEyeEventTriggered = true
                            Log.d("TemporalGesture", "INTENTIONAL_HOLD_DETECTED | dur=$closedDurationMs | EAR=${faceState.averageEAR} | Base=$baseline")
                        }
                    }
                } else {
                    eyeClosedStartTimestampMs = null
                }
            }

            // Xử lý khi mắt mở lại để bắt Natural Blink (cả hai mắt)
            if (currentEyeState == FaceStateDetector.EyeState.OPEN) {
                if (previousEyeState == FaceStateDetector.EyeState.CLOSED) {
                    val startTimestamp = eyeClosedStartTimestampMs
                    if (startTimestamp != null) {
                        val totalClosedDuration = timestampMs - startTimestamp
                        if (!intentionalEyeEventTriggered &&
                            totalClosedDuration >= minimumBlinkDurationMs &&
                            totalClosedDuration <= maximumBlinkDurationMs &&
                            detectedEvent == GestureEvent.NONE) {
                            detectedEvent = GestureEvent.NATURAL_BLINK
                            closedDurationMs = totalClosedDuration
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

        previousLeftEyeState =
            faceState.leftEyeState

        previousRightEyeState =
            faceState.rightEyeState

        previousMouthState =
            currentMouthState

        return TemporalResult(
            event = detectedEvent,
            eyeClosedDurationMs = closedDurationMs,
            mouthOpenDurationMs =
                mouthOpenDurationMs,
            eyeState = currentEyeState,
            mouthState = currentMouthState,
            leftEyeState = faceState.leftEyeState,
            rightEyeState = faceState.rightEyeState,
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

        leftEyeClosedStartMs = null
        rightEyeClosedStartMs = null
        leftWinkTriggered = false
        bothEyesClosedTriggered = false
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
        
        ignoreCurrentMouthCycle = false
        // lastMouthOpenRisingEdgeMs KHÔNG được reset ở đây vì nó dùng để đếm giữa các lần mở
    }

    // Reset toàn bộ detector khi mất khuôn mặt hoặc restart pipeline
    fun reset() {

        resetEyeCycle()

        previousEyeState =
            FaceStateDetector.EyeState.UNKNOWN

        previousLeftEyeState =
            FaceStateDetector.EyeState.UNKNOWN

        previousRightEyeState =
            FaceStateDetector.EyeState.UNKNOWN

        resetMouthCycle()

        previousMouthState =
            FaceStateDetector.MouthState.UNKNOWN

        openEyeBaselineEar =
            null

        suppressEyeGestureUntilOpen =
            false

        minLeftEarDuringCycle = 1.0f
        minRightEarDuringCycle = 1.0f
        otherEyeClosedDurationDuringCycle = 0
        lastOtherEyeClosedTimestampDuringCycle = null
    }

    companion object {

        private const val TAG_YAWN_DEBUG =
            "YawnDebug"
    }
}