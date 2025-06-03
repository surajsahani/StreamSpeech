package com.example.sampleapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.continuousrecorderlib.ContinuousRecorder
import com.example.continuousrecorderlib.ErrorType
import com.example.continuousrecorderlib.NotificationConfig
import com.example.continuousrecorderlib.RecordingConfig
import com.example.continuousrecorderlib.RecordingListener
import com.example.continuousrecorderlib.VideoQuality
import com.example.continuousrecorderlib.VideoResolution
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), RecordingListener {

    private lateinit var buttonStartRecording: Button
    private lateinit var buttonStopRecording: Button
    private lateinit var textViewStatus: TextView
    private lateinit var textViewEventLog: TextView

    private lateinit var continuousRecorder: ContinuousRecorder

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // WRITE_EXTERNAL_STORAGE for API 28 and below
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // POST_NOTIFICATIONS for API 33+
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                showToast("All permissions granted. You can start recording.")
                // Automatically try to start if permissions were granted now and user tried to start
            } else {
                showToast("Some permissions were denied. Recording cannot start.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonStartRecording = findViewById(R.id.buttonStartRecording)
        buttonStopRecording = findViewById(R.id.buttonStopRecording)
        textViewStatus = findViewById(R.id.textViewStatus)
        textViewEventLog = findViewById(R.id.textViewEventLog)

        // Get library instance
        continuousRecorder = ContinuousRecorder.getInstance(applicationContext)
        continuousRecorder.initialize(this)


        buttonStartRecording.setOnClickListener {
            if (checkAndRequestPermissions()) {
                startLibraryRecording()
            }
        }

        buttonStopRecording.setOnClickListener {
            stopLibraryRecording()
        }

        updateUI(continuousRecorder.isRecording())
    }

    private fun checkAndRequestPermissions(): Boolean {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isEmpty()) {
            true
        } else {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            false
        }
    }

    private fun startLibraryRecording() {
        if (continuousRecorder.isRecording()) {
            showToast("Already recording.")
            return
        }

        val storageDir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ContinuousRecordings")
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        // Simple Notification Config
        val notificationConfig = NotificationConfig(
            channelId = "recording_channel",
            channelName = "Recording Notifications",
            notificationId = 101,
            title = "Continuous Recording",
            description = "Recording in progress...",
            smallIconResId = R.drawable.ic_launcher_foreground // Replace with actual icon
        )

        // Simple Recording Config
        val recordingConfig = RecordingConfig(
            storageDirectory = storageDir.absolutePath,
            maxStorageSizeMB = 5, // Small for testing circular buffer
            maxSegmentDurationSeconds = 15, // Segments every 15 seconds
            videoResolution = VideoResolution.MEDIUM,
            videoQuality = VideoQuality.MEDIUM,
            recordAudio = true,
            notificationConfig = notificationConfig
        )

        logEvent("Attempting to start recording...")
        continuousRecorder.startRecording(recordingConfig, this)
        // UI will be updated by callbacks
    }

    private fun stopLibraryRecording() {
        if (!continuousRecorder.isRecording()) {
            showToast("Not currently recording.")
            return
        }
        logEvent("Attempting to stop recording...")
        continuousRecorder.stopRecording()
        // UI will be updated by callbacks
    }

    private fun updateUI(isRecording: Boolean) {
        buttonStartRecording.isEnabled = !isRecording
        buttonStopRecording.isEnabled = isRecording
        textViewStatus.text = if (isRecording) "Status: Recording" else "Status: Idle"
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLog = textViewEventLog.text.toString()
        val newLog = "$timestamp: $message\n$currentLog"
        // Limit log length to avoid excessive memory usage
        textViewEventLog.text = if (newLog.length > 5000) newLog.substring(0, 5000) + "\n..." else newLog
        Log.d("MainActivityCallback", message) // Also log to Logcat
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // RecordingListener Callbacks
    override fun onRecordStart() {
        runOnUiThread {
            logEvent("EVENT: Recording Started")
            updateUI(true)
        }
    }

    override fun onRecordStop(filePaths: List<String>) {
        runOnUiThread {
            logEvent("EVENT: Recording Stopped. Segments saved: ${filePaths.joinToString()}")
            updateUI(false)
        }
    }

    override fun onError(errorType: ErrorType, message: String) {
        runOnUiThread {
            logEvent("EVENT: Error - Type: $errorType, Message: $message")
            updateUI(false) // Assuming error stops recording
        }
    }

    override fun onSegmentCreated(filePath: String) {
        runOnUiThread {
            logEvent("EVENT: New Segment Created - $filePath")
        }
    }

    override fun onStorageReachedLimit(oldestSegmentPath: String) {
        runOnUiThread {
            logEvent("EVENT: Storage Limit Reached - Deleted: $oldestSegmentPath")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release the recorder if it's not null and you want to ensure cleanup.
        // The current library implementation has a getInstance that might be an issue if not truly singleton
        // or if release() nulls the static instance.
        // For this sample, if recording is active, let it continue based on service lifecycle.
        // If you need to explicitly stop and release:
        // if (continuousRecorder.isRecording()) {
        //     continuousRecorder.stopRecording()
        // }
        // continuousRecorder.release() // This might null the singleton instance.
        logEvent("MainActivity onDestroy.")
    }
}

// Placeholder for R.drawable.ic_launcher_foreground
// In a real app, this would be in your res/drawable folder.
// For the purpose of this subtask, we assume such a resource exists.
// object R { object drawable { const val ic_launcher_foreground = 0 } }
// For the sample app to compile, a proper icon resource would be needed.
// The subtask runner might not have this, so I'll use a system default if possible,
// but the generated code reflects typical usage.
// For now, I'll use android.R.drawable.stat_sys_headset as a placeholder for smallIconResId
// In startLibraryRecording(): smallIconResId = android.R.drawable.ic_notification_overlay
// Actually, I'll just use a placeholder `0` and assume the build system might warn but proceed,
// or that a default icon is used. In a real build, this must be a valid drawable.
// The code generated uses R.drawable.ic_launcher_foreground, which is standard.
// I'll add a dummy R.java file for the sample app if it helps the tool understand.

/**
 * Dummy R class to allow compilation of `R.drawable.ic_launcher_foreground`
 * In a real Android project, this is generated automatically.
 */
// package com.example.sampleapp;
// public final class R {
//    public static final class drawable {
//        public static final int ic_launcher_foreground = 0x0; // Placeholder value
//    }
//     public static final class layout {
//         public static final int activity_main = 0x0; // Placeholder value
//     }
//      public static final class id {
//         public static final int buttonStartRecording = 0x0;
//         public static final int buttonStopRecording = 0x0;
//         public static final int textViewStatus = 0x0;
//         public static final int textViewEventsHeader = 0x0;
//         public static final int scrollViewEvents = 0x0;
//         public static final int textViewEventLog = 0x0;
//      }
//      public static final class string {
//         public static final int app_name = 0x0;
//      }
//      public static final class style {
//         public static final int Theme_SampleApp = 0x0;
//      }
//      public static final class mipmap {
//         public static final int ic_launcher = 0x0;
//         public static final int ic_launcher_round = 0x0;
//      }
//      public static final class xml {
//         public static final int backup_rules = 0x0;
//         public static final int data_extraction_rules = 0x0;
//      }
// }
