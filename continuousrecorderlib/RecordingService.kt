package com.example.continuousrecorderlib // Updated package name

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.Camera // Using Camera 1 API
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

class RecordingService : Service() {

    companion object {
        // Intent actions for service control
        const val ACTION_START_RECORDING = "com.example.continuousrecorderlib.ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.continuousrecorderlib.ACTION_STOP_RECORDING"
        const val EXTRA_RECORDING_CONFIG = "com.example.continuousrecorderlib.EXTRA_RECORDING_CONFIG"

        // Broadcast actions for event callbacks
        const val BROADCAST_RECORD_STARTED = "com.example.continuousrecorderlib.BROADCAST_RECORD_STARTED"
        const val BROADCAST_RECORD_STOPPED = "com.example.continuousrecorderlib.BROADCAST_RECORD_STOPPED"
        const val BROADCAST_RECORD_ERROR = "com.example.continuousrecorderlib.BROADCAST_RECORD_ERROR"
        const val BROADCAST_SEGMENT_CREATED = "com.example.continuousrecorderlib.BROADCAST_SEGMENT_CREATED"
        const val BROADCAST_STORAGE_REACHED_LIMIT = "com.example.continuousrecorderlib.BROADCAST_STORAGE_REACHED_LIMIT"


        // Extras for broadcast intents
        const val EXTRA_FILE_PATH = "com.example.continuousrecorderlib.EXTRA_FILE_PATH"
        const val EXTRA_ERROR_TYPE = "com.example.continuousrecorderlib.EXTRA_ERROR_TYPE"
        const val EXTRA_ERROR_MESSAGE = "com.example.continuousrecorderlib.EXTRA_ERROR_MESSAGE"
        const val EXTRA_OLDEST_SEGMENT_PATH = "com.example.continuousrecorderlib.EXTRA_OLDEST_SEGMENT_PATH"


        private const val TAG = "RecordingService"
    }

    private var currentConfig: RecordingConfig? = null
    private var isSessionRecording = false // Overall session recording state
    private var isSegmentCurrentlyRecording = false // Current segment recording state
    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var currentSegmentFilePath: String? = null

    private val recordedSegments = LinkedList<File>()
    private var totalSegmentsSize: Long = 0L

    private var notificationManager: NotificationManager? = null
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private val segmentationHandler = Handler(Looper.getMainLooper())


    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        Log.d(TAG, "RecordingService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "RecordingService onStartCommand, action: $action")

        when (action) {
            ACTION_START_RECORDING -> {
                val newConfig = intent.getParcelableExtra<RecordingConfig>(EXTRA_RECORDING_CONFIG)
                if (newConfig == null) {
                    Log.e(TAG, "RecordingConfig is missing in start intent.")
                    sendErrorBroadcast(ErrorType.CONFIG_ERROR, "RecordingConfig missing.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentConfig = newConfig

                if (isSessionRecording) {
                    Log.w(TAG, "Session already recording. Ignoring start command.")
                    sendErrorBroadcast(ErrorType.SERVICE_ERROR, "Service already recording. Stop first to apply new config.")
                    return START_STICKY
                }

                isSessionRecording = true
                recordedSegments.clear()
                totalSegmentsSize = 0L

                startForegroundServiceWithNotification(currentConfig!!.notificationConfig, "Recording starting...")
                if (startNewSegment()) {
                    updateNotificationContent("Recording in progress...")
                    sendStartedBroadcast()
                } else {
                    isSessionRecording = false
                    stopForeground(true)
                    stopSelf()
                }
            }
            ACTION_STOP_RECORDING -> {
                stopCurrentSegment(isStoppingSession = true)
                isSessionRecording = false
                segmentationHandler.removeCallbacksAndMessages(null)

                Log.d(TAG, "Recording session stopped by intent. Total segments: ${recordedSegments.size}, Total size: $totalSegmentsSize bytes")
                sendStoppedBroadcast(ArrayList(recordedSegments.map { it.absolutePath }))

                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun sendStartedBroadcast() {
        val intent = Intent(BROADCAST_RECORD_STARTED)
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast: RECORD_STARTED")
    }

    private fun sendStoppedBroadcast(finalFilePaths: ArrayList<String>) {
        val intent = Intent(BROADCAST_RECORD_STOPPED).apply {
            putStringArrayListExtra(EXTRA_FILE_PATH, finalFilePaths)
        }
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast: RECORD_STOPPED with paths: $finalFilePaths")
    }

    private fun sendErrorBroadcast(errorType: ErrorType, message: String) {
        val intent = Intent(BROADCAST_RECORD_ERROR).apply {
            putExtra(EXTRA_ERROR_TYPE, errorType.name)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        localBroadcastManager.sendBroadcast(intent)
        Log.e(TAG, "Sent broadcast: RECORD_ERROR - Type: $errorType, Message: $message")
    }

    private fun sendSegmentCreatedBroadcast(filePath: String) {
        val intent = Intent(BROADCAST_SEGMENT_CREATED).apply {
            putExtra(EXTRA_FILE_PATH, filePath)
        }
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast: SEGMENT_CREATED for path: $filePath")
    }

    private fun sendStorageLimitReachedBroadcast(deletedSegmentPath: String) {
        val intent = Intent(BROADCAST_STORAGE_REACHED_LIMIT).apply {
            putExtra(EXTRA_OLDEST_SEGMENT_PATH, deletedSegmentPath)
        }
        localBroadcastManager.sendBroadcast(intent)
        Log.d(TAG, "Sent broadcast: STORAGE_LIMIT_REACHED, deleted: $deletedSegmentPath")
    }


    private fun startForegroundServiceWithNotification(config: NotificationConfig, initialContent: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                config.channelId,
                config.channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, config.channelId)
            .setContentTitle(config.title)
            .setContentText(initialContent)
            .setSmallIcon(config.smallIconResId)
            .setOngoing(true)
            .build()

        try {
            startForeground(config.notificationId, notification)
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
            sendErrorBroadcast(ErrorType.SERVICE_ERROR, "Failed to start foreground service: ${e.message}")
        }
    }

    private fun updateNotificationContent(contentText: String) {
        currentConfig?.notificationConfig?.let { config ->
            val notification: Notification = NotificationCompat.Builder(this, config.channelId)
                .setContentTitle(config.title)
                .setContentText(contentText)
                .setSmallIcon(config.smallIconResId)
                .setOngoing(true)
                .build()
            notificationManager?.notify(config.notificationId, notification)
        }
    }

    private fun initializeCamera(): Boolean {
        if (camera != null) return true

        try {
            var cameraId = -1
            val numberOfCameras = Camera.getNumberOfCameras()
            for (i in 0 until numberOfCameras) {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i
                    break
                }
            }
            if (cameraId == -1 && numberOfCameras > 0) cameraId = 0

            if (cameraId != -1) {
                camera = Camera.open(cameraId)
                camera?.unlock()
                surfaceTexture = SurfaceTexture(10)
                camera?.setPreviewTexture(surfaceTexture) // Required for MediaRecorder with Camera1
                camera?.startPreview() // Preview must be active
                Log.d(TAG, "Camera initialized and preview started.")
                return true
            } else {
                Log.e(TAG, "No camera available on this device.")
                sendErrorBroadcast(ErrorType.CAMERA_ERROR, "No camera available.")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera: ${e.message}", e)
            sendErrorBroadcast(ErrorType.CAMERA_ERROR, "Failed to initialize camera: ${e.message}")
            releaseCameraAndSurface()
            return false
        }
    }

    private fun startNewSegment(): Boolean {
        if (currentConfig == null) {
            sendErrorBroadcast(ErrorType.CONFIG_ERROR, "Recording config is null, cannot start segment.")
            return false
        }
        val config = currentConfig!!

        if (!initializeCamera()) {
            return false
        }

        try {
            mediaRecorder = MediaRecorder()
            mediaRecorder?.setCamera(camera)

            if (config.recordAudio) {
                mediaRecorder?.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            mediaRecorder?.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            mediaRecorder?.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val outputDir = File(config.storageDirectory)
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                 Log.e(TAG, "Failed to create storage directory: ${config.storageDirectory}")
                 sendErrorBroadcast(ErrorType.STORAGE_ERROR, "Failed to create storage directory.")
                 return false
            }
            currentSegmentFilePath = "${config.storageDirectory}/VID_SEGMENT_$timeStamp.mp4"
            mediaRecorder?.setOutputFile(currentSegmentFilePath)

            if (config.recordAudio) {
                mediaRecorder?.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            mediaRecorder?.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            val camParams = camera?.parameters
            val (width, height) = when (config.videoResolution) {
                VideoResolution.LOW -> 640 to 480
                VideoResolution.MEDIUM -> 1280 to 720
                VideoResolution.HIGH -> 1920 to 1080
                VideoResolution.MAX -> camParams?.supportedPreviewSizes?.maxByOrNull { it.width * it.height }?.let { it.width to it.height } ?: (1920 to 1080)
            }
            mediaRecorder?.setVideoSize(width, height)
            // TODO: Set frame rate and encoding bit rate based on VideoQuality from config

            mediaRecorder?.prepare()
            mediaRecorder?.start()

            isSegmentCurrentlyRecording = true
            Log.d(TAG, "New segment started. Output file: $currentSegmentFilePath")

            config.maxSegmentDurationSeconds?.takeIf { it > 0 }?.let { duration ->
                segmentationHandler.postDelayed({
                    if (isSessionRecording && isSegmentCurrentlyRecording) {
                       Log.d(TAG, "Max segment duration reached. Creating new segment.")
                       stopCurrentSegment(isStoppingSession = false)
                       if(isSessionRecording) {
                            startNewSegment()
                       }
                    }
                }, duration * 1000L)
            }
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting new segment: ${e.message}", e)
            sendErrorBroadcast(ErrorType.SERVICE_ERROR, "Error starting segment: ${e.message}")
            releaseMediaRecorderOnly()
            isSegmentCurrentlyRecording = false
            // If starting the very first segment fails, the whole session might need to stop.
            // This is currently handled by the caller of startNewSegment in onStartCommand.
            return false
        }
    }

    private fun releaseMediaRecorderOnly() {
        try {
            mediaRecorder?.apply {
                if (isSegmentCurrentlyRecording) {
                    try { stop() } catch (e: RuntimeException) { Log.e(TAG, "MediaRecorder stop failed: ${e.message}") }
                }
                try { reset() } catch (e: RuntimeException) { Log.e(TAG, "MediaRecorder reset failed: ${e.message}") }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder instance: ${e.message}", e)
        }
        mediaRecorder = null
    }

    private fun releaseCameraAndSurface() {
        try {
            camera?.apply {
                stopPreview()
                release()
            }
            surfaceTexture?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Camera/SurfaceTexture: ${e.message}", e)
        }
        camera = null
        surfaceTexture = null
    }

    private fun stopCurrentSegment(isStoppingSession: Boolean) {
        if (!isSegmentCurrentlyRecording) {
            Log.d(TAG, "No active segment recording to stop.")
            if (isStoppingSession) releaseCameraAndSurface()
            return
        }
        Log.d(TAG, "Stopping current segment (isStoppingSession: $isStoppingSession)...")

        releaseMediaRecorderOnly()
        isSegmentCurrentlyRecording = false

        currentSegmentFilePath?.let { filePath ->
            val segmentFile = File(filePath)
            if (segmentFile.exists() && segmentFile.length() > 0) {
                Log.d(TAG, "Segment saved at: $filePath, size: ${segmentFile.length()} bytes")
                recordedSegments.add(segmentFile)
                totalSegmentsSize += segmentFile.length()
                sendSegmentCreatedBroadcast(filePath)
                checkStorageLimit()
            } else {
                Log.w(TAG, "Segment file does not exist or is empty: $filePath")
            }
        }
        currentSegmentFilePath = null

        if (isStoppingSession) {
            releaseCameraAndSurface()
        }
    }

    private fun checkStorageLimit() {
        currentConfig?.maxStorageSizeMB?.takeIf { it > 0 }?.let { limitMB ->
            val limitBytes = limitMB * 1024 * 1024
            Log.d(TAG, "Checking storage limit. Current size: $totalSegmentsSize bytes, Limit: $limitBytes bytes")
            while (totalSegmentsSize > limitBytes && recordedSegments.isNotEmpty()) {
                val oldestSegment = recordedSegments.poll()
                if (oldestSegment != null) {
                    val fileSize = oldestSegment.length()
                    val oldestPath = oldestSegment.absolutePath
                    if (oldestSegment.delete()) {
                        totalSegmentsSize -= fileSize
                        Log.i(TAG, "Storage limit exceeded. Deleted oldest segment: $oldestPath, size: $fileSize bytes. New total size: $totalSegmentsSize bytes")
                        sendStorageLimitReachedBroadcast(oldestPath)
                    } else {
                        Log.e(TAG, "Failed to delete oldest segment: $oldestPath")
                        recordedSegments.addFirst(oldestSegment)
                        break
                    }
                } else {
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "RecordingService onDestroy. Current session recording: $isSessionRecording, segment recording: $isSegmentCurrentlyRecording")
        segmentationHandler.removeCallbacksAndMessages(null)
        if (isSegmentCurrentlyRecording) {
            Log.w(TAG, "Service destroyed while a segment was recording. Attempting to finalize.")
            stopCurrentSegment(isStoppingSession = true)
        } else if (isSessionRecording) {
             releaseCameraAndSurface()
        }

        // If the session was marked as active but is now being destroyed,
        // ensure a final STOPPED broadcast is sent if segments were recorded.
        if(isSessionRecording && recordedSegments.isNotEmpty()) {
            sendStoppedBroadcast(ArrayList(recordedSegments.map { it.absolutePath }))
        } else if (isSessionRecording) { // Session was active but no segments (e.g. error early on)
            sendStoppedBroadcast(ArrayList()) // Send stop with empty list
        }

        isSessionRecording = false
        isSegmentCurrentlyRecording = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
