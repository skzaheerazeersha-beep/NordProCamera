package com.nordpro.camera.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import com.nordpro.camera.core.probe.SensorCapabilityProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.pow

data class CapturedBurstFrame(
    val yuvImage: Image?,
    val rawImage: Image?,
    val jpegBytes: ByteArray?,
    val result: TotalCaptureResult
)

/**
 * Crash-safe Camera2 session controller for CAM2📷 on OnePlus Nord 6 (CPH2793).
 * Features throttled telemetry (zero UI stutter), persistent tap-to-focus + YUV object tracking,
 * and reliable CameraConstrainedHighSpeedCaptureSession with regular-session fallback for Slo-Mo.
 */
class Camera2SessionController(
    private val context: Context,
    private val analyzer: LiveViewfinderAnalyzer = LiveViewfinderAnalyzer()
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("CAM2Session").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val sessionMutex = Mutex()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var videoSurface: Surface? = null
    private var activeProfile: SensorCapabilityProfile? = null
    private var activeCharacteristics: CameraCharacteristics? = null

    private var analysisReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    private var currentControls = ProControlsState()
    private var currentMode = CaptureMode.PHOTO
    private var highSpeedFpsRange: Range<Int>? = null
    private var activeMeteringRegions: Array<MeteringRectangle>? = null
    private var lastDisplayRotation: Int = Surface.ROTATION_0

    private var lastAnalysisMs = 0L
    private var lastFaceEmitMs = 0L

    private val _telemetry = MutableStateFlow(ViewfinderTelemetry(IntArray(64), 0f, FloatArray(0)))
    val telemetry: StateFlow<ViewfinderTelemetry> = _telemetry.asStateFlow()

    private val _lastCaptureResult = MutableStateFlow<TotalCaptureResult?>(null)
    val lastCaptureResult: StateFlow<TotalCaptureResult?> = _lastCaptureResult.asStateFlow()

    @Volatile private var pendingJpegDeferred: CompletableDeferred<ByteArray?>? = null
    @Volatile private var pendingRawDeferred: CompletableDeferred<Image?>? = null

    fun resolvePreviewSize(cameraId: String, targetAspect: Float): Size {
        val chars = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull()
            ?: return Size(1440, 1080)
        return CameraTransformHelper.chooseOptimalPreviewSize(chars, targetAspect)
    }

    @SuppressLint("MissingPermission")
    suspend fun openCamera(
        profile: SensorCapabilityProfile,
        preview: Surface,
        mode: CaptureMode,
        controls: ProControlsState,
        extraVideoSurface: Surface? = null,
        slowMoFpsRange: Range<Int>? = null
    ) = sessionMutex.withLock {
        closeSessionInternal()
        analyzer.clearTracking()
        activeMeteringRegions = null
        if (!preview.isValid) return@withLock

        activeProfile = profile
        activeCharacteristics = runCatching { cameraManager.getCameraCharacteristics(profile.cameraId) }.getOrNull()
        previewSurface = preview
        videoSurface = extraVideoSurface
        currentMode = mode
        currentControls = controls
        highSpeedFpsRange = slowMoFpsRange

        val opened = CompletableDeferred<CameraDevice>()
        cameraManager.openCamera(profile.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                opened.complete(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                runCatching { camera.close() }
                if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("Camera disconnected"))
            }
            override fun onError(camera: CameraDevice, error: Int) {
                runCatching { camera.close() }
                if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("Camera error $error"))
            }
        }, cameraHandler)

        val device = withTimeoutOrNull(4000L) { opened.await() } ?: return@withLock
        createCaptureSession(device, profile)
    }

    private suspend fun createCaptureSession(device: CameraDevice, profile: SensorCapabilityProfile) {
        val preview = previewSurface ?: return
        if (!preview.isValid) return

        val outputs = mutableListOf<OutputConfiguration>()
        outputs.add(OutputConfiguration(preview))

        if (highSpeedFpsRange != null && videoSurface != null) {
            outputs.add(OutputConfiguration(videoSurface!!))
            val sessionReady = CompletableDeferred<CameraCaptureSession>()
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                outputs,
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        sessionReady.complete(session)
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        sessionReady.completeExceptionally(IllegalStateException("High-speed session failed"))
                    }
                }
            )
            runCatching { device.createCaptureSession(config) }
            val highSpeedSession = runCatching { withTimeoutOrNull(4000L) { sessionReady.await() } }.getOrNull()
            if (highSpeedSession != null) {
                updatePreviewRequest(highSpeedSession)
                return
            }
            // Fallback to regular recording session if high-speed constraints fail on this lens/resolution
            highSpeedFpsRange = null
            outputs.clear()
            outputs.add(OutputConfiguration(preview))
        }

        analysisReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                runCatching {
                    val img = reader.acquireLatestImage() ?: return@runCatching
                    val now = SystemClock.uptimeMillis()
                    val needHistOrPeak = currentControls.histogramEnabled || currentControls.focusPeakingEnabled
                    val needTrack = analyzer.isTrackingActive()
                    if ((needHistOrPeak || needTrack) && now - lastAnalysisMs >= 90L) {
                        lastAnalysisMs = now
                        val stats = analyzer.analyzeYuvFrame(
                            image = img,
                            computeHistogramOrPeaking = needHistOrPeak,
                            computePeaking = currentControls.focusPeakingEnabled,
                            zebraThreshold = currentControls.zebraThreshold
                        )
                        _telemetry.value = stats.copy(detectedFaces = _telemetry.value.detectedFaces)
                        // Continuously update AF region to follow tracked object
                        stats.trackedObject?.let { rect ->
                            val cx = (rect.left + rect.right) / 2f
                            val cy = (rect.top + rect.bottom) / 2f
                            updateTrackingMeteringRegion(cx, cy)
                        }
                    }
                    img.close()
                }
            }, cameraHandler)
            outputs.add(OutputConfiguration(surface))
        }

        val photoSize = profile.maxJpegSize ?: Size(4096, 3072)
        if (videoSurface == null) {
            jpegReader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.JPEG, 4).apply {
                setOnImageAvailableListener({ reader ->
                    runCatching {
                        val img = reader.acquireNextImage() ?: return@runCatching
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        img.close()
                        pendingJpegDeferred?.complete(bytes)
                    }
                }, cameraHandler)
                outputs.add(OutputConfiguration(surface))
            }

            if (profile.supportsRawCapture && profile.maxRawSize != null &&
                currentControls.rawFormatOption != RawFormatOption.JPEG_ONLY
            ) {
                rawReader = ImageReader.newInstance(
                    profile.maxRawSize!!.width,
                    profile.maxRawSize!!.height,
                    ImageFormat.RAW_SENSOR,
                    2
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        runCatching {
                            val img = reader.acquireNextImage() ?: return@runCatching
                            val d = pendingRawDeferred
                            if (d != null && !d.isCompleted) d.complete(img) else img.close()
                        }
                    }, cameraHandler)
                    outputs.add(OutputConfiguration(surface))
                }
            }
        } else {
            outputs.add(OutputConfiguration(videoSurface!!))
        }

        val sessionReady = CompletableDeferred<CameraCaptureSession>()
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    sessionReady.complete(session)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    sessionReady.completeExceptionally(IllegalStateException("Session configure failed"))
                }
            }
        )
        device.createCaptureSession(config)
        val session = withTimeoutOrNull(4000L) { sessionReady.await() } ?: return
        updatePreviewRequest(session)
    }

    fun updateControls(controls: ProControlsState, mode: CaptureMode = currentMode) {
        currentControls = controls
        currentMode = mode
        runCatching {
            captureSession?.let { updatePreviewRequest(it) }
        }
    }

    private fun updateTrackingMeteringRegion(normX: Float, normY: Float) {
        val activeArray = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val rect = CameraTransformHelper.mapViewfinderToSensorRect(
            normX, normY, activeCharacteristics, activeArray, lastDisplayRotation, 160
        )
        activeMeteringRegions = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
    }

    private fun updatePreviewRequest(session: CameraCaptureSession) {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        if (!preview.isValid) return

        val template = if (videoSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template).apply {
            addTarget(preview)
            if (highSpeedFpsRange == null) {
                analysisReader?.surface?.let { addTarget(it) }
            }
            if (videoSurface != null) addTarget(videoSurface!!)
            highSpeedFpsRange?.let { fps ->
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            }
            if (highSpeedFpsRange == null) {
                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                activeMeteringRegions?.let { regions ->
                    set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                    set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                }
            }
            applyProControls(this, currentControls, isCapture = false)
        }

        if (session is CameraConstrainedHighSpeedCaptureSession) {
            val burstList = session.createHighSpeedRequestList(builder.build())
            session.setRepeatingBurst(burstList, null, cameraHandler)
        } else {
            session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFaceEmitMs < 66L) return
                    lastFaceEmitMs = now

                    val activeArray = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val faces = result.get(CaptureResult.STATISTICS_FACES)
                    if (activeArray != null && faces != null) {
                        val w = activeArray.width().toFloat().coerceAtLeast(1f)
                        val h = activeArray.height().toFloat().coerceAtLeast(1f)
                        val isFront = activeCharacteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                        val mapped = faces.map { f ->
                            val b = f.bounds
                            val nx1 = 1f - (b.bottom / h)
                            val nx2 = 1f - (b.top / h)
                            val ny1 = b.left / w
                            val ny2 = b.right / w
                            if (isFront) RectF(1f - nx2, 1f - ny2, 1f - nx1, 1f - ny1)
                            else RectF(nx1, ny1, nx2, ny2)
                        }
                        if (mapped.isNotEmpty() || _telemetry.value.detectedFaces.isNotEmpty()) {
                            _telemetry.value = _telemetry.value.copy(detectedFaces = mapped)
                        }
                    }
                }
            }, cameraHandler)
        }
    }

    suspend fun captureRawOrJpeg(
        rawOption: RawFormatOption,
        displayRotation: Int = Surface.ROTATION_0
    ): CapturedBurstFrame? {
        val device = cameraDevice ?: return null
        val session = captureSession ?: return null

        val needJpeg = rawOption != RawFormatOption.RAW_DNG || rawReader == null
        val needRaw = rawOption != RawFormatOption.JPEG_ONLY && rawReader != null

        val jpegDef = if (needJpeg) CompletableDeferred<ByteArray?>().also { pendingJpegDeferred = it } else null
        val rawDef = if (needRaw) CompletableDeferred<Image?>().also { pendingRawDeferred = it } else null
        val resDef = CompletableDeferred<TotalCaptureResult>()

        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            if (needJpeg) jpegReader?.surface?.let { addTarget(it) }
            if (needRaw) rawReader?.surface?.let { addTarget(it) }
            activeMeteringRegions?.let { regions ->
                set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                set(CaptureRequest.CONTROL_AE_REGIONS, regions)
            }
            applyProControls(this, currentControls, isCapture = true)
            set(CaptureRequest.JPEG_QUALITY, 98.toByte())
            set(
                CaptureRequest.JPEG_ORIENTATION,
                CameraTransformHelper.getCaptureOrientationDegrees(activeCharacteristics, displayRotation)
            )
        }

        runCatching {
            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    resDef.complete(result)
                }
            }, cameraHandler)
        }.onFailure {
            pendingJpegDeferred = null
            pendingRawDeferred = null
            return null
        }

        val result = withTimeoutOrNull(5000L) { resDef.await() } ?: return null
        val jpegBytes = withTimeoutOrNull(5000L) { jpegDef?.await() }
        val rawImage = withTimeoutOrNull(5000L) { rawDef?.await() }

        pendingJpegDeferred = null
        pendingRawDeferred = null

        return CapturedBurstFrame(yuvImage = null, rawImage = rawImage, jpegBytes = jpegBytes, result = result)
    }

    fun triggerTapToFocus(normX: Float, normY: Float, displayRotation: Int = Surface.ROTATION_0) {
        lastDisplayRotation = displayRotation
        val isFront = activeCharacteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        analyzer.startTracking(normX, normY, isFront)

        runCatching {
            val session = captureSession ?: return
            val device = cameraDevice ?: return
            val preview = previewSurface ?: return
            if (!preview.isValid || session is CameraConstrainedHighSpeedCaptureSession) return
            val activeArray = activeCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val rect = CameraTransformHelper.mapViewfinderToSensorRect(
                normX, normY, activeCharacteristics, activeArray, displayRotation, 160
            )
            val region = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX))
            activeMeteringRegions = region

            // Persist region on repeating request
            updatePreviewRequest(session)

            // Trigger immediate AF scan
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                analysisReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_REGIONS, region)
                set(CaptureRequest.CONTROL_AE_REGIONS, region)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                applyProControls(this, currentControls, isCapture = false)
            }
            session.capture(builder.build(), null, cameraHandler)
        }
    }

    fun clearTapFocus() {
        analyzer.clearTracking()
        activeMeteringRegions = null
        _telemetry.value = _telemetry.value.copy(trackedObject = null)
        runCatching {
            captureSession?.let { updatePreviewRequest(it) }
        }
    }

    private fun applyProControls(
        builder: CaptureRequest.Builder,
        controls: ProControlsState,
        isCapture: Boolean
    ) {
        val profile = activeProfile
        if (highSpeedFpsRange != null) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val clampedZoom = controls.zoomRatio.coerceIn(1.0f, profile?.maxZoomRatio ?: 10f)
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
            }
            return
        }

        if (profile?.supportsOis == true) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (videoSurface != null && profile?.supportsPreviewStabilization == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val clampedZoom = controls.zoomRatio.coerceIn(1.0f, profile?.maxZoomRatio ?: 10f)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clampedZoom)
        }

        if (controls.isManualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val maxD = profile?.minimumFocusDistanceDiopters ?: 10f
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, controls.focusDistanceDiopters.coerceIn(0f, maxD))
        } else {
            val afMode = if (videoSurface != null) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        }

        if (controls.isManualIso || controls.isManualShutter) {
            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val iso = controls.iso.coerceIn(profile?.isoRange?.lower ?: 50, profile?.isoRange?.upper ?: 12800)
            val expNs = controls.shutterSpeedNs.coerceIn(
                profile?.exposureTimeRangeNs?.lower ?: 100_000L,
                profile?.exposureTimeRangeNs?.upper ?: 2_000_000_000L
            )
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
            if (controls.flashMode == FlashMode.TORCH) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        } else {
            when (controls.flashMode) {
                FlashMode.OFF -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                FlashMode.AUTO -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
                FlashMode.ON -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                }
                FlashMode.TORCH -> {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            }
            builder.set(CaptureRequest.CONTROL_AE_LOCK, controls.aeLocked)
            val baseEv = if (currentMode == CaptureMode.NIGHT) controls.evCompensationIndex + 3 else controls.evCompensationIndex
            val clampedEv = baseEv.coerceIn(
                profile?.evCompensationRange?.lower ?: -12,
                profile?.evCompensationRange?.upper ?: 12
            )
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedEv)

            if (currentMode == CaptureMode.NIGHT) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
            } else if (currentMode == CaptureMode.PORTRAIT) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT)
            } else if (controls.hdrMode == HdrMode.ON) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            }
        }

        if (controls.isManualWhiteBalance) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinToRggbGains(controls.whiteBalanceKelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        if (isCapture) {
            builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        }
    }

    private fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val temp = (kelvin.coerceIn(2000, 10000) / 100.0)
        val r = if (temp <= 66.0) 1.0 else (1.292936 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.4, 2.5)
        val g = 1.0
        val b = if (temp >= 66.0) 1.0 else if (temp <= 19.0) 0.35 else (0.543206789 * kotlin.math.ln(temp - 10.0) - 1.196254).coerceIn(0.35, 2.5)
        return RggbChannelVector(r.toFloat(), g.toFloat(), g.toFloat(), b.toFloat())
    }

    fun getCharacteristics(): CameraCharacteristics? = activeCharacteristics

    suspend fun closeSession() = sessionMutex.withLock {
        closeSessionInternal()
    }

    private fun closeSessionInternal() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { analysisReader?.close() }
        analysisReader = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
    }
}
