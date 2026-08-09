package com.smsreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/**
 * Main configuration screen.
 * - Enter server URL and API key
 * - Test connection
 * - Start/stop the forwarding service
 * - View queue status
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var etServerUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var btnToggleService: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvQueueStatus: TextView
    private lateinit var tvPermissionStatus: TextView

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("sms_reader_config", Context.MODE_PRIVATE)

        // Bind views
        etServerUrl = findViewById(R.id.et_server_url)
        etApiKey = findViewById(R.id.et_api_key)
        btnSave = findViewById(R.id.btn_save)
        btnTest = findViewById(R.id.btn_test)
        btnToggleService = findViewById(R.id.btn_toggle_service)
        tvStatus = findViewById(R.id.tv_status)
        tvQueueStatus = findViewById(R.id.tv_queue_status)
        tvPermissionStatus = findViewById(R.id.tv_permission_status)

        // Load saved config
        etServerUrl.setText(prefs.getString("server_url", ""))
        etApiKey.setText(prefs.getString("api_key", ""))

        // Save button
        btnSave.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            if (url.isBlank() || key.isBlank()) {
                Toast.makeText(this, "Please fill in both fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("server_url", url)
                .putString("api_key", key)
                .apply()

            tvStatus.text = "✅ Configuration saved!"
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
        }

        // Test connection button
        btnTest.setOnClickListener {
            val url = etServerUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            if (url.isBlank()) {
                Toast.makeText(this, "Enter server URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvStatus.text = "⏳ Testing connection..."
            btnTest.isEnabled = false

            mainScope.launch {
                val forwarder = SmsForwarder(url, key)
                val result = forwarder.testConnection()
                tvStatus.text = result
                btnTest.isEnabled = true
            }
        }

        // Toggle service button
        btnToggleService.setOnClickListener {
            val isEnabled = prefs.getBoolean("service_enabled", false)

            if (isEnabled) {
                // Stop service
                stopService(Intent(this, ForwarderService::class.java))
                prefs.edit().putBoolean("service_enabled", false).apply()
                updateServiceButton(false)
                tvStatus.text = "🛑 Service stopped"
            } else {
                // Check config first
                val url = prefs.getString("server_url", "") ?: ""
                val key = prefs.getString("api_key", "") ?: ""
                if (url.isBlank() || key.isBlank()) {
                    Toast.makeText(this, "Save server configuration first!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                // Check permissions
                if (!hasAllPermissions()) {
                    requestPermissions()
                    return@setOnClickListener
                }

                // Start service
                val intent = Intent(this, ForwarderService::class.java).apply {
                    action = ForwarderService.ACTION_START_FOREGROUND
                }
                startForegroundService(intent)
                prefs.edit().putBoolean("service_enabled", true).apply()
                updateServiceButton(true)
                tvStatus.text = "✅ Service started — SMS forwarding active!"
            }
        }

        // Request permissions on first launch
        if (!hasAllPermissions()) {
            requestPermissions()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
    }

    private fun updateUI() {
        val isEnabled = prefs.getBoolean("service_enabled", false)
        updateServiceButton(isEnabled)
        updatePermissionStatus()
        updateQueueStatus()
    }

    private fun updateServiceButton(isRunning: Boolean) {
        if (isRunning) {
            btnToggleService.text = "🛑 Stop SMS Forwarding"
            btnToggleService.setBackgroundColor(0xFFEF4444.toInt())
        } else {
            btnToggleService.text = "▶️ Start SMS Forwarding"
            btnToggleService.setBackgroundColor(0xFF10B981.toInt())
        }
    }

    private fun updatePermissionStatus() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        tvPermissionStatus.text = if (missing.isEmpty()) {
            "✅ All permissions granted"
        } else {
            "⚠️ Missing permissions: ${missing.joinToString { it.substringAfterLast('.') }}"
        }
    }

    private fun updateQueueStatus() {
        try {
            val queue = MessageQueue(this)
            val count = queue.getPendingCount()
            tvQueueStatus.text = if (count > 0) {
                "📬 $count message(s) in queue (pending send)"
            } else {
                "📭 Queue empty — all messages sent"
            }
        } catch (e: Exception) {
            tvQueueStatus.text = "Queue: unknown"
        }
    }

    // ---- Permissions ----

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionStatus()
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "All permissions granted! ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Some permissions were denied. SMS forwarding may not work properly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
