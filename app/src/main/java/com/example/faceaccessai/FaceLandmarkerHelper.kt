package com.example.faceaccessai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log

import androidx.camera.core.ImageProxy

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult


class FaceLandmarkerHelper(

    private val context: Context,

    private val listener: LandmarkerListener? = null

) {

    // MediaPipe Face Landmarker
    private var faceLandmarker: FaceLandmarker? = null


    // Bộ đếm kết quả để giới hạn số lượng log
    private var resultCounter = 0


    // Xác định trạng thái mắt và miệng
    private val faceStateDetector =
        FaceStateDetector()


    // Nhận diện gesture mắt và miệng theo thời gian
    private val temporalGestureDetector =
        TemporalGestureDetector()


    // Calibration tư thế đầu trung tính
    private val headPoseCalibrator =
        HeadPoseCalibrator()


    // Làm mượt head pose sau calibration
    private val headPoseSmoother =
        HeadPoseSmoother()


    // Nhận diện gesture đầu 4 hướng
    private val headGestureDetector =
        HeadGestureDetector()


    // Nhận diện gesture HOME bằng chuỗi nghiêng đầu
    private val homeGestureDetector =
        HomeGestureDetector()


    // Chuyển gesture thành lệnh điều khiển thống nhất
    private val faceCommandResolver =
        FaceCommandResolver()


    // Kiểm tra an toàn trước khi cho phép thực thi command
    private val faceCommandSafetyGate =
        FaceCommandSafetyGate()


    init {
        setupFaceLandmarker()
        applySensitivityConfig()
    }

    fun applySensitivityConfig() {
        val sensitivity = GestureSensitivityManager.getInstance(context).getSensitivity()
        val homeConfig = GestureSensitivityConfigProvider.homeConfig(sensitivity)
        val headConfig = GestureSensitivityConfigProvider.headConfig(sensitivity)
        
        homeGestureDetector.updateConfig(homeConfig)
        headGestureDetector.updateConfig(headConfig)
        
        Log.d(TAG, "GestureSensitivity | Applied=$sensitivity")
    }


    // Khởi tạo MediaPipe Face Landmarker
    private fun setupFaceLandmarker() {

        try {

            // Model nằm trong app/src/main/assets
            val baseOptions =
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_NAME)
                    .build()


            val options =
                FaceLandmarker.FaceLandmarkerOptions.builder()

                    .setBaseOptions(baseOptions)

                    // Hiện tại chỉ xử lý một khuôn mặt
                    .setNumFaces(1)

                    // Yêu cầu MediaPipe trả về ma trận biến đổi khuôn mặt
                    .setOutputFacialTransformationMatrixes(
                        true
                    )

                    .setMinFaceDetectionConfidence(
                        DEFAULT_FACE_DETECTION_CONFIDENCE
                    )

                    .setMinFacePresenceConfidence(
                        DEFAULT_FACE_PRESENCE_CONFIDENCE
                    )

                    .setMinTrackingConfidence(
                        DEFAULT_FACE_TRACKING_CONFIDENCE
                    )

                    // Camera realtime
                    .setRunningMode(
                        RunningMode.LIVE_STREAM
                    )

                    // Nhận kết quả MediaPipe
                    .setResultListener(
                        this::returnLivestreamResult
                    )

                    // Nhận lỗi MediaPipe
                    .setErrorListener(
                        this::returnLivestreamError
                    )

                    .build()


            faceLandmarker =
                FaceLandmarker.createFromOptions(
                    context,
                    options
                )


            Log.d(
                TAG,
                "Face Landmarker initialized successfully"
            )


        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Face Landmarker initialization failed",
                exception
            )


            listener?.onError(
                exception.message
                    ?: "Không thể khởi tạo Face Landmarker."
            )
        }
    }


    // Nhận frame từ CameraX
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {

        val frameTime =
            SystemClock.uptimeMillis()


        val imageWidth =
            imageProxy.width


        val imageHeight =
            imageProxy.height


        val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees


        val bitmapBuffer =
            Bitmap.createBitmap(
                imageWidth,
                imageHeight,
                Bitmap.Config.ARGB_8888
            )


        try {

            // Copy ImageProxy sang Bitmap
            bitmapBuffer.copyPixelsFromBuffer(
                imageProxy.planes[0].buffer
            )


        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Không thể chuyển frame CameraX sang Bitmap",
                exception
            )


            listener?.onError(
                "Không thể xử lý frame camera."
            )


            imageProxy.close()

            return
        }


        imageProxy.close()


        // Xoay và mirror frame
        val matrix =
            Matrix().apply {

                postRotate(
                    rotationDegrees.toFloat()
                )


                if (isFrontCamera) {

                    postScale(
                        -1f,
                        1f
                    )
                }
            }


        val rotatedBitmap =
            Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )


        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        detectAsync(
            mpImage,
            frameTime
        )
    }


    // Gửi frame vào MediaPipe
    private fun detectAsync(
        mpImage: MPImage,
        frameTime: Long
    ) {

        try {

            faceLandmarker?.detectAsync(
                mpImage,
                frameTime
            )


        } catch (exception: Exception) {

            Log.e(
                TAG,
                "Face detection failed",
                exception
            )


            listener?.onError(
                exception.message
                    ?: "Face detection failed."
            )
        }
    }


    // Xử lý kết quả MediaPipe
    private fun returnLivestreamResult(
        result: FaceLandmarkerResult,
        input: MPImage
    ) {

        if (result.faceLandmarks().isNotEmpty()) {

            resultCounter++


            val timestampMs =
                result.timestampMs()


            // Lấy landmarks của khuôn mặt đầu tiên
            val landmarks =
                result
                    .faceLandmarks()
                    .first()


            // Kiểm tra khuôn mặt có quá sát mép frame không
            val frameQuality =
                FaceFrameQualityChecker.check(
                    landmarks = landmarks
                )


            val isFrameSafe =
                frameQuality != null &&
                        !frameQuality.tooCloseToEdge


            // Lấy ma trận biến đổi khuôn mặt đầu tiên
            val transformationMatrix =
                result
                    .facialTransformationMatrixes()
                    .orElse(null)
                    ?.firstOrNull()


            // Tính EAR, MAR và head pose
            val faceFeatures =
                FaceFeatureExtractor.extract(
                    landmarks = landmarks,
                    imageWidth = input.width,
                    imageHeight = input.height,
                    transformMatrixColumnMajor =
                        transformationMatrix
                )


            // Calibration chỉ chạy khi khuôn mặt an toàn trong frame
            if (
                faceFeatures != null &&
                faceFeatures.headPoseAvailable &&
                isFrameSafe
            ) {

                if (
                    headPoseCalibrator.getState() ==
                    HeadPoseCalibrator.CalibrationState.IDLE
                ) {

                    headPoseSmoother.reset()

                    headGestureDetector.reset()


                    headPoseCalibrator.start(
                        timestampMs = timestampMs
                    )


                    Log.d(
                        TAG_HEAD_CALIBRATION,
                        "STARTED | Giữ đầu nhìn thẳng trong 3 giây"
                    )
                }


                if (
                    headPoseCalibrator.getState() ==
                    HeadPoseCalibrator.CalibrationState.CALIBRATING
                ) {

                    val calibrationUpdate =
                        headPoseCalibrator.update(
                            yawDeg =
                                faceFeatures.yawDeg,

                            pitchDeg =
                                faceFeatures.pitchDeg,

                            rollDeg =
                                faceFeatures.rollDeg,

                            timestampMs =
                                timestampMs
                        )


                    if (
                        calibrationUpdate.justCompleted
                    ) {

                        val profile =
                            calibrationUpdate.profile


                        if (profile != null) {

                            // Bắt đầu lại smoothing sau calibration
                            headPoseSmoother.reset()

                            headGestureDetector.reset()


                            Log.d(
                                TAG_HEAD_CALIBRATION,

                                "COMPLETE | " +
                                        "NeutralYaw=${profile.neutralYawDeg} | " +
                                        "NeutralPitch=${profile.neutralPitchDeg} | " +
                                        "NeutralRoll=${profile.neutralRollDeg} | " +
                                        "Samples=${profile.sampleCount}"
                            )
                        }
                    }
                }

            } else {

                // Nếu frame không an toàn trong lúc calibration thì làm lại
                if (
                    headPoseCalibrator.getState() ==
                    HeadPoseCalibrator.CalibrationState.CALIBRATING
                ) {

                    headPoseCalibrator.reset()

                    headPoseSmoother.reset()

                    headGestureDetector.reset()


                    val reason =
                        if (!isFrameSafe) {
                            "UNSAFE_FRAME"
                        } else {
                            "HEAD_POSE_UNAVAILABLE"
                        }


                    Log.w(
                        TAG_HEAD_CALIBRATION,
                        "CANCELLED | Reason=$reason"
                    )
                }
            }


            // Head pose sau khi trừ neutral
            val calibratedHeadPose =
                if (
                    faceFeatures != null &&
                    faceFeatures.headPoseAvailable &&
                    isFrameSafe &&
                    headPoseCalibrator.getState() ==
                    HeadPoseCalibrator.CalibrationState.READY
                ) {

                    headPoseCalibrator.calibrate(
                        yawDeg =
                            faceFeatures.yawDeg,

                        pitchDeg =
                            faceFeatures.pitchDeg,

                        rollDeg =
                            faceFeatures.rollDeg
                    )

                } else {

                    null
                }


            // Làm mượt head pose đã calibration
            val smoothedHeadPose =
                if (
                    calibratedHeadPose != null &&
                    isFrameSafe
                ) {

                    headPoseSmoother.update(
                        yawDeg =
                            calibratedHeadPose.yawDeg,

                        pitchDeg =
                            calibratedHeadPose.pitchDeg,

                        rollDeg =
                            calibratedHeadPose.rollDeg
                    )

                } else {

                    // Gián đoạn pose sau calibration nhưng giữ khóa chống lặp
                    if (
                        headPoseCalibrator.getState() ==
                        HeadPoseCalibrator.CalibrationState.READY
                    ) {

                        headPoseSmoother.reset()

                        headGestureDetector.interrupt()
                    }

                    null
                }


            // Nhận diện gesture đầu 4 hướng
            val headGestureResult =
                if (
                    smoothedHeadPose != null &&
                    isFrameSafe
                ) {

                    headGestureDetector.update(
                        pose =
                            smoothedHeadPose,

                        timestampMs =
                            timestampMs
                    )

                } else {

                    null
                }


            // Nhận diện gesture HOME bằng chuỗi nghiêng đầu
            val homeGestureResult =
                if (
                    smoothedHeadPose != null &&
                    isFrameSafe
                ) {

                    homeGestureDetector.update(
                        yaw = smoothedHeadPose.yawDeg,
                        pitch = smoothedHeadPose.pitchDeg,
                        roll = smoothedHeadPose.rollDeg,
                        timestampMs = timestampMs
                    )

                } else {

                    // Gián đoạn pose thì reset an toàn
                    homeGestureDetector.reset()

                    null
                }


            // Xác định trạng thái mắt và miệng
            val faceState =
                faceFeatures?.let { features ->

                    faceStateDetector.detect(
                        features
                    )
                }


            // Nhận diện gesture mắt và miệng theo thời gian
            val temporalResult =
                faceState?.let { state ->

                    temporalGestureDetector.update(
                        faceState = state,
                        timestampMs = timestampMs
                    )
                }


            // Chuyển các gesture đã nhận diện thành lệnh thống nhất
            val commandResult =
                faceCommandResolver.resolve(
                    headGestureResult =
                        headGestureResult,
                    temporalResult =
                        temporalResult,
                    homeGestureResult =
                        homeGestureResult
                )


            // Chặn command nếu frame không an toàn hoặc đang cooldown
            val commandSafetyResult =
                faceCommandSafetyGate.evaluate(
                    commandResult =
                        commandResult,
                    isFrameSafe =
                        isFrameSafe,
                    timestampMs =
                        timestampMs
                )


            val inferenceTime =
                SystemClock.uptimeMillis() -
                        timestampMs


            val resultBundle =
                ResultBundle(
                    result = result,
                    features = faceFeatures,
                    calibratedHeadPose =
                        calibratedHeadPose,
                    smoothedHeadPose =
                        smoothedHeadPose,
                    headGestureResult =
                        headGestureResult,
                    homeGestureResult =
                        homeGestureResult,
                    state = faceState,
                    temporalResult = temporalResult,
                    commandResult =
                        commandResult,
                    commandSafetyResult =
                        commandSafetyResult,
                    inferenceTime = inferenceTime,
                    inputImageHeight = input.height,
                    inputImageWidth = input.width
                )


            // Log ngay khi phát hiện gesture mắt hoặc miệng
            if (
                temporalResult != null &&
                temporalResult.event !=
                TemporalGestureDetector.GestureEvent.NONE
            ) {

                Log.d(
                    TAG_GESTURE,

                    "Event=${temporalResult.event} | " +
                            "Eye=${temporalResult.eyeState} | " +
                            "EyeClosedDuration=${temporalResult.eyeClosedDurationMs}ms | " +
                            "Mouth=${temporalResult.mouthState} | " +
                            "MouthOpenDuration=${temporalResult.mouthOpenDurationMs}ms"
                )
            }


            // Log ngay khi phát hiện gesture đầu
            if (
                headGestureResult != null &&
                headGestureResult.event !=
                HeadGestureDetector.GestureEvent.NONE
            ) {

                Log.d(
                    TAG_HEAD_GESTURE,

                    "Event=${headGestureResult.event} | " +
                            "Zone=${headGestureResult.zone} | " +
                            "Yaw=${headGestureResult.yawDeg} | " +
                            "Pitch=${headGestureResult.pitchDeg} | " +
                            "Roll=${headGestureResult.rollDeg} | " +
                            "Duration=${headGestureResult.candidateDurationMs}ms | " +
                            "RollGate=${headGestureResult.rollGatePassed} | " +
                            "CrossAxisGate=${headGestureResult.crossAxisGatePassed}"
                )
            }


            // Log ngay khi phát hiện HOME sequence
            if (
                homeGestureResult != null &&
                homeGestureResult.event !=
                HomeGestureDetector.HomeGestureEvent.NONE
            ) {

                Log.d(
                    "HomeGesture",
                    "HOME_ACCEPT"
                )
            }


            // Log ngay khi phát sinh lệnh điều khiển
            if (
                commandResult.command !=
                FaceCommandResolver.FaceCommand.NONE
            ) {

                Log.d(
                    TAG_FACE_COMMAND,

                    "Command=${commandResult.command} | " +
                            "Source=${commandResult.source}"
                )
            }


            // Log quyết định của lớp an toàn
            if (
                commandSafetyResult.decision !=
                FaceCommandSafetyGate.Decision.NO_COMMAND
            ) {

                Log.d(
                    TAG_FACE_COMMAND_SAFETY,

                    "Command=${commandSafetyResult.command} | " +
                            "Source=${commandSafetyResult.source} | " +
                            "Decision=${commandSafetyResult.decision} | " +
                            "Allowed=${commandSafetyResult.isAllowed} | " +
                            "CooldownRemaining=${commandSafetyResult.cooldownRemainingMs}ms | " +
                            "FrameSafe=$isFrameSafe"
                )
            }


            // Log định kỳ để tránh Logcat quá nhiều dòng
            if (resultCounter % 30 == 0) {

                Log.d(
                    TAG,

                    "Face detected | " +
                            "landmarks=${landmarks.size} | " +
                            "inference=${inferenceTime}ms"
                )


                // Log vị trí khuôn mặt trong frame
                if (frameQuality != null) {

                    val status =
                        if (frameQuality.tooCloseToEdge) {
                            "TOO_CLOSE_TO_EDGE"
                        } else {
                            "SAFE"
                        }


                    Log.d(
                        TAG_FRAME_QUALITY,

                        "Left=${frameQuality.leftMargin} | " +
                                "Right=${frameQuality.rightMargin} | " +
                                "Top=${frameQuality.topMargin} | " +
                                "Bottom=${frameQuality.bottomMargin} | " +
                                "Status=$status"
                    )
                }


                // Log EAR và MAR
                if (faceFeatures != null) {

                    Log.d(
                        TAG_FEATURES,

                        "LeftEAR=${faceFeatures.leftEAR} | " +
                                "RightEAR=${faceFeatures.rightEAR} | " +
                                "AvgEAR=${faceFeatures.averageEAR} | " +
                                "MAR=${faceFeatures.mar}"
                    )

                } else {

                    Log.w(
                        TAG_FEATURES,
                        "Không thể tính đặc trưng khuôn mặt."
                    )
                }


                // Log head pose 3D và baseline 2D
                if (
                    faceFeatures != null &&
                    faceFeatures.headPoseAvailable
                ) {

                    Log.d(
                        TAG_HEAD_POSE,

                        "Yaw=${faceFeatures.yawDeg} | " +
                                "Pitch=${faceFeatures.pitchDeg} | " +
                                "Roll=${faceFeatures.rollDeg} | " +
                                "Horizontal2D=${faceFeatures.horizontalHeadDeviation} | " +
                                "Vertical2D=${faceFeatures.verticalHeadDeviation}"
                    )

                } else {

                    Log.w(
                        TAG_HEAD_POSE,
                        "Không nhận được facial transformation matrix."
                    )
                }


                // Log tiến trình calibration
                if (
                    headPoseCalibrator.getState() ==
                    HeadPoseCalibrator.CalibrationState.CALIBRATING
                ) {

                    Log.d(
                        TAG_HEAD_CALIBRATION,
                        "CALIBRATING | Giữ đầu nhìn thẳng"
                    )
                }


                // Log head pose đã calibration
                if (calibratedHeadPose != null) {

                    Log.d(
                        TAG_CALIBRATED_HEAD_POSE,

                        "Yaw=${calibratedHeadPose.yawDeg} | " +
                                "Pitch=${calibratedHeadPose.pitchDeg} | " +
                                "Roll=${calibratedHeadPose.rollDeg}"
                    )
                }


                // Log head pose đã làm mượt
                if (smoothedHeadPose != null) {

                    Log.d(
                        TAG_SMOOTHED_HEAD_POSE,

                        "Yaw=${smoothedHeadPose.yawDeg} | " +
                                "Pitch=${smoothedHeadPose.pitchDeg} | " +
                                "Roll=${smoothedHeadPose.rollDeg}"
                    )
                }


                // Log trạng thái detector gesture đầu
                if (headGestureResult != null) {

                    Log.d(
                        TAG_HEAD_GESTURE_STATE,

                        "Zone=${headGestureResult.zone} | " +
                                "Yaw=${headGestureResult.yawDeg} | " +
                                "Pitch=${headGestureResult.pitchDeg} | " +
                                "Roll=${headGestureResult.rollDeg} | " +
                                "Duration=${headGestureResult.candidateDurationMs}ms | " +
                                "RollGate=${headGestureResult.rollGatePassed} | " +
                                "CrossAxisGate=${headGestureResult.crossAxisGatePassed} | " +
                                "Locked=${headGestureResult.lockedUntilCenter}"
                    )
                }


                // Log trạng thái mắt và miệng
                if (faceState != null) {

                    Log.d(
                        TAG_STATE,

                        "Eye=${faceState.eyeState} | " +
                                "Mouth=${faceState.mouthState} | " +
                                "AvgEAR=${faceState.averageEAR} | " +
                                "MAR=${faceState.mar}"
                    )

                } else {

                    Log.w(
                        TAG_STATE,
                        "Không thể xác định trạng thái khuôn mặt."
                    )
                }


                // Log temporal khi mắt nhắm hoặc miệng mở
                if (
                    temporalResult != null &&
                    (
                            temporalResult.eyeState ==
                                    FaceStateDetector.EyeState.CLOSED ||
                                    temporalResult.mouthState ==
                                    FaceStateDetector.MouthState.OPEN
                            )
                ) {

                    Log.d(
                        TAG_TEMPORAL,

                        "Eye=${temporalResult.eyeState} | " +
                                "EyeClosedDuration=${temporalResult.eyeClosedDurationMs}ms | " +
                                "Mouth=${temporalResult.mouthState} | " +
                                "MouthOpenDuration=${temporalResult.mouthOpenDurationMs}ms | " +
                                "Event=${temporalResult.event}"
                    )
                }
            }


            listener?.onResults(
                resultBundle
            )


        } else {

            // Reset trạng thái mắt và gesture khi mất khuôn mặt
            faceStateDetector.reset()

            temporalGestureDetector.reset()

            headPoseSmoother.reset()

            homeGestureDetector.reset()

            // Mất mặt tạm thời không được xóa khóa chống lặp
            headGestureDetector.interrupt()


            // Chỉ hủy calibration nếu nó chưa hoàn thành
            if (
                headPoseCalibrator.getState() ==
                HeadPoseCalibrator.CalibrationState.CALIBRATING
            ) {

                headPoseCalibrator.reset()


                Log.w(
                    TAG_HEAD_CALIBRATION,
                    "CANCELLED | Reason=FACE_LOST"
                )
            }


            listener?.onEmpty()
        }
    }


    // Xử lý lỗi MediaPipe
    private fun returnLivestreamError(
        error: RuntimeException
    ) {

        Log.e(
            TAG,
            "MediaPipe LiveStream error",
            error
        )


        listener?.onError(
            error.message
                ?: "MediaPipe xảy ra lỗi không xác định."
        )
    }


    // Giải phóng tài nguyên
    fun close() {

        faceStateDetector.reset()

        temporalGestureDetector.reset()

        headPoseCalibrator.reset()

        headPoseSmoother.reset()

        headGestureDetector.reset()

        homeGestureDetector.reset()

        faceCommandSafetyGate.reset()


        faceLandmarker?.close()

        faceLandmarker = null


        Log.d(
            TAG,
            "Face Landmarker closed"
        )
    }


    // Dữ liệu kết quả của một frame
    data class ResultBundle(

        val result: FaceLandmarkerResult,

        val features:
        FaceFeatureExtractor.FaceFeatures?,

        val calibratedHeadPose:
        HeadPoseCalibrator.CalibratedHeadPose?,

        val smoothedHeadPose:
        HeadPoseSmoother.SmoothedHeadPose?,

        val headGestureResult:
        HeadGestureDetector.HeadGestureResult?,

        val homeGestureResult:
        HomeGestureDetector.HomeGestureResult?,

        val state:
        FaceStateDetector.FaceState?,

        val temporalResult:
        TemporalGestureDetector.TemporalResult?,

        val commandResult:
        FaceCommandResolver.CommandResult,

        val commandSafetyResult:
        FaceCommandSafetyGate.SafetyResult,

        val inferenceTime: Long,

        val inputImageHeight: Int,

        val inputImageWidth: Int
    )


    // Listener gửi kết quả ra ngoài helper
    interface LandmarkerListener {

        fun onResults(
            resultBundle: ResultBundle
        )


        fun onEmpty()


        fun onError(
            error: String
        )
    }


    companion object {

        private const val TAG =
            "FaceLandmarkerHelper"


        private const val TAG_FEATURES =
            "FaceFeatures"


        private const val TAG_HEAD_POSE =
            "HeadPose"


        private const val TAG_CALIBRATED_HEAD_POSE =
            "CalibratedHeadPose"


        private const val TAG_SMOOTHED_HEAD_POSE =
            "SmoothedHeadPose"


        private const val TAG_HEAD_CALIBRATION =
            "HeadCalibration"


        private const val TAG_FRAME_QUALITY =
            "FaceFrameQuality"


        private const val TAG_HEAD_GESTURE =
            "HeadGesture"


        private const val TAG_HEAD_GESTURE_STATE =
            "HeadGestureState"


        private const val TAG_STATE =
            "FaceState"


        private const val TAG_TEMPORAL =
            "TemporalState"


        private const val TAG_GESTURE =
            "FaceGesture"


        private const val TAG_FACE_COMMAND =
            "FaceCommand"


        private const val TAG_FACE_COMMAND_SAFETY =
            "FaceCommandSafety"


        private const val MODEL_NAME =
            "face_landmarker.task"


        private const val DEFAULT_FACE_DETECTION_CONFIDENCE =
            0.5f


        private const val DEFAULT_FACE_PRESENCE_CONFIDENCE =
            0.5f


        private const val DEFAULT_FACE_TRACKING_CONFIDENCE =
            0.5f
    }
}