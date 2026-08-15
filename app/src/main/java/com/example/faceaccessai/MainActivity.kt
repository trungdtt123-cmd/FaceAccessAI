package com.example.faceaccessai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Button
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

import com.example.faceaccessai.ui.theme.FaceAccessAITheme

import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            FaceAccessAITheme {

                CameraPermissionScreen(
                    lifecycleOwner = this@MainActivity
                )
            }
        }
    }
}


@Composable
fun CameraPermissionScreen(
    lifecycleOwner: LifecycleOwner
) {

    val context =
        LocalContext.current

    var hasCameraPermission by remember {

        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }


    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.RequestPermission()
        ) { granted ->

            hasCameraPermission =
                granted
        }


    if (hasCameraPermission) {

        CameraPreview(
            lifecycleOwner = lifecycleOwner
        )

    } else {

        Box(
            modifier =
                Modifier.fillMaxSize(),
            contentAlignment =
                Alignment.Center
        ) {

            Column(
                horizontalAlignment =
                    Alignment.CenterHorizontally,
                modifier =
                    Modifier.padding(24.dp)
            ) {

                Text(
                    text =
                        "FaceAccess AI cần Camera để nhận diện cử chỉ khuôn mặt."
                )

                Spacer(
                    modifier =
                        Modifier.height(16.dp)
                )

                Button(
                    onClick = {

                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                ) {

                    Text(
                        text =
                            "Cho phép sử dụng Camera"
                    )
                }
            }
        }
    }
}


@Composable
fun CameraPreview(
    lifecycleOwner: LifecycleOwner
) {

    val context =
        LocalContext.current


    var cameraError by remember {
        mutableStateOf<String?>(null)
    }


    // Thread riêng xử lý frame camera
    val analysisExecutor =
        remember {
            Executors.newSingleThreadExecutor()
        }


    // Main thread dùng cho UI và Accessibility
    val mainExecutor =
        remember(context) {
            ContextCompat.getMainExecutor(
                context
            )
        }


    // Thực thi command sau Safety Gate
    val actionDispatcher =
        remember {
            FaceActionDispatcher()
        }


    // Khởi tạo MediaPipe và nhận kết quả
    val faceLandmarkerHelper =
        remember {

            FaceLandmarkerHelper(
                context =
                    context.applicationContext,

                listener =
                    object :
                        FaceLandmarkerHelper.LandmarkerListener {

                        override fun onResults(
                            resultBundle:
                            FaceLandmarkerHelper.ResultBundle
                        ) {

                            val safetyResult =
                                resultBundle.commandSafetyResult


                            // Chỉ thực thi command được phép
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

                            mainExecutor.execute {

                                cameraError =
                                    error
                            }
                        }
                    }
            )
        }


    // Giải phóng MediaPipe và thread camera
    DisposableEffect(Unit) {

        onDispose {

            faceLandmarkerHelper.close()

            analysisExecutor.shutdown()
        }
    }


    Box(
        modifier =
            Modifier.fillMaxSize()
    ) {

        AndroidView(

            modifier =
                Modifier.fillMaxSize(),

            factory = { ctx ->

                val previewView =
                    PreviewView(ctx)


                // Hiển thị toàn bộ frame và căn giữa
                previewView.scaleType =
                    PreviewView.ScaleType.FIT_CENTER


                val cameraProviderFuture =
                    ProcessCameraProvider.getInstance(
                        ctx
                    )


                cameraProviderFuture.addListener({

                    try {

                        val cameraProvider =
                            cameraProviderFuture.get()


                        // Sử dụng camera trước
                        val cameraSelector =
                            CameraSelector.DEFAULT_FRONT_CAMERA


                        // Kiểm tra camera trước
                        if (
                            !cameraProvider.hasCamera(
                                cameraSelector
                            )
                        ) {

                            cameraError =
                                "Không tìm thấy camera trước trên thiết bị."


                            Log.e(
                                TAG_FACE_ACCESS,
                                "Front camera not available"
                            )


                            return@addListener
                        }


                        // Khởi tạo Preview
                        val preview =
                            Preview.Builder()
                                .build()


                        preview.setSurfaceProvider(
                            previewView.surfaceProvider
                        )


                        // Khởi tạo ImageAnalysis
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


                        var frameCount =
                            0


                        // Gửi frame sang MediaPipe
                        imageAnalysis.setAnalyzer(
                            analysisExecutor
                        ) { imageProxy ->

                            frameCount++


                            if (
                                frameCount % 30 ==
                                0
                            ) {

                                Log.d(
                                    TAG_FACE_FRAME,
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


                        // Xóa CameraX use case cũ
                        cameraProvider.unbindAll()


                        // Bind Preview và ImageAnalysis
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )


                        cameraError =
                            null


                        Log.d(
                            TAG_FACE_ACCESS,
                            "CameraX + MediaPipe pipeline started successfully"
                        )


                    } catch (
                        exception: Exception
                    ) {

                        cameraError =
                            "Không thể khởi động camera."


                        Log.e(
                            TAG_FACE_ACCESS,
                            "Camera failed",
                            exception
                        )
                    }

                }, mainExecutor)


                previewView
            }
        )


        // Hiển thị lỗi camera
        if (cameraError != null) {

            Box(
                modifier =
                    Modifier.fillMaxSize(),
                contentAlignment =
                    Alignment.Center
            ) {

                Text(
                    text =
                        cameraError ?: ""
                )
            }
        }
    }
}


private const val TAG_FACE_ACCESS =
    "FaceAccessAI"


private const val TAG_FACE_FRAME =
    "FaceAccessFrame"


private const val TAG_FACE_ACTION =
    "FaceAction"