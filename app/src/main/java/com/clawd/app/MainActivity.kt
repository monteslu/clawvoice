package com.clawd.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clawd.app.data.Agent
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
        checkSetupState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val agent = SecureStorage.getActiveAgent(this)

        if (agent != null && agent.deviceToken != null) {
            connectToAgent(agent)
        } else if (agent != null) {
            // Agent exists but no token yet — show setup to re-pair
            showSetup(agent)
        } else {
            showSetup()
        }
    }

    private fun connectToAgent(agent: Agent) {
        // Disconnect previous client
        clawdClient?.disconnect()

        SecureStorage.setActiveAgentId(this, agent.id)

        clawdClient = ClawdClient(this, agent.gatewayUrl)
        clawdClient?.connect()

        showChatFragment()
        startServices()
    }

    private fun switchToAgent(agent: Agent) {
        // Disconnect current
        clawdClient?.disconnect()
        stopServices()

        SecureStorage.setActiveAgentId(this, agent.id)

        if (agent.deviceToken != null) {
            connectToAgent(agent)
        } else {
            // Needs pairing — show setup (but agent already saved)
            showSetup()
        }
    }

    private fun showSetup(existingAgent: Agent? = null) {
        val setupFragment = SetupFragment()
        existingAgent?.let { setupFragment.setExistingAgent(it) }
        setupFragment.setOnConnectedListener { _ ->
            clawdClient = setupFragment.getClient()
            showChatFragment()
            startServices()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, setupFragment)
            .commit()
    }

    private fun showChatFragment() {
        val chatFragment = ChatFragment()
        clawdClient?.let { chatFragment.setClient(it) }

        chatFragment.setOnSwitchAgentListener { agent ->
            switchToAgent(agent)
        }

        chatFragment.setOnAddAgentListener {
            showSetup()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, chatFragment)
            .commit()
    }

    private fun startServices() {
        val connectionIntent = Intent(this, ClawdConnectionService::class.java)
        ContextCompat.startForegroundService(this, connectionIntent)

        // Only start ntfy if topic configured
        val ntfyTopic = SecureStorage.getNtfyTopic(this)
        if (!ntfyTopic.isNullOrBlank()) {
            val ntfyIntent = Intent(this, NtfyService::class.java)
            ContextCompat.startForegroundService(this, ntfyIntent)
        }
    }

    private fun stopServices() {
        try {
            stopService(Intent(this, ClawdConnectionService::class.java))
            stopService(Intent(this, NtfyService::class.java))
        } catch (e: Exception) {
            // Ignore if services not running
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clawdClient?.disconnect()
    }
}
