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

    private var cameraStarted =
        false

    private var frameCount =
        0

    override fun onCreate() {
        super.onCreate()

        setServiceState(
            ServiceState.STARTING
        )

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
            Log.d(TAG_SERVICE, "Stop requested from notification")
            stopServiceInternal()
            return START_NOT_STICKY
        }

        if (action == ACTION_TOGGLE_PAUSE) {
            val isPaused = FaceControlStateManager.togglePaused()
            
            // Cập nhật visual trên overlay thông qua bridge
            FaceAccessAccessibilityService.setFaceControlPausedVisual(isPaused)
            
            // Cập nhật lại notification để đổi nhãn nút bấm
            updateNotification()
            
            return START_NOT_STICKY
        }

        if (action == ACTION_UPDATE_SENSITIVITY) {
            if (::faceLandmarkerHelper.isInitialized) {
                faceLandmarkerHelper.applySensitivityConfig()
            }
            return START_NOT_STICKY
        }

        try {
            // Đưa service lên foreground trước khi khởi tạo MediaPipe
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

        if (
            cameraStarted
        ) {

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

                val imageAnalysis =
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

                imageAnalysis.setAnalyzer(
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

                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageAnalysis
                )

                cameraProvider =
                    provider

                cameraStarted =
                    true

                setServiceState(
                    ServiceState.RUNNING
                )

                Log.d(
                    TAG_SERVICE,
                    "Background CameraX pipeline started"
                )

            } catch (
                exception: Exception
            ) {

                Log.e(
                    TAG_SERVICE,
                    "Unable to start background camera",
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

        FaceControlStateManager.resetToActive()
        FaceAccessAccessibilityService.setFaceControlPausedVisual(false)

        setServiceState(
            ServiceState.STOPPED
        )

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun setServiceState(
        newState: ServiceState
    ) {

        serviceState =
            newState

        val intent =
            Intent(
                ACTION_SERVICE_STATE_CHANGED
            ).apply {

                setPackage(
                    packageName
                )
            }

        sendBroadcast(
            intent
        )
    }

    override fun onDestroy() {

        cameraStarted =
            false

        cameraProvider
            ?.unbindAll()

        cameraProvider =
            null

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

        serviceState =
            ServiceState.STOPPED

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        val intent =
            Intent(
                ACTION_SERVICE_STATE_CHANGED
            ).apply {

                setPackage(
                    packageName
                )
            }

        sendBroadcast(
            intent
        )

        FaceControlStateManager.resetToActive()
        FaceAccessAccessibilityService.setFaceControlPausedVisual(false)

        super.onDestroy()
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

        fun getServiceState():
                ServiceState {

            return serviceState
        }

        fun isServiceRunning():
                Boolean {

            return serviceState !=
                    ServiceState.STOPPED
        }

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

            if (
                serviceState !=
                ServiceState.STOPPED
            ) {

                return true
            }

            serviceState =
                ServiceState.STARTING

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

                serviceState =
                    ServiceState.STOPPED

                Log.e(
                    TAG_SERVICE,
                    "Unable to start service",
                    exception
                )

                false
            }
        }

        fun stop(
            context: Context
        ) {

            serviceState =
                ServiceState.STOPPED

            val intent =
                Intent(
                    context,
                    FaceAccessCameraService::class.java
                )

            context.stopService(
                intent
            )
        }
    }
}