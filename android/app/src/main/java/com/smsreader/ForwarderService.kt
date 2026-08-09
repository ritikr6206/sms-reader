package com.smsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

/**
 * Foreground service that processes the message queue.
 * Keeps the app alive so SMS reception works reliably.
 * Processes queued messages and retries failed sends.
 */
class ForwarderService : Service() {

    companion object {
        private const val TAG = "ForwarderService"
        const val ACTION_PROCESS_QUEUE = "com.smsreader.PROCESS_QUEUE"
        const val ACTION_START_FOREGROUND = "com.smsreader.START_FOREGROUND"
        private const val CHANNEL_ID = "sms_reader_service"
        private const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var networkMonitor: NetworkMonitor
    private var isProcessing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("SMS Reader active"))
        networkMonitor = NetworkMonitor(this)
        networkMonitor.startMonitoring()
        Log.d(TAG, "ForwarderService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_QUEUE -> processQueue()
            ACTION_START_FOREGROUND -> { /* just keep running */ }
        }
        return START_STICKY // restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.stopMonitoring()
        serviceScope.cancel()
        Log.d(TAG, "ForwarderService destroyed")
    }

    private fun processQueue() {
        if (isProcessing) return
        isProcessing = true

        serviceScope.launch {
            try {
                val prefs = getSharedPreferences("sms_reader_config", Context.MODE_PRIVATE)
                val serverUrl = prefs.getString("server_url", "") ?: ""
                val apiKey = prefs.getString("api_key", "") ?: ""

                if (serverUrl.isBlank() || apiKey.isBlank()) {
                    Log.w(TAG, "Server not configured — skipping queue processing")
                    isProcessing = false
                    return@launch
                }

                val queue = MessageQueue(this@ForwarderService)
                val network = NetworkMonitor(this@ForwarderService)

                if (!network.isOnline()) {
                    Log.d(TAG, "Offline — will retry when connected")
                    isProcessing = false
                    return@launch
                }

                val pending = queue.getPendingMessages()
                if (pending.isEmpty()) {
                    Log.d(TAG, "No pending messages")
                    isProcessing = false
                    return@launch
                }

                Log.d(TAG, "Processing ${pending.size} pending messages")
                updateNotification("Sending ${pending.size} message(s)...")

                val forwarder = SmsForwarder(serverUrl, apiKey)

                for (msg in pending) {
                    queue.markSending(msg.id)

                    val success = forwarder.sendSingle(msg)
                    if (success) {
                        queue.markSent(msg.id)
                    } else {
                        queue.markFailed(msg.id, msg.retryCount)
                    }

                    // Small delay between sends to avoid overwhelming the server
                    delay(200)
                }

                val remainingCount = queue.getPendingCount()
                updateNotification(
                    if (remainingCount > 0) "$remainingCount message(s) pending"
                    else "SMS Reader active"
                )

                // Cleanup old messages
                queue.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Queue processing error", e)
            } finally {
                isProcessing = false
            }
        }
    }

    // ---- Notification helpers ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SMS Reader Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps SMS Reader running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Reader")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
