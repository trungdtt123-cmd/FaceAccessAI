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

    val context = LocalContext.current

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
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->

            hasCameraPermission = granted

        }


    if (hasCameraPermission) {

        CameraPreview(
            lifecycleOwner = lifecycleOwner
        )

    } else {

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {

                Text(
                    text = "FaceAccess AI cần Camera để nhận diện cử chỉ khuôn mặt."
                )

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Button(
                    onClick = {

                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )

                    }
                ) {

                    Text(
                        text = "Cho phép sử dụng Camera"
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

    val context = LocalContext.current


    var cameraError by remember {
        mutableStateOf<String?>(null)
    }


    /*
     * Thread riêng xử lý frame camera.
     */
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor()
    }


    /*
     * Khởi tạo MediaPipe Face Landmarker.
     *
     * Helper sẽ tự load:
     *
     * assets/face_landmarker.task
     */
    val faceLandmarkerHelper = remember {

        FaceLandmarkerHelper(
            context = context.applicationContext
        )

    }


    /*
     * Khi màn hình Camera bị đóng:
     *
     * - đóng MediaPipe
     * - đóng thread ImageAnalysis
     */
    DisposableEffect(Unit) {

        onDispose {

            faceLandmarkerHelper.close()

            analysisExecutor.shutdown()

        }
    }


    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        AndroidView(

            modifier = Modifier.fillMaxSize(),

            factory = { ctx ->

                /*
                 * =================================================
                 * CAMERA PREVIEW
                 * =================================================
                 */
                val previewView =
                    PreviewView(ctx)


                // Hiển thị toàn bộ frame và căn giữa
                previewView.scaleType =
                    PreviewView.ScaleType.FIT_CENTER


                /*
                 * Lấy CameraProvider.
                 */
                val cameraProviderFuture =
                    ProcessCameraProvider.getInstance(ctx)


                cameraProviderFuture.addListener({

                    try {

                        val cameraProvider =
                            cameraProviderFuture.get()


                        /*
                         * FaceAccess AI sử dụng camera trước.
                         */
                        val cameraSelector =
                            CameraSelector.DEFAULT_FRONT_CAMERA


                        /*
                         * Kiểm tra thiết bị có camera trước.
                         */
                        if (!cameraProvider.hasCamera(cameraSelector)) {

                            cameraError =
                                "Không tìm thấy camera trước trên thiết bị."


                            Log.e(
                                "FaceAccessAI",
                                "Front camera not available"
                            )


                            return@addListener

                        }


                        /*
                         * =================================================
                         * 1. PREVIEW
                         * =================================================
                         */
                        val preview =
                            Preview.Builder()
                                .build()


                        preview.setSurfaceProvider(
                            previewView.surfaceProvider
                        )


                        /*
                         * =================================================
                         * 2. IMAGE ANALYSIS
                         * =================================================
                         *
                         * Frame CameraX sẽ được đưa vào MediaPipe.
                         */
                        val imageAnalysis =
                            ImageAnalysis.Builder()

                                /*
                                 * Không để frame cũ xếp hàng.
                                 *
                                 * Nếu AI xử lý chậm:
                                 * chỉ giữ frame mới nhất.
                                 */
                                .setBackpressureStrategy(
                                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                )

                                /*
                                 * QUAN TRỌNG:
                                 *
                                 * FaceLandmarkerHelper đang chuyển
                                 * ImageProxy → Bitmap ARGB_8888.
                                 *
                                 * Vì vậy CameraX phải xuất RGBA_8888.
                                 */
                                .setOutputImageFormat(
                                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                )

                                .build()


                        /*
                         * Chỉ dùng để debug số frame.
                         */
                        var frameCount = 0


                        /*
                         * =================================================
                         * 3. ANALYZER
                         * =================================================
                         */
                        imageAnalysis.setAnalyzer(
                            analysisExecutor
                        ) { imageProxy ->

                            /*
                             * Tăng số frame.
                             */
                            frameCount++


                            /*
                             * Debug khoảng mỗi 30 frame.
                             */
                            if (frameCount % 30 == 0) {

                                Log.d(
                                    "FaceAccessFrame",
                                    "Frame #$frameCount | " +
                                            "${imageProxy.width}x${imageProxy.height} | " +
                                            "rotation=${imageProxy.imageInfo.rotationDegrees}"
                                )

                            }


                            /*
                             * =================================================
                             * GỬI FRAME SANG MEDIAPIPE
                             * =================================================
                             *
                             * KHÔNG gọi imageProxy.close() ở đây.
                             *
                             * FaceLandmarkerHelper sẽ chịu trách nhiệm
                             * đóng ImageProxy sau khi copy dữ liệu.
                             */
                            faceLandmarkerHelper.detectLiveStream(
                                imageProxy = imageProxy,
                                isFrontCamera = true
                            )

                        }


                        /*
                         * Xóa các CameraX use case cũ.
                         */
                        cameraProvider.unbindAll()


                        /*
                         * =================================================
                         * 4. BIND CAMERA
                         * =================================================
                         *
                         * Camera có hai nhiệm vụ:
                         *
                         * Preview
                         *      → hiển thị webcam
                         *
                         * ImageAnalysis
                         *      → MediaPipe Face Landmarker
                         */
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageAnalysis
                        )


                        cameraError = null


                        Log.d(
                            "FaceAccessAI",
                            "CameraX + MediaPipe pipeline started successfully"
                        )


                    } catch (exception: Exception) {

                        cameraError =
                            "Không thể khởi động camera."


                        Log.e(
                            "FaceAccessAI",
                            "Camera failed",
                            exception
                        )

                    }

                }, ContextCompat.getMainExecutor(ctx))


                previewView

            }
        )


        /*
         * Hiển thị lỗi Camera nếu có.
         */
        if (cameraError != null) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = cameraError ?: ""
                )

            }
        }
    }
}