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
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope

import com.example.faceaccessai.ui.theme.FaceAccessAITheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity :
    ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var accessibilityRunning by mutableStateOf(false)
    private var cameraServiceState by mutableStateOf(FaceAccessCameraService.ServiceState.STOPPED)
    private var currentSensitivity by mutableStateOf(GestureSensitivity.BALANCED)
    private var isPersonalized by mutableStateOf(false)
    private var calibrationStep by mutableStateOf<HeadDirectionalCalibrationSession.Step?>(null)
    private var passedDirections by mutableStateOf<Set<HeadDirectionalCalibrationSession.Direction>>(emptySet())
    private var calibrationStatus by mutableStateOf(FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND)
    private var overlayEnabled by mutableStateOf(true)
    private var currentFaceControlMode by mutableStateOf(FaceControlMode.NAVIGATION)

    private var serviceReceiverRegistered = false

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshRuntimeState()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshRuntimeState()
        }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FaceAccessCameraService.ACTION_SERVICE_STATE_CHANGED -> {
                    cameraServiceState = FaceAccessCameraService.getServiceState()
                }
                FaceAccessCameraService.ACTION_CALIBRATION_UPDATE -> {
                    val stepName = intent.getStringExtra(FaceAccessCameraService.EXTRA_CALIBRATION_STEP)
                    val passedNames = intent.getStringArrayExtra(FaceAccessCameraService.EXTRA_CALIBRATION_PASSED)
                    val isComplete = intent.getBooleanExtra(FaceAccessCameraService.EXTRA_CALIBRATION_COMPLETE, false)
                    val statusName = intent.getStringExtra(FaceAccessCameraService.EXTRA_CALIBRATION_STATUS)
                    val error = intent.getStringExtra(FaceAccessCameraService.EXTRA_CALIBRATION_ERROR)

                    if (error != null) {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
                    }

                    statusName?.let {
                        calibrationStatus = runCatching { 
                            FaceLandmarkerHelper.CalibrationTrackingStatus.valueOf(it) 
                        }.getOrDefault(FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND)
                    }

                    if (isComplete) {
                        calibrationStep = HeadDirectionalCalibrationSession.Step.COMPLETE
                        passedDirections = passedNames?.mapNotNull { 
                            runCatching { HeadDirectionalCalibrationSession.Direction.valueOf(it) }.getOrNull()
                        }?.toSet() ?: setOf(
                            HeadDirectionalCalibrationSession.Direction.LEFT,
                            HeadDirectionalCalibrationSession.Direction.RIGHT,
                            HeadDirectionalCalibrationSession.Direction.UP,
                            HeadDirectionalCalibrationSession.Direction.DOWN
                        )
                        
                        lifecycleScope.launch {
                            delay(600)
                            calibrationStep = null
                            passedDirections = emptySet()
                            refreshRuntimeState()
                        }
                    } else {
                        val newPassed = passedNames?.mapNotNull { 
                            runCatching { HeadDirectionalCalibrationSession.Direction.valueOf(it) }.getOrNull()
                        }?.toSet() ?: emptySet()
                        
                        if (passedNames != null) {
                            passedDirections = newPassed
                        }

                        val parsedStep = stepName?.let {
                            runCatching { HeadDirectionalCalibrationSession.Step.valueOf(it) }.getOrNull()
                        }
                        
                        if (parsedStep != null) {
                            calibrationStep = parsedStep
                        } else if (FaceAccessCameraService.isCalibrationActive()) {
                            calibrationStep = FaceAccessCameraService.getCalibrationStep()
                        } else {
                            calibrationStep = null
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshRuntimeState()

        setContent {
            FaceAccessAITheme {
                FaceAccessScreen(
                    hasCameraPermission = hasCameraPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    accessibilityRunning = accessibilityRunning,
                    serviceState = cameraServiceState,
                    sensitivity = currentSensitivity,
                    isPersonalized = isPersonalized,
                    calibrationStep = calibrationStep,
                    passedDirections = passedDirections,
                    calibrationStatus = calibrationStatus,
                    overlayEnabled = overlayEnabled,
                    currentMode = currentFaceControlMode,
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onStartControl = {
                        val accepted = FaceAccessCameraService.start(this@MainActivity)
                        if (accepted) {
                            cameraServiceState = FaceAccessCameraService.getServiceState()
                        } else {
                            Toast.makeText(this@MainActivity, "Không thể bắt đầu điều khiển.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStopControl = {
                        FaceAccessCameraService.stop(this@MainActivity)
                    },
                    onSensitivityChange = { newSensitivity ->
                        GestureSensitivityManager.getInstance(this@MainActivity).setSensitivity(newSensitivity)
                        currentSensitivity = newSensitivity
                        if (FaceAccessCameraService.isServiceRunning()) {
                            val intent = Intent(this@MainActivity, FaceAccessCameraService::class.java).apply {
                                action = FaceAccessCameraService.ACTION_UPDATE_SENSITIVITY
                            }
                            startService(intent)
                        }
                    },
                    onStartCalibration = {
                        if (cameraServiceState == FaceAccessCameraService.ServiceState.RUNNING) {
                            val intent = Intent(this@MainActivity, FaceAccessCameraService::class.java).apply {
                                action = FaceAccessCameraService.ACTION_START_CALIBRATION
                            }
                            startService(intent)
                        } else {
                            Toast.makeText(this@MainActivity, "Hãy bật điều khiển trước khi hiệu chỉnh", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStopCalibration = {
                        val intent = Intent(this@MainActivity, FaceAccessCameraService::class.java).apply {
                            action = FaceAccessCameraService.ACTION_STOP_CALIBRATION
                        }
                        startService(intent)
                    },
                    onResetCalibration = {
                        HeadDirectionalCalibrationManager.getInstance(this@MainActivity).clearCalibration()
                        refreshRuntimeState()
                        if (FaceAccessCameraService.isServiceRunning()) {
                            val intent = Intent(this@MainActivity, FaceAccessCameraService::class.java).apply {
                                action = FaceAccessCameraService.ACTION_UPDATE_SENSITIVITY
                            }
                            startService(intent)
                        }
                        Toast.makeText(this@MainActivity, "Đã đặt lại hiệu chỉnh", Toast.LENGTH_SHORT).show()
                    },
                    onToggleOverlay = {
                        val newState = !overlayEnabled
                        FaceAccessAccessibilityService.setOverlayEnabled(this@MainActivity, newState)
                        overlayEnabled = newState
                    },
                    onModeChange = { newMode ->
                        FaceControlModeManager.setMode(this@MainActivity, newMode)
                        currentFaceControlMode = newMode
                        val message = when (newMode) {
                            FaceControlMode.NAVIGATION -> "Đã chuyển sang chế độ Điều hướng."
                            FaceControlMode.MEDIA -> "Đã chuyển sang chế độ Media."
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    },
                    onMediaAction = { action ->
                        val manager = MediaControlManager(this@MainActivity)
                        val result = manager.dispatch(action)
                        val message = when (result) {
                            MediaControlManager.MediaControlResult.DISPATCHED -> "Đã gửi lệnh media."
                            MediaControlManager.MediaControlResult.FAILED -> "Không thể gửi lệnh media."
                        }
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!serviceReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(FaceAccessCameraService.ACTION_SERVICE_STATE_CHANGED)
                addAction(FaceAccessCameraService.ACTION_CALIBRATION_UPDATE)
            }
            ContextCompat.registerReceiver(this, serviceStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            serviceReceiverRegistered = true
        }
        refreshRuntimeState()
    }

    override fun onResume() {
        super.onResume()
        refreshRuntimeState()
    }

    override fun onStop() {
        if (serviceReceiverRegistered) {
            unregisterReceiver(serviceStateReceiver)
            serviceReceiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshRuntimeState() {
        hasCameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        accessibilityRunning = FaceAccessAccessibilityService.isServiceRunning()
        cameraServiceState = FaceAccessCameraService.getServiceState()
        currentSensitivity = GestureSensitivityManager.getInstance(this).getSensitivity()
        isPersonalized = HeadDirectionalCalibrationManager.getInstance(this).hasCalibration()
        overlayEnabled = FaceAccessAccessibilityService.isOverlayEnabled(this)
        currentFaceControlMode = FaceControlModeManager.getMode(this)
        
        if (FaceAccessCameraService.isCalibrationActive()) {
            calibrationStep = FaceAccessCameraService.getCalibrationStep()
            passedDirections = FaceAccessCameraService.getPassedDirections()
            calibrationStatus = FaceAccessCameraService.getCalibrationTrackingStatus()
        } else {
            calibrationStep = null
            passedDirections = emptySet()
            calibrationStatus = FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND
        }
    }
}

@Composable
fun FaceAccessScreen(
    hasCameraPermission: Boolean,
    hasNotificationPermission: Boolean,
    accessibilityRunning: Boolean,
    serviceState: FaceAccessCameraService.ServiceState,
    sensitivity: GestureSensitivity,
    isPersonalized: Boolean,
    calibrationStep: HeadDirectionalCalibrationSession.Step?,
    passedDirections: Set<HeadDirectionalCalibrationSession.Direction>,
    calibrationStatus: FaceLandmarkerHelper.CalibrationTrackingStatus,
    overlayEnabled: Boolean,
    currentMode: FaceControlMode,
    onRequestCameraPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartControl: () -> Unit,
    onStopControl: () -> Unit,
    onSensitivityChange: (GestureSensitivity) -> Unit,
    onStartCalibration: () -> Unit,
    onStopCalibration: () -> Unit,
    onResetCalibration: () -> Unit,
    onToggleOverlay: () -> Unit,
    onModeChange: (FaceControlMode) -> Unit,
    onMediaAction: (MediaControlManager.MediaAction) -> Unit
) {
    if (!hasCameraPermission) {
        CameraPermissionContent(onRequestCameraPermission = onRequestCameraPermission)
        return
    }

    if (calibrationStep != null && calibrationStep != HeadDirectionalCalibrationSession.Step.IDLE) {
        CalibrationWizardScreen(
            step = calibrationStep, 
            passedDirections = passedDirections,
            status = calibrationStatus,
            onCancel = onStopCalibration
        )
        return
    }

    FaceAccessControlScreen(
        hasNotificationPermission = hasNotificationPermission,
        accessibilityRunning = accessibilityRunning,
        serviceState = serviceState,
        sensitivity = sensitivity,
        isPersonalized = isPersonalized,
        overlayEnabled = overlayEnabled,
        currentMode = currentMode,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onStartControl = onStartControl,
        onStopControl = onStopControl,
        onSensitivityChange = onSensitivityChange,
        onStartCalibration = onStartCalibration,
        onResetCalibration = onResetCalibration,
        onToggleOverlay = onToggleOverlay,
        onModeChange = onModeChange,
        onMediaAction = onMediaAction
    )
}

@Composable
fun CameraPermissionContent(onRequestCameraPermission: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(text = "FaceAccess AI cần Camera để nhận diện cử chỉ khuôn mặt.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestCameraPermission) {
                Text(text = "Cho phép sử dụng Camera")
            }
        }
    }
}

@Composable
fun FaceAccessControlScreen(
    hasNotificationPermission: Boolean,
    accessibilityRunning: Boolean,
    serviceState: FaceAccessCameraService.ServiceState,
    sensitivity: GestureSensitivity,
    isPersonalized: Boolean,
    overlayEnabled: Boolean,
    currentMode: FaceControlMode,
    onRequestNotificationPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartControl: () -> Unit,
    onStopControl: () -> Unit,
    onSensitivityChange: (GestureSensitivity) -> Unit,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit,
    onToggleOverlay: () -> Unit,
    onModeChange: (FaceControlMode) -> Unit,
    onMediaAction: (MediaControlManager.MediaAction) -> Unit
) {
    val isStopped = serviceState == FaceAccessCameraService.ServiceState.STOPPED
    val canStart = hasNotificationPermission && accessibilityRunning && isStopped

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(text = "FaceAccess AI", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        SensitivitySelector(currentSensitivity = sensitivity, onSensitivityChange = onSensitivityChange)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        FaceControlModeSection(currentMode = currentMode, onModeChange = onModeChange)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        PersonalizationSection(
            isPersonalized = isPersonalized,
            isServiceRunning = !isStopped,
            onStartCalibration = onStartCalibration,
            onResetCalibration = onResetCalibration
        )
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        MediaControlSection(onMediaAction = onMediaAction)
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        OverlaySection(
            accessibilityRunning = accessibilityRunning,
            overlayEnabled = overlayEnabled,
            onToggleOverlay = onToggleOverlay
        )
        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        ServiceStatusSection(
            serviceState = serviceState,
            accessibilityRunning = accessibilityRunning,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings
        )

        if (!hasNotificationPermission) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Hãy cho phép thông báo để luôn thấy trạng thái camera và nút Dừng điều khiển.")
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestNotificationPermission) {
                Text(text = "Cho phép thông báo")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Button(enabled = canStart, onClick = onStartControl, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Bắt đầu điều khiển")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(enabled = !isStopped, onClick = onStopControl, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Dừng điều khiển")
        }
    }
}

@Composable
fun FaceControlModeSection(
    currentMode: FaceControlMode,
    onModeChange: (FaceControlMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "Chế độ cử chỉ", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        FaceControlMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (mode == currentMode),
                        onClick = { onModeChange(mode) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (mode == currentMode), onClick = null)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = when (mode) {
                            FaceControlMode.NAVIGATION -> "Điều hướng"
                            FaceControlMode.MEDIA -> "Media"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = when (mode) {
                            FaceControlMode.NAVIGATION -> "Quay đầu để di chuyển ô điều hướng. Nhắm mắt để xác nhận."
                            FaceControlMode.MEDIA -> {
                                "Trái: Bài trước | Phải: Bài sau\n" +
                                "Nhắm mắt: Phát / Tạm dừng | Há miệng: Quay lại\n" +
                                "Lên/Xuống: Không dùng | HOME: Giữ nguyên"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MediaControlSection(
    onMediaAction: (MediaControlManager.MediaAction) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(text = "Điều khiển media", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Điều khiển trình phát media đang hoạt động trên thiết bị.", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onMediaAction(MediaControlManager.MediaAction.PREVIOUS) },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Bài trước", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = { onMediaAction(MediaControlManager.MediaAction.PLAY_PAUSE) },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Phát / Tạm dừng", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = { onMediaAction(MediaControlManager.MediaAction.NEXT) },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Bài sau", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun OverlaySection(
    accessibilityRunning: Boolean,
    overlayEnabled: Boolean,
    onToggleOverlay: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(text = "Ô điều hướng (Overlay)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = if (overlayEnabled) "Ô điều hướng đang hiển thị." else "Ô điều hướng đang ẩn.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onToggleOverlay, 
            modifier = Modifier.fillMaxWidth(),
            enabled = accessibilityRunning
        ) {
            Text(text = if (overlayEnabled) "Ẩn ô điều hướng" else "Hiện ô điều hướng")
        }
        if (!accessibilityRunning) {
            Text(text = "Bật Accessibility để quản lý ô điều hướng", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun PersonalizationSection(
    isPersonalized: Boolean,
    isServiceRunning: Boolean,
    onStartCalibration: () -> Unit,
    onResetCalibration: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(text = "Cá nhân hóa cử chỉ đầu", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = if (isPersonalized) "Trạng thái: Đã hiệu chỉnh" else "Trạng thái: Chưa hiệu chỉnh")
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartCalibration, modifier = Modifier.weight(1f), enabled = isServiceRunning) {
                Text(text = "Hiệu chỉnh")
            }
            if (isPersonalized) {
                Button(onClick = onResetCalibration, modifier = Modifier.weight(1f)) {
                    Text(text = "Đặt lại")
                }
            }
        }
        if (!isServiceRunning) {
            Text(text = "Bật điều khiển để bắt đầu hiệu chỉnh", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun ServiceStatusSection(
    serviceState: FaceAccessCameraService.ServiceState,
    accessibilityRunning: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(text = "Trạng thái dịch vụ", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = when (serviceState) {
            FaceAccessCameraService.ServiceState.STOPPED -> "Nhận diện khuôn mặt đang dừng."
            FaceAccessCameraService.ServiceState.STARTING -> "Đang khởi động..."
            FaceAccessCameraService.ServiceState.RUNNING -> "Nhận diện khuôn mặt đang hoạt động."
        })
        Text(text = if (accessibilityRunning) "Accessibility đang bật." else "Accessibility đang tắt.")
        
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onOpenAccessibilitySettings) {
            Text(text = "Mở cài đặt Accessibility")
        }
    }
}

@Composable
fun CalibrationWizardScreen(
    step: HeadDirectionalCalibrationSession.Step, 
    passedDirections: Set<HeadDirectionalCalibrationSession.Direction>,
    status: FaceLandmarkerHelper.CalibrationTrackingStatus,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Hiệu chỉnh cử chỉ đầu", 
                style = MaterialTheme.typography.headlineSmall, 
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // Box for oval and arrows
            Box(
                modifier = Modifier.size(320.dp, 420.dp),
                contentAlignment = Alignment.Center
            ) {
                // Central Oval View with Camera Preview
                Box(
                    modifier = Modifier
                        .size(240.dp, 340.dp)
                        .clip(GenericShape { size, _ ->
                            addOval(Rect(0f, 0f, size.width, size.height))
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                FaceAccessCameraService.setPreviewSurfaceProvider(context, this.surfaceProvider)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            FaceAccessCameraService.setPreviewSurfaceProvider(context, null)
                        }
                    )
                    
                    // Direction border feedback on the oval
                    CalibrationOvalBorder(passedDirections = passedDirections, currentStep = step)
                }

                // Arrows around the oval
                DirectionIndicator(
                    direction = HeadDirectionalCalibrationSession.Direction.UP,
                    passed = passedDirections.contains(HeadDirectionalCalibrationSession.Direction.UP),
                    active = step == HeadDirectionalCalibrationSession.Step.UP,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                DirectionIndicator(
                    direction = HeadDirectionalCalibrationSession.Direction.DOWN,
                    passed = passedDirections.contains(HeadDirectionalCalibrationSession.Direction.DOWN),
                    active = step == HeadDirectionalCalibrationSession.Step.DOWN,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
                DirectionIndicator(
                    direction = HeadDirectionalCalibrationSession.Direction.LEFT,
                    passed = passedDirections.contains(HeadDirectionalCalibrationSession.Direction.LEFT),
                    active = step == HeadDirectionalCalibrationSession.Step.LEFT,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                DirectionIndicator(
                    direction = HeadDirectionalCalibrationSession.Direction.RIGHT,
                    passed = passedDirections.contains(HeadDirectionalCalibrationSession.Direction.RIGHT),
                    active = step == HeadDirectionalCalibrationSession.Step.RIGHT,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Instruction Text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val primaryText = when (status) {
                    FaceLandmarkerHelper.CalibrationTrackingStatus.TRACKING_OK -> {
                        when (step) {
                            HeadDirectionalCalibrationSession.Step.WAIT_NEUTRAL -> "Nhìn thẳng vào màn hình"
                            HeadDirectionalCalibrationSession.Step.LEFT -> "Xoay mặt sang TRÁI"
                            HeadDirectionalCalibrationSession.Step.WAIT_CENTER_AFTER_LEFT -> "Đưa mặt về giữa"
                            HeadDirectionalCalibrationSession.Step.RIGHT -> "Xoay mặt sang PHẢI"
                            HeadDirectionalCalibrationSession.Step.WAIT_CENTER_AFTER_RIGHT -> "Đưa mặt về giữa"
                            HeadDirectionalCalibrationSession.Step.UP -> "Ngẩng mặt lên"
                            HeadDirectionalCalibrationSession.Step.WAIT_CENTER_AFTER_UP -> "Đưa mặt về giữa"
                            HeadDirectionalCalibrationSession.Step.DOWN -> "Cúi mặt xuống"
                            HeadDirectionalCalibrationSession.Step.COMPLETE -> "✓ Hiệu chỉnh hoàn tất"
                            else -> ""
                        }
                    }
                    FaceLandmarkerHelper.CalibrationTrackingStatus.FRAME_TOO_CLOSE -> "Đưa điện thoại ra xa một chút"
                    FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND -> "Chưa thấy khuôn mặt"
                    FaceLandmarkerHelper.CalibrationTrackingStatus.POSE_UNAVAILABLE -> "Giữ khuôn mặt rõ trước camera"
                }
                
                val secondaryText = when (status) {
                    FaceLandmarkerHelper.CalibrationTrackingStatus.TRACKING_OK -> null
                    FaceLandmarkerHelper.CalibrationTrackingStatus.FRAME_TOO_CLOSE -> "Giữ toàn bộ khuôn mặt trong khung để tiếp tục"
                    FaceLandmarkerHelper.CalibrationTrackingStatus.FACE_NOT_FOUND -> "Đưa khuôn mặt vào khung để tiếp tục"
                    FaceLandmarkerHelper.CalibrationTrackingStatus.POSE_UNAVAILABLE -> "Giữ yên một chút để tiếp tục"
                }

                Text(
                    text = primaryText,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                
                secondaryText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
            
            if (step != HeadDirectionalCalibrationSession.Step.COMPLETE) {
                Button(onClick = onCancel) {
                    Text(text = "Hủy hiệu chỉnh")
                }
            }
        }
    }
}

@Composable
fun CalibrationOvalBorder(
    passedDirections: Set<HeadDirectionalCalibrationSession.Direction>,
    currentStep: HeadDirectionalCalibrationSession.Step
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 8.dp.toPx()
        val colorPassed = Color.Green
        val colorActive = Color.Cyan
        val colorPending = Color.LightGray.copy(alpha = 0.3f)

        drawOval(
            color = colorPending,
            style = Stroke(width = strokeWidth)
        )

        // UP
        if (passedDirections.contains(HeadDirectionalCalibrationSession.Direction.UP)) {
            drawArc(color = colorPassed, startAngle = 225f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth))
        } else if (currentStep == HeadDirectionalCalibrationSession.Step.UP) {
            drawArc(color = colorActive, startAngle = 225f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth + 2.dp.toPx()))
        }

        // DOWN
        if (passedDirections.contains(HeadDirectionalCalibrationSession.Direction.DOWN)) {
            drawArc(color = colorPassed, startAngle = 45f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth))
        } else if (currentStep == HeadDirectionalCalibrationSession.Step.DOWN) {
            drawArc(color = colorActive, startAngle = 45f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth + 2.dp.toPx()))
        }

        // LEFT
        if (passedDirections.contains(HeadDirectionalCalibrationSession.Direction.LEFT)) {
            drawArc(color = colorPassed, startAngle = 135f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth))
        } else if (currentStep == HeadDirectionalCalibrationSession.Step.LEFT) {
            drawArc(color = colorActive, startAngle = 135f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth + 2.dp.toPx()))
        }

        // RIGHT
        if (passedDirections.contains(HeadDirectionalCalibrationSession.Direction.RIGHT)) {
            drawArc(color = colorPassed, startAngle = 315f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth))
        } else if (currentStep == HeadDirectionalCalibrationSession.Step.RIGHT) {
            drawArc(color = colorActive, startAngle = 315f, sweepAngle = 90f, useCenter = false, style = Stroke(width = strokeWidth + 2.dp.toPx()))
        }
    }
}

@Composable
fun DirectionIndicator(
    direction: HeadDirectionalCalibrationSession.Direction,
    passed: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when {
        passed -> Color.Green
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }

    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val path = Path().apply {
                when(direction) {
                    HeadDirectionalCalibrationSession.Direction.UP -> {
                        moveTo(size.width / 2, 0f)
                        lineTo(0f, size.height)
                        lineTo(size.width, size.height)
                    }
                    HeadDirectionalCalibrationSession.Direction.DOWN -> {
                        moveTo(size.width / 2, size.height)
                        lineTo(0f, 0f)
                        lineTo(size.width, 0f)
                    }
                    HeadDirectionalCalibrationSession.Direction.LEFT -> {
                        moveTo(0f, size.height / 2)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                    }
                    HeadDirectionalCalibrationSession.Direction.RIGHT -> {
                        moveTo(size.width, size.height / 2)
                        lineTo(0f, 0f)
                        lineTo(0f, size.height)
                    }
                }
                close()
            }
            drawPath(path = path, color = color)
            
            if (passed) {
                // Checkmark
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.3f, size.height * 0.5f),
                    end = Offset(size.width * 0.5f, size.height * 0.7f),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.5f, size.height * 0.7f),
                    end = Offset(size.width * 0.8f, size.height * 0.3f),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when(direction) {
                HeadDirectionalCalibrationSession.Direction.UP -> "LÊN"
                HeadDirectionalCalibrationSession.Direction.DOWN -> "XUỐNG"
                HeadDirectionalCalibrationSession.Direction.LEFT -> "TRÁI"
                HeadDirectionalCalibrationSession.Direction.RIGHT -> "PHẢI"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun SensitivitySelector(
    currentSensitivity: GestureSensitivity,
    onSensitivityChange: (GestureSensitivity) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(text = "Độ nhạy cử chỉ", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        GestureSensitivity.entries.forEach { sensitivity ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (sensitivity == currentSensitivity),
                        onClick = { onSensitivityChange(sensitivity) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = (sensitivity == currentSensitivity), onClick = null)
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = when (sensitivity) {
                            GestureSensitivity.SENSITIVE -> "Nhạy"
                            GestureSensitivity.BALANCED -> "Cân bằng"
                            GestureSensitivity.STABLE -> "Ổn định"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = when (sensitivity) {
                            GestureSensitivity.SENSITIVE -> "Cử động đầu nhỏ hơn, dễ kích hoạt hơn."
                            GestureSensitivity.BALANCED -> "Phù hợp với đa số người dùng."
                            GestureSensitivity.STABLE -> "Cần cử động rõ hơn, giảm kích hoạt nhầm."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
