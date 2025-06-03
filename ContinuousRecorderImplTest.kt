package com.example.continuousrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// Using Robolectric to allow LocalBroadcastManager.getInstance() and for Context
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P]) // Configure for a specific SDK if needed
class ContinuousRecorderImplTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockListener: RecordingListener

    @Mock
    private lateinit var mockLocalBroadcastManager: LocalBroadcastManager

    @Captor
    private lateinit var intentCaptor: ArgumentCaptor<Intent>

    @Captor
    private lateinit var broadcastReceiverCaptor: ArgumentCaptor<BroadcastReceiver>

    @Captor
    private lateinit var intentFilterCaptor: ArgumentCaptor<IntentFilter>

    private lateinit var continuousRecorder: ContinuousRecorderImpl
    private lateinit var applicationContext: Context


    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // Use Robolectric's application context for LocalBroadcastManager
        applicationContext = androidx.test.core.app.ApplicationProvider.getApplicationContext()

        // Mock LocalBroadcastManager.getInstance to return our mock
        // This is tricky because getInstance is static. Robolectric handles this better.
        // For a pure Mockito test, we might need to inject LocalBroadcastManager.
        // With Robolectric, we can use its shadow.
        val shadowLocalBroadcastManager = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))

        // Instead of mocking, we'll use Robolectric's real LocalBroadcastManager and spy on it,
        // or test its effects directly. For this test, we'll spy on register/unregister.
        // For sending broadcasts to the receiver, we'll get the real LBM.

        // Re-instantiate ContinuousRecorderImpl before each test to ensure a clean state
        // and to use the fresh applicationContext for LocalBroadcastManager.
        ContinuousRecorderImpl.getInstance(applicationContext).release() // Release any previous instance
        continuousRecorder = ContinuousRecorderImpl.getInstance(applicationContext)
        continuousRecorder.initialize(applicationContext) // Initialize with the test context

        // We need to spy on the LocalBroadcastManager instance that ContinuousRecorderImpl uses.
        // Since ContinuousRecorderImpl gets its own instance, we'll also get our own for sending.
        // For verifying register/unregister, we'd ideally inject a mock LBM or use a test constructor.
        // As a workaround for this setup, we'll assume the getInstance() in ContinuousRecorderImpl
        // gets the same one we can get here for verification via Robolectric's shadows.
    }

    @After
    fun tearDown() {
        // Ensure looper is drained
        ShadowLooper.idleMainLooper()
        continuousRecorder.release()
    }

    private fun getTestNotificationConfig(): NotificationConfig {
        return NotificationConfig("channel", "Channel Name", 1, "Title", "Desc", 0)
    }

    private fun getTestRecordingConfig(): RecordingConfig {
        return RecordingConfig(
            storageDirectory = "/fake/path",
            maxStorageSizeMB = 100,
            maxSegmentDurationSeconds = 10,
            videoResolution = VideoResolution.MEDIUM,
            videoQuality = VideoQuality.MEDIUM,
            recordAudio = true,
            notificationConfig = getTestNotificationConfig()
        )
    }

    @Test
    fun startRecording_sendsCorrectIntentAndRegistersReceiver() {
        val config = getTestRecordingConfig()
        // Use Robolectric's application context for starting services
        val spyContext = spy(applicationContext)
        continuousRecorder.initialize(spyContext) // Re-initialize with spy context

        continuousRecorder.startRecording(config, mockListener)

        verify(spyContext).startForegroundService(intentCaptor.capture())
        val capturedIntent = intentCaptor.value
        assertEquals(RecordingService.ACTION_START_RECORDING, capturedIntent.action)
        assertEquals(RecordingService::class.java.name, capturedIntent.component?.className)
        assertEquals(config, capturedIntent.getParcelableExtra(RecordingService.EXTRA_RECORDING_CONFIG))

        // Verify receiver registration using Robolectric's ShadowLocalBroadcastManager
        val shadowLbm = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))
        assertTrue(shadowLbm.isReceiverRegistered(continuousRecorder.serviceEventReceiver))
    }

    @Test
    fun stopRecording_sendsCorrectIntent() {
        // Start recording first to set up the receiver and active state
        val config = getTestRecordingConfig()
        continuousRecorder.startRecording(config, mockListener)
        // Simulate record started broadcast to make isRecording() true and keep receiver registered
        val startIntent = Intent(RecordingService.BROADCAST_RECORD_STARTED)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(startIntent)
        ShadowLooper.idleMainLooper() // Process the broadcast

        val spyContext = spy(applicationContext)
        continuousRecorder.initialize(spyContext) // Re-initialize with spy context

        continuousRecorder.stopRecording()

        verify(spyContext).startService(intentCaptor.capture())
        val capturedIntent = intentCaptor.value
        assertEquals(RecordingService.ACTION_STOP_RECORDING, capturedIntent.action)
        assertEquals(RecordingService::class.java.name, capturedIntent.component?.className)

        // Receiver unregistration is now handled upon receiving STOPPED or ERROR broadcast.
    }

    @Test
    fun broadcastReceiver_onRecordStarted_invokesListenerAndSetsState() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener) // Registers the receiver

        val intent = Intent(RecordingService.BROADCAST_RECORD_STARTED)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        ShadowLooper.idleMainLooper() // Process the broadcast

        verify(mockListener).onRecordStart()
        assertTrue(continuousRecorder.isRecording())
    }

    @Test
    fun broadcastReceiver_onRecordStopped_invokesListenerAndResetsStateAndUnregisters() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)
        // Simulate record started
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(RecordingService.BROADCAST_RECORD_STARTED))
        ShadowLooper.idleMainLooper()

        val filePaths = arrayListOf("/path/to/video1.mp4")
        val intent = Intent(RecordingService.BROADCAST_RECORD_STOPPED).apply {
            putStringArrayListExtra(RecordingService.EXTRA_FILE_PATH, filePaths)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        ShadowLooper.idleMainLooper()

        verify(mockListener).onRecordStop(filePaths)
        assertFalse(continuousRecorder.isRecording())

        val shadowLbm = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))
        assertFalse(shadowLbm.isReceiverRegistered(continuousRecorder.serviceEventReceiver))
    }

    @Test
    fun broadcastReceiver_onSegmentCreated_invokesListener() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)

        val filePath = "/path/to/segment.mp4"
        val intent = Intent(RecordingService.BROADCAST_SEGMENT_CREATED).apply {
            putExtra(RecordingService.EXTRA_FILE_PATH, filePath)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        ShadowLooper.idleMainLooper()

        verify(mockListener).onSegmentCreated(filePath)
    }

    @Test
    fun broadcastReceiver_onError_invokesListenerAndResetsStateAndUnregisters() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)
        // Simulate record started
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(RecordingService.BROADCAST_RECORD_STARTED))
        ShadowLooper.idleMainLooper()

        val errorType = ErrorType.CAMERA_ERROR
        val errorMessage = "Camera failed"
        val intent = Intent(RecordingService.BROADCAST_RECORD_ERROR).apply {
            putExtra(RecordingService.EXTRA_ERROR_TYPE, errorType.name)
            putExtra(RecordingService.EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        ShadowLooper.idleMainLooper()

        verify(mockListener).onError(errorType, errorMessage)
        assertFalse(continuousRecorder.isRecording())

        val shadowLbm = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))
        assertFalse(shadowLbm.isReceiverRegistered(continuousRecorder.serviceEventReceiver))
    }

    @Test
    fun broadcastReceiver_onStorageLimitReached_invokesListener() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)

        val deletedPath = "/path/to/deleted_segment.mp4"
        val intent = Intent(RecordingService.BROADCAST_STORAGE_REACHED_LIMIT).apply {
            putExtra(RecordingService.EXTRA_OLDEST_SEGMENT_PATH, deletedPath)
        }
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        ShadowLooper.idleMainLooper()

        verify(mockListener).onStorageReachedLimit(deletedPath)
    }

    @Test
    fun release_unregistersReceiver_whenRecording() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)
        // Simulate record started to make isRecording() true
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(RecordingService.BROADCAST_RECORD_STARTED))
        ShadowLooper.idleMainLooper()

        assertTrue(continuousRecorder.isRecording()) // Pre-condition

        continuousRecorder.release()
        // Stop recording is called, which sends an intent.
        // The receiver should be unregistered when the STOPPED or ERROR broadcast is eventually received.
        // For this test, we'll assume the stop intent leads to a stop broadcast.
        val stopIntent = Intent(RecordingService.BROADCAST_RECORD_STOPPED)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(stopIntent)
        ShadowLooper.idleMainLooper()

        val shadowLbm = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))
        assertNull(continuousRecorder.serviceEventReceiver) // Check if receiver field is nulled
        // More robustly, check shadowLbm if the specific receiver instance was indeed unregistered.
        // This is harder without direct access to the receiver instance *before* it's nulled.
    }

    @Test
    fun release_unregistersReceiver_whenNotRecording() {
        continuousRecorder.startRecording(getTestRecordingConfig(), mockListener)
        // Receiver is registered. Now simulate it being stopped.
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(RecordingService.BROADCAST_RECORD_STOPPED).putStringArrayListExtra(RecordingService.EXTRA_FILE_PATH, arrayListOf())
        )
        ShadowLooper.idleMainLooper() // Process stop, receiver gets unregistered

        assertFalse(continuousRecorder.isRecording()) // Pre-condition
        assertNull(continuousRecorder.serviceEventReceiver) // Receiver should be null now

        continuousRecorder.release() // Should not throw, and receiver should remain unregistered (null)

        val shadowLbm = Shadows.shadowOf(LocalBroadcastManager.getInstance(applicationContext))
        // Check that no attempts to unregister a null receiver are made, or that it's benign.
        // The main check is that serviceEventReceiver is null before and after release in this state.
        assertNull(continuousRecorder.serviceEventReceiver)
    }

    @Test
    fun startRecording_whenAlreadyRecording_reportsErrorToNewListener() {
        val initialListener = mock(RecordingListener::class.java)
        val config = getTestRecordingConfig()
        continuousRecorder.startRecording(config, initialListener)

        // Simulate that recording actually started
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(Intent(RecordingService.BROADCAST_RECORD_STARTED))
        ShadowLooper.idleMainLooper()
        assertTrue(continuousRecorder.isRecording())

        val newListener = mock(RecordingListener::class.java)
        continuousRecorder.startRecording(config, newListener) // Attempt to start again

        verify(newListener).onError(ErrorType.SERVICE_ERROR, "Another recording session might be active or client state is stale.")
        verifyNoMoreInteractions(initialListener) // Ensure original listener isn't affected by this specific error
    }
}
