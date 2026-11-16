package com.nordpro.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nordpro.camera.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private val vm: NordProViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color.Black,
                    surface = Color(0xFF1C1C1E),
                    primary = Color(0xFFEB0028),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Cam2Root(vm)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (vm.onVolumeButtonPressed(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun Cam2Root(vm: NordProViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.uiState.collectAsState()

    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermissions = grants[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.onPause()
                Lifecycle.Event.ON_RESUME -> if (hasPermissions) vm.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermissions) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = {
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }) {
                Text("Grant CAM2📷 Permissions")
            }
        }
        return
    }

    CameraScreen(
        state = state,
        onSurfaceTextureReady = vm::attachPreviewTexture,
        onDisplayRotationChanged = vm::updateDisplayRotation,
        onSwitchLens = vm::switchLens,
        onSelectMode = vm::selectMode,
        onSelectFilter = vm::selectFilter,
        onPinchZoom = vm::onPinchZoom,
        onCycleFlash = vm::cycleFlash,
        onCycleHdr = vm::cycleHdr,
        onCycleTimer = vm::cycleTimer,
        onCycleAspectRatio = vm::cycleAspectRatio,
        onSelectAspectRatio = vm::selectAspectRatio,
        onUpdateFullScreenAspect = vm::updateFullScreenAspect,
        onDismissLensResult = vm::dismissLensResult,
        onUpdateProControls = vm::updateProControls,
        onTapToFocus = vm::tapToFocus,
        onShutterPressed = vm::onShutterPressed,
        onRestoreDefaults = vm::restoreDefaultSettings
    )
}
