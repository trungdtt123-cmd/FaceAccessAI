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


    // Nhận diện gesture theo thời gian
    private val temporalGestureDetector =
        TemporalGestureDetector()


    // Calibration tư thế đầu trung tính
    private val headPoseCalibrator =
        HeadPoseCalibrator()


    init {
        setupFaceLandmarker()
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


            val inferenceTime =
                SystemClock.uptimeMillis() -
                        timestampMs


            val resultBundle =
                ResultBundle(
                    result = result,
                    features = faceFeatures,
                    calibratedHeadPose =
                        calibratedHeadPose,
                    state = faceState,
                    temporalResult = temporalResult,
                    inferenceTime = inferenceTime,
                    inputImageHeight = input.height,
                    inputImageWidth = input.width
                )


            // Log ngay khi phát hiện gesture
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

        val state:
        FaceStateDetector.FaceState?,

        val temporalResult:
        TemporalGestureDetector.TemporalResult?,

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


        private const val TAG_HEAD_CALIBRATION =
            "HeadCalibration"


        private const val TAG_FRAME_QUALITY =
            "FaceFrameQuality"


        private const val TAG_STATE =
            "FaceState"


        private const val TAG_TEMPORAL =
            "TemporalState"


        private const val TAG_GESTURE =
            "FaceGesture"


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