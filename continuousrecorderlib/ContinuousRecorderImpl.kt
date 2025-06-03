package com.example.continuousrecorderlib

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
        private var INSTANCE: ContinuousRecorder? = null // Changed to interface type
        private const val TAG = "ContinuousRecorderImpl"

        // Changed to return interface type
        fun getInstance(context: Context): ContinuousRecorder =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ContinuousRecorderImpl(context.applicationContext).also { INSTANCE = it }
            }
    }

    override fun initialize(context: Context) {
        Log.d(TAG, "ContinuousRecorder initialized with context: ${context.packageName}")
    }

    private fun registerReceiver() {
        if (serviceEventReceiver != null) {
            Log.w(TAG, "Receiver already registered.")
            return
        }

        serviceEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "Received broadcast: ${intent?.action}")
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
                        unregisterReceiver()
                    }
                    RecordingService.BROADCAST_RECORD_ERROR -> {
                        recordingActive = false
                        val errorTypeName = intent.getStringExtra(RecordingService.EXTRA_ERROR_TYPE)
                        val errorType = errorTypeName?.let { runCatching { ErrorType.valueOf(it) }.getOrNull() } ?: ErrorType.UNKNOWN_ERROR
                        val message = intent.getStringExtra(RecordingService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                        currentListener?.onError(errorType, message)
                        Log.e(TAG, "Event: Record Error - Type: $errorType, Message: $message")
                        unregisterReceiver()
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
            try {
                localBroadcastManager.unregisterReceiver(it)
                Log.d(TAG, "Service event receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered or already unregistered: $e")
            }
        }
        serviceEventReceiver = null
    }

    override fun startRecording(config: RecordingConfig, listener: RecordingListener) {
        if (recordingActive) {
            Log.w(TAG, "startRecording called but recording is already active.")
            listener.onError(ErrorType.SERVICE_ERROR, "Recording session is already active.")
            return
        }

        // TODO: Add actual permission checks here before proceeding.
        // For now, assuming permissions are granted by the sample app.
        // if (!hasRequiredPermissions(applicationContext)) {
        //     listener.onError(ErrorType.PERMISSION_ERROR, "Required permissions not granted.")
        //     return
        // }

        this.currentListener = listener
        registerReceiver()

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
    }

    override fun stopRecording() {
        Log.d(TAG, "Attempting to stop recording (sending intent to service).")
        // Receiver is unregistered when STOPPED or ERROR is received.
        val serviceIntent = Intent(applicationContext, RecordingService::class.java).apply {
            action = RecordingService.ACTION_STOP_RECORDING
        }
        applicationContext.startService(serviceIntent)
    }

    override fun isRecording(): Boolean {
        return recordingActive
    }

    override fun release() {
        Log.d(TAG, "Releasing ContinuousRecorderImpl.")
        if (recordingActive) {
            Log.w(TAG, "Releasing while recording was active. Attempting to stop service.")
            stopRecording() // Service should send stop/error, which will unregister receiver.
        } else {
            unregisterReceiver() // Ensure unregistration if not recording.
        }
        currentListener = null
        // Making INSTANCE null here makes it behave more like a true singleton that can be fully reset,
        // useful for testing or specific app lifecycles.
        // However, typical singletons retain the instance for the app's lifetime.
        // For this library's purpose, allowing a full release might be cleaner.
        synchronized(ContinuousRecorderImpl::class.java) { // Synchronize access to INSTANCE
            INSTANCE = null
        }
        Log.d(TAG, "ContinuousRecorder released and instance nulled.")
    }

    // TODO: Consider adding a method to check required permissions (CAMERA, RECORD_AUDIO, etc.)
    // private fun hasRequiredPermissions(context: Context): Boolean { ... }
}
