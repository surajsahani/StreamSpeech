package com.example.continuousrecorder

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Enum for defining video resolution options.
 * Specific resolution dimensions can be mapped internally by the library.
 */
enum class VideoResolution {
    LOW,        // e.g., 480p
    MEDIUM,     // e.g., 720p
    HIGH,       // e.g., 1080p
    MAX         // Highest available on the device
}

/**
 * Enum for defining video quality options.
 * This can map to encoding bitrate or other quality parameters.
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
    STORAGE_ERROR,      // Issues with file system, out of space (before circular buffer kicks in for new sessions)
    CAMERA_ERROR,       // Camera hardware or access issues
    AUDIO_ERROR,        // Microphone access or configuration issues
    SERVICE_ERROR,      // Background service issues
    PERMISSION_ERROR,   // Missing required permissions
    CONFIG_ERROR,       // Invalid RecordingConfig
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
    val maxStorageSizeMB: Long? = null, // Optional: if null, no overall storage limit beyond device capacity (circular buffer per segment still applies if maxSegmentDurationSeconds is set)
    val maxSegmentDurationSeconds: Int? = null, // Optional: if null, records as one continuous file until stop or storage limit. If set, creates segments.
    val videoResolution: VideoResolution = VideoResolution.MEDIUM,
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val recordAudio: Boolean = true,
    val notificationConfig: NotificationConfig
) : Parcelable

/**
 * Listener interface for receiving recording event callbacks.
 */
interface RecordingListener {
    /**
     * Called when recording successfully starts.
     */
    fun onRecordStart()

    /**
     * Called when recording successfully stops.
     * @param filePaths List of paths to the video segment(s) created during the session.
     *                  If not segmented, this will contain one file path.
     */
    fun onRecordStop(filePaths: List<String>)

    /**
     * Called when an error occurs during recording.
     * @param errorType The type of error.
     * @param message A descriptive message for the error.
     */
    fun onError(errorType: ErrorType, message: String)

    /**
     * Called when a new video segment is successfully created and saved.
     * This is only relevant if maxSegmentDurationSeconds is set in RecordingConfig.
     * @param filePath The path to the newly created video segment.
     */
    fun onSegmentCreated(filePath: String)

    /**
     * Called when the storage limit is reached and the circular buffer mechanism
     * is about to delete the oldest segment to make space for a new one.
     * This is relevant if maxStorageSizeMB or maxSegmentDurationSeconds (leading to multiple segments) is set.
     * @param oldestSegmentPath The path to the oldest segment that will be deleted.
     */
    fun onStorageReachedLimit(oldestSegmentPath: String)
}

/**
 * Main interface for the Continuous Video Recorder.
 */
interface ContinuousRecorder {
    /**
     * Initializes the recorder. Must be called before other methods.
     * @param context Application or Activity context.
     */
    fun initialize(context: Context)

    /**
     * Starts a new recording session with the given configuration.
     * @param config The configuration for this recording session.
     * @param listener The listener to receive recording events.
     * @throws IllegalStateException if already recording or not initialized.
     * @throws SecurityException if necessary permissions are not granted.
     */
    fun startRecording(config: RecordingConfig, listener: RecordingListener)

    /**
     * Stops the current recording session.
     * If no recording is in progress, this method does nothing.
     * The listener's onRecordStop will be invoked with the paths to the recorded files.
     */
    fun stopRecording()

    /**
     * Checks if a recording session is currently active.
     * @return True if recording, false otherwise.
     */
    fun isRecording(): Boolean

    /**
     * Releases any resources held by the recorder.
     * Should be called when the recorder is no longer needed.
     */
    fun release()
}
