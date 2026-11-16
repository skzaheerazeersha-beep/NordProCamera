package com.nordpro.camera

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaActionSound
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nordpro.camera.core.camera.AspectRatioOption
import com.nordpro.camera.core.camera.Camera2SessionController
import com.nordpro.camera.core.camera.CameraFilter
import com.nordpro.camera.core.camera.CameraTransformHelper
import com.nordpro.camera.core.camera.CaptureMode
import com.nordpro.camera.core.camera.FlashMode
import com.nordpro.camera.core.camera.HdrMode
import com.nordpro.camera.core.camera.ProControlsState
import com.nordpro.camera.core.camera.RawFormatOption
import com.nordpro.camera.core.camera.TimerOption
import com.nordpro.camera.core.camera.ViewfinderTelemetry
import com.nordpro.camera.core.probe.DeviceHardwareReport
import com.nordpro.camera.core.probe.LensRole
import com.nordpro.camera.core.probe.Nord6HardwareAnalyzer
import com.nordpro.camera.core.probe.SensorCapabilityProfile
import com.nordpro.camera.core.raw.DngRawCaptureEngine
import com.nordpro.camera.core.video.ProVideoRecorder
import com.nordpro.camera.core.video.VideoRecordingConfig
import com.nordpro.camera.core.video.VideoResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NordProUiState(
    val hardwareReport: DeviceHardwareReport? = null,
    val activeSensor: SensorCapabilityProfile? = null,
    val captureMode: CaptureMode = CaptureMode.PHOTO,
    val proControls: ProControlsState = ProControlsState(),
    val previewSize: Size = Size(1440, 1080),
    val displayRotation: Int = Surface.ROTATION_0,
    val telemetry: ViewfinderTelemetry = ViewfinderTelemetry(IntArray(64), 0f, FloatArray(0)),
    val isCapturing: Boolean = false,
    val timerCountdownSeconds: Int = 0,
    val isRecordingVideo: Boolean = false,
    val isCapturingPano: Boolean = false,
    val panoProgress: Float = 0f,
    val lensResultText: String? = null,
    val lensResultType: String? = null,
    val fullScreenAspect: Float = 9f / 20f,
    val statusBanner: String? = null,
    val lastCapturedBitmap: Bitmap? = null,
    val lastSavedUri: Uri? = null,
    val lastSavedIsVideo: Boolean = false
)

class NordProViewModel(application: Application) : AndroidViewModel(application) {

    private val probe = Nord6HardwareAnalyzer(application)
    private val cameraController = Camera2SessionController(application)
    private val rawEngine = DngRawCaptureEngine(application)
    private val videoRecorder = ProVideoRecorder(application)

    private val _uiState = MutableStateFlow(NordProUiState())
    val uiState: StateFlow<NordProUiState> = _uiState.asStateFlow()

    private var currentPreviewSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private val panoFrames = mutableListOf<Bitmap>()

    init {
        val report = probe.probeDevice()
        val mainSensor = report.sensors.firstOrNull { it.role == LensRole.MAIN_WIDE } ?: report.sensors.firstOrNull()
        val initialPreviewSize = mainSensor?.let {
            cameraController.resolvePreviewSize(it.cameraId, 4f / 3f)
        } ?: Size(1440, 1080)

        _uiState.value = _uiState.value.copy(
            hardwareReport = report,
            activeSensor = mainSensor,
            previewSize = initialPreviewSize,
            statusBanner = "CAM2📷"
        )
        viewModelScope.launch {
            cameraController.telemetry.collect { stats ->
                _uiState.value = _uiState.value.copy(telemetry = stats)
            }
        }
    }

    fun updateDisplayRotation(rotation: Int) {
        if (_uiState.value.displayRotation != rotation) {
            _uiState.value = _uiState.value.copy(displayRotation = rotation)
        }
    }

    fun attachPreviewTexture(surfaceTexture: SurfaceTexture) {
        currentSurfaceTexture = surfaceTexture
        val pSize = _uiState.value.previewSize
        surfaceTexture.setDefaultBufferSize(pSize.width, pSize.height)
        attachPreviewSurface(Surface(surfaceTexture))
    }

    fun attachPreviewSurface(surface: Surface) {
        currentPreviewSurface = surface
        val sensor = _uiState.value.activeSensor ?: return
        val pSize = _uiState.value.previewSize
        currentSurfaceTexture?.setDefaultBufferSize(pSize.width, pSize.height)
        viewModelScope.launch {
            runCatching {
                cameraController.openCamera(
                    profile = sensor,
                    preview = surface,
                    mode = _uiState.value.captureMode,
                    controls = _uiState.value.proControls
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(statusBanner = "Camera error: ${err.message}")
            }
        }
    }

    fun onPause() {
        viewModelScope.launch {
            if (_uiState.value.isRecordingVideo) {
                runCatching { videoRecorder.stop() }
                _uiState.value = _uiState.value.copy(isRecordingVideo = false)
            }
            cameraController.closeSession()
        }
    }

    fun onResume() {
        val surface = currentPreviewSurface
        if (surface != null && surface.isValid && !_uiState.value.isRecordingVideo) {
            attachPreviewSurface(surface)
        }
    }

    fun switchLens(role: LensRole) {
        val target = _uiState.value.hardwareReport?.sensors?.firstOrNull { it.role == role } ?: return
        val isVideo = isVideoMode(_uiState.value.captureMode)
        val ar = if (isVideo) 16f / 9f else _uiState.value.proControls.aspectRatio.safeAspectFloat()
        val newSize = cameraController.resolvePreviewSize(target.cameraId, ar)
        currentSurfaceTexture?.setDefaultBufferSize(newSize.width, newSize.height)
        _uiState.value = _uiState.value.copy(activeSensor = target, previewSize = newSize)
        currentPreviewSurface?.let { attachPreviewSurface(it) }
    }

    fun selectMode(mode: CaptureMode) {
        val isVideo = isVideoMode(mode)
        val targetAr = if (isVideo) 16f / 9f else _uiState.value.proControls.aspectRatio.safeAspectFloat()
        val sensor = _uiState.value.activeSensor
        val oldSize = _uiState.value.previewSize
        val newSize = if (sensor != null) {
            cameraController.resolvePreviewSize(sensor.cameraId, targetAr)
        } else oldSize

        if (newSize != oldSize) {
            currentSurfaceTexture?.setDefaultBufferSize(newSize.width, newSize.height)
        }
        _uiState.value = _uiState.value.copy(captureMode = mode, previewSize = newSize)
        if (newSize != oldSize && currentPreviewSurface != null) {
            attachPreviewSurface(currentPreviewSurface!!)
        } else {
            cameraController.updateControls(_uiState.value.proControls, mode)
        }
    }

    fun selectFilter(filter: CameraFilter) {
        updateProControls { it.copy(selectedFilter = filter) }
    }

    fun onPinchZoom(zoomFactor: Float) {
        val maxZoom = (_uiState.value.activeSensor?.maxZoomRatio ?: 10f).coerceAtMost(20f)
        updateProControls { cur ->
            val nextZoom = (cur.zoomRatio * zoomFactor).coerceIn(1.0f, maxZoom)
            cur.copy(zoomRatio = nextZoom)
        }
    }

    fun cycleFlash() {
        updateProControls { cur ->
            val next = when (cur.flashMode) {
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.TORCH
                FlashMode.TORCH -> FlashMode.OFF
                FlashMode.OFF -> FlashMode.AUTO
            }
            cur.copy(flashMode = next)
        }
    }

    fun cycleHdr() {
        updateProControls { cur ->
            val next = when (cur.hdrMode) {
                HdrMode.AUTO -> HdrMode.ON
                HdrMode.ON -> HdrMode.OFF
                HdrMode.OFF -> HdrMode.AUTO
            }
            cur.copy(hdrMode = next)
        }
    }

    fun cycleTimer() {
        updateProControls { cur ->
            val next = when (cur.timerOption) {
                TimerOption.OFF -> TimerOption.THREE
                TimerOption.THREE -> TimerOption.TEN
                TimerOption.TEN -> TimerOption.OFF
            }
            cur.copy(timerOption = next)
        }
    }

    fun updateFullScreenAspect(aspect: Float) {
        if (aspect > 0.2f && kotlin.math.abs(_uiState.value.fullScreenAspect - aspect) > 0.01f) {
            _uiState.value = _uiState.value.copy(fullScreenAspect = aspect)
        }
    }

    fun dismissLensResult() {
        _uiState.value = _uiState.value.copy(lensResultText = null, lensResultType = null)
    }

    fun selectAspectRatio(nextAr: AspectRatioOption) {
        val sensor = _uiState.value.activeSensor
        val oldSize = _uiState.value.previewSize
        val newSize = if (sensor != null) {
            cameraController.resolvePreviewSize(sensor.cameraId, nextAr.safeAspectFloat())
        } else oldSize

        val updated = _uiState.value.proControls.copy(aspectRatio = nextAr)
        _uiState.value = _uiState.value.copy(proControls = updated, previewSize = newSize)
        if (newSize != oldSize) {
            currentSurfaceTexture?.setDefaultBufferSize(newSize.width, newSize.height)
            currentPreviewSurface?.let { attachPreviewSurface(it) }
        }
    }

    fun cycleAspectRatio() {
        val nextAr = when (_uiState.value.proControls.aspectRatio) {
            AspectRatioOption.RATIO_4_3 -> AspectRatioOption.RATIO_16_9
            AspectRatioOption.RATIO_16_9 -> AspectRatioOption.RATIO_1_1
            AspectRatioOption.RATIO_1_1 -> AspectRatioOption.FULL
            AspectRatioOption.FULL -> AspectRatioOption.RATIO_4_3
        }
        val sensor = _uiState.value.activeSensor
        val oldSize = _uiState.value.previewSize
        val newSize = if (sensor != null) {
            cameraController.resolvePreviewSize(sensor.cameraId, nextAr.safeAspectFloat())
        } else oldSize

        val updated = _uiState.value.proControls.copy(aspectRatio = nextAr)
        _uiState.value = _uiState.value.copy(proControls = updated, previewSize = newSize)
        if (newSize != oldSize) {
            currentSurfaceTexture?.setDefaultBufferSize(newSize.width, newSize.height)
            currentPreviewSurface?.let { attachPreviewSurface(it) }
        }
    }

    fun updateProControls(transform: (ProControlsState) -> ProControlsState) {
        val prevRaw = _uiState.value.proControls.rawFormatOption
        val updated = transform(_uiState.value.proControls)
        _uiState.value = _uiState.value.copy(proControls = updated)
        if (prevRaw != updated.rawFormatOption) {
            currentPreviewSurface?.let { attachPreviewSurface(it) }
        } else {
            cameraController.updateControls(updated)
        }
    }

    fun tapToFocus(normX: Float, normY: Float) {
        cameraController.triggerTapToFocus(normX, normY, _uiState.value.displayRotation)
    }

    fun restoreDefaultSettings() {
        val defaults = ProControlsState()
        _uiState.value = _uiState.value.copy(proControls = defaults, statusBanner = "Settings restored")
        cameraController.updateControls(defaults, _uiState.value.captureMode)
    }

    fun onVolumeButtonPressed(isUp: Boolean): Boolean {
        return when (_uiState.value.proControls.volumeButtonAction) {
            com.nordpro.camera.core.camera.VolumeButtonAction.SHUTTER -> {
                onShutterPressed()
                true
            }
            com.nordpro.camera.core.camera.VolumeButtonAction.ZOOM -> {
                onPinchZoom(if (isUp) 1.15f else 0.87f)
                true
            }
            com.nordpro.camera.core.camera.VolumeButtonAction.VOLUME -> false
        }
    }

    fun onShutterPressed() {
        val state = _uiState.value
        if (isVideoMode(state.captureMode)) {
            toggleVideoRecording()
            return
        }
        if (state.captureMode == CaptureMode.PANORAMA) {
            togglePanoramaCapture()
            return
        }
        if (state.isCapturing || state.timerCountdownSeconds > 0) return

        viewModelScope.launch {
            val timerSec = state.proControls.timerOption.seconds
            if (timerSec > 0) {
                for (t in timerSec downTo 1) {
                    _uiState.value = _uiState.value.copy(timerCountdownSeconds = t)
                    delay(1000L)
                }
                _uiState.value = _uiState.value.copy(timerCountdownSeconds = 0)
            }

            if (state.proControls.shutterSoundEnabled) {
                runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
            }
            _uiState.value = _uiState.value.copy(isCapturing = true, statusBanner = "Capturing...")
            val rawOption = if (state.captureMode == CaptureMode.PRO) state.proControls.rawFormatOption else RawFormatOption.JPEG_ONLY
            val displayRot = _uiState.value.displayRotation
            val activeFilter = state.proControls.selectedFilter

            val capture = cameraController.captureRawOrJpeg(rawOption, displayRot)
            val chars = cameraController.getCharacteristics()

            if (capture?.rawImage != null && chars != null) {
                val orientationDeg = CameraTransformHelper.getCaptureOrientationDegrees(chars, displayRot)
                val out = rawEngine.saveDngAndOptionalJpeg(
                    characteristics = chars,
                    captureResult = capture.result,
                    rawImage = capture.rawImage!!,
                    jpegBytes = capture.jpegBytes,
                    orientationDegrees = orientationDeg
                )
                capture.rawImage!!.close()
                val previewBmp = capture.jpegBytes?.let { decodeThumbnail(it) }
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    lastCapturedBitmap = previewBmp ?: _uiState.value.lastCapturedBitmap,
                    lastSavedUri = out.dngUri ?: out.jpegUri,
                    lastSavedIsVideo = false,
                    statusBanner = "Saved ${out.bitDepthNote}"
                )
                return@launch
            }

            val jpegBytes = capture?.jpegBytes
            if (jpegBytes != null) {
                val targetAr = state.proControls.aspectRatio
                val needCrop = targetAr != AspectRatioOption.RATIO_4_3
                val needBitmapPost = activeFilter != CameraFilter.ORIGINAL ||
                    state.captureMode == CaptureMode.PORTRAIT ||
                    state.captureMode == CaptureMode.DOC_SCAN ||
                    needCrop ||
                    state.proControls.watermarkEnabled ||
                    (state.proControls.mirrorSelfie && state.activeSensor?.role == LensRole.FRONT)
                val uri = if (!needBitmapPost) {
                    saveJpegBytesToMediaStore(jpegBytes)
                } else {
                    val desiredRatio = when (targetAr) {
                        AspectRatioOption.RATIO_1_1 -> 1f
                        AspectRatioOption.RATIO_16_9 -> if (displayRot == Surface.ROTATION_90 || displayRot == Surface.ROTATION_270) 16f / 9f else 9f / 16f
                        AspectRatioOption.FULL -> if (displayRot == Surface.ROTATION_90 || displayRot == Surface.ROTATION_270) 1f / state.fullScreenAspect else state.fullScreenAspect
                        AspectRatioOption.RATIO_4_3 -> 0f
                    }
                    val processed = processBitmapEffects(
                        bytes = jpegBytes,
                        filter = activeFilter,
                        watermark = state.proControls.watermarkEnabled,
                        mirror = state.proControls.mirrorSelfie && state.activeSensor?.role == LensRole.FRONT,
                        applyPortraitBokeh = state.captureMode == CaptureMode.PORTRAIT,
                        portraitFStop = state.proControls.portraitFStop,
                        faceRect = state.telemetry.detectedFaces.firstOrNull(),
                        targetAspectRatio = desiredRatio,
                        isDocScan = state.captureMode == CaptureMode.DOC_SCAN
                    )
                    if (state.captureMode == CaptureMode.DOC_SCAN && processed != null) {
                        runLensScan(processed)
                    }
                    processed?.let { saveBitmapToMediaStore(it) } ?: saveJpegBytesToMediaStore(jpegBytes)
                }
                val bmp = decodeThumbnail(jpegBytes)
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    lastCapturedBitmap = bmp ?: _uiState.value.lastCapturedBitmap,
                    lastSavedUri = uri,
                    lastSavedIsVideo = false,
                    statusBanner = if (uri != null) "CAM2📷 • Saved" else "Save failed"
                )
            } else {
                _uiState.value = _uiState.value.copy(isCapturing = false, statusBanner = "Capture failed")
            }
        }
    }


    private fun togglePanoramaCapture() {
        val state = _uiState.value
        if (!state.isCapturingPano) {
            panoFrames.forEach { it.recycle() }
            panoFrames.clear()
            _uiState.value = state.copy(isCapturingPano = true, panoProgress = 0.05f, statusBanner = "Pan slowly →")
            viewModelScope.launch {
                val displayRot = _uiState.value.displayRotation
                for (step in 1..6) {
                    if (!_uiState.value.isCapturingPano) break
                    val cap = cameraController.captureRawOrJpeg(RawFormatOption.JPEG_ONLY, displayRot)
                    val bytes = cap?.jpegBytes
                    if (bytes != null) {
                        val bmp = withContext(Dispatchers.Default) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        }
                        if (bmp != null) panoFrames.add(bmp)
                    }
                    _uiState.value = _uiState.value.copy(panoProgress = (step / 6f).coerceIn(0.1f, 1f))
                    delay(550L)
                }
                if (_uiState.value.isCapturingPano) {
                    finishPanoramaStitch()
                }
            }
        } else {
            viewModelScope.launch { finishPanoramaStitch() }
        }
    }

    private suspend fun finishPanoramaStitch() {
        _uiState.value = _uiState.value.copy(isCapturingPano = false, statusBanner = "Stitching Pano...")
        val frames = panoFrames.toList()
        panoFrames.clear()
        if (frames.isEmpty()) {
            _uiState.value = _uiState.value.copy(panoProgress = 0f, statusBanner = "Pano failed")
            return
        }
        val stitched = withContext(Dispatchers.Default) {
            if (frames.size == 1) {
                frames.first()
            } else {
                val baseH = frames.first().height
                val stepW = (frames.first().width * 0.55f).toInt().coerceAtLeast(1)
                val totalW = frames.first().width + stepW * (frames.size - 1)
                val out = Bitmap.createBitmap(totalW, baseH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                frames.forEachIndexed { idx, bmp ->
                    val x = idx * stepW
                    canvas.drawBitmap(bmp, x.toFloat(), 0f, paint)
                    bmp.recycle()
                }
                out
            }
        }
        val uri = saveBitmapToMediaStore(stitched, prefix = "CAM2_PANO")
        val thumb = withContext(Dispatchers.Default) {
            Bitmap.createScaledBitmap(stitched, (stitched.width / 4).coerceAtLeast(1), (stitched.height / 4).coerceAtLeast(1), true)
        }
        _uiState.value = _uiState.value.copy(
            panoProgress = 0f,
            lastCapturedBitmap = thumb,
            lastSavedUri = uri,
            lastSavedIsVideo = false,
            statusBanner = if (uri != null) "Panorama saved" else "Pano save failed"
        )
    }

    private suspend fun runLensScan(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        val barcodeScanner = BarcodeScanning.getClient()
        val barcodes = runCatching {
            suspendCancellableCoroutine<List<com.google.mlkit.vision.barcode.common.Barcode>> { cont ->
                barcodeScanner.process(input)
                    .addOnSuccessListener { list -> if (cont.isActive) cont.resume(list) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
            }
        }.getOrDefault(emptyList())

        val firstBarcode = barcodes.firstOrNull()?.rawValue
        if (!firstBarcode.isNullOrBlank()) {
            val isUrl = firstBarcode.startsWith("http://", true) || firstBarcode.startsWith("https://", true)
            _uiState.value = _uiState.value.copy(
                lensResultText = firstBarcode,
                lensResultType = if (isUrl) "URL" else "QR"
            )
            return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val visionText = runCatching {
            suspendCancellableCoroutine<String> { cont ->
                recognizer.process(input)
                    .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resume("") }
            }
        }.getOrDefault("")

        if (visionText.isNotBlank()) {
            val phoneMatch = Regex("\\+?[0-9][0-9\\-\\s]{6,14}[0-9]").find(visionText)?.value?.trim()
            _uiState.value = _uiState.value.copy(
                lensResultText = phoneMatch ?: visionText.take(400),
                lensResultType = if (phoneMatch != null) "PHONE" else "TEXT"
            )
        }
    }

    private suspend fun processBitmapEffects(
        bytes: ByteArray,
        filter: CameraFilter,
        watermark: Boolean,
        mirror: Boolean,
        applyPortraitBokeh: Boolean = false,
        portraitFStop: Float = 4.5f,
        faceRect: android.graphics.RectF? = null,
        targetAspectRatio: Float = 0f,
        isDocScan: Boolean = false
    ): Bitmap? = withContext(Dispatchers.Default) {
        val rawSrc = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        val src = if (targetAspectRatio > 0.1f) {
            val srcRatio = rawSrc.width.toFloat() / rawSrc.height.toFloat()
            val cropW: Int
            val cropH: Int
            if (srcRatio > targetAspectRatio) {
                cropH = rawSrc.height
                cropW = (cropH * targetAspectRatio).toInt().coerceIn(1, rawSrc.width)
            } else {
                cropW = rawSrc.width
                cropH = (cropW / targetAspectRatio).toInt().coerceIn(1, rawSrc.height)
            }
            val x = ((rawSrc.width - cropW) / 2).coerceAtLeast(0)
            val y = ((rawSrc.height - cropH) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(rawSrc, x, y, cropW, cropH).also {
                if (it !== rawSrc) rawSrc.recycle()
            }
        } else {
            rawSrc
        }

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (isDocScan) {
            // Crisp high-contrast document enhancement
            val docMatrix = android.graphics.ColorMatrix().apply {
                setSaturation(0.15f)
                postConcat(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            1.45f, 0f, 0f, 0f, -35f,
                            0f, 1.45f, 0f, 0f, -35f,
                            0f, 0f, 1.45f, 0f, -35f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            paint.colorFilter = ColorMatrixColorFilter(docMatrix)
        } else if (filter != CameraFilter.ORIGINAL) {
            paint.colorFilter = ColorMatrixColorFilter(filter.toColorMatrix())
        }
        if (mirror) {
            val m = Matrix().apply { preScale(-1f, 1f, src.width / 2f, src.height / 2f) }
            canvas.drawBitmap(src, m, paint)
        } else {
            canvas.drawBitmap(src, 0f, 0f, paint)
        }
        if (applyPortraitBokeh && portraitFStop < 12f) {
            val strength = ((12f - portraitFStop) / 10.6f).coerceIn(0.1f, 1f)
            val downScale = (8 + (strength * 14).toInt()).coerceIn(6, 24)
            val smallW = (out.width / downScale).coerceAtLeast(16)
            val smallH = (out.height / downScale).coerceAtLeast(16)
            val small = Bitmap.createScaledBitmap(out, smallW, smallH, true)
            val blurredBg = Bitmap.createScaledBitmap(small, out.width, out.height, true)
            small.recycle()

            val sharpFg = out.copy(Bitmap.Config.ARGB_8888, false)
            canvas.drawBitmap(blurredBg, 0f, 0f, null)
            blurredBg.recycle()

            val cx = faceRect?.let { (it.left + it.right) * 0.5f * out.width } ?: (out.width * 0.5f)
            val cy = faceRect?.let { (it.top + it.bottom) * 0.5f * out.height } ?: (out.height * 0.42f)
            val rx = faceRect?.let { (it.width() * 1.4f * out.width).coerceAtLeast(out.width * 0.25f) } ?: (out.width * 0.30f)
            val ry = faceRect?.let { (it.height() * 2.2f * out.height).coerceAtLeast(out.height * 0.36f) } ?: (out.height * 0.40f)

            val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    cx, cy, maxOf(rx, ry),
                    intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0f, 0.58f, 1.0f),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            val maskedFg = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            val fgCanvas = Canvas(maskedFg)
            fgCanvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, maskPaint)
            val srcInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
            }
            fgCanvas.drawBitmap(sharpFg, 0f, 0f, srcInPaint)
            sharpFg.recycle()

            canvas.drawBitmap(maskedFg, 0f, 0f, null)
            maskedFg.recycle()
        }
        if (watermark) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = (src.height * 0.028f).coerceAtLeast(32f)
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
            }
            canvas.drawText("CAM2📷  •  OnePlus Nord 6", 48f, src.height - 52f, textPaint)
        }
        src.recycle()
        out
    }

    private suspend fun applyFilterToJpegBytes(bytes: ByteArray, filter: CameraFilter): Bitmap? =
        withContext(Dispatchers.Default) {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(filter.toColorMatrix())
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            src.recycle()
            out
        }

    private suspend fun decodeThumbnail(bytes: ByteArray): Bitmap? = withContext(Dispatchers.Default) {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun toggleVideoRecording() {
        val state = _uiState.value
        val preview = currentPreviewSurface ?: return
        val sensor = state.activeSensor ?: return

        viewModelScope.launch {
            if (!state.isRecordingVideo) {
                val isTimelapse = state.captureMode == CaptureMode.TIMELAPSE
                val isSloMo = state.captureMode == CaptureMode.SLO_MO

                val slowMoPair = if (isSloMo) {
                    val reqFps = state.proControls.sloMoFps
                    sensor.supportedHighSpeedSizes.firstOrNull { it.second.upper == reqFps }
                        ?: sensor.supportedHighSpeedSizes.firstOrNull { it.first.width == 1920 && it.second.upper >= 120 }
                        ?: sensor.supportedHighSpeedSizes.firstOrNull { it.second.upper >= 120 }
                        ?: sensor.supportedHighSpeedSizes.firstOrNull()
                } else null

                val config = when {
                    isSloMo -> VideoRecordingConfig(
                        customSize = slowMoPair?.first ?: Size(1920, 1080),
                        fps = slowMoPair?.second?.upper ?: 120,
                        bitrateBps = 45_000_000,
                        useHevc = false,
                        isSlowMotion = true
                    )
                    isTimelapse -> VideoRecordingConfig(
                        resolution = VideoResolution.FHD_1080P,
                        fps = 30,
                        bitrateBps = 40_000_000,
                        isTimelapse = true,
                        timelapseCaptureRateFps = state.proControls.timelapseSpeed
                    )
                    else -> VideoRecordingConfig(
                        resolution = if (state.proControls.video4kEnabled) VideoResolution.UHD_4K else VideoResolution.FHD_1080P,
                        fps = if (state.proControls.video60fpsEnabled) 60 else 30,
                        bitrateBps = 120_000_000
                    )
                }

                runCatching {
                    val orientationHint = CameraTransformHelper.getCaptureOrientationDegrees(
                        cameraController.getCharacteristics(),
                        _uiState.value.displayRotation
                    )
                    if (isSloMo && slowMoPair != null) {
                        currentSurfaceTexture?.setDefaultBufferSize(slowMoPair.first.width, slowMoPair.first.height)
                        _uiState.value = _uiState.value.copy(previewSize = slowMoPair.first)
                    }
                    val videoSurface = videoRecorder.prepare(config, orientationHintDegrees = orientationHint)
                    cameraController.openCamera(
                        profile = sensor,
                        preview = preview,
                        mode = state.captureMode,
                        controls = state.proControls,
                        extraVideoSurface = videoSurface,
                        slowMoFpsRange = slowMoPair?.second
                    )
                    videoRecorder.start()
                    _uiState.value = _uiState.value.copy(
                        isRecordingVideo = true,
                        statusBanner = when {
                            isSloMo -> "SLO-MO ${slowMoPair?.second?.upper ?: 120}fps"
                            isTimelapse -> "TIME-LAPSE"
                            else -> "REC 4K60"
                        }
                    )
                }.onFailure { err ->
                    videoRecorder.release()
        runCatching { shutterSound.release() }
                    _uiState.value = _uiState.value.copy(
                        isRecordingVideo = false,
                        statusBanner = "Record error: ${err.message}"
                    )
                }
            } else {
                val uri = videoRecorder.stop()
                val restoredSize = cameraController.resolvePreviewSize(sensor.cameraId, 16f / 9f)
                currentSurfaceTexture?.setDefaultBufferSize(restoredSize.width, restoredSize.height)
                _uiState.value = _uiState.value.copy(previewSize = restoredSize)
                cameraController.openCamera(sensor, preview, state.captureMode, state.proControls, null, null)
                _uiState.value = _uiState.value.copy(
                    isRecordingVideo = false,
                    lastSavedUri = uri,
                    lastSavedIsVideo = true,
                    statusBanner = "Video saved"
                )
            }
        }
    }

    private fun isVideoMode(mode: CaptureMode): Boolean =
        mode == CaptureMode.VIDEO || mode == CaptureMode.TIMELAPSE || mode == CaptureMode.SLO_MO

    private suspend fun saveBitmapToMediaStore(bitmap: Bitmap, prefix: String = "CAM2"): Uri? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CAM2_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CAM2")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = getApplication<Application>().contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    private suspend fun saveJpegBytesToMediaStore(bytes: ByteArray): Uri? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "CAM2_$timestamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CAM2")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = getApplication<Application>().contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri
    }

    override fun onCleared() {
        super.onCleared()
        videoRecorder.release()
        runCatching { shutterSound.release() }
    }
}
