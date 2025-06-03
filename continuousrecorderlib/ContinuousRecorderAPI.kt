package com.example.continuousrecorderlib

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Enum for defining video resolution options.
 */
enum class VideoResolution {
    LOW,        // e.g., 480p
    MEDIUM,     // e.g., 720p
    HIGH,       // e.g., 1080p
    MAX         // Highest available on the device
}

/**
 * Enum for defining video quality options.
 */
enum class VideoQuality {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Enum for defining error types that can occur during recording.
 */
enum class ErrorType {
    STORAGE_ERROR,
    CAMERA_ERROR,
    AUDIO_ERROR,
    SERVICE_ERROR,
    PERMISSION_ERROR,
    CONFIG_ERROR,
    UNKNOWN_ERROR
}

/**
 * Data class for configuring notification details for the foreground service.
 */
@Parcelize
data class NotificationConfig(
    val channelId: String,
    val channelName: String,
    val notificationId: Int,
    val title: String,
    val description: String,
    val smallIconResId: Int // Drawable resource ID for the notification icon
) : Parcelable

/**
 * Data class for configuring the recording session.
 */
@Parcelize
data class RecordingConfig(
    val storageDirectory: String,
    val maxStorageSizeMB: Long? = null,
    val maxSegmentDurationSeconds: Int? = null,
    val videoResolution: VideoResolution = VideoResolution.MEDIUM,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val recordAudio: Boolean = true,
    val notificationConfig: NotificationConfig
) : Parcelable

/**
 * Listener interface for receiving recording event callbacks.
 */
interface RecordingListener {
    fun onRecordStart()
    fun onRecordStop(filePaths: List<String>)
    fun onError(errorType: ErrorType, message: String)
    fun onSegmentCreated(filePath: String)
    fun onStorageReachedLimit(oldestSegmentPath: String)
}

/**
 * Main interface for the Continuous Video Recorder.
 */
interface ContinuousRecorder {
    fun initialize(context: Context)
    fun startRecording(config: RecordingConfig, listener: RecordingListener)
    fun stopRecording()
    fun isRecording(): Boolean
    fun release()
}
