package com.example.faceaccessai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import androidx.core.content.ContextCompat

import com.example.faceaccessai.ui.theme.FaceAccessAITheme

class MainActivity :
    ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        setContent {

            FaceAccessAITheme {

                CameraPermissionScreen()
            }
        }
    }
}

@Composable
fun CameraPermissionScreen() {

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

    if (
        hasCameraPermission
    ) {

        FaceAccessControlScreen()

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
fun FaceAccessControlScreen() {

    val context =
        LocalContext.current

    var isServiceRunning by remember {

        mutableStateOf(
            FaceAccessCameraService
                .isServiceRunning()
        )
    }

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
                    Modifier.height(12.dp)
            )

            Text(
                text =
                    if (isServiceRunning) {
                        "Nhận diện khuôn mặt đang hoạt động."
                    } else {
                        "Nhận diện khuôn mặt đang dừng."
                    }
            )

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Button(
                enabled =
                    !isServiceRunning,
                onClick = {

                    val started =
                        FaceAccessCameraService
                            .start(
                                context
                            )

                    if (started) {

                        isServiceRunning =
                            true
                    }
                }
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
                    isServiceRunning,
                onClick = {

                    FaceAccessCameraService
                        .stop(
                            context
                        )

                    isServiceRunning =
                        false
                }
            ) {

                Text(
                    text =
                        "Dừng điều khiển"
                )
            }

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )

            Button(
                onClick = {

                    val intent =
                        Intent(
                            Settings
                                .ACTION_ACCESSIBILITY_SETTINGS
                        )

                    context.startActivity(
                        intent
                    )
                }
            ) {

                Text(
                    text =
                        "Mở cài đặt Accessibility"
                )
            }
        }
    }
}