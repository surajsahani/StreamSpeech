# Android Continuous Video Recording Library

## 1. Overview

This Android library provides a robust solution for continuous video recording in the background. It is designed to be easy to integrate and use, offering features like video segmentation, circular buffer storage management to prevent unbounded storage consumption, and clear event callbacks for monitoring the recording lifecycle.

## 2. Features

*   **Continuous Background Recording**: Records video via a foreground service, ensuring operation even when the app is not in the foreground.
*   **Video Segmentation**: Automatically splits recordings into multiple video files based on a configurable maximum segment duration.
*   **Circular Buffer Storage**: Manages storage by automatically deleting the oldest video segments when a configurable maximum storage size is reached.
*   **Configurable Parameters**: Allows customization of:
    *   Storage directory
    *   Maximum storage size
    *   Maximum video segment duration
    *   Video resolution and quality
    *   Audio recording (enable/disable)
    *   Foreground service notification details
*   **Event Callbacks**: Provides a listener interface to receive notifications for events like recording start/stop, segment creation, storage limit reached, and errors.
*   **Simple API**: Easy-to-use interface for starting, stopping, and monitoring recordings.

## 3. Setup

### 3.1. Include the Library

Currently, this library is provided as source files. To use it in your project:

1.  Copy the `continuousrecorderlib` directory (containing `ContinuousRecorderAPI.kt`, `ContinuousRecorderImpl.kt`, and `RecordingService.kt`) into your project's source structure. Ensure the package name `com.example.continuousrecorderlib` is maintained or update imports accordingly.

    *Alternatively, if you've set this up as a separate Gradle module named, for example, `:continuousrecorderlib`, add it to your app's `build.gradle` dependencies:*
    ```gradle
    dependencies {
        implementation project(':continuousrecorderlib')
        // Other dependencies
    }
    ```

2.  Ensure your project includes necessary AndroidX libraries:
    ```gradle
    dependencies {
        implementation "androidx.core:core-ktx:1.9.0" // Or later
        implementation "androidx.appcompat:appcompat:1.6.1" // Or later
        implementation "androidx.localbroadcastmanager:localbroadcastmanager:1.1.0"
    }
    ```

### 3.2. Permissions

Add the following permissions to your app's `AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.your.app.package_name">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

    <!-- Required for Android 13 (API 33) and above for foreground service notifications -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <!-- For saving video files. Needed for API 28 (Android P) and below if not using app-specific directories.
         For API 29+ (Android Q+), prefer app-specific directories (see storageDirectory in RecordingConfig)
         or MediaStore, which may not require this permission. -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
                     android:maxSdkVersion="28" />

    <application ...>
        ...
    </application>
</manifest>
```
**Note on Storage**: For Android 10 (API 29) and above, it's recommended to use app-specific directories (e.g., `getExternalFilesDir()`) for `storageDirectory` in `RecordingConfig`. These directories do not require the `WRITE_EXTERNAL_STORAGE` permission.

### 3.3. Register Service

Declare the `RecordingService` in your app's `AndroidManifest.xml` within the `<application>` tag:

```xml
<application
    ...>
    <service
        android:name="com.example.continuousrecorderlib.RecordingService"
        android:enabled="true"
        android:exported="false" />

    <activity ...>
        ...
    </activity>
</application>
```

## 4. Usage

### 4.1. Getting an Instance

Get a singleton instance of the `ContinuousRecorder`:

```kotlin
import com.example.continuousrecorderlib.ContinuousRecorder

// In your Activity or Application class
val recorder: ContinuousRecorder = ContinuousRecorder.getInstance(applicationContext)
recorder.initialize(this) // Optional: Can be used for pre-initialization if needed
```

### 4.2. `RecordingConfig`

This data class holds all configurations for a recording session.

```kotlin
import com.example.continuousrecorderlib.RecordingConfig
import com.example.continuousrecorderlib.VideoResolution
import com.example.continuousrecorderlib.VideoQuality
import com.example.continuousrecorderlib.NotificationConfig
import java.io.File // Ensure this import is present if using File

// Example:
// Make sure to import android.os.Environment if you use it for getExternalFilesDir
// import android.os.Environment

val storageDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "MyRecordings")
if (!storageDir.exists()) {
    storageDir.mkdirs()
}

val notificationConfig = NotificationConfig(
    channelId = "my_recording_channel",
    channelName = "Background Recording",
    notificationId = 12345, // Must be unique within your app
    title = "Recording Service",
    description = "Video recording in progress...",
    smallIconResId = R.drawable.ic_stat_recording // Replace with your notification icon
)

val recordingConfig = RecordingConfig(
    storageDirectory = storageDir.absolutePath,
    maxStorageSizeMB = 100L, // Optional: Max total size of all segments (e.g., 100MB). Oldest deleted if exceeded.
    maxSegmentDurationSeconds = 60, // Optional: Max duration per segment (e.g., 60 seconds).
    videoResolution = VideoResolution.MEDIUM, // LOW, MEDIUM, HIGH, MAX
    videoQuality = VideoQuality.MEDIUM,       // LOW, MEDIUM, HIGH
    recordAudio = true,                       // true to record audio, false otherwise
    notificationConfig = notificationConfig
)
```

**`RecordingConfig` Fields:**

*   `storageDirectory` (String): Absolute path to the directory where video segments will be saved.
*   `maxStorageSizeMB` (Long?, default: `null`): Optional. Maximum total size in megabytes for all stored video segments. If exceeded, the oldest segments are deleted. If `null`, no size limit is enforced by the circular buffer (besides device storage).
*   `maxSegmentDurationSeconds` (Int?, default: `null`): Optional. Maximum duration in seconds for each video segment. If `null`, recording proceeds as a single segment until stopped or storage limit (if set). If set, new segments are created after this duration.
*   `videoResolution` (`VideoResolution`, default: `VideoResolution.MEDIUM`): Desired video resolution. See [VideoResolution Enum](#7-videoresolution-and-videoquality-enums).
*   `videoQuality` (`VideoQuality`, default: `VideoQuality.MEDIUM`): Desired video quality (can affect bitrate). See [VideoResolution and VideoQuality Enums](#7-videoresolution-and-videoquality-enums).
*   `recordAudio` (Boolean, default: `true`): Set to `true` to include audio in the recording, `false` to record video only.
*   `notificationConfig` (`NotificationConfig`): Configuration for the foreground service notification. See [NotificationConfig](#43-notificationconfig).

### 4.3. `NotificationConfig`

This data class configures the notification displayed for the foreground service.

```kotlin
import com.example.continuousrecorderlib.NotificationConfig

// Example (also shown above):
val notificationConfig = NotificationConfig(
    channelId = "my_recording_channel", // Unique ID for the notification channel
    channelName = "Background Recording", // User-visible name for the channel
    notificationId = 12345,             // Unique integer ID for the notification itself
    title = "Recording Service",        // Title of the notification
    description = "Video recording in progress...", // Text/content of the notification
    smallIconResId = R.drawable.ic_stat_recording // Your drawable resource for the small icon (e.g., in status bar)
)
```

**`NotificationConfig` Fields:**

*   `channelId` (String): A unique ID for the notification channel (required for Android 8.0+).
*   `channelName` (String): The user-visible name for the notification channel (required for Android 8.0+).
*   `notificationId` (Int): A unique integer ID for the notification.
*   `title` (String): The title of the notification.
*   `description` (String): The main text content of the notification.
*   `smallIconResId` (Int): The resource ID of the small icon to be displayed in the status bar and notification shade (e.g., `R.drawable.ic_your_icon`). **Ensure this is a valid drawable resource in your app.**

### 4.4. Implementing `RecordingListener`

Create a class that implements the `RecordingListener` interface to handle recording events:

```kotlin
import com.example.continuousrecorderlib.RecordingListener
import com.example.continuousrecorderlib.ErrorType
import android.util.Log

class MyRecordingListener : RecordingListener {
    private val TAG = "MyRecordingListener"

    override fun onRecordStart() {
        Log.d(TAG, "Recording started.")
        // Update UI, etc.
    }

    override fun onRecordStop(filePaths: List<String>) {
        Log.d(TAG, "Recording stopped. Segments: ${filePaths.joinToString()}")
        // Handle saved video files, update UI.
        // filePaths contains absolute paths to all segments from the session.
    }

    override fun onSegmentCreated(filePath: String) {
        Log.d(TAG, "New segment created: $filePath")
        // A new video segment file is available.
    }

    override fun onStorageReachedLimit(oldestSegmentPath: String) {
        Log.d(TAG, "Storage limit reached. Oldest segment deleted: $oldestSegmentPath")
        // Notify user or handle as needed.
    }

    override fun onError(errorType: ErrorType, message: String) {
        Log.e(TAG, "Recording Error - Type: $errorType, Message: $message")
        // Handle error, update UI.
    }
}
```

**`RecordingListener` Methods:**

*   `onRecordStart()`: Called when the recording session successfully starts.
*   `onRecordStop(filePaths: List<String>)`: Called when the recording session is successfully stopped. `filePaths` contains a list of absolute paths to all video segments recorded during that session.
*   `onSegmentCreated(filePath: String)`: Called when a new video segment is successfully created and saved (due to `maxSegmentDurationSeconds` being reached).
*   `onStorageReachedLimit(oldestSegmentPath: String)`: Called when `maxStorageSizeMB` is exceeded and the oldest video segment is deleted to make space. `oldestSegmentPath` is the path of the deleted segment.
*   `onError(errorType: ErrorType, message: String)`: Called when an error occurs during the recording process.

### 4.5. Starting and Stopping Recording

```kotlin
// Assuming 'recorder' is your ContinuousRecorder instance
// and 'recordingConfig' is your configured RecordingConfig object.

val myListener = MyRecordingListener() // Your implementation of RecordingListener

// To start recording:
// Ensure you have requested necessary permissions before calling startRecording.
// For example:
// if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
//     ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED /* && other perms */) {
//    recorder.startRecording(recordingConfig, myListener)
// } else {
//    // Request permissions using ActivityResultLauncher
// }


// Simplified call (assuming permissions are handled):
recorder.startRecording(recordingConfig, myListener)


// To stop recording:
recorder.stopRecording()
```

### 4.6. Checking Recording Status

```kotlin
if (recorder.isRecording()) {
    // Recording is active
} else {
    // Recorder is idle
}
```

### 4.7. Runtime Permissions

Your application is responsible for requesting the necessary runtime permissions (CAMERA, RECORD_AUDIO, and potentially POST_NOTIFICATIONS on Android 13+, WRITE_EXTERNAL_STORAGE on older APIs if not using app-specific directories) before starting a recording. The library may report a `PERMISSION_ERROR` via the `onError` callback if it encounters permission issues, but proactive checking by the app is crucial for a good user experience.

### 4.8. Releasing the Recorder

When the recorder is no longer needed (e.g., in your Activity's `onDestroy` or when your app is shutting down components that use it), call `release()`:

```kotlin
recorder.release()
```
This helps clean up resources, unregister internal receivers, and nullifies the singleton instance, allowing it to be re-created fresh if `getInstance()` is called again.

## 5. Error Handling

Errors encountered by the library are reported through the `onError(errorType: ErrorType, message: String)` callback in your `RecordingListener`. The `errorType` parameter is an enum value from `ErrorType`, and `message` provides a human-readable description.

## 6. `ErrorType` Enum

*   `STORAGE_ERROR`: Issues related to file system access, writing files, or directory creation.
*   `CAMERA_ERROR`: Problems initializing, accessing, or using the camera.
*   `AUDIO_ERROR`: Issues with the microphone or audio recording setup.
*   `SERVICE_ERROR`: Problems related to the Android background service, or if the service is already busy or fails to start/stop.
*   `PERMISSION_ERROR`: Required permissions (Camera, Audio, etc.) are missing.
*   `CONFIG_ERROR`: Invalid or missing `RecordingConfig` or `NotificationConfig`.
*   `UNKNOWN_ERROR`: An unexpected error occurred.

## 7. `VideoResolution` and `VideoQuality` Enums

### `VideoResolution`
Determines the dimensions of the output video. Actual dimensions may vary slightly based on device capabilities.
*   `LOW`: Lower resolution (e.g., 480p).
*   `MEDIUM`: Medium resolution (e.g., 720p).
*   `HIGH`: Higher resolution (e.g., 1080p).
*   `MAX`: Attempts to use the highest available resolution supported by the device's camera.

### `VideoQuality`
Influences the encoding bitrate and overall visual quality.
*   `LOW`
*   `MEDIUM`
*   `HIGH`

(Note: The current implementation uses these enums as guides for setting video size but does not yet explicitly map `VideoQuality` to specific bitrates. This can be expanded in future versions.)

## 8. Sample App

A sample application is available in the `sampleapp` directory (conceptually, as part of this project structure). This app demonstrates how to integrate and use the `ContinuousRecorder` library, including permission handling, UI updates based on listener events, and basic configuration. It serves as a practical reference.

## 9. Limitations/Known Issues

*   **Camera1 API**: The current version of the library uses the older `android.hardware.Camera` (Camera1) API. This API is deprecated and has limitations on newer Android devices. Future versions should migrate to the `CameraX` or `android.hardware.camera2` (Camera2) API for better compatibility and more advanced features.
*   **Audio/Video Encoder Flexibility**: Encoder types are currently hardcoded (AAC for audio, H264 for video). Configuration options could be added.
*   **Bitrate and Frame Rate Control**: `VideoQuality` currently only guides resolution selection; direct bitrate and frame rate control are not yet implemented but can be added.
*   **Error Granularity**: Some internal errors might be reported under a general `ErrorType`; more specific error types could be added.
*   **Storage Scan on Start**: The circular buffer does not currently scan the storage directory for pre-existing segments from a crashed session on startup. It manages segments created during the active session.
*   **Notification Icon**: The `smallIconResId` in `NotificationConfig` must be a valid drawable resource provided by the consuming application.

---

This README provides a comprehensive guide to using the Android Continuous Video Recording Library. Remember to replace placeholder values like `R.drawable.ic_stat_recording` with your actual application resources.
