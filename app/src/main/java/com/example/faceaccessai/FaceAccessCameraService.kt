package com.example.faceaccessai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceAccessCameraService :
    Service(),
    LifecycleOwner {

    enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING
    }

    private val lifecycleRegistry =
        LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private lateinit var analysisExecutor:
            ExecutorService

    private lateinit var faceLandmarkerHelper:
            FaceLandmarkerHelper

    private lateinit var actionDispatcher:
            FaceActionDispatcher

    private var cameraProvider:
            ProcessCameraProvider? = null

    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

    private var cameraStarted =
        false

    private var stopRequested = false

    @Volatile
    private var trackingAvailable = false

    @Volatile
    private var calibrationTrackingStatus = 
        FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND

    private var frameCount =
        0

    override fun onCreate() {
        super.onCreate()
        instance = this

        // If start(context) was used, state might already be STARTING.
        if (serviceState == ServiceState.STOPPED) {
            publishServiceState(this, ServiceState.STARTING)
        }

        lifecycleRegistry.currentState =
            Lifecycle.State.CREATED

        analysisExecutor =
            Executors.newSingleThreadExecutor()

        actionDispatcher =
            FaceActionDispatcher()

        createNotificationChannel()

        Log.d(
            TAG_SERVICE,
            "FaceAccess camera service created"
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val action = intent?.action

        if (action == ACTION_STOP) {
            Log.d(TAG_SERVICE, "Stop requested via intent")
            stopServiceInternal()
            return START_NOT_STICKY
        }

        if (action == ACTION_TOGGLE_PAUSE) {
            val isPaused = FaceControlStateManager.togglePaused()
            
            FaceAccessAccessibilityService.setFaceControlPausedVisual(isPaused)
            
            updateNotification()
            
            return START_NOT_STICKY
        }

        if (action == ACTION_UPDATE_SENSITIVITY) {
            if (::faceLandmarkerHelper.isInitialized) {
                faceLandmarkerHelper.applySensitivityConfig()
            }
            return START_NOT_STICKY
        }

        if (action == ACTION_START_CALIBRATION) {
            if (getServiceState() == ServiceState.RUNNING && ::faceLandmarkerHelper.isInitialized) {
                calibrationActive = true
                resetCalibrationTrackingState()
                
                faceLandmarkerHelper.startCalibration(
                    onStepChanged = { step, passed ->
                        currentCalibrationStep = step
                        passedDirections = passed
                        sendCalibrationBroadcast(
                            step = step, 
                            passed = passed, 
                            isTracking = trackingAvailable,
                            status = calibrationTrackingStatus
                        )
                    },
                    onComplete = { profile ->
                        HeadDirectionalCalibrationManager.getInstance(this).save(profile)
                        faceLandmarkerHelper.applySensitivityConfig()
                        
                        val finalPassed = setOf(
                            HeadDirectionalCalibrationSession.Direction.LEFT,
                            HeadDirectionalCalibrationSession.Direction.RIGHT,
                            HeadDirectionalCalibrationSession.Direction.UP,
                            HeadDirectionalCalibrationSession.Direction.DOWN
                        )
                        
                        // Send success state first for UI feedback
                        sendCalibrationBroadcast(
                            step = HeadDirectionalCalibrationSession.Step.COMPLETE,
                            passed = finalPassed,
                            isComplete = true,
                            isTracking = true,
                            status = FaceLandmarkerHelper.CalibrationTrackingStatus.TRACKING_OK
                        )

                        // Cleanup internal state after broadcast
                        calibrationActive = false
                        currentCalibrationStep = null
                        passedDirections = emptySet()
                        resetCalibrationTrackingState()
                    },
                    onCancelled = { reason ->
                        calibrationActive = false
                        currentCalibrationStep = null
                        passedDirections = emptySet()
                        resetCalibrationTrackingState()
                        sendCalibrationBroadcast(error = reason)
                    },
                    onError = { error ->
                        sendCalibrationBroadcast(
                            step = currentCalibrationStep,
                            passed = passedDirections,
                            isTracking = trackingAvailable,
                            status = calibrationTrackingStatus,
                            error = error
                        )
                    }
                )
            } else {
                sendCalibrationBroadcast(error = "Dịch vụ chưa sẵn sàng. Hãy thử lại sau giây lát.")
            }
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_CALIBRATION) {
            if (::faceLandmarkerHelper.isInitialized) {
                faceLandmarkerHelper.stopCalibration()
            }
            calibrationActive = false
            currentCalibrationStep = null
            passedDirections = emptySet()
            resetCalibrationTrackingState()
            sendCalibrationBroadcast() 
            return START_NOT_STICKY
        }

        if (action == ACTION_SET_PREVIEW) {
            previewUseCase?.setSurfaceProvider(staticPreviewSurfaceProvider)
            staticPreviewSurfaceProvider = null // Clear handoff reference
            return START_NOT_STICKY
        }

        try {
            // New valid start command clears stopRequested for this run
            stopRequested = false
            
            startAsForegroundService()

            if (
                !::faceLandmarkerHelper
                    .isInitialized
            ) {

                initializeFaceLandmarker()
            }

            lifecycleRegistry.currentState =
                Lifecycle.State.STARTED

            startCamera()

            Log.d(
                TAG_SERVICE,
                "FaceAccess camera service start requested"
            )

        } catch (
            exception: Exception
        ) {

            Log.e(
                TAG_SERVICE,
                "Unable to initialize foreground camera service",
                exception
            )

            stopServiceInternal()
        }

        return START_NOT_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    private fun initializeFaceLandmarker() {

        val mainExecutor =
            ContextCompat.getMainExecutor(
                this
            )

        faceLandmarkerHelper =
            FaceLandmarkerHelper(
                context =
                    applicationContext,

                listener =
                    object :
                        FaceLandmarkerHelper.LandmarkerListener {

                        override fun onResults(
                            resultBundle:
                            FaceLandmarkerHelper.ResultBundle
                        ) {
                            if (calibrationActive) {
                                val currentTracking = resultBundle.calibrationTrackingAvailable
                                val currentStatus = resultBundle.calibrationTrackingStatus
                                if (currentTracking != trackingAvailable || currentStatus != calibrationTrackingStatus) {
                                    trackingAvailable = currentTracking
                                    calibrationTrackingStatus = currentStatus
                                    sendCalibrationBroadcast(
                                        step = currentCalibrationStep,
                                        passed = passedDirections,
                                        isTracking = currentTracking,
                                        status = currentStatus
                                    )
                                }
                            }

                            val safetyResult =
                                resultBundle
                                    .commandSafetyResult

                            if (
                                !safetyResult.isAllowed
                            ) {

                                return
                            }

                            mainExecutor.execute {

                                val dispatchResult =
                                    actionDispatcher.dispatch(
                                        safetyResult
                                    )

                                Log.d(
                                    TAG_FACE_ACTION,
                                    "Command=${safetyResult.command} | " +
                                            "Source=${safetyResult.source} | " +
                                            "Result=$dispatchResult"
                                )
                            }
                        }

                        override fun onEmpty() {
                            if (calibrationActive) {
                                if (trackingAvailable || calibrationTrackingStatus != FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND) {
                                    trackingAvailable = false
                                    calibrationTrackingStatus = FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND
                                    sendCalibrationBroadcast(
                                        step = currentCalibrationStep,
                                        passed = passedDirections,
                                        isTracking = false,
                                        status = FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND
                                    )
                                }
                            }
                        }

                        override fun onCancelled(reason: String) {
                            mainExecutor.execute {
                                calibrationActive = false
                                currentCalibrationStep = null
                                passedDirections = emptySet()
                                resetCalibrationTrackingState()
                                sendCalibrationBroadcast(error = reason)
                            }
                        }

                        override fun onError(
                            error: String
                        ) {

                            Log.e(
                                TAG_SERVICE,
                                "MediaPipe error: $error"
                            )
                        }
                    }
            )

        Log.d(
            TAG_SERVICE,
            "FaceLandmarker initialized"
        )
    }

    private fun startCamera() {

        if (cameraStarted) {
            return
        }

        val mainExecutor =
            ContextCompat.getMainExecutor(
                this
            )

        val cameraProviderFuture =
            ProcessCameraProvider
                .getInstance(
                    this
                )

        cameraProviderFuture.addListener({

            try {
                if (stopRequested) {
                    Log.d(TAG_SERVICE, "Camera bind aborted: stop requested")
                    return@addListener
                }

                val provider =
                    cameraProviderFuture.get()

                val cameraSelector =
                    CameraSelector
                        .DEFAULT_FRONT_CAMERA

                if (
                    !provider.hasCamera(
                        cameraSelector
                    )
                ) {

                    Log.e(
                        TAG_SERVICE,
                        "Front camera not available"
                    )

                    stopServiceInternal()

                    return@addListener
                }

                // Exactly one ImageAnalysis use case
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setOutputImageFormat(
                            ImageAnalysis
                                .OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .build()

                analysis.setAnalyzer(
                    analysisExecutor
                ) { imageProxy ->

                    frameCount++

                    if (
                        frameCount % 30 ==
                        0
                    ) {

                        Log.d(
                            TAG_FRAME,
                            "Frame #$frameCount | " +
                                    "${imageProxy.width}x${imageProxy.height} | " +
                                    "rotation=${imageProxy.imageInfo.rotationDegrees}"
                        )
                    }

                    faceLandmarkerHelper
                        .detectLiveStream(
                            imageProxy =
                                imageProxy,
                            isFrontCamera =
                                true
                        )
                }

                // Exactly one Preview use case
                val preview = Preview.Builder().build()
                
                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    analysis,
                    preview
                )

                cameraProvider = provider
                analysisUseCase = analysis
                previewUseCase = preview
                cameraStarted = true

                publishServiceState(this, ServiceState.RUNNING)

                Log.d(
                    TAG_SERVICE,
                    "CameraX bound once with Analysis + Preview"
                )

            } catch (
                exception: Exception
            ) {

                Log.e(
                    TAG_SERVICE,
                    "Unable to start camera",
                    exception
                )

                stopServiceInternal()
            }

        }, mainExecutor)
    }

    private fun createNotificationChannel() {

        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FaceAccess AI camera",
                NotificationManager
                    .IMPORTANCE_LOW
            )

        channel.description =
            "FaceAccess AI đang nhận diện cử chỉ khuôn mặt"

        notificationManager
            .createNotificationChannel(
                channel
            )
    }

    private fun createNotification():
            Notification {

        val openAppIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val openAppPendingIntent =
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent =
            Intent(
                this,
                FaceAccessCameraService::class.java
            ).apply {

                action =
                    ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                REQUEST_STOP_SERVICE,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val isPaused = FaceControlStateManager.isPaused()

        val togglePauseIntent =
            Intent(
                this,
                FaceAccessCameraService::class.java
            ).apply {
                action = ACTION_TOGGLE_PAUSE
            }

        val togglePausePendingIntent =
            PendingIntent.getService(
                this,
                REQUEST_PAUSE_RESUME,
                togglePauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val contentTitle = if (isPaused) getString(R.string.notification_title_paused) else getString(R.string.notification_title_active)
        val contentText = if (isPaused) getString(R.string.notification_text_paused) else getString(R.string.notification_text_active)
        val pauseResumeLabel = if (isPaused) getString(R.string.action_resume) else getString(R.string.action_pause)

        return Notification.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(
                contentTitle
            )
            .setContentText(
                contentText
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setContentIntent(
                openAppPendingIntent
            )
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                pauseResumeLabel,
                togglePausePendingIntent
            )
            .addAction(
                android.R.drawable
                    .ic_menu_close_clear_cancel,
                getString(R.string.action_stop),
                stopPendingIntent
            )
            .setOngoing(
                true
            )
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun sendCalibrationBroadcast(
        step: HeadDirectionalCalibrationSession.Step? = null,
        passed: Set<HeadDirectionalCalibrationSession.Direction>? = null,
        isComplete: Boolean = false,
        isTracking: Boolean? = null,
        status: FaceLandmarkerHelper.CalibrationTrackingStatus? = null,
        error: String? = null
    ) {
        val intent = Intent(ACTION_CALIBRATION_UPDATE).apply {
            setPackage(packageName)
            step?.let { putExtra(EXTRA_CALIBRATION_STEP, it.name) }
            passed?.let { putExtra(EXTRA_CALIBRATION_PASSED, it.map { d -> d.name }.toTypedArray()) }
            putExtra(EXTRA_CALIBRATION_COMPLETE, isComplete)
            // Always include tracking if requested or use current status
            putExtra(EXTRA_CALIBRATION_TRACKING, isTracking ?: trackingAvailable)
            // Include status name
            val statusName = status?.name ?: calibrationTrackingStatus.name
            putExtra(EXTRA_CALIBRATION_STATUS, statusName)
            error?.let { putExtra(EXTRA_CALIBRATION_ERROR, it) }
        }
        sendBroadcast(intent)
    }

    private fun startAsForegroundService() {

        val notification =
            createNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_CAMERA
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        Log.d(
            TAG_SERVICE,
            "Service promoted to foreground"
        )
    }

    private fun stopServiceInternal() {
        stopRequested = true

        if (::faceLandmarkerHelper.isInitialized) {
            faceLandmarkerHelper.stopCalibration()
        }
        
        calibrationActive = false
        currentCalibrationStep = null
        passedDirections = emptySet()
        resetCalibrationTrackingState()

        FaceControlStateManager.resetToActive()
        FaceAccessAccessibilityService.setFaceControlPausedVisual(false)

        cameraStarted = false
        cameraProvider?.unbindAll()
        analysisUseCase = null
        previewUseCase = null

        publishServiceState(this, ServiceState.STOPPED)

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    override fun onDestroy() {
        if (getServiceState() != ServiceState.STOPPED) {
            cameraProvider?.unbindAll()
        }

        cameraStarted =
            false

        cameraProvider =
            null
        analysisUseCase = null
        previewUseCase = null

        if (
            ::faceLandmarkerHelper
                .isInitialized
        ) {

            faceLandmarkerHelper
                .close()
        }

        if (
            ::analysisExecutor
                .isInitialized
        ) {

            analysisExecutor
                .shutdown()
        }

        lifecycleRegistry.currentState =
            Lifecycle.State.DESTROYED

        calibrationActive = false
        currentCalibrationStep = null
        passedDirections = emptySet()
        resetCalibrationTrackingState()

        if (instance === this) {
            instance = null
        }
        
        staticPreviewSurfaceProvider = null

        if (getServiceState() != ServiceState.STOPPED) {
            publishServiceState(this, ServiceState.STOPPED)
        }

        super.onDestroy()
    }

    private fun resetCalibrationTrackingState() {
        trackingAvailable = false
        calibrationTrackingStatus = FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND
    }

    companion object {

        const val ACTION_SERVICE_STATE_CHANGED =
            "com.example.faceaccessai.action.CAMERA_SERVICE_STATE_CHANGED"

        private const val ACTION_STOP =
            "com.example.faceaccessai.action.STOP_CAMERA_SERVICE"

        private const val ACTION_TOGGLE_PAUSE =
            "com.example.faceaccessai.action.TOGGLE_PAUSE_RESUME"

        const val ACTION_UPDATE_SENSITIVITY =
            "com.example.faceaccessai.action.UPDATE_SENSITIVITY"

        const val ACTION_START_CALIBRATION =
            "com.example.faceaccessai.action.START_CALIBRATION"

        const val ACTION_STOP_CALIBRATION =
            "com.example.faceaccessai.action.STOP_CALIBRATION"

        const val ACTION_CALIBRATION_UPDATE =
            "com.example.faceaccessai.action.CALIBRATION_UPDATE"

        const val EXTRA_CALIBRATION_STEP = "calibration_step"
        const val EXTRA_CALIBRATION_PASSED = "calibration_passed"
        const val EXTRA_CALIBRATION_COMPLETE = "calibration_complete"
        const val EXTRA_CALIBRATION_TRACKING = "calibration_tracking"
        const val EXTRA_CALIBRATION_STATUS = "calibration_status"
        const val EXTRA_CALIBRATION_ERROR = "calibration_error"

        private const val TAG_SERVICE =
            "FaceAccessCameraService"

        private const val TAG_FRAME =
            "FaceAccessFrame"

        private const val TAG_FACE_ACTION =
            "FaceAction"

        private const val NOTIFICATION_CHANNEL_ID =
            "face_access_camera"

        private const val NOTIFICATION_ID =
            1001

        private const val REQUEST_OPEN_APP =
            1002

        private const val REQUEST_STOP_SERVICE =
            1003

        private const val REQUEST_PAUSE_RESUME =
            1004

        @Volatile
        private var serviceState =
            ServiceState.STOPPED

        @Volatile
        private var calibrationActive = false

        @Volatile
        private var currentCalibrationStep: HeadDirectionalCalibrationSession.Step? = null

        @Volatile
        private var passedDirections: Set<HeadDirectionalCalibrationSession.Direction> = emptySet()

        private var instance: FaceAccessCameraService? = null

        fun getServiceState():
                ServiceState {

            return serviceState
        }

        fun isCalibrationActive(): Boolean = calibrationActive

        fun getCalibrationStep(): HeadDirectionalCalibrationSession.Step? = currentCalibrationStep

        fun getPassedDirections(): Set<HeadDirectionalCalibrationSession.Direction> = passedDirections
        
        fun isCalibrationTrackingAvailable(): Boolean {
            val service = FaceAccessCameraService.instance
            return service?.trackingAvailable ?: false
        }

        fun getCalibrationTrackingStatus(): FaceLandmarkerHelper.CalibrationTrackingStatus {
            val service = FaceAccessCameraService.instance
            return service?.calibrationTrackingStatus ?: FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND
        }

        fun setPreviewSurfaceProvider(context: Context, provider: androidx.camera.core.Preview.SurfaceProvider?) {
            staticPreviewSurfaceProvider = provider
            val intent = Intent(context, FaceAccessCameraService::class.java).apply {
                action = ACTION_SET_PREVIEW
            }
            context.startService(intent)
        }

        @Volatile
        private var staticPreviewSurfaceProvider: androidx.camera.core.Preview.SurfaceProvider? = null

        private const val ACTION_SET_PREVIEW = "com.example.faceaccessai.action.SET_PREVIEW"

        private fun publishServiceState(context: Context, newState: ServiceState) {
            if (serviceState == newState) return
            
            serviceState = newState
            val intent = Intent(ACTION_SERVICE_STATE_CHANGED).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        fun isServiceRunning():
                Boolean {

            return serviceState !=
                    ServiceState.STOPPED
        }

        @Synchronized
        fun start(
            context: Context
        ): Boolean {

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                Log.w(
                    TAG_SERVICE,
                    "Camera permission not granted"
                )

                return false
            }

            if (serviceState != ServiceState.STOPPED) {
                return true
            }

            // Enter STARTING state immediately before calling startForegroundService
            publishServiceState(context, ServiceState.STARTING)

            return try {

                val intent =
                    Intent(
                        context,
                        FaceAccessCameraService::class.java
                    )

                ContextCompat
                    .startForegroundService(
                        context,
                        intent
                    )

                true

            } catch (
                exception: Exception
            ) {
                publishServiceState(context, ServiceState.STOPPED)
                Log.e(
                    TAG_SERVICE,
                    "Unable to start service",
                    exception
                )
                false
            }
        }

        @Synchronized
        fun stop(
            context: Context
        ) {
            if (serviceState == ServiceState.STOPPED) {
                return
            }

            val intent = Intent(context, FaceAccessCameraService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
