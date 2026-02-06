package com.clawd.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clawd.app.data.SecureStorage
import com.clawd.app.databinding.ActivityMainBinding
import com.clawd.app.network.ClawdClient
import com.clawd.app.service.ClawdConnectionService
import com.clawd.app.service.NtfyService
import com.clawd.app.ui.chat.ChatFragment
import com.clawd.app.ui.setup.SetupFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var clawdClient: ClawdClient? = null

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Continue regardless of permission result
        checkSetupState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkSetupState()
    }

    private fun checkSetupState() {
        val hasToken = SecureStorage.getDeviceToken(this) != null
        val hasUrl = SecureStorage.getGatewayUrl(this) != null

        if (hasToken && hasUrl) {
            // Auto-connect and show chat
            showChat()
        } else {
            // Show setup
            showSetup()
        }
    }

    private fun showSetup() {
        val setupFragment = SetupFragment()
        setupFragment.setOnConnectedListener {
            clawdClient = setupFragment.getClient()
            showChatFragment()
            startServices()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, setupFragment)
            .commit()
    }

    private fun showChat() {
        val url = SecureStorage.getGatewayUrl(this) ?: return showSetup()

        clawdClient = ClawdClient(this, url)
        clawdClient?.connect()

        showChatFragment()
        startServices()
    }

    private fun showChatFragment() {
        val chatFragment = ChatFragment()
        clawdClient?.let { chatFragment.setClient(it) }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, chatFragment)
            .commit()
    }

    private fun startServices() {
        // Start connection service
        val connectionIntent = Intent(this, ClawdConnectionService::class.java)
        ContextCompat.startForegroundService(this, connectionIntent)

        // Start ntfy service for push notifications
        val ntfyIntent = Intent(this, NtfyService::class.java)
        ContextCompat.startForegroundService(this, ntfyIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        clawdClient?.disconnect()
    }
}
