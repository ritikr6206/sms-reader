package com.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * BroadcastReceiver that intercepts every incoming SMS.
 * Saves the full message to the local SQLite queue,
 * then triggers the ForwarderService to send it to the server.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group multi-part SMS by sender
        val grouped = mutableMapOf<String, StringBuilder>()
        for (msg in messages) {
            val sender = msg.displayOriginatingAddress ?: "Unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(msg.messageBody ?: "")
        }

        val queue = MessageQueue(context)
        val timestamp = System.currentTimeMillis()

        for ((sender, body) in grouped) {
            val message = body.toString()
            Log.d(TAG, "SMS from $sender: ${message.take(50)}...")

            // Save to local SQLite queue
            queue.enqueue(sender, message, timestamp)
        }

        // Trigger the forwarder service to process the queue
        val serviceIntent = Intent(context, ForwarderService::class.java).apply {
            action = ForwarderService.ACTION_PROCESS_QUEUE
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ForwarderService", e)
        }
    }
}
