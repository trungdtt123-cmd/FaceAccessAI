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


    // Xác định trạng thái mắt và miệng từ EAR và MAR
    private val faceStateDetector =
        FaceStateDetector()


    // Nhận diện gesture dựa trên chuỗi trạng thái theo thời gian
    private val temporalGestureDetector =
        TemporalGestureDetector()


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

        // Timestamp của frame dùng cho LIVE_STREAM
        val frameTime =
            SystemClock.uptimeMillis()


        val imageWidth =
            imageProxy.width


        val imageHeight =
            imageProxy.height


        val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees


        // Tạo Bitmap để chứa frame CameraX
        val bitmapBuffer =
            Bitmap.createBitmap(
                imageWidth,
                imageHeight,
                Bitmap.Config.ARGB_8888
            )


        try {

            // Copy dữ liệu ImageProxy sang Bitmap
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


        // Đã copy dữ liệu nên có thể đóng ImageProxy
        imageProxy.close()


        // Xoay frame theo orientation của camera
        val matrix =
            Matrix().apply {

                postRotate(
                    rotationDegrees.toFloat()
                )


                // Mirror camera trước
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


        // Chuyển Bitmap thành MPImage
        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        // Gửi frame vào MediaPipe
        detectAsync(
            mpImage,
            frameTime
        )
    }


    // Gửi frame vào Face Landmarker theo chế độ bất đồng bộ
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


            // Lấy landmarks của khuôn mặt đầu tiên
            val landmarks =
                result
                    .faceLandmarks()
                    .first()


            // Tính EAR và MAR
            val faceFeatures =
                FaceFeatureExtractor.extract(
                    landmarks = landmarks,
                    imageWidth = input.width,
                    imageHeight = input.height
                )


            // Xác định trạng thái mắt và miệng
            val faceState =
                faceFeatures?.let { features ->

                    faceStateDetector.detect(
                        features
                    )
                }


            // Phân tích trạng thái mắt theo thời gian
            val temporalResult =
                faceState?.let { state ->

                    temporalGestureDetector.update(
                        faceState = state,
                        timestampMs = result.timestampMs()
                    )
                }


            // Tính thời gian xử lý frame
            val inferenceTime =
                SystemClock.uptimeMillis() -
                        result.timestampMs()


            // Gom kết quả để trả về cho phần khác của ứng dụng
            val resultBundle =
                ResultBundle(

                    result = result,

                    features = faceFeatures,

                    state = faceState,

                    temporalResult = temporalResult,

                    inferenceTime = inferenceTime,

                    inputImageHeight = input.height,

                    inputImageWidth = input.width
                )


            // Log ngay khi phát hiện một gesture
            if (
                temporalResult != null &&
                temporalResult.event !=
                TemporalGestureDetector.GestureEvent.NONE
            ) {

                Log.d(
                    TAG_GESTURE,

                    "Event=${temporalResult.event} | " +
                            "Eye=${temporalResult.eyeState} | " +
                            "ClosedDuration=${temporalResult.eyeClosedDurationMs}ms"
                )
            }


            // Log thông tin định kỳ để tránh Logcat quá nhiều dòng
            if (resultCounter % 30 == 0) {

                Log.d(
                    TAG,

                    "Face detected | " +
                            "landmarks=${landmarks.size} | " +
                            "inference=${inferenceTime}ms"
                )


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
                        "Không thể tính EAR/MAR từ landmarks."
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


                // Log thời gian mắt đang nhắm
                if (
                    temporalResult != null &&
                    temporalResult.eyeState ==
                    FaceStateDetector.EyeState.CLOSED
                ) {

                    Log.d(
                        TAG_TEMPORAL,

                        "Eye=CLOSED | " +
                                "Duration=${temporalResult.eyeClosedDurationMs}ms | " +
                                "Event=${temporalResult.event}"
                    )
                }
            }


            listener?.onResults(
                resultBundle
            )


        } else {

            // Reset khi không còn nhìn thấy khuôn mặt
            faceStateDetector.reset()

            temporalGestureDetector.reset()

            listener?.onEmpty()
        }
    }


    // Xử lý lỗi từ MediaPipe
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

        val state:
        FaceStateDetector.FaceState?,

        val temporalResult:
        TemporalGestureDetector.TemporalResult?,

        val inferenceTime: Long,

        val inputImageHeight: Int,

        val inputImageWidth: Int
    )


    // Listener để gửi kết quả ra ngoài helper
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

        // Log MediaPipe
        private const val TAG =
            "FaceLandmarkerHelper"


        // Log EAR và MAR
        private const val TAG_FEATURES =
            "FaceFeatures"


        // Log trạng thái mắt và miệng
        private const val TAG_STATE =
            "FaceState"


        // Log thời gian nhắm mắt
        private const val TAG_TEMPORAL =
            "TemporalState"


        // Log gesture được phát hiện
        private const val TAG_GESTURE =
            "FaceGesture"


        // Tên model trong thư mục assets
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