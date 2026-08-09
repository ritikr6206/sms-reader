package com.smsreader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite-backed offline message queue.
 * Messages are stored locally first, then sent to the server.
 * Failed sends are retried with exponential backoff.
 */
class MessageQueue(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "MessageQueue"
        private const val DB_NAME = "sms_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE = "message_queue"

        // Column names
        private const val COL_ID = "id"
        private const val COL_SENDER = "sender"
        private const val COL_MESSAGE = "message"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_STATUS = "status"       // "pending", "sending", "sent", "failed"
        private const val COL_RETRY_COUNT = "retry_count"
        private const val COL_NEXT_RETRY = "next_retry_at"
        private const val COL_CREATED_AT = "created_at"

        // Status values
        const val STATUS_PENDING = "pending"
        const val STATUS_SENDING = "sending"
        const val STATUS_SENT = "sent"
        const val STATUS_FAILED = "failed"

        // Retry settings
        const val MAX_RETRIES = 5
        private const val BASE_RETRY_MS = 5000L // 5 seconds
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENDER TEXT NOT NULL,
                $COL_MESSAGE TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL DEFAULT '$STATUS_PENDING',
                $COL_RETRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_NEXT_RETRY INTEGER NOT NULL DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /**
     * Add a new message to the queue.
     */
    fun enqueue(sender: String, message: String, timestamp: Long): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SENDER, sender)
            put(COL_MESSAGE, message)
            put(COL_TIMESTAMP, timestamp)
            put(COL_STATUS, STATUS_PENDING)
            put(COL_RETRY_COUNT, 0)
            put(COL_NEXT_RETRY, 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        val id = db.insert(TABLE, null, values)
        Log.d(TAG, "Enqueued message #$id from $sender")
        return id
    }

    /**
     * Get all messages that are ready to be sent (pending or retryable).
     */
    fun getPendingMessages(): List<QueuedMessage> {
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val cursor = db.query(
            TABLE,
            null,
            "($COL_STATUS = ? OR ($COL_STATUS = ? AND $COL_NEXT_RETRY <= ? AND $COL_RETRY_COUNT < $MAX_RETRIES))",
            arrayOf(STATUS_PENDING, STATUS_FAILED, now.toString()),
            null, null,
            "$COL_CREATED_AT ASC",
            "50" // batch size
        )

        val messages = mutableListOf<QueuedMessage>()
        cursor.use {
            while (it.moveToNext()) {
                messages.add(
                    QueuedMessage(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        sender = it.getString(it.getColumnIndexOrThrow(COL_SENDER)),
                        message = it.getString(it.getColumnIndexOrThrow(COL_MESSAGE)),
                        timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                        retryCount = it.getInt(it.getColumnIndexOrThrow(COL_RETRY_COUNT))
                    )
                )
            }
        }
        return messages
    }

    /**
     * Mark a message as currently being sent.
     */
    fun markSending(id: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_SENDING)
        }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /**
     * Mark a message as successfully sent — removes it from the queue.
     */
    fun markSent(id: Long) {
        val db = writableDatabase
        db.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        Log.d(TAG, "Message #$id sent successfully, removed from queue")
    }

    /**
     * Mark a message as failed — schedules a retry with exponential backoff.
     */
    fun markFailed(id: Long, currentRetryCount: Int) {
        val db = writableDatabase
        val nextRetry = currentRetryCount + 1
        val delayMs = BASE_RETRY_MS * (1L shl minOf(nextRetry, 6)) // exponential: 10s, 20s, 40s, 80s, 160s, 320s
        val nextRetryAt = System.currentTimeMillis() + delayMs

        val values = ContentValues().apply {
            put(COL_STATUS, STATUS_FAILED)
            put(COL_RETRY_COUNT, nextRetry)
            put(COL_NEXT_RETRY, nextRetryAt)
        }
        db.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        Log.d(TAG, "Message #$id failed (retry $nextRetry/$MAX_RETRIES, next in ${delayMs / 1000}s)")
    }

    /**
     * Get count of pending messages in the queue.
     */
    fun getPendingCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS != ?",
            arrayOf(STATUS_SENT)
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Clean up old sent/expired messages (older than 24h).
     */
    fun cleanup() {
        val db = writableDatabase
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        val deleted = db.delete(TABLE, "$COL_CREATED_AT < ?", arrayOf(cutoff.toString()))
        if (deleted > 0) {
            Log.d(TAG, "Cleaned up $deleted old messages")
        }
    }
}

/**
 * Data class representing a queued message.
 */
data class QueuedMessage(
    val id: Long,
    val sender: String,
    val message: String,
    val timestamp: Long,
    val retryCount: Int
)
