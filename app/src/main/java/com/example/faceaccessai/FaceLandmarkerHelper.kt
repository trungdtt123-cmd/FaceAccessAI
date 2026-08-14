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

    /*
     * MediaPipe Face Landmarker
     */
    private var faceLandmarker: FaceLandmarker? = null


    /*
     * Bộ đếm kết quả dùng cho Logcat.
     */
    private var resultCounter = 0


    /*
     * =========================================================
     * FACE STATE DETECTOR
     * =========================================================
     *
     * Nhận EAR / MAR rồi xác định:
     *
     * EyeState.OPEN
     * EyeState.CLOSED
     *
     * MouthState.OPEN
     * MouthState.CLOSED
     */
    private val faceStateDetector =
        FaceStateDetector()


    init {

        setupFaceLandmarker()

    }


    /*
     * =========================================================
     * KHỞI TẠO MEDIAPIPE FACE LANDMARKER
     * =========================================================
     */
    private fun setupFaceLandmarker() {

        try {

            /*
             * Model:
             *
             * app/src/main/assets/face_landmarker.task
             */
            val baseOptions =
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_NAME)
                    .build()


            val options =
                FaceLandmarker.FaceLandmarkerOptions.builder()

                    .setBaseOptions(baseOptions)

                    /*
                     * Hiện tại chỉ xử lý một khuôn mặt.
                     */
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

                    /*
                     * Camera realtime.
                     */
                    .setRunningMode(
                        RunningMode.LIVE_STREAM
                    )

                    /*
                     * Callback khi MediaPipe xử lý thành công.
                     */
                    .setResultListener(
                        this::returnLivestreamResult
                    )

                    /*
                     * Callback khi MediaPipe gặp lỗi.
                     */
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


    /*
     * =========================================================
     * NHẬN FRAME TỪ CAMERAX
     * =========================================================
     */
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean
    ) {

        /*
         * Timestamp dùng cho LIVE_STREAM.
         */
        val frameTime =
            SystemClock.uptimeMillis()


        val imageWidth =
            imageProxy.width

        val imageHeight =
            imageProxy.height

        val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees


        /*
         * MainActivity đã cấu hình:
         *
         * OUTPUT_IMAGE_FORMAT_RGBA_8888
         */
        val bitmapBuffer =
            Bitmap.createBitmap(
                imageWidth,
                imageHeight,
                Bitmap.Config.ARGB_8888
            )


        try {

            /*
             * CameraX ImageProxy → Bitmap.
             */
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


        /*
         * Dữ liệu đã được copy sang Bitmap.
         */
        imageProxy.close()


        /*
         * =========================================================
         * XOAY + MIRROR FRAME
         * =========================================================
         */
        val matrix =
            Matrix().apply {

                /*
                 * Xoay theo orientation CameraX.
                 */
                postRotate(
                    rotationDegrees.toFloat()
                )


                /*
                 * Camera trước cần mirror.
                 */
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


        /*
         * Bitmap → MPImage.
         */
        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        /*
         * Gửi sang MediaPipe.
         */
        detectAsync(
            mpImage,
            frameTime
        )
    }


    /*
     * =========================================================
     * MEDIAPIPE ASYNC
     * =========================================================
     */
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


    /*
     * =========================================================
     * KẾT QUẢ MEDIAPIPE
     * =========================================================
     */
    private fun returnLivestreamResult(
        result: FaceLandmarkerResult,
        input: MPImage
    ) {

        /*
         * =====================================================
         * CÓ KHUÔN MẶT
         * =====================================================
         */
        if (result.faceLandmarks().isNotEmpty()) {

            resultCounter++


            /*
             * =================================================
             * 1. LẤY LANDMARKS
             * =================================================
             */
            val landmarks =
                result
                    .faceLandmarks()
                    .first()


            /*
             * =================================================
             * 2. TÍNH EAR + MAR
             * =================================================
             */
            val faceFeatures =
                FaceFeatureExtractor.extract(
                    landmarks = landmarks,
                    imageWidth = input.width,
                    imageHeight = input.height
                )


            /*
             * =================================================
             * 3. XÁC ĐỊNH TRẠNG THÁI MẮT / MIỆNG
             * =================================================
             */
            val faceState =
                faceFeatures?.let { features ->

                    faceStateDetector.detect(
                        features
                    )

                }


            /*
             * =================================================
             * 4. THỜI GIAN INFERENCE
             * =================================================
             */
            val inferenceTime =
                SystemClock.uptimeMillis() -
                        result.timestampMs()


            /*
             * =================================================
             * 5. RESULT BUNDLE
             * =================================================
             *
             * Bây giờ ResultBundle chứa:
             *
             * result
             * features
             * state
             * inferenceTime
             */
            val resultBundle =
                ResultBundle(

                    result = result,

                    features = faceFeatures,

                    state = faceState,

                    inferenceTime = inferenceTime,

                    inputImageHeight = input.height,

                    inputImageWidth = input.width

                )


            /*
             * =================================================
             * 6. DEBUG
             * =================================================
             *
             * Log khoảng mỗi 30 kết quả.
             */
            if (resultCounter % 30 == 0) {

                val numberOfLandmarks =
                    landmarks.size


                /*
                 * MediaPipe debug.
                 */
                Log.d(
                    TAG,
                    "Face detected | " +
                            "landmarks=$numberOfLandmarks | " +
                            "inference=${inferenceTime}ms"
                )


                /*
                 * EAR / MAR debug.
                 */
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


                /*
                 * =================================================
                 * TRẠNG THÁI MẮT + MIỆNG
                 * =================================================
                 */
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
            }


            /*
             * Gửi kết quả ra ngoài nếu có listener.
             */
            listener?.onResults(
                resultBundle
            )


        } else {

            /*
             * =====================================================
             * KHÔNG TÌM THẤY KHUÔN MẶT
             * =====================================================
             *
             * Reset trạng thái cũ để khi khuôn mặt
             * xuất hiện trở lại không bị giữ state trước đó.
             */
            faceStateDetector.reset()


            listener?.onEmpty()

        }
    }


    /*
     * =========================================================
     * LỖI MEDIAPIPE
     * =========================================================
     */
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


    /*
     * =========================================================
     * GIẢI PHÓNG TÀI NGUYÊN
     * =========================================================
     */
    fun close() {

        /*
         * Reset trạng thái.
         */
        faceStateDetector.reset()


        /*
         * Đóng MediaPipe.
         */
        faceLandmarker?.close()

        faceLandmarker = null


        Log.d(
            TAG,
            "Face Landmarker closed"
        )
    }


    /*
     * =========================================================
     * RESULT BUNDLE
     * =========================================================
     */
    data class ResultBundle(

        /*
         * Kết quả MediaPipe gốc.
         */
        val result: FaceLandmarkerResult,


        /*
         * EAR / MAR.
         */
        val features:
        FaceFeatureExtractor.FaceFeatures?,


        /*
         * Eye OPEN/CLOSED
         *
         * Mouth OPEN/CLOSED
         */
        val state:
        FaceStateDetector.FaceState?,


        /*
         * Thời gian inference.
         */
        val inferenceTime: Long,


        val inputImageHeight: Int,


        val inputImageWidth: Int

    )


    /*
     * =========================================================
     * LISTENER
     * =========================================================
     */
    interface LandmarkerListener {

        fun onResults(
            resultBundle: ResultBundle
        )


        fun onEmpty()


        fun onError(
            error: String
        )
    }


    /*
     * =========================================================
     * CONSTANTS
     * =========================================================
     */
    companion object {

        /*
         * MediaPipe log.
         */
        private const val TAG =
            "FaceLandmarkerHelper"


        /*
         * EAR / MAR log.
         */
        private const val TAG_FEATURES =
            "FaceFeatures"


        /*
         * Eye / Mouth state log.
         */
        private const val TAG_STATE =
            "FaceState"


        /*
         * Model trong assets.
         */
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