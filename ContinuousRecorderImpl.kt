package com.example.continuousrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ContinuousRecorderImpl private constructor(private val applicationContext: Context) : ContinuousRecorder {

    private var currentListener: RecordingListener? = null
    private var recordingActive: Boolean = false
    private val localBroadcastManager: LocalBroadcastManager = LocalBroadcastManager.getInstance(applicationContext)
    private var serviceEventReceiver: BroadcastReceiver? = null


    companion object {
        @Volatile
        private var INSTANCE: ContinuousRecorderImpl? = null
        private const val TAG = "ContinuousRecorderImpl"

        fun getInstance(context: Context): ContinuousRecorderImpl =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContinuousRecorderImpl(context.applicationContext).also { INSTANCE = it }
            }
    }

    override fun initialize(context: Context) {
        Log.d(TAG, "ContinuousRecorder initialized.")
        // No receiver registration here anymore, it's per recording session
    }

    private fun registerReceiver() {
        if (serviceEventReceiver != null) return // Already registered

        serviceEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    RecordingService.BROADCAST_RECORD_STARTED -> {
                        recordingActive = true
                        currentListener?.onRecordStart()
                        Log.d(TAG, "Event: Record Started")
                    }
                    RecordingService.BROADCAST_RECORD_STOPPED -> {
                        recordingActive = false
                        val filePaths = intent.getStringArrayListExtra(RecordingService.EXTRA_FILE_PATH) ?: emptyList<String>()
                        currentListener?.onRecordStop(filePaths)
                        Log.d(TAG, "Event: Record Stopped. Files: $filePaths")
                        unregisterReceiver() // Unregister after recording stops
                    }
                    RecordingService.BROADCAST_RECORD_ERROR -> {
                        recordingActive = false
                        val errorTypeName = intent.getStringExtra(RecordingService.EXTRA_ERROR_TYPE)
                        val errorType = errorTypeName?.let { runCatching { ErrorType.valueOf(it) }.getOrNull() } ?: ErrorType.UNKNOWN_ERROR
                        val message = intent.getStringExtra(RecordingService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                        currentListener?.onError(errorType, message)
                        Log.e(TAG, "Event: Record Error - Type: $errorType, Message: $message")
                        unregisterReceiver() // Unregister on error
                    }
                    RecordingService.BROADCAST_SEGMENT_CREATED -> {
                        val filePath = intent.getStringExtra(RecordingService.EXTRA_FILE_PATH)
                        if (filePath != null) {
                            currentListener?.onSegmentCreated(filePath)
                            Log.d(TAG, "Event: Segment Created - Path: $filePath")
                        } else {
                            Log.w(TAG, "Segment created broadcast received without file path.")
                        }
                    }
                     RecordingService.BROADCAST_STORAGE_REACHED_LIMIT -> {
                        val oldestSegmentPath = intent.getStringExtra(RecordingService.EXTRA_OLDEST_SEGMENT_PATH)
                        if (oldestSegmentPath != null) {
                            currentListener?.onStorageReachedLimit(oldestSegmentPath)
                            Log.d(TAG, "Event: Storage Limit Reached - Oldest Segment: $oldestSegmentPath")
                        } else {
                             Log.w(TAG, "Storage limit reached broadcast received without oldest segment path.")
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(RecordingService.BROADCAST_RECORD_STARTED)
            addAction(RecordingService.BROADCAST_RECORD_STOPPED)
            addAction(RecordingService.BROADCAST_RECORD_ERROR)
            addAction(RecordingService.BROADCAST_SEGMENT_CREATED)
            addAction(RecordingService.BROADCAST_STORAGE_REACHED_LIMIT)
        }
        localBroadcastManager.registerReceiver(serviceEventReceiver!!, intentFilter)
        Log.d(TAG, "Service event receiver registered.")
    }

    private fun unregisterReceiver() {
        serviceEventReceiver?.let {
            localBroadcastManager.unregisterReceiver(it)
            Log.d(TAG, "Service event receiver unregistered.")
        }
        serviceEventReceiver = null
    }


    override fun startRecording(config: RecordingConfig, listener: RecordingListener) {
        if (recordingActive) {
            Log.w(TAG, "Recording is already believed to be in progress by client.")
            // Potentially allow re-start if service died, or rely on user to stop first.
            // For now, let's report an error to the new listener and not proceed.
            listener.onError(ErrorType.SERVICE_ERROR, "Another recording session might be active or client state is stale.")
            return
        }

        if (!hasRequiredPermissions()) {
            listener.onError(ErrorType.PERMISSION_ERROR, "Required permissions not granted.")
            Log.e(TAG, "Missing essential permissions to start recording.")
            return
        }

        this.currentListener = listener
        registerReceiver() // Register receiver for this recording session

        Log.d(TAG, "Attempting to start recording with config: $config")

        val serviceIntent = Intent(applicationContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START_RECORDING
            putExtra(RecordingService.EXTRA_RECORDING_CONFIG, config)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
        // recordingActive state will be updated by broadcast from the service.
    }

    override fun stopRecording() {
        Log.d(TAG, "Attempting to stop recording (sending intent to service).")
        // We don't unregister receiver here immediately.
        // It will be unregistered when BROADCAST_RECORD_STOPPED or BROADCAST_RECORD_ERROR is received.
        // This ensures that final events from the service are captured.
        // If the service is already stopped or fails to send a broadcast,
        // the receiver might remain registered if not handled carefully in release().
        val serviceIntent = Intent(applicationContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        applicationContext.startService(serviceIntent)
        // recordingActive state will be updated by broadcast.
    }

    override fun isRecording(): Boolean {
        return recordingActive // This flag is now controlled by broadcasts from the service
    }

    override fun release() {
        Log.d(TAG, "Releasing ContinuousRecorderImpl.")
        if (recordingActive) { // If client thinks it's recording, attempt to stop service
            Log.w(TAG, "Releasing while recording was active. Attempting to stop service.")
            stopRecording() // This will send stop intent, receiver should handle unregistration.
        } else {
            // If not recording, ensure receiver is unregistered if it somehow wasn't.
            unregisterReceiver()
        }
        currentListener = null
        INSTANCE = null // Allow GC and re-creation of singleton if needed
        Log.d(TAG, "ContinuousRecorder released and instance nulled.")
    }

    // Direct update methods are removed as state is now driven by broadcasts.
    // The reportError, updateRecordingState, etc. methods are effectively replaced by the BroadcastReceiver logic.

    private fun hasRequiredPermissions(): Boolean {
        // TODO: Implement actual permission checking logic
        Log.w(TAG, "Permission checking is not fully implemented yet.")
        return true
    }
}
