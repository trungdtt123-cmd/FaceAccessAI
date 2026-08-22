package com.example.faceaccessai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
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

    enum class CalibrationTrackingStatus {
        TRACKING_OK,
        FRAME_TOO_CLOSE,
        FACE_NOT_FOUND,
        POSE_UNAVAILABLE
    }

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


    // Làm mượt head pose sau calibration (Dành cho runtime bình thường)
    private val headPoseSmoother =
        HeadPoseSmoother()

    // V3: Làm mượt pose riêng cho phiên hiệu chỉnh cá nhân (Dành cho SAFE + CAUTION)
    private val calibrationPoseSmoother =
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


    // Engine xử lý con trỏ ảo
    private val virtualCursorEngine =
        VirtualCursorEngine(context)


    // Virtual cursor target tracking
    private var lastHoverTarget: Rect? = null

    // Locked scroll/swipe logic
    private var lastScrollTimeMs: Long = 0
    private val scrollCooldownMs = 800L
    private val scrollThresholdPitch = 9f
    private val scrollThresholdYaw = 10f
    
    // Yêu cầu trở về gốc (neutral) mới được nhận lệnh tiếp theo khi khóa
    private var pitchActionFired = false
    private var yawActionFired = false
    private val neutralThreshold = 4f


    // Kiểm tra an toàn trước khi cho phép thực thi command
    private val faceCommandSafetyGate =
        FaceCommandSafetyGate()


    // Quản lý phiên hiệu chỉnh cá nhân hóa
    private var calibrationSession: HeadDirectionalCalibrationSession? = null

    // V3: Lưu trữ step hiện tại phục vụ logging chẩn đoán
    private var currentCalibrationStep: HeadDirectionalCalibrationSession.Step? = null

    // Cursor mode state tracking
    private var previousMode: FaceControlMode? = null
    private var lastTrackingStatus: CalibrationTrackingStatus? = null
    private var reusableBitmap: Bitmap? = null
    private var reusableRotatedBitmap: Bitmap? = null


    init {
        setupFaceLandmarker()
        applySensitivityConfig()
    }


    fun startCalibration(
        onStepChanged: (HeadDirectionalCalibrationSession.Step, Set<HeadDirectionalCalibrationSession.Direction>) -> Unit,
        onComplete: (HeadDirectionalCalibrationProfile) -> Unit,
        onCancelled: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        calibrationPoseSmoother.reset()
        currentCalibrationStep = null
        calibrationSession = HeadDirectionalCalibrationSession(
            onStepChanged = { step, passed ->
                currentCalibrationStep = step
                onStepChanged(step, passed)
            },
            onComplete = { profile ->
                calibrationSession = null
                currentCalibrationStep = null
                calibrationPoseSmoother.reset()
                onComplete(profile)
            },
            onCancelled = { reason ->
                calibrationSession = null
                currentCalibrationStep = null
                calibrationPoseSmoother.reset()
                onCancelled(reason)
            },
            onError = onError
        )
        calibrationSession?.start()
    }


    fun stopCalibration() {
        calibrationSession?.cancel()
        calibrationSession = null
        currentCalibrationStep = null
        calibrationPoseSmoother.reset()
    }


    fun applySensitivityConfig() {
        val sensitivity = GestureSensitivityManager.getInstance(context).getSensitivity()
        val personalProfile = HeadDirectionalCalibrationManager.getInstance(context).load()
        
        val homeConfig = GestureSensitivityConfigProvider.homeConfig(sensitivity)
        val headConfig = EffectiveHeadGestureConfigProvider.getEffectiveConfig(sensitivity, personalProfile)
        
        homeGestureDetector.updateConfig(homeConfig)
        headGestureDetector.updateConfig(headConfig)
        virtualCursorEngine.setCalibrationProfile(personalProfile)
        
        Log.d(TAG, "GestureSensitivity | Applied=$sensitivity | Personalized=${personalProfile != null}")
    }


    fun toggleCursorLock() {
        virtualCursorEngine.toggleLock()
    }

    fun isCursorLocked(): Boolean = virtualCursorEngine.isLocked()


    fun performCursorClick(): Boolean {
        val (cx, cy) = virtualCursorEngine.getPosition()
        return FaceAccessAccessibilityService.performCursorClickAt(cx, cy)
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
        if (faceLandmarker == null) {
            imageProxy.close()
            return
        }

        val frameTime =
            SystemClock.uptimeMillis()


        val imageWidth =
            imageProxy.width


        val imageHeight =
            imageProxy.height


        val rotationDegrees =
            imageProxy.imageInfo.rotationDegrees

        // Tối ưu hóa: Sử dụng Reusable Bitmap để tránh OOM/Crash
        if (reusableBitmap == null || reusableBitmap!!.width != imageWidth || reusableBitmap!!.height != imageHeight) {
            reusableBitmap?.recycle()
            reusableBitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        }
        
        val bitmapBuffer = reusableBitmap!!

        try {
            // Copy ImageProxy sang Bitmap
            imageProxy.planes[0].buffer.rewind() // Quan trọng: Đưa con trỏ về đầu
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

        // Tối ưu hóa: Tính toán kích thước rotated bitmap (thường là 90/270 độ sẽ đảo W/H)
        val rotatedWidth = if (rotationDegrees % 180 != 0) imageHeight else imageWidth
        val rotatedHeight = if (rotationDegrees % 180 != 0) imageWidth else imageHeight

        if (reusableRotatedBitmap == null || reusableRotatedBitmap!!.width != rotatedWidth || reusableRotatedBitmap!!.height != rotatedHeight) {
            reusableRotatedBitmap?.recycle()
            reusableRotatedBitmap = Bitmap.createBitmap(rotatedWidth, rotatedHeight, Bitmap.Config.ARGB_8888)
        }

        val rotatedBitmap = reusableRotatedBitmap!!
        val canvas = android.graphics.Canvas(rotatedBitmap)
        
        // Cần điều chỉnh matrix để vẽ đúng vào canvas mới
        val drawMatrix = Matrix()
        // 1. Dịch chuyển tâm để xoay
        drawMatrix.postTranslate(-imageWidth / 2f, -imageHeight / 2f)
        // 2. Áp dụng các phép biến đổi gốc (xoay, mirror)
        drawMatrix.postConcat(matrix)
        // 3. Dịch chuyển về tâm của bitmap đích
        drawMatrix.postTranslate(rotatedWidth / 2f, rotatedHeight / 2f)
        
        canvas.drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(bitmapBuffer, drawMatrix, null)

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


            // Kiểm tra khuôn mặt có quá sát mép frame không (Dành cho runtime)
            val frameQuality =
                FaceFrameQualityChecker.check(
                    landmarks = landmarks
                )


            val isFrameSafe =
                frameQuality != null &&
                        !frameQuality.tooCloseToEdge

            // V3 Near-Face: Kiểm tra khả năng sử dụng frame cho phiên hiệu chỉnh
            val isCalibrationFrameUsable = frameQuality != null &&
                frameQuality.calibrationFrameQuality != FaceFrameQualityChecker.CalibrationFrameQuality.UNUSABLE


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


            // V3 Near-Face: Bootstrap policy cho shared headPoseCalibrator
            val isHeadPoseBootstrapFrameUsable = if (calibrationSession != null) {
                isCalibrationFrameUsable
            } else {
                isFrameSafe
            }


            // Calibration chỉ chạy khi khuôn mặt an toàn trong frame
            if (
                faceFeatures != null &&
                faceFeatures.headPoseAvailable &&
                isHeadPoseBootstrapFrameUsable
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
                        if (!isHeadPoseBootstrapFrameUsable) {
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


            // Head pose sau khi trừ neutral (Runtime bình thường - vẫn yêu cầu isFrameSafe)
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


            // Làm mượt head pose đã calibration (Runtime bình thường - vẫn yêu cầu isFrameSafe)
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


            // --- XỬ LÝ VIRTUAL CURSOR ---
            val currentMode = FaceControlModeManager.getMode(context)
            val isLocked = virtualCursorEngine.isLocked()

            // Detect mode switch to CURSOR
            if (currentMode == FaceControlMode.CURSOR && previousMode != FaceControlMode.CURSOR) {
                FaceAccessAccessibilityService.showCursorInstructions()
                
                virtualCursorEngine.reset()
                lastHoverTarget = null
                lastScrollTimeMs = 0
                
                // Initial visibility even if no pose yet
                val (initialX, initialY) = virtualCursorEngine.getPosition()
                FaceAccessAccessibilityService.updateCursorPosition(initialX, initialY, isLocked, false)
                FaceAccessAccessibilityService.updateCursorStatus(true, isLocked)
            } 
            // Detect mode switch away from CURSOR
            else if (currentMode != FaceControlMode.CURSOR && previousMode == FaceControlMode.CURSOR) {
                FaceAccessAccessibilityService.removeCursor()
                FaceAccessAccessibilityService.clearCandidate()
                FaceAccessAccessibilityService.updateCursorStatus(false, false)
            }

            previousMode = currentMode

            // Luôn cập nhật chỉ báo tracking (chấm xanh/đỏ) ở mọi chế độ
            val currentTrackingStatus = when {
                smoothedHeadPose != null -> CalibrationTrackingStatus.TRACKING_OK
                frameQuality?.calibrationFrameQuality == FaceFrameQualityChecker.CalibrationFrameQuality.UNUSABLE -> CalibrationTrackingStatus.FRAME_TOO_CLOSE
                else -> CalibrationTrackingStatus.FACE_NOT_FOUND
            }
            
            // Gọi service mọi frame, việc tối ưu hóa vẽ sẽ do OverlayController đảm nhận
            FaceAccessAccessibilityService.updateTrackingIndicator(currentTrackingStatus)
            lastTrackingStatus = currentTrackingStatus

            if (currentMode == FaceControlMode.CURSOR) {
                // ... logic to update cursor position if pose is available ...
                if (smoothedHeadPose != null) {
                    var (cx, cy) = virtualCursorEngine.getPosition()
                    
                    if (isLocked) {
                        // Logic CUỘN/VUỐT khi bị khóa (Locked Scroll/Swipe)
                        val pitch = smoothedHeadPose.pitchDeg
                        val yaw = smoothedHeadPose.yawDeg

                        // 1. Kiểm tra trở về Neutral để reset flag
                        if (Math.abs(pitch) < neutralThreshold) {
                            pitchActionFired = false
                        }
                        if (Math.abs(yaw) < neutralThreshold) {
                            yawActionFired = false
                        }

                        // 2. Kiểm tra điều kiện kích hoạt lệnh mới
                        if (timestampMs - lastScrollTimeMs >= scrollCooldownMs) {
                            var actionTriggered = false
                            
                            // Ưu tiên trục có độ lệch lớn hơn
                            if (Math.abs(pitch) > Math.abs(yaw)) {
                                // Trục Dọc: Chuẩn hóa hướng theo yêu cầu (Ngẩng lên -> Vuốt lên, Cúi xuống -> Vuốt xuống)
                                if (!pitchActionFired) {
                                    if (pitch > scrollThresholdPitch) { // Cúi xuống
                                        FaceAccessAccessibilityService.performScrollAt(cx, cy, ScrollDirection.DOWN)
                                        actionTriggered = true
                                        pitchActionFired = true
                                    } else if (pitch < -scrollThresholdPitch) { // Ngẩng lên
                                        FaceAccessAccessibilityService.performScrollAt(cx, cy, ScrollDirection.UP)
                                        actionTriggered = true
                                        pitchActionFired = true
                                    }
                                }
                            } else {
                                // Trục Ngang: (Xoay phải -> Vuốt phải, Xoay trái -> Vuốt trái)
                                if (!yawActionFired) {
                                    if (yaw > scrollThresholdYaw) { // Xoay Phải
                                        FaceAccessAccessibilityService.performScrollAt(cx, cy, ScrollDirection.RIGHT)
                                        actionTriggered = true
                                        yawActionFired = true
                                    } else if (yaw < -scrollThresholdYaw) { // Xoay Trái
                                        FaceAccessAccessibilityService.performScrollAt(cx, cy, ScrollDirection.LEFT)
                                        actionTriggered = true
                                        yawActionFired = true
                                    }
                                }
                            }
                            
                            if (actionTriggered) {
                                lastScrollTimeMs = timestampMs
                            }
                        }
                        
                        // Khi khóa: Yêu cầu của bạn là KHÔNG hiện ô xanh (candidate highlight)
                        FaceAccessAccessibilityService.clearCandidate()
                        lastHoverTarget = null
                    } else {
                        // Logic DI CHUYỂN bình thường
                        virtualCursorEngine.update(
                            yaw = smoothedHeadPose.yawDeg,
                            pitch = smoothedHeadPose.pitchDeg,
                            timestampMs = timestampMs
                        )
                        val pos = virtualCursorEngine.getPosition()
                        cx = pos.first
                        cy = pos.second

                        // Tìm target để snap và highlight
                        val targetBounds = FaceAccessAccessibilityService.findCursorTarget(cx, cy)
                        if (targetBounds != null) {
                            // Snap cao hơn tâm thực tế một chút (vị trí Icon thay vì cả icon+label)
                            // Đã tăng thêm 3dp theo yêu cầu (10dp -> 13dp)
                            val density = context.resources.displayMetrics.density
                            val snapYOffset = 13f * density
                            
                            virtualCursorEngine.snapTo(
                                targetBounds.centerX().toFloat(), 
                                targetBounds.centerY().toFloat() - snapYOffset
                            )
                            val snappedPos = virtualCursorEngine.getPosition()
                            cx = snappedPos.first
                            cy = snappedPos.second
                            FaceAccessAccessibilityService.showCandidate(targetBounds)
                        } else {
                            FaceAccessAccessibilityService.clearCandidate()
                        }
                    }

                    FaceAccessAccessibilityService.updateCursorPosition(
                        x = cx,
                        y = cy,
                        isLocked = isLocked,
                        isHovering = !isLocked && lastHoverTarget != null
                    )
                }
                
                // Always update status to show current state
                FaceAccessAccessibilityService.updateCursorStatus(true, isLocked)
                
            } else if (currentMode != FaceControlMode.CURSOR && previousMode == FaceControlMode.CURSOR) {
                FaceAccessAccessibilityService.removeCursor()
                FaceAccessAccessibilityService.clearCandidate()
                FaceAccessAccessibilityService.updateCursorStatus(false, false)
                // Không xóa tracking indicator ở đây nữa vì nó đã được cập nhật ở trên
            }


            // V3 Near-Face: Tính toán pose riêng biệt cho phiên hiệu chỉnh cá nhân (Cho phép SAFE + CAUTION)
            val calibrationPose = if (calibrationSession != null &&
                isCalibrationFrameUsable && 
                faceFeatures != null && 
                faceFeatures.headPoseAvailable &&
                headPoseCalibrator.getState() == HeadPoseCalibrator.CalibrationState.READY) {
                
                val cal = headPoseCalibrator.calibrate(
                    yawDeg = faceFeatures.yawDeg, 
                    pitchDeg = faceFeatures.pitchDeg, 
                    rollDeg = faceFeatures.rollDeg
                )
                
                if (cal != null) {
                    calibrationPoseSmoother.update(
                        yawDeg = cal.yawDeg,
                        pitchDeg = cal.pitchDeg,
                        rollDeg = cal.rollDeg
                    )
                } else {
                    null
                }
            } else {
                null
            }


            // Quản lý trạng thái hiệu chỉnh cho frame hiện tại
            val calibrationWasActiveAtStart = calibrationSession != null


            // Nhận diện gesture đầu 4 hướng (Runtime bình thường - vẫn yêu cầu isFrameSafe)
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

            // V3: Cập nhật session hiệu chỉnh cá nhân với pipeline smoothing riêng (Cho phép SAFE + CAUTION)
            if (calibrationWasActiveAtStart) {
                if (calibrationPose != null) {
                    calibrationSession?.update(
                        pose = calibrationPose,
                        timestampMs = timestampMs,
                        faceScale = frameQuality?.faceScale ?: 1f
                    )
                } else {
                    // V3: Tạm dừng bằng chứng hiệu chỉnh khi mất tracking hoặc frame không sử dụng được
                    calibrationPoseSmoother.reset()
                    calibrationSession?.pauseCurrentProgress()
                }
            }


            // Nhận diện gesture HOME bằng chuỗi nghiêng đầu (Runtime bình thường - vẫn yêu cầu isFrameSafe)
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
            val rawCommandResult =
                faceCommandResolver.resolve(
                    headGestureResult =
                        headGestureResult,
                    temporalResult =
                        temporalResult,
                    homeGestureResult = homeGestureResult
                )


            // Chặn toàn bộ lệnh nếu đang trong phiên hiệu chỉnh
            val commandResult = if (calibrationWasActiveAtStart) {
                FaceCommandResolver.CommandResult(
                    command = FaceCommandResolver.FaceCommand.NONE,
                    source = FaceCommandResolver.CommandSource.NONE
                )
            } else {
                rawCommandResult
            }


            // Chặn command nếu frame không an toàn hoặc đang cooldown (Runtime bình thường dùng isFrameSafe)
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

            // V3 Tracking Status Policy
            val calStatus = when {
                calibrationPose != null -> CalibrationTrackingStatus.TRACKING_OK
                frameQuality?.calibrationFrameQuality == FaceFrameQualityChecker.CalibrationFrameQuality.UNUSABLE -> CalibrationTrackingStatus.FRAME_TOO_CLOSE
                faceFeatures == null || !faceFeatures.headPoseAvailable -> CalibrationTrackingStatus.POSE_UNAVAILABLE
                else -> CalibrationTrackingStatus.POSE_UNAVAILABLE
            }


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
                    calibrationTrackingAvailable = 
                        calibrationPose != null,
                    calibrationTrackingStatus = calStatus,
                    inferenceTime = inferenceTime,
                    inputImageHeight = input.height,
                    inputImageWidth = input.width
                )


            // Diagnostic log cho personal calibration tuning
            if (calibrationSession != null && resultCounter % 30 == 0) {
                Log.d("NearFaceCalibration", 
                    "Step=$currentCalibrationStep | " +
                    "Quality=${frameQuality?.calibrationFrameQuality} | " +
                    "Scale=${frameQuality?.faceScale} | " +
                    "WRatio=${frameQuality?.faceWidthRatio} | " +
                    "HRatio=${frameQuality?.faceHeightRatio} | " +
                    "Margins(L=${frameQuality?.leftMargin}, R=${frameQuality?.rightMargin}, T=${frameQuality?.topMargin}, B=${frameQuality?.bottomMargin}) | " +
                    "isSafe=$isFrameSafe | " +
                    "isUsable=$isCalibrationFrameUsable | " +
                    "CalState=${headPoseCalibrator.getState()} | " +
                    "PoseReady=${calibrationPose != null}"
                )
            }


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

            // V3: Pause calibration evidence during temporary tracking loss
            calibrationPoseSmoother.reset()
            calibrationSession?.pauseCurrentProgress()

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


            // Cập nhật chỉ báo tracking khi mất khuôn mặt
            if (lastTrackingStatus != CalibrationTrackingStatus.FACE_NOT_FOUND) {
                FaceAccessAccessibilityService.updateTrackingIndicator(CalibrationTrackingStatus.FACE_NOT_FOUND)
                lastTrackingStatus = CalibrationTrackingStatus.FACE_NOT_FOUND
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
        
        calibrationPoseSmoother.reset()

        headGestureDetector.reset()

        homeGestureDetector.reset()

        faceCommandSafetyGate.reset()


        currentCalibrationStep = null

        reusableBitmap?.recycle()
        reusableBitmap = null
        reusableRotatedBitmap?.recycle()
        reusableRotatedBitmap = null

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

        val calibrationTrackingAvailable: Boolean,

        val calibrationTrackingStatus: CalibrationTrackingStatus,

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


        fun onCancelled(
            reason: String
        )


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
