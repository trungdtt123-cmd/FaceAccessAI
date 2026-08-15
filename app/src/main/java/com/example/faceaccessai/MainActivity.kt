package com.example.faceaccessai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

import androidx.compose.material3.Button
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat

import com.example.faceaccessai.ui.theme.FaceAccessAITheme

class MainActivity :
    ComponentActivity() {

    private var hasCameraPermission by
    mutableStateOf(false)

    private var hasNotificationPermission by
    mutableStateOf(false)

    private var accessibilityRunning by
    mutableStateOf(false)

    private var cameraServiceState by
    mutableStateOf(
        FaceAccessCameraService
            .ServiceState.STOPPED
    )

    private var serviceReceiverRegistered =
        false

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestPermission()
        ) {

            refreshRuntimeState()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestPermission()
        ) {

            refreshRuntimeState()
        }

    private val serviceStateReceiver =
        object :
            BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (
                    intent?.action ==
                    FaceAccessCameraService
                        .ACTION_SERVICE_STATE_CHANGED
                ) {

                    cameraServiceState =
                        FaceAccessCameraService
                            .getServiceState()
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        refreshRuntimeState()

        setContent {

            FaceAccessAITheme {

                FaceAccessScreen(
                    hasCameraPermission =
                        hasCameraPermission,
                    hasNotificationPermission =
                        hasNotificationPermission,
                    accessibilityRunning =
                        accessibilityRunning,
                    serviceState =
                        cameraServiceState,

                    onRequestCameraPermission = {

                        cameraPermissionLauncher
                            .launch(
                                Manifest.permission.CAMERA
                            )
                    },

                    onRequestNotificationPermission = {

                        if (
                            Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.TIRAMISU
                        ) {

                            notificationPermissionLauncher
                                .launch(
                                    Manifest.permission
                                        .POST_NOTIFICATIONS
                                )
                        }
                    },

                    onOpenAccessibilitySettings = {

                        val intent =
                            Intent(
                                Settings
                                    .ACTION_ACCESSIBILITY_SETTINGS
                            )

                        startActivity(
                            intent
                        )
                    },

                    onStartControl = {

                        val started =
                            FaceAccessCameraService
                                .start(
                                    this@MainActivity
                                )

                        cameraServiceState =
                            if (started) {

                                FaceAccessCameraService
                                    .getServiceState()

                            } else {

                                FaceAccessCameraService
                                    .ServiceState.STOPPED
                            }
                    },

                    onStopControl = {

                        FaceAccessCameraService
                            .stop(
                                this@MainActivity
                            )

                        cameraServiceState =
                            FaceAccessCameraService
                                .ServiceState.STOPPED
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (
            !serviceReceiverRegistered
        ) {

            val filter =
                IntentFilter(
                    FaceAccessCameraService
                        .ACTION_SERVICE_STATE_CHANGED
                )

            ContextCompat.registerReceiver(
                this,
                serviceStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            serviceReceiverRegistered =
                true
        }

        refreshRuntimeState()
    }

    override fun onResume() {
        super.onResume()

        refreshRuntimeState()
    }

    override fun onStop() {

        if (
            serviceReceiverRegistered
        ) {

            unregisterReceiver(
                serviceStateReceiver
            )

            serviceReceiverRegistered =
                false
        }

        super.onStop()
    }

    private fun refreshRuntimeState() {

        hasCameraPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        hasNotificationPermission =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

            } else {

                true
            }

        accessibilityRunning =
            FaceAccessAccessibilityService
                .isServiceRunning()

        cameraServiceState =
            FaceAccessCameraService
                .getServiceState()
    }
}

@Composable
fun FaceAccessScreen(
    hasCameraPermission: Boolean,
    hasNotificationPermission: Boolean,
    accessibilityRunning: Boolean,
    serviceState:
    FaceAccessCameraService.ServiceState,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartControl: () -> Unit,
    onStopControl: () -> Unit
) {

    if (
        !hasCameraPermission
    ) {

        CameraPermissionContent(
            onRequestCameraPermission =
                onRequestCameraPermission
        )

        return
    }

    FaceAccessControlScreen(
        hasNotificationPermission =
            hasNotificationPermission,
        accessibilityRunning =
            accessibilityRunning,
        serviceState =
            serviceState,
        onRequestNotificationPermission =
            onRequestNotificationPermission,
        onOpenAccessibilitySettings =
            onOpenAccessibilitySettings,
        onStartControl =
            onStartControl,
        onStopControl =
            onStopControl
    )
}

@Composable
fun CameraPermissionContent(
    onRequestCameraPermission: () -> Unit
) {

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
                onClick =
                    onRequestCameraPermission
            ) {

                Text(
                    text =
                        "Cho phép sử dụng Camera"
                )
            }
        }
    }
}

@Composable
fun FaceAccessControlScreen(
    hasNotificationPermission: Boolean,
    accessibilityRunning: Boolean,
    serviceState:
    FaceAccessCameraService.ServiceState,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartControl: () -> Unit,
    onStopControl: () -> Unit
) {

    val serviceRunning =
        serviceState !=
                FaceAccessCameraService
                    .ServiceState.STOPPED

    val canStart =
        hasNotificationPermission &&
                accessibilityRunning &&
                serviceState ==
                FaceAccessCameraService
                    .ServiceState.STOPPED

    Box(
        modifier =
            Modifier.fillMaxSize(),
        contentAlignment =
            Alignment.Center
    ) {

        Column(
            horizontalAlignment =
                Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.Center,
            modifier =
                Modifier.padding(24.dp)
        ) {

            Text(
                text =
                    "FaceAccess AI"
            )

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Text(
                text =
                    when (
                        serviceState
                    ) {

                        FaceAccessCameraService
                            .ServiceState.STOPPED -> {

                            "Nhận diện khuôn mặt đang dừng."
                        }

                        FaceAccessCameraService
                            .ServiceState.STARTING -> {

                            "Đang khởi động nhận diện khuôn mặt..."
                        }

                        FaceAccessCameraService
                            .ServiceState.RUNNING -> {

                            "Nhận diện khuôn mặt đang hoạt động."
                        }
                    }
            )

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )

            Text(
                text =
                    if (
                        accessibilityRunning
                    ) {

                        "Accessibility đang bật."

                    } else {

                        "Accessibility đang tắt."
                    }
            )

            if (
                !accessibilityRunning
            ) {

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick =
                        onOpenAccessibilitySettings
                ) {

                    Text(
                        text =
                            "Mở cài đặt Accessibility"
                    )
                }
            }

            if (
                !hasNotificationPermission
            ) {

                Spacer(
                    modifier =
                        Modifier.height(16.dp)
                )

                Text(
                    text =
                        "Hãy cho phép thông báo để luôn thấy trạng thái camera và nút Dừng điều khiển."
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick =
                        onRequestNotificationPermission
                ) {

                    Text(
                        text =
                            "Cho phép thông báo"
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Button(
                enabled =
                    canStart,
                onClick =
                    onStartControl
            ) {

                Text(
                    text =
                        "Bắt đầu điều khiển"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )

            Button(
                enabled =
                    serviceRunning,
                onClick =
                    onStopControl
            ) {

                Text(
                    text =
                        "Dừng điều khiển"
                )
            }

            if (
                accessibilityRunning
            ) {

                Spacer(
                    modifier =
                        Modifier.height(24.dp)
                )

                Button(
                    onClick =
                        onOpenAccessibilitySettings
                ) {

                    Text(
                        text =
                            "Mở cài đặt Accessibility"
                    )
                }
            }
        }
    }
}