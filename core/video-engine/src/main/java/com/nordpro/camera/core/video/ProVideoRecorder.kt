package com.nordpro.camera.core.video

import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VideoResolution(val size: Size, val label: String) {
    UHD_4K(Size(3840, 2160), "4K"),
    FHD_1080P(Size(1920, 1080), "1080p")
}

enum class VideoColorProfile(val label: String) {
    STANDARD_8BIT("Rec.709 8-bit"),
    HLG_10BIT("HLG 10-bit HDR"),
    FLAT_LOG_10BIT("Nord-Log 10-bit Flat")
}

data class VideoRecordingConfig(
    val resolution: VideoResolution = VideoResolution.UHD_4K,
    val customSize: Size? = null,
    val fps: Int = 60,
    val bitrateBps: Int = 100_000_000,
    val useHevc: Boolean = true,
    val colorProfile: VideoColorProfile = VideoColorProfile.STANDARD_8BIT,
    val audioBitrateBps: Int = 320_000,
    val audioSampleRateHz: Int = 48_000,
    val preferExternalMic: Boolean = true,
    val isTimelapse: Boolean = false,
    val isSlowMotion: Boolean = false,
    val timelapseCaptureRateFps: Double = 2.0
)

/**
 * Video Recording Engine for CAM2📷 on OnePlus Nord 6 (CPH2793).
 * Handles 4K@60fps, 1080p@240/120fps Slow-Mo, Time-Lapse, and external mic routing.
 */
class ProVideoRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var activeVideoUri: Uri? = null

    fun detectExternalMicrophones(): List<AudioDeviceInfo> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    fun prepare(config: VideoRecordingConfig, orientationHintDegrees: Int): Surface {
        release()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = when {
            config.isSlowMotion -> "CAM2_SLOMO"
            config.isTimelapse -> "CAM2_TIMELAPSE"
            else -> "CAM2_VID"
        }
        val fileName = "${prefix}_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/CAM2")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to allocate MediaStore URI for video recording")
        activeVideoUri = uri
        outputPfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: error("Unable to open ParcelFileDescriptor for video recording")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val recordAudio = !config.isTimelapse && !config.isSlowMotion
        val targetSize = config.customSize ?: config.resolution.size
        val targetFps = if (config.isTimelapse || config.isSlowMotion) 30 else config.fps

        recorder.apply {
            if (recordAudio) {
                val audioSource = if (config.preferExternalMic && detectExternalMicrophones().isNotEmpty()) {
                    MediaRecorder.AudioSource.CAMCORDER
                } else {
                    MediaRecorder.AudioSource.MIC
                }
                setAudioSource(audioSource)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputPfd!!.fileDescriptor)

            setVideoEncodingBitRate(config.bitrateBps)
            setVideoFrameRate(targetFps)
            setVideoSize(targetSize.width, targetSize.height)

            val encoder = if (config.isSlowMotion) {
                MediaRecorder.VideoEncoder.H264
            } else if (config.useHevc || config.colorProfile != VideoColorProfile.STANDARD_8BIT) {
                MediaRecorder.VideoEncoder.HEVC
            } else {
                MediaRecorder.VideoEncoder.H264
            }
            setVideoEncoder(encoder)

            if (!config.isSlowMotion &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                config.colorProfile != VideoColorProfile.STANDARD_8BIT
            ) {
                setVideoEncodingProfileLevel(
                    android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                    android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                )
            }

            if (recordAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(config.audioBitrateBps)
                setAudioSamplingRate(config.audioSampleRateHz)
                setAudioChannels(2)
            } else if (config.isTimelapse) {
                setCaptureRate(config.timelapseCaptureRateFps)
            } else if (config.isSlowMotion) {
                setCaptureRate(config.fps.toDouble())
            }

            setOrientationHint(orientationHintDegrees)
            if (recordAudio && config.preferExternalMic) {
                detectExternalMicrophones().firstOrNull()?.let { extMic ->
                    setPreferredDevice(extMic)
                }
            }
            prepare()
        }

        mediaRecorder = recorder
        return recorder.surface
    }

    fun start() {
        mediaRecorder?.start()
    }

    fun stop(): Uri? {
        runCatching { mediaRecorder?.stop() }
        val uri = activeVideoUri
        release()
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        }
        return uri
    }

    fun release() {
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { outputPfd?.close() }
        outputPfd = null
    }
}
