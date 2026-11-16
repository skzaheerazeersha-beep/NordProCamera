package com.nordpro.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nordpro.camera.NordProUiState
import com.nordpro.camera.core.camera.CameraFilter
import com.nordpro.camera.core.camera.CameraTransformHelper
import com.nordpro.camera.core.camera.CaptureMode
import com.nordpro.camera.core.camera.FlashMode
import com.nordpro.camera.core.camera.ProControlsState
import com.nordpro.camera.core.camera.RawFormatOption
import com.nordpro.camera.core.probe.LensRole
import kotlinx.coroutines.delay

private val OnePlusRed = Color(0xFFEB0028)
private val AppleRed = Color(0xFFFF3B30)

private fun Modifier.liquidGlass(shape: Shape = CircleShape, highlightActive: Boolean = false): Modifier =
    this
        .shadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = Color.White.copy(alpha = 0.18f),
            spotColor = Color.Black.copy(alpha = 0.35f)
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.24f),
                    Color(0x381C1C22),
                    Color.White.copy(alpha = 0.11f)
                )
            )
        )
        .border(
            width = if (highlightActive) 1.3.dp else 1.0.dp,
            brush = Brush.linearGradient(
                if (highlightActive) {
                    listOf(OnePlusRed.copy(alpha = 0.96f), Color.White.copy(alpha = 0.60f), OnePlusRed.copy(alpha = 0.50f))
                } else {
                    listOf(
                        Color.White.copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.62f),
                        Color.White.copy(alpha = 0.20f)
                    )
                }
            ),
            shape = shape
        )

private val PrimaryBottomModes = listOf(
    CaptureMode.TIMELAPSE,
    CaptureMode.SLO_MO,
    CaptureMode.VIDEO,
    CaptureMode.PHOTO,
    CaptureMode.PORTRAIT,
    CaptureMode.DOC_SCAN
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CameraScreen(
    state: NordProUiState,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onDisplayRotationChanged: (Int) -> Unit,
    onSwitchLens: (LensRole) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onSelectFilter: (CameraFilter) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onCycleFlash: () -> Unit,
    onCycleHdr: () -> Unit,
    onCycleTimer: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onSelectAspectRatio: (com.nordpro.camera.core.camera.AspectRatioOption) -> Unit = {},
    onUpdateFullScreenAspect: (Float) -> Unit = {},
    onDismissLensResult: () -> Unit = {},
    onUpdateProControls: ((ProControlsState) -> ProControlsState) -> Unit,
    onTapToFocus: (Float, Float) -> Unit,
    onShutterPressed: () -> Unit,
    onRestoreDefaults: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var showControlDrawer by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showFilterTray by remember { mutableStateOf(false) }
    var showZoomArcDial by remember { mutableStateOf(false) }
    var showExposureScale by remember { mutableStateOf(false) }
    var showRatioPicker by remember { mutableStateOf(false) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(focusTapOffset, state.proControls.evCompensationIndex) {
        if (focusTapOffset != null) {
            delay(3000L)
            focusTapOffset = null
        }
    }

    var recSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.isRecordingVideo) {
        if (state.isRecordingVideo) {
            recSeconds = 0
            while (true) {
                delay(1000L)
                recSeconds++
            }
        } else {
            recSeconds = 0
        }
    }

    BackHandler(enabled = showControlDrawer || showSettingsSheet || showFilterTray || showZoomArcDial || showExposureScale || showRatioPicker || state.captureMode == CaptureMode.PRO) {
        when {
            showSettingsSheet -> showSettingsSheet = false
            showControlDrawer -> showControlDrawer = false
            showFilterTray -> showFilterTray = false
            showZoomArcDial -> showZoomArcDial = false
            showExposureScale -> showExposureScale = false
            showRatioPicker -> showRatioPicker = false
            state.captureMode == CaptureMode.PRO -> onSelectMode(CaptureMode.PHOTO)
        }
    }

    val displayRotation = remember(configuration.orientation) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
        }
    }
    LaunchedEffect(displayRotation) {
        onDisplayRotationChanged(displayRotation)
    }

    val hasUltraWide = remember(state.hardwareReport) {
        state.hardwareReport?.sensors?.any { it.role == LensRole.ULTRA_WIDE } == true
    }
    val latestPreviewSize by rememberUpdatedState(state.previewSize)
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // True Full-Screen Edge-to-Edge Viewfinder (no black borders!)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        FullScreenViewfinder(
            state = state,
            displayRotation = displayRotation,
            latestPreviewSize = latestPreviewSize,
            focusTapOffset = focusTapOffset,
            onFocusTap = { off, nx, ny ->
                focusTapOffset = off
                onTapToFocus(nx, ny)
            },
            onSurfaceTextureReady = onSurfaceTextureReady,
            onPinchZoom = onPinchZoom,
            onUpdateProControls = onUpdateProControls,
            onUpdateFullScreenAspect = onUpdateFullScreenAspect
        )

        if (isLandscape) {
            // Landscape floating controls over full-screen viewfinder
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .systemBarsPadding()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlashButton(state, onCycleFlash)
                GlassCircleButton(onClick = { showControlDrawer = !showControlDrawer }) {
                    Icon(
                        imageVector = if (showControlDrawer) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Controls",
                        tint = if (showControlDrawer) OnePlusRed else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 112.dp)
                    .liquidGlass(CircleShape)
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ZoomPillsContent(state, hasUltraWide, onSwitchLens, onUpdateProControls)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                ModeCapsuleBar(state.captureMode, onSelectMode, isLandscape = true)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .systemBarsPadding()
                    .fillMaxHeight()
                    .width(92.dp)
                    .padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                GlassCircleButton(
                    sizeDp = 46,
                    onClick = {
                        val next = if (state.activeSensor?.role == LensRole.FRONT) LensRole.MAIN_WIDE else LensRole.FRONT
                        onSwitchLens(next)
                    }
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White, modifier = Modifier.size(21.dp))
                }
                ShutterButton(state, onShutterPressed)
                GalleryThumbnailButton(state, context)
            }
        } else {
            // Portrait floating controls over full-screen viewfinder
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    FlashButton(state, onCycleFlash)
                }
                Box(modifier = Modifier.align(Alignment.Center)) {
                    if (state.isRecordingVideo) {
                        Row(
                            modifier = Modifier
                                .liquidGlass(CircleShape)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(AppleRed))
                            Text(
                                text = String.format("%02d:%02d", recSeconds / 60, recSeconds % 60),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        GlassCircleButton(onClick = { showControlDrawer = !showControlDrawer }) {
                            Icon(
                                imageVector = if (showControlDrawer) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Controls",
                                tint = if (showControlDrawer) OnePlusRed else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.captureMode == CaptureMode.VIDEO) {
                        GlassCircleButton(onClick = { onUpdateProControls { it.copy(micEnabled = !it.micEnabled) } }) {
                            Icon(
                                imageVector = if (state.proControls.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Mic",
                                tint = if (state.proControls.micEnabled) Color.White else AppleRed,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                    GlassPill(
                        text = when {
                            state.captureMode == CaptureMode.PRO && state.activeSensor?.supportsRawCapture == true -> state.proControls.rawFormatOption.label
                            state.captureMode == CaptureMode.VIDEO -> "${if (state.proControls.video4kEnabled) "4K" else "1080"} ${if (state.proControls.video60fpsEnabled) "60" else "30"}"
                            state.captureMode == CaptureMode.SLO_MO -> "${state.proControls.sloMoFps}fps"
                            state.captureMode == CaptureMode.TIMELAPSE -> "${state.proControls.timelapseSpeed.toInt()}x"
                            state.captureMode == CaptureMode.NIGHT -> "NIGHT"
                            state.captureMode == CaptureMode.PRO -> "PRO"
                            state.captureMode == CaptureMode.HI_RES_50MP -> "50MP"
                            state.captureMode == CaptureMode.PANORAMA -> "PANO"
                            else -> state.proControls.aspectRatio.label
                        },
                        active = state.captureMode == CaptureMode.NIGHT ||
                            (state.captureMode == CaptureMode.PRO && state.proControls.rawFormatOption != RawFormatOption.JPEG_ONLY),
                        onClick = { showRatioPicker = !showRatioPicker }
                    )
                    GlassCircleButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }

            // Bottom floating stack: Filter/EV/Pro -> Zoom Pill (equal 38dp circles) -> Shutter -> Mode Capsule
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = showFilterTray,
                    enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .liquidGlass(RoundedCornerShape(22.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CameraFilter.entries.forEach { f ->
                            val active = f == state.proControls.selectedFilter
                            Text(
                                text = f.label,
                                color = if (active) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (active) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable { onSelectFilter(f) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = state.captureMode == CaptureMode.PRO,
                    enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    ProControlStrip(controls = state.proControls, onUpdate = onUpdateProControls)
                }

                // Hide 0.6/1/2/5 pill when full-width Half-Wheel Zoom Dial is open
                AnimatedVisibility(
                    visible = !showZoomArcDial && !showExposureScale,
                    enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                    exit = fadeOut() + scaleOut()
                ) {
                    Row(
                        modifier = Modifier
                            .liquidGlass(CircleShape)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ZoomPillsContent(
                            state = state,
                            hasUltraWide = hasUltraWide,
                            onSwitchLens = onSwitchLens,
                            onUpdateProControls = onUpdateProControls,
                            onLongPressZoom = { showZoomArcDial = !showZoomArcDial }
                        )
                    }
                }

                // Gallery | Exact-Center Shutter | Flip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                ) {
                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                        GalleryThumbnailButton(state, context)
                    }
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        ShutterButton(state, onShutterPressed)
                    }
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        GlassCircleButton(
                            sizeDp = 48,
                            onClick = {
                                val next = if (state.activeSensor?.role == LensRole.FRONT) LensRole.MAIN_WIDE else LensRole.FRONT
                                onSwitchLens(next)
                            }
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }

                // Compact Mode Capsule with ZERO empty space
                ModeCapsuleBar(state.captureMode, onSelectMode)
            }
        }

        // Panorama live guide bar
        if (state.captureMode == CaptureMode.PANORAMA) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .liquidGlass(RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (state.isCapturingPano) "Pan slowly →  Tap shutter to finish" else "Tap shutter to start Panorama",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.panoProgress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(OnePlusRed)
                        )
                    }
                }
            }
        }

        // Tap-outside backdrop to dismiss Control Sheet, Zoom Dial, Exposure Scale, Filter Tray, or Ratio Picker
        if (showControlDrawer || showZoomArcDial || showExposureScale || showFilterTray || showRatioPicker) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            showControlDrawer = false
                            showZoomArcDial = false
                            showExposureScale = false
                            showFilterTray = false
                            showRatioPicker = false
                        }
                    }
            )
        }

        // Top-right Aspect Ratio Picker (FULL | 4:3 | 9:16 | 1:1)
        AnimatedVisibility(
            visible = showRatioPicker,
            enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = 56.dp, end = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .liquidGlass(CircleShape)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state.captureMode) {
                    CaptureMode.VIDEO -> {
                        listOf(
                            Triple("4K 60", true, true),
                            Triple("4K 30", true, false),
                            Triple("1080 60", false, true),
                            Triple("1080 30", false, false)
                        ).forEach { (lbl, is4k, is60) ->
                            val selected = state.proControls.video4kEnabled == is4k && state.proControls.video60fpsEnabled == is60
                            Text(
                                text = lbl,
                                color = if (selected) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable {
                                        onUpdateProControls { it.copy(video4kEnabled = is4k, video60fpsEnabled = is60) }
                                        showRatioPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    CaptureMode.SLO_MO -> {
                        listOf(120, 240).forEach { fps ->
                            val selected = state.proControls.sloMoFps == fps
                            Text(
                                text = "${fps}fps",
                                color = if (selected) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable {
                                        onUpdateProControls { it.copy(sloMoFps = fps) }
                                        showRatioPicker = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    CaptureMode.TIMELAPSE -> {
                        listOf(2.0, 5.0, 10.0, 30.0).forEach { spd ->
                            val selected = state.proControls.timelapseSpeed == spd
                            Text(
                                text = "${spd.toInt()}x",
                                color = if (selected) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable {
                                        onUpdateProControls { it.copy(timelapseSpeed = spd) }
                                        showRatioPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    CaptureMode.PRO -> {
                        RawFormatOption.entries.forEach { rawOpt ->
                            val selected = state.proControls.rawFormatOption == rawOpt
                            Text(
                                text = rawOpt.label,
                                color = if (selected) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable {
                                        onUpdateProControls { it.copy(rawFormatOption = rawOpt) }
                                        showRatioPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    else -> {
                        listOf(
                            com.nordpro.camera.core.camera.AspectRatioOption.FULL,
                            com.nordpro.camera.core.camera.AspectRatioOption.RATIO_4_3,
                            com.nordpro.camera.core.camera.AspectRatioOption.RATIO_16_9,
                            com.nordpro.camera.core.camera.AspectRatioOption.RATIO_1_1
                        ).forEach { ar ->
                            val selected = state.proControls.aspectRatio == ar
                            Text(
                                text = ar.label,
                                color = if (selected) OnePlusRed else Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent)
                                    .clickable {
                                        onSelectAspectRatio(ar)
                                        showRatioPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Google Lens-style Result Card for DOC SCAN (QR / URL / Phone / Text)
        val lensText = state.lensResultText
        if (lensText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .liquidGlass(RoundedCornerShape(22.dp))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lens ${state.lensResultType ?: "SCAN"}",
                            color = OnePlusRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Close",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onDismissLensResult() }
                        )
                    }
                    Text(
                        text = lensText,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 5
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.lensResultType == "URL") {
                            GlassPill("Open Link", active = true) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lensText))) }
                            }
                        } else if (state.lensResultType == "PHONE") {
                            GlassPill("Call", active = true) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$lensText"))) }
                            }
                        } else {
                            GlassPill("Search Google", active = true) {
                                runCatching {
                                    val q = Uri.encode(lensText.take(120))
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$q")))
                                }
                            }
                        }
                        GlassPill("Copy", active = false) {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("CAM2 Lens", lensText))
                            onDismissLensResult()
                        }
                    }
                }
            }
        }

        // Bottom-left corner 'f' Depth-of-Field button in PORTRAIT mode
        if (!isLandscape && state.captureMode == CaptureMode.PORTRAIT && !showExposureScale) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .systemBarsPadding()
                    .padding(start = 20.dp, bottom = 212.dp)
                    .size(40.dp)
                    .liquidGlass(CircleShape, highlightActive = showExposureScale)
                    .clickable { showExposureScale = !showExposureScale },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "f",
                    color = if (showExposureScale) OnePlusRed else Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Interactive Floating Overlays ABOVE the tap-outside backdrop (0mm above shutter)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(bottom = 148.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = showExposureScale,
                enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = fadeOut() + scaleOut()
            ) {
                HorizontalScalePanel(
                    isPortrait = state.captureMode == CaptureMode.PORTRAIT,
                    evIndex = state.proControls.evCompensationIndex,
                    fStop = state.proControls.portraitFStop,
                    onEvChange = { ev -> onUpdateProControls { it.copy(evCompensationIndex = ev) } },
                    onFStopChange = { f -> onUpdateProControls { it.copy(portraitFStop = f) } }
                )
            }

            AnimatedVisibility(
                visible = showZoomArcDial,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                val maxZ = (state.activeSensor?.maxZoomRatio ?: 10f).coerceAtMost(20f)
                HalfWheelZoomDial(
                    currentZoom = if (state.activeSensor?.role == LensRole.ULTRA_WIDE) 0.6f else state.proControls.zoomRatio,
                    maxZoom = maxZ,
                    hasUltraWide = hasUltraWide,
                    onZoomSelected = { zVal ->
                        if (zVal < 0.85f && hasUltraWide) {
                            onUpdateProControls { it.copy(zoomRatio = 1.0f) }
                            if (state.activeSensor?.role != LensRole.ULTRA_WIDE) onSwitchLens(LensRole.ULTRA_WIDE)
                        } else {
                            if (state.activeSensor?.role == LensRole.ULTRA_WIDE) onSwitchLens(LensRole.MAIN_WIDE)
                            onUpdateProControls { it.copy(zoomRatio = zVal.coerceIn(1.0f, maxZ)) }
                        }
                    }
                )
            }
        }

        // Fixed-size White-Tinted Liquid Glass 3x4 Control Sheet
        AnimatedVisibility(
            visible = showControlDrawer,
            enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 60.dp, start = 20.dp, end = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .liquidGlass(RoundedCornerShape(28.dp))
                    .padding(vertical = 20.dp, horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ControlTile(Icons.Default.FlashAuto, "FLASH", state.proControls.flashMode != FlashMode.OFF) { onCycleFlash() }
                    ControlTile(Icons.Default.HdrOn, "HDR", state.proControls.hdrMode.name != "OFF") { onCycleHdr() }
                    ControlTile(Icons.Default.Timer, "TIMER", state.proControls.timerOption.seconds > 0) { onCycleTimer() }
                    ControlTile(Icons.Default.GridOn, "GRID", state.proControls.gridEnabled) {
                        onUpdateProControls { it.copy(gridEnabled = !it.gridEnabled) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ControlTile(Icons.Default.AutoAwesome, "FILTER", state.proControls.selectedFilter != CameraFilter.ORIGINAL) {
                        showFilterTray = !showFilterTray
                        showControlDrawer = false
                    }
                    ControlTile(Icons.Default.AspectRatio, state.proControls.aspectRatio.label, true) { onCycleAspectRatio() }
                    ControlTile(Icons.Default.Highlight, "NIGHT", state.captureMode == CaptureMode.NIGHT) {
                        onSelectMode(CaptureMode.NIGHT)
                        showControlDrawer = false
                    }
                    ControlTile(Icons.Default.Exposure, "PRO", state.captureMode == CaptureMode.PRO) {
                        onSelectMode(CaptureMode.PRO)
                        showControlDrawer = false
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ControlTile(Icons.Default.Exposure, "EXPOSURE", state.proControls.evCompensationIndex != 0 || showExposureScale) {
                        showExposureScale = true
                        showControlDrawer = false
                    }
                    ControlTile(Icons.Default.AutoAwesome, "50MP", state.captureMode == CaptureMode.HI_RES_50MP) {
                        onSelectMode(CaptureMode.HI_RES_50MP)
                        showControlDrawer = false
                    }
                    ControlTile(Icons.Default.PhotoLibrary, "PANO", state.captureMode == CaptureMode.PANORAMA) {
                        onSelectMode(CaptureMode.PANORAMA)
                        showControlDrawer = false
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        FullScreenSettingsPage(
            state = state,
            onUpdate = onUpdateProControls,
            onRestoreDefaults = onRestoreDefaults,
            onBack = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun FullScreenViewfinder(
    state: NordProUiState,
    displayRotation: Int,
    latestPreviewSize: android.util.Size,
    focusTapOffset: Offset?,
    onFocusTap: (Offset, Float, Float) -> Unit,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onUpdateProControls: ((ProControlsState) -> ProControlsState) -> Unit,
    onUpdateFullScreenAspect: (Float) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val isVideo = state.captureMode == CaptureMode.VIDEO ||
        state.captureMode == CaptureMode.TIMELAPSE ||
        state.captureMode == CaptureMode.SLO_MO

    val isCompactRatio = !isVideo && (
        state.proControls.aspectRatio == com.nordpro.camera.core.camera.AspectRatioOption.RATIO_4_3 ||
            state.proControls.aspectRatio == com.nordpro.camera.core.camera.AspectRatioOption.RATIO_1_1
        )
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = if (isCompactRatio && !isLandscape) androidx.compose.ui.BiasAlignment(0f, -0.26f) else Alignment.Center
    ) {
        val screenRatio = (maxWidth / maxHeight).let { if (it > 0f) it else 9f / 20f }
        LaunchedEffect(screenRatio, isLandscape) {
            if (!isLandscape) onUpdateFullScreenAspect(screenRatio)
        }
        val aspectMod = when {
            isVideo -> {
                val r = if (isLandscape) 16f / 9f else 9f / 16f
                if (maxWidth / maxHeight > r) Modifier.fillMaxHeight().aspectRatio(r) else Modifier.fillMaxWidth().aspectRatio(r)
            }
            state.proControls.aspectRatio == com.nordpro.camera.core.camera.AspectRatioOption.FULL -> Modifier.fillMaxSize()
            state.proControls.aspectRatio == com.nordpro.camera.core.camera.AspectRatioOption.RATIO_1_1 -> {
                if (maxWidth > maxHeight) Modifier.fillMaxHeight().aspectRatio(1f) else Modifier.fillMaxWidth().aspectRatio(1f)
            }
            state.proControls.aspectRatio == com.nordpro.camera.core.camera.AspectRatioOption.RATIO_16_9 -> {
                val r = if (isLandscape) 16f / 9f else 9f / 16f
                if (maxWidth / maxHeight > r) Modifier.fillMaxHeight().aspectRatio(r) else Modifier.fillMaxWidth().aspectRatio(r)
            }
            else -> {
                val r = if (isLandscape) 4f / 3f else 3f / 4f
                if (maxWidth / maxHeight > r) Modifier.fillMaxHeight().aspectRatio(r) else Modifier.fillMaxWidth().aspectRatio(r)
            }
        }

    Box(
        modifier = aspectMod
            .animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (offset.y < size.height * 0.72f) {
                        onFocusTap(offset, offset.x / size.width, offset.y / size.height)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) onPinchZoom(zoom)
                }
            }
            .pointerInput(focusTapOffset != null) {
                if (focusTapOffset != null) {
                    detectVerticalDragGestures { _, dragAmount ->
                        val deltaEv = (-dragAmount / 18f).toInt()
                        if (deltaEv != 0) {
                            onUpdateProControls { cur ->
                                cur.copy(evCompensationIndex = (cur.evCompensationIndex + deltaEv).coerceIn(-12, 12))
                            }
                        }
                    }
                }
            }
    ) {
        var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

        LaunchedEffect(state.previewSize, displayRotation, state.proControls.selectedFilter) {
            textureViewRef?.let { tv ->
                val filter = state.proControls.selectedFilter
                if (filter == CameraFilter.ORIGINAL) {
                    tv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                } else {
                    val paint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(filter.toColorMatrix())
                    }
                    tv.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                }
                if (tv.isAvailable) {
                    val rot = tv.display?.rotation ?: displayRotation
                    tv.surfaceTexture?.setDefaultBufferSize(state.previewSize.width, state.previewSize.height)
                    tv.setTransform(
                        CameraTransformHelper.buildPreviewTransformMatrix(
                            tv.width, tv.height, state.previewSize.width, state.previewSize.height, rot
                        )
                    )
                }
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    textureViewRef = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            val rot = display?.rotation ?: Surface.ROTATION_0
                            st.setDefaultBufferSize(latestPreviewSize.width, latestPreviewSize.height)
                            setTransform(
                                CameraTransformHelper.buildPreviewTransformMatrix(
                                    w, h, latestPreviewSize.width, latestPreviewSize.height, rot
                                )
                            )
                            onSurfaceTextureReady(st)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            val rot = display?.rotation ?: Surface.ROTATION_0
                            setTransform(
                                CameraTransformHelper.buildPreviewTransformMatrix(
                                    w, h, latestPreviewSize.width, latestPreviewSize.height, rot
                                )
                            )
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    addOnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                        val nw = r - l
                        val nh = b - t
                        if (nw > 0 && nh > 0 && (nw != oldR - oldL || nh != oldB - oldT)) {
                            val rot = v.display?.rotation ?: Surface.ROTATION_0
                            setTransform(
                                CameraTransformHelper.buildPreviewTransformMatrix(
                                    nw, nh, latestPreviewSize.width, latestPreviewSize.height, rot
                                )
                            )
                        }
                    }
                }
            }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (state.proControls.gridEnabled) {
                val gridColor = Color.White.copy(alpha = 0.20f)
                drawLine(gridColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
                drawLine(gridColor, Offset(2f * size.width / 3f, 0f), Offset(2f * size.width / 3f, size.height), 1f)
                drawLine(gridColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1f)
                drawLine(gridColor, Offset(0f, 2f * size.height / 3f), Offset(size.width, 2f * size.height / 3f), 1f)
            }
            if (state.proControls.levelIndicatorEnabled) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val c = Color.White.copy(alpha = 0.40f)
                drawLine(c, Offset(cx - 16f, cy), Offset(cx + 16f, cy), 1.5f)
                drawLine(c, Offset(cx, cy - 16f), Offset(cx, cy + 16f), 1.5f)
            }
            if (state.proControls.focusPeakingEnabled) {
                val pts = state.telemetry.focusPeakingPoints
                var i = 0
                while (i + 1 < pts.size) {
                    drawCircle(
                        color = OnePlusRed,
                        radius = 2.5f,
                        center = Offset(pts[i] * size.width, pts[i + 1] * size.height)
                    )
                    i += 2
                }
            }
            // Live Face / Eye Tracking Brackets
            state.telemetry.detectedFaces.forEach { face ->
                val l = face.left.coerceIn(0f, 1f) * size.width
                val t = face.top.coerceIn(0f, 1f) * size.height
                val rgt = face.right.coerceIn(0f, 1f) * size.width
                val b = face.bottom.coerceIn(0f, 1f) * size.height
                val len = 26f
                val stroke = 3.5f
                drawLine(OnePlusRed, Offset(l, t), Offset(l + len, t), stroke)
                drawLine(OnePlusRed, Offset(l, t), Offset(l, t + len), stroke)
                drawLine(OnePlusRed, Offset(rgt, t), Offset(rgt - len, t), stroke)
                drawLine(OnePlusRed, Offset(rgt, t), Offset(rgt, t + len), stroke)
                drawLine(OnePlusRed, Offset(l, b), Offset(l + len, b), stroke)
                drawLine(OnePlusRed, Offset(l, b), Offset(l, b - len), stroke)
                drawLine(OnePlusRed, Offset(rgt, b), Offset(rgt - len, b), stroke)
                drawLine(OnePlusRed, Offset(rgt, b), Offset(rgt, b - len), stroke)
            }
            // Live Object Tracking Brackets
            state.telemetry.trackedObject?.let { obj ->
                val l = obj.left.coerceIn(0f, 1f) * size.width
                val t = obj.top.coerceIn(0f, 1f) * size.height
                val rgt = obj.right.coerceIn(0f, 1f) * size.width
                val b = obj.bottom.coerceIn(0f, 1f) * size.height
                val len = 26f
                val stroke = 3.5f
                drawLine(OnePlusRed, Offset(l, t), Offset(l + len, t), stroke)
                drawLine(OnePlusRed, Offset(l, t), Offset(l, t + len), stroke)
                drawLine(OnePlusRed, Offset(rgt, t), Offset(rgt - len, t), stroke)
                drawLine(OnePlusRed, Offset(rgt, t), Offset(rgt, t + len), stroke)
                drawLine(OnePlusRed, Offset(l, b), Offset(l + len, b), stroke)
                drawLine(OnePlusRed, Offset(l, b), Offset(l, b - len), stroke)
                drawLine(OnePlusRed, Offset(rgt, b), Offset(rgt - len, b), stroke)
                drawLine(OnePlusRed, Offset(rgt, b), Offset(rgt, b - len), stroke)
            }
            // Larger Tap-to-Focus Bracket Box + vertical sun EV line (matches 68273.jpg / 68275.jpg)
            focusTapOffset?.let { pos ->
                val r = 86f
                val len = 26f
                val stroke = 3.5f
                drawLine(Color.White, Offset(pos.x - r, pos.y - r), Offset(pos.x - r + len, pos.y - r), stroke)
                drawLine(Color.White, Offset(pos.x - r, pos.y - r), Offset(pos.x - r, pos.y - r + len), stroke)
                drawLine(Color.White, Offset(pos.x + r, pos.y - r), Offset(pos.x + r - len, pos.y - r), stroke)
                drawLine(Color.White, Offset(pos.x + r, pos.y - r), Offset(pos.x + r, pos.y - r + len), stroke)
                drawLine(Color.White, Offset(pos.x - r, pos.y + r), Offset(pos.x - r + len, pos.y + r), stroke)
                drawLine(Color.White, Offset(pos.x - r, pos.y + r), Offset(pos.x - r, pos.y + r - len), stroke)
                drawLine(Color.White, Offset(pos.x + r, pos.y + r), Offset(pos.x + r - len, pos.y + r), stroke)
                drawLine(Color.White, Offset(pos.x + r, pos.y + r), Offset(pos.x + r, pos.y + r - len), stroke)

                // Vertical EV line right beside the focus box
                val lineX = (pos.x + r + 34f).coerceAtMost(size.width - 24f)
                val lineTop = (pos.y - 95f).coerceAtLeast(24f)
                val lineBottom = (pos.y + 95f).coerceAtMost(size.height - 24f)
                drawLine(Color.White.copy(alpha = 0.75f), Offset(lineX, lineTop), Offset(lineX, lineBottom), 2.5f)
                val evNorm = (1f - ((state.proControls.evCompensationIndex + 12f) / 24f)).coerceIn(0f, 1f)
                val sunY = lineTop + evNorm * (lineBottom - lineTop)
                drawCircle(Color.White, radius = 11f, center = Offset(lineX, sunY))
                drawCircle(OnePlusRed, radius = 4f, center = Offset(lineX, sunY))
            }
        }

        if (state.proControls.histogramEnabled) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(top = 56.dp, end = 12.dp)
                    .size(width = 78.dp, height = 32.dp)
                    .liquidGlass(RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                val bins = state.telemetry.lumaHistogram
                val barW = size.width / bins.size.coerceAtLeast(1)
                bins.forEachIndexed { idx, v ->
                    val barH = (v / 100f) * size.height
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(idx * barW, size.height),
                        end = Offset(idx * barW, size.height - barH),
                        strokeWidth = barW
                    )
                }
            }
        }

        if (state.timerCountdownSeconds > 0) {
            Text(
                text = "${state.timerCountdownSeconds}",
                color = Color.White,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
    }

@Composable
private fun ZoomPillsContent(
    state: NordProUiState,
    hasUltraWide: Boolean,
    onSwitchLens: (LensRole) -> Unit,
    onUpdateProControls: ((ProControlsState) -> ProControlsState) -> Unit,
    onLongPressZoom: () -> Unit = {}
) {
    val isUltraWide = state.activeSensor?.role == LensRole.ULTRA_WIDE
    val z = state.proControls.zoomRatio
    if (hasUltraWide) {
        BouncyZoomPill("0.6", isUltraWide, onLongClick = onLongPressZoom) {
            onUpdateProControls { it.copy(zoomRatio = 1.0f) }
            onSwitchLens(LensRole.ULTRA_WIDE)
        }
    }
    val p1Label = if (!isUltraWide && z in 1.05f..1.94f) String.format("%.1f", z) else "1"
    val p2Label = if (!isUltraWide && z in 2.05f..4.94f) String.format("%.1f", z) else "2"
    val p3Label = if (!isUltraWide && z >= 5.05f) String.format("%.1f", z) else "5"

    BouncyZoomPill(p1Label, !isUltraWide && z < 1.95f, onLongClick = onLongPressZoom) {
        if (isUltraWide) onSwitchLens(LensRole.MAIN_WIDE)
        onUpdateProControls { it.copy(zoomRatio = 1.0f) }
    }
    BouncyZoomPill(p2Label, !isUltraWide && z in 1.95f..4.94f, onLongClick = onLongPressZoom) {
        if (isUltraWide) onSwitchLens(LensRole.MAIN_WIDE)
        onUpdateProControls { it.copy(zoomRatio = 2.0f) }
    }
    BouncyZoomPill(p3Label, !isUltraWide && z >= 4.95f, onLongClick = onLongPressZoom) {
        if (isUltraWide) onSwitchLens(LensRole.MAIN_WIDE)
        onUpdateProControls { it.copy(zoomRatio = 5.0f) }
    }
}

@Composable
private fun ModeCapsuleBar(
    currentMode: CaptureMode,
    onSelectMode: (CaptureMode) -> Unit,
    isLandscape: Boolean = false
) {
    val activeIdx = PrimaryBottomModes.indexOf(currentMode).let { if (it >= 0) it else 3 }
    val latestIdx by rememberUpdatedState(activeIdx)
    var dragAccum by remember { mutableFloatStateOf(0f) }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .liquidGlass(RoundedCornerShape(28.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrimaryBottomModes.forEach { mode ->
                val selected = mode == currentMode
                Text(
                    text = mode.label,
                    color = if (selected) OnePlusRed else Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    softWrap = false,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) Color.White.copy(alpha = 0.28f) else Color.Transparent)
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    // Bigger 3-at-a-time slideable Mode Capsule with smooth directional slide transition
    val n = PrimaryBottomModes.size
    var prevIdx by remember { mutableIntStateOf(activeIdx) }
    val dir = remember(activeIdx) {
        val d = if ((activeIdx - prevIdx + n) % n <= n / 2) 1 else -1
        prevIdx = activeIdx
        d
    }

    Box(
        modifier = Modifier
            .width(348.dp)
            .height(54.dp)
            .liquidGlass(RoundedCornerShape(30.dp))
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccum = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccum += dragAmount
                        if (dragAccum > 58f) {
                            dragAccum = 0f
                            val prev = (latestIdx - 1 + n) % n
                            onSelectMode(PrimaryBottomModes[prev])
                        } else if (dragAccum < -58f) {
                            dragAccum = 0f
                            val next = (latestIdx + 1) % n
                            onSelectMode(PrimaryBottomModes[next])
                        }
                    }
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = activeIdx,
            transitionSpec = {
                (slideInHorizontally(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) { w -> dir * (w / 3) } + fadeIn()) togetherWith
                    (slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { w -> -dir * (w / 3) } + fadeOut())
            },
            label = "modeSlide"
        ) { targetIdx ->
            val visibleIndices = listOf((targetIdx - 1 + n) % n, targetIdx, (targetIdx + 1) % n)
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                visibleIndices.forEach { idx ->
                    val mode = PrimaryBottomModes[idx]
                    val isCenter = idx == targetIdx
                    Box(
                        modifier = Modifier
                            .weight(if (isCenter) 1.24f else 0.88f)
                            .fillMaxHeight()
                            .then(
                                if (isCenter) {
                                    Modifier
                                        .shadow(14.dp, CircleShape, ambientColor = OnePlusRed, spotColor = Color.White)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.28f))
                                        .border(1.dp, Color.White.copy(alpha = 0.68f), CircleShape)
                                } else {
                                    Modifier
                                        .clip(CircleShape)
                                        .alpha(0.55f)
                                }
                            )
                            .clickable { onSelectMode(mode) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            color = if (isCenter) OnePlusRed else Color.White,
                            fontSize = if (isCenter) 12.sp else 9.5.sp,
                            fontWeight = if (isCenter) FontWeight.ExtraBold else FontWeight.Medium,
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(state: NordProUiState, onShutterPressed: () -> Unit) {
    val shutterScale by animateFloatAsState(
        targetValue = if (state.isCapturing) 0.82f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "shutterBounce"
    )
    val innerShutterSize by animateDpAsState(
        targetValue = if (state.isRecordingVideo) 28.dp else 60.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutterMorph"
    )
    val innerShutterCorner by animateDpAsState(
        targetValue = if (state.isRecordingVideo) 8.dp else 30.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "shutterCorner"
    )

    Box(
        modifier = Modifier
            .size(74.dp)
            .scale(shutterScale)
            .clip(CircleShape)
            .border(3.5.dp, Color.White, CircleShape)
            .clickable { onShutterPressed() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(innerShutterSize)
                .clip(RoundedCornerShape(innerShutterCorner))
                .background(
                    if (state.isRecordingVideo ||
                        state.captureMode == CaptureMode.VIDEO ||
                        state.captureMode == CaptureMode.SLO_MO ||
                        state.captureMode == CaptureMode.TIMELAPSE
                    ) AppleRed else Color.White
                )
        )
    }
}

@Composable
private fun GalleryThumbnailButton(state: NordProUiState, context: android.content.Context) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .liquidGlass(CircleShape)
            .clickable {
                val uri = state.lastSavedUri
                val intent = if (uri != null) {
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, if (state.lastSavedIsVideo) "video/*" else "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
                runCatching { context.startActivity(intent) }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = state.lastCapturedBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Open Gallery",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FlashButton(state: NordProUiState, onCycleFlash: () -> Unit) {
    GlassCircleButton(onClick = onCycleFlash) {
        val flashIcon = when (state.proControls.flashMode) {
            FlashMode.AUTO -> Icons.Default.FlashAuto
            FlashMode.ON -> Icons.Default.FlashOn
            FlashMode.TORCH -> Icons.Default.Highlight
            FlashMode.OFF -> Icons.Default.FlashOff
        }
        Icon(
            imageVector = flashIcon,
            contentDescription = "Flash",
            tint = if (state.proControls.flashMode != FlashMode.OFF) OnePlusRed else Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ControlTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .liquidGlass(CircleShape, highlightActive = active),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) OnePlusRed else Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GlassCircleButton(
    sizeDp: Int = 36,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .liquidGlass(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun GlassPill(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (active) OnePlusRed else Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        softWrap = false,
        maxLines = 1,
        modifier = Modifier
            .liquidGlass(CircleShape, highlightActive = active)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BouncyZoomPill(
    label: String,
    selected: Boolean,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "zoomBounce"
    )
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    Box(
        modifier = Modifier
            .size(38.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (selected) Color.White.copy(alpha = 0.28f) else Color.Transparent)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val upBeforeTimeout = withTimeoutOrNull(200L) {
                        waitForUpOrCancellation()
                    }
                    if (upBeforeTimeout != null) {
                        latestOnClick()
                    } else {
                        latestOnLongClick()
                        waitForUpOrCancellation()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) OnePlusRed else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            softWrap = false,
            maxLines = 1
        )
    }
}

@Composable
private fun TickRulerCanvas() {
    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp).padding(top = 4.dp)) {
        val ticks = 32
        val step = size.width / ticks
        for (i in 0..ticks) {
            val h = if (i % 4 == 0) size.height else size.height * 0.5f
            drawLine(
                color = Color.White.copy(alpha = if (i % 4 == 0) 0.70f else 0.35f),
                start = Offset(i * step, size.height - h),
                end = Offset(i * step, size.height),
                strokeWidth = if (i % 4 == 0) 2f else 1f
            )
        }
    }
}

@Composable
private fun ProControlStrip(
    controls: ProControlsState,
    onUpdate: ((ProControlsState) -> ProControlsState) -> Unit
) {
    var activeParam by remember { mutableStateOf("ISO") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .liquidGlass(RoundedCornerShape(18.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(
                "ISO" to if (controls.isManualIso) "${controls.iso}" else "AUTO",
                "SS" to if (controls.isManualShutter) "1/${(1_000_000_000L / controls.shutterSpeedNs.coerceAtLeast(1L)).coerceAtLeast(1)}" else "AUTO",
                "WB" to if (controls.isManualWhiteBalance) "${controls.whiteBalanceKelvin}K" else "AUTO",
                "MF" to if (controls.isManualFocus) String.format("%.1fD", controls.focusDistanceDiopters) else "AF-C",
                "EV" to String.format("%+.1f", controls.evCompensationIndex / 6f)
            ).forEach { (key, valStr) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { activeParam = key }
                ) {
                    Text(key, color = if (activeParam == key) OnePlusRed else Color(0xFFAEAEB2), fontSize = 10.sp)
                    Text(valStr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                "AUTO",
                color = OnePlusRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    onUpdate { it.copy(isManualIso = false, isManualShutter = false, isManualFocus = false, isManualWhiteBalance = false, evCompensationIndex = 0) }
                }.padding(4.dp)
            )
        }
        val norm = when (activeParam) {
            "ISO" -> ((controls.iso - 50f) / 6350f).coerceIn(0f, 1f)
            "SS" -> (((1_000_000_000f / controls.shutterSpeedNs.coerceAtLeast(1_000_000L)) - 1f) / 1999f).coerceIn(0f, 1f)
            "WB" -> ((controls.whiteBalanceKelvin - 2500f) / 6000f).coerceIn(0f, 1f)
            "MF" -> (controls.focusDistanceDiopters / 10f).coerceIn(0f, 1f)
            else -> ((controls.evCompensationIndex + 12f) / 24f).coerceIn(0f, 1f)
        }
        SlideableTickRuler(norm = norm) { nextNorm ->
            when (activeParam) {
                "ISO" -> onUpdate { it.copy(isManualIso = true, iso = (50 + nextNorm * 6350).toInt().coerceIn(50, 6400)) }
                "SS" -> {
                    val denom = (1 + nextNorm * 1999).toLong().coerceIn(1L, 2000L)
                    onUpdate { it.copy(isManualShutter = true, shutterSpeedNs = 1_000_000_000L / denom) }
                }
                "WB" -> onUpdate { it.copy(isManualWhiteBalance = true, whiteBalanceKelvin = (2500 + nextNorm * 6000).toInt().coerceIn(2500, 8500)) }
                "MF" -> onUpdate { it.copy(isManualFocus = true, focusDistanceDiopters = (nextNorm * 10f).coerceIn(0f, 10f)) }
                "EV" -> onUpdate { it.copy(evCompensationIndex = ((nextNorm * 24f) - 12f).toInt().coerceIn(-12, 12)) }
            }
        }
    }
}

@Composable
private fun SlideableTickRuler(
    norm: Float,
    onNormChange: (Float) -> Unit
) {
    val latestNorm by rememberUpdatedState(norm)
    val latestCallback by rememberUpdatedState(onNormChange)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val delta = dragAmount / size.width.toFloat().coerceAtLeast(1f)
                    latestCallback((latestNorm + delta).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    latestCallback(frac)
                }
            }
    ) {
        val ticks = 28
        val step = size.width / ticks
        val indicatorX = norm.coerceIn(0f, 1f) * size.width
        for (i in 0..ticks) {
            val x = i * step
            val isMajor = i % 4 == 0
            val tickH = if (isMajor) size.height * 0.56f else size.height * 0.34f
            val topY = (size.height - tickH) / 2f
            drawLine(
                color = Color.White.copy(alpha = if (isMajor) 0.85f else 0.35f),
                start = Offset(x, topY),
                end = Offset(x, topY + tickH),
                strokeWidth = if (isMajor) 2.5f else 1.5f
            )
        }
        drawLine(
            color = OnePlusRed,
            start = Offset(indicatorX, 0f),
            end = Offset(indicatorX, size.height),
            strokeWidth = 4f
        )
    }
}

@Composable
private fun HorizontalScalePanel(
    isPortrait: Boolean,
    evIndex: Int,
    fStop: Float,
    onEvChange: (Int) -> Unit,
    onFStopChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .liquidGlass(RoundedCornerShape(20.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (isPortrait) "Depth of field" else "Exposure",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isPortrait) "Strong" else "-2.0",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (isPortrait) String.format("f %.1f", fStop) else String.format("%+.1f EV", evIndex / 6f),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isPortrait) "Weak" else "+2.0",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
        val normPos = if (isPortrait) {
            ((fStop - 1.4f) / (16f - 1.4f)).coerceIn(0f, 1f)
        } else {
            ((evIndex + 12f) / 24f).coerceIn(0f, 1f)
        }
        SlideableTickRuler(norm = normPos) { nextNorm ->
            if (isPortrait) {
                onFStopChange((1.4f + nextNorm * 14.6f).coerceIn(1.4f, 16f))
            } else {
                onEvChange(((nextNorm * 24f) - 12f).toInt().coerceIn(-12, 12))
            }
        }
    }
}

private val HalfCircleShape = GenericShape { size, _ ->
    moveTo(0f, size.height)
    arcTo(
        rect = Rect(0f, 0f, size.width, size.height * 2f),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
    )
    close()
}

@Composable
private fun HalfWheelZoomDial(
    currentZoom: Float,
    maxZoom: Float,
    hasUltraWide: Boolean,
    onZoomSelected: (Float) -> Unit
) {
    val stops = remember(maxZoom, hasUltraWide) {
        buildList {
            if (hasUltraWide) add(0.6f to "16 mm")
            add(1f to "25 mm")
            add(2f to "50 mm")
            if (maxZoom >= 3f) add(3f to "")
            if (maxZoom >= 5f) add(5f to "")
            if (maxZoom >= 10f) add(10f to "")
            if (maxZoom >= 20f) add(20f to "")
        }
    }
    val minZ = if (hasUltraWide) 0.6f else 1.0f
    val textMeasurer = rememberTextMeasurer()
    val latestZoom by rememberUpdatedState(currentZoom)
    val latestOnZoom by rememberUpdatedState(onZoomSelected)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(195.dp)
            .liquidGlass(HalfCircleShape)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(maxZoom, minZ) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val fracDelta = -dragAmount / (size.width.toFloat() * 0.85f)
                    val logMin = kotlin.math.ln(minZ.toDouble())
                    val logMax = kotlin.math.ln(maxZoom.toDouble())
                    val curLog = kotlin.math.ln(latestZoom.coerceIn(minZ, maxZoom).toDouble())
                    val nextLog = (curLog + fracDelta * (logMax - logMin)).coerceIn(logMin, logMax)
                    latestOnZoom(kotlin.math.exp(nextLog).toFloat())
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val radius = (size.width / 2f - 12f).coerceAtMost(size.height - 12f)
            val cy = size.height - 2f

            val logMin = kotlin.math.ln(minZ.toDouble())
            val logMax = kotlin.math.ln(maxZoom.toDouble())
            val curLog = kotlin.math.ln(currentZoom.coerceIn(minZ, maxZoom).toDouble())
            val curFrac = ((curLog - logMin) / (logMax - logMin).coerceAtLeast(0.01)).toFloat()

            // Fixed center indicator needle at top-center
            drawLine(
                color = OnePlusRed,
                start = Offset(cx, cy - radius + 2f),
                end = Offset(cx, cy - radius + 28f),
                strokeWidth = 5f
            )

            // Rotating tick marks along the true half-circle arc
            val totalTicks = 56
            for (i in 0..totalTicks) {
                val frac = i.toFloat() / totalTicks
                val angleDeg = -90f + (frac - curFrac) * 140f
                if (angleDeg < -172f || angleDeg > -8f) continue
                val rad = Math.toRadians(angleDeg.toDouble())
                val isMajor = i % 7 == 0
                val innerR = radius - (if (isMajor) 24f else 13f)
                val outerR = radius - 3f
                drawLine(
                    color = Color.White.copy(alpha = if (isMajor) 0.85f else 0.35f),
                    start = Offset(cx + (innerR * kotlin.math.cos(rad)).toFloat(), cy + (innerR * kotlin.math.sin(rad)).toFloat()),
                    end = Offset(cx + (outerR * kotlin.math.cos(rad)).toFloat(), cy + (outerR * kotlin.math.sin(rad)).toFloat()),
                    strokeWidth = if (isMajor) 3f else 1.5f
                )
            }

            // Focal length labels along the arc
            stops.forEach { (zVal, mmLabel) ->
                val stopLog = kotlin.math.ln(zVal.toDouble())
                val stopFrac = ((stopLog - logMin) / (logMax - logMin).coerceAtLeast(0.01)).toFloat()
                val angleDeg = -90f + (stopFrac - curFrac) * 140f
                if (angleDeg in -165f..-15f) {
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val labelR = radius - 58f
                    val lx = cx + (labelR * kotlin.math.cos(rad)).toFloat()
                    val ly = cy + (labelR * kotlin.math.sin(rad)).toFloat()
                    val isNear = kotlin.math.abs(currentZoom - zVal) < (zVal * 0.14f).coerceAtLeast(0.18f)
                    val labelStr = if (zVal < 1f) "0.6" else if (isNear) "${zVal.toInt()}x" else "${zVal.toInt()}"
                    val layout = textMeasurer.measure(
                        text = labelStr,
                        style = TextStyle(
                            color = if (isNear) OnePlusRed else Color.White,
                            fontSize = if (isNear) 17.sp else 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(lx - layout.size.width / 2f, ly - layout.size.height / 2f)
                    )
                    if (mmLabel.isNotEmpty()) {
                        val mmLayout = textMeasurer.measure(
                            text = mmLabel,
                            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                        )
                        drawText(
                            textLayoutResult = mmLayout,
                            topLeft = Offset(lx - mmLayout.size.width / 2f, ly + layout.size.height / 2f)
                        )
                    }
                }
            }
        }
        Text(
            text = String.format("%.1fx", currentZoom),
            color = OnePlusRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 14.dp)
        )
    }
}

@Composable
private fun FullScreenSettingsPage(
    state: NordProUiState,
    onUpdate: ((ProControlsState) -> ProControlsState) -> Unit,
    onRestoreDefaults: () -> Unit,
    onBack: () -> Unit
) {
    val c = state.proControls
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard {
                SettingsToggleRow("Watermark", c.watermarkEnabled) { v -> onUpdate { it.copy(watermarkEnabled = v) } }
                SettingsToggleRow("Grid Lines", c.gridEnabled) { v -> onUpdate { it.copy(gridEnabled = v) } }
                SettingsToggleRow("Spirit Level Crosshair", c.levelIndicatorEnabled) { v -> onUpdate { it.copy(levelIndicatorEnabled = v) } }
                SettingsToggleRow("Live Histogram", c.histogramEnabled) { v -> onUpdate { it.copy(histogramEnabled = v) } }
                SettingsToggleRow("Focus Peaking", c.focusPeakingEnabled) { v -> onUpdate { it.copy(focusPeakingEnabled = v) } }
                SettingsToggleRow("Shutter sound", c.shutterSoundEnabled) { v -> onUpdate { it.copy(shutterSoundEnabled = v) } }
                SettingsToggleRow("Mirror selfie", c.mirrorSelfie) { v -> onUpdate { it.copy(mirrorSelfie = v) } }
                SettingsToggleRow("Add location tags", c.locationTagsEnabled) { v -> onUpdate { it.copy(locationTagsEnabled = v) } }
                SettingsActionRow(
                    title = "Video format",
                    value = "${if (c.video4kEnabled) "4K" else "1080p"} • ${if (c.video60fpsEnabled) "60fps" else "30fps"}"
                ) {
                    onUpdate { it.copy(video4kEnabled = !it.video4kEnabled) }
                }
                SettingsToggleRow("Preserve settings", c.preserveSettings) { v -> onUpdate { it.copy(preserveSettings = v) } }
            }

            Text("Photo", color = Color(0xFF8E8E93), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            SettingsCard {
                SettingsActionRow(
                    title = "Touch and hold shutter button",
                    value = if (c.touchHoldRecordVideo) "Record video" else "Burst"
                ) {
                    onUpdate { it.copy(touchHoldRecordVideo = !it.touchHoldRecordVideo) }
                }
                SettingsActionRow(
                    title = "Shooting methods",
                    value = if (c.timerOption.seconds > 0) "Timer ${c.timerOption.label}" else "Tap"
                ) {
                    onUpdate {
                        val next = when (it.timerOption) {
                            com.nordpro.camera.core.camera.TimerOption.OFF -> com.nordpro.camera.core.camera.TimerOption.THREE
                            com.nordpro.camera.core.camera.TimerOption.THREE -> com.nordpro.camera.core.camera.TimerOption.TEN
                            com.nordpro.camera.core.camera.TimerOption.TEN -> com.nordpro.camera.core.camera.TimerOption.OFF
                        }
                        it.copy(timerOption = next)
                    }
                }
                SettingsToggleRow("QR code scanning", c.qrScanningEnabled) { v -> onUpdate { it.copy(qrScanningEnabled = v) } }
                SettingsToggleRow(
                    title = "Portrait distortion correction",
                    subtitle = "Remove edge distortion when taking portraits of people.",
                    checked = c.portraitDistortionCorrection
                ) { v -> onUpdate { it.copy(portraitDistortionCorrection = v) } }
            }

            Text("Handy features", color = Color(0xFF8E8E93), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            SettingsCard {
                SettingsActionRow(
                    title = "Volume button action",
                    value = c.volumeButtonAction.label
                ) {
                    onUpdate {
                        val next = when (it.volumeButtonAction) {
                            com.nordpro.camera.core.camera.VolumeButtonAction.SHUTTER -> com.nordpro.camera.core.camera.VolumeButtonAction.ZOOM
                            com.nordpro.camera.core.camera.VolumeButtonAction.ZOOM -> com.nordpro.camera.core.camera.VolumeButtonAction.VOLUME
                            com.nordpro.camera.core.camera.VolumeButtonAction.VOLUME -> com.nordpro.camera.core.camera.VolumeButtonAction.SHUTTER
                        }
                        it.copy(volumeButtonAction = next)
                    }
                }
                SettingsToggleRow("Quick launch", c.quickLaunchEnabled) { v -> onUpdate { it.copy(quickLaunchEnabled = v) } }
                SettingsToggleRow(
                    title = "Smart suggestions",
                    subtitle = "Get optimisation suggestions in Photo and Video modes.",
                    checked = c.smartSuggestionsEnabled
                ) { v -> onUpdate { it.copy(smartSuggestionsEnabled = v) } }
            }

            SettingsCard {
                SettingsActionRow("About Camera", if (showAbout) "Hide" else "CAM2📷 v3.4") {
                    showAbout = !showAbout
                }
                if (showAbout) {
                    val s = state.activeSensor
                    Text(
                        text = "Model: ${state.hardwareReport?.model ?: "Android"}\nSensor ID ${s?.cameraId} (${s?.role}) • ${s?.hardwareLevelLabel}\nRAW DNG: ${s?.supportsRawCapture} • OIS: ${s?.supportsOis}",
                        color = Color(0xFFAEAEB2),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C1C1E))
                    .clickable { onRestoreDefaults() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text("Restore defaults", color = AppleRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1E))
            .padding(vertical = 4.dp),
        content = content
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = Color(0xFF8E8E93), fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = OnePlusRed)
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(value, color = Color(0xFF8E8E93), fontSize = 13.sp)
    }
}
