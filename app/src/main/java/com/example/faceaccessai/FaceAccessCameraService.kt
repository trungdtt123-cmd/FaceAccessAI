package com.example.faceaccessai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

        serviceRunning = true

        lifecycleRegistry.currentState =
            Lifecycle.State.CREATED

        analysisExecutor =
            Executors.newSingleThreadExecutor()

        actionDispatcher =
            FaceActionDispatcher()

        createNotificationChannel()

        initializeFaceLandmarker()

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

        startAsForegroundService()

        lifecycleRegistry.currentState =
            Lifecycle.State.STARTED

        startCamera()

        Log.d(
            TAG_SERVICE,
            "FaceAccess camera service started"
        )

        return START_NOT_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun initializeFaceLandmarker() {

        val mainExecutor =
            ContextCompat.getMainExecutor(this)

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
                                resultBundle.commandSafetyResult

                            if (!safetyResult.isAllowed) {
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
    }

    private fun startCamera() {

        if (cameraStarted) {
            return
        }

        val mainExecutor =
            ContextCompat.getMainExecutor(this)

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(
                this
            )

        cameraProviderFuture.addListener({

            try {

                val provider =
                    cameraProviderFuture.get()

                val cameraSelector =
                    CameraSelector.DEFAULT_FRONT_CAMERA

                if (
                    !provider.hasCamera(
                        cameraSelector
                    )
                ) {

                    Log.e(
                        TAG_SERVICE,
                        "Front camera not available"
                    )

                    stopSelf()

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

                stopSelf()
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
                NotificationManager.IMPORTANCE_LOW
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

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return Notification.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(
                "FaceAccess AI đang hoạt động"
            )
            .setContentText(
                "Camera đang nhận diện cử chỉ khuôn mặt"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setContentIntent(
                pendingIntent
            )
            .setOngoing(
                true
            )
            .setCategory(
                Notification.CATEGORY_SERVICE
            )
            .build()
    }

    private fun startAsForegroundService() {

        val notification =
            createNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
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

        serviceRunning =
            false

        Log.d(
            TAG_SERVICE,
            "FaceAccess camera service destroyed"
        )

        super.onDestroy()
    }

    companion object {

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

        @Volatile
        private var serviceRunning =
            false

        fun isServiceRunning():
                Boolean {

            return serviceRunning
        }

        fun start(
            context: Context
        ): Boolean {

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