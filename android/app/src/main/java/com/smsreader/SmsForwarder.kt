package com.smsreader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles sending SMS data to the remote server.
 * Supports both single and batch message sending.
 */
class SmsForwarder(
    private val serverUrl: String,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "SmsForwarder"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Send a single SMS to the server.
     * Returns true on success, false on failure.
     */
    suspend fun sendSingle(message: QueuedMessage): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("sender", message.sender)
                put("message", message.message)
                put("timestamp", message.timestamp)
            }

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/sms")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", apiKey)
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful

            if (success) {
                Log.d(TAG, "✅ Sent message #${message.id} from ${message.sender}")
            } else {
                Log.w(TAG, "❌ Server returned ${response.code} for message #${message.id}")
            }

            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send message #${message.id}: ${e.message}")
            false
        }
    }

    /**
     * Send multiple messages in a single batch request.
     * Returns true on success, false on failure.
     */
    suspend fun sendBatch(messages: List<QueuedMessage>): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonArray = JSONArray()
            for (msg in messages) {
                jsonArray.put(JSONObject().apply {
                    put("sender", msg.sender)
                    put("message", msg.message)
                    put("timestamp", msg.timestamp)
                })
            }

            val json = JSONObject().apply {
                put("messages", jsonArray)
            }

            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/sms/batch")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-api-key", apiKey)
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful

            if (success) {
                Log.d(TAG, "✅ Batch sent ${messages.size} messages")
            } else {
                Log.w(TAG, "❌ Batch failed with ${response.code}")
            }

            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌ Batch send failed: ${e.message}")
            false
        }
    }

    /**
     * Test the connection to the server.
     * Returns a status message.
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/api/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            response.close()

            if (response.isSuccessful) {
                val json = JSONObject(body)
                "✅ Connected! Server uptime: ${json.optInt("uptime", 0)}s, " +
                        "Messages: ${json.optInt("messageCount", 0)}, " +
                        "Telegram: ${json.optBoolean("telegramConnected", false)}"
            } else {
                "❌ Server returned: ${response.code}"
            }
        } catch (e: Exception) {
            "❌ Connection failed: ${e.message}"
        }
    }
}
