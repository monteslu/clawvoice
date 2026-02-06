package com.clawd.app.ui.setup

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.clawd.app.R
import com.clawd.app.data.Agent
import com.clawd.app.data.ClawdConfig
import com.clawd.app.data.SecureStorage
import com.clawd.app.databinding.FragmentSetupBinding
import com.clawd.app.network.ClawdClient
import com.clawd.app.network.protocol.ConnectionState
import com.clawd.app.network.protocol.GatewayEvent
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.URI

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private var clawdClient: ClawdClient? = null
    private var onConnected: ((Agent) -> Unit)? = null
    private var pendingAgent: Agent? = null
    private var nameManuallySet = false
    private var existingAgent: Agent? = null

    fun setOnConnectedListener(listener: (Agent) -> Unit) {
        onConnected = listener
    }

    fun setExistingAgent(agent: Agent) {
        existingAgent = agent
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill from existing agent if re-pairing
        existingAgent?.let { agent ->
            binding.urlInput.setText(agent.gatewayUrl)
            binding.nameInput.setText(agent.name)
            agent.ntfyTopic?.let { binding.ntfyTopicInput.setText(it) }
            nameManuallySet = true  // Don't overwrite the pre-filled name
        }

        // Track if user manually edited the name
        binding.nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.nameInput.hasFocus()) nameManuallySet = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Auto-fill name from URL when URL changes
        binding.urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!nameManuallySet) {
                    val name = extractNameFromUrl(s?.toString()?.trim() ?: "")
                    binding.nameInput.setText(name)
                }
            }
        })

        binding.connectButton.setOnClickListener {
            connect()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return try {
            val uri = URI(url.let {
                if (!it.contains("://")) "https://$it" else it
            })
            val host = uri.host ?: return ""
            // Use first subdomain segment as name
            val parts = host.split(".")
            if (parts.size >= 2) parts[0] else host
        } catch (e: Exception) {
            ""
        }
    }

    private fun connect() {
        val url = binding.urlInput.text?.toString()?.trim()
        val token = binding.tokenInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val name = binding.nameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractNameFromUrl(url ?: "")
        val ntfyTopic = binding.ntfyTopicInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        if (url.isNullOrBlank()) {
            binding.urlInputLayout.error = "URL is required"
            return
        }

        if (name.isBlank()) {
            binding.nameInputLayout.error = "Name is required"
            return
        }

        binding.urlInputLayout.error = null
        binding.nameInputLayout.error = null
        binding.connectButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.GONE

        // Reuse existing agent ID if re-pairing, otherwise create new
        val agent = if (existingAgent != null) {
            existingAgent!!.copy(
                name = name,
                gatewayUrl = url,
                ntfyTopic = ntfyTopic
            )
        } else {
            Agent(
                name = name,
                gatewayUrl = url,
                ntfyTopic = ntfyTopic
            )
        }
        pendingAgent = agent

        // Save agent and set active
        if (existingAgent != null) {
            SecureStorage.updateAgent(requireContext(), agent)
        } else {
            SecureStorage.addAgent(requireContext(), agent)
        }
        SecureStorage.setActiveAgentId(requireContext(), agent.id)
        ClawdConfig.gatewayUrl = url

        // Create client and connect
        clawdClient = ClawdClient(requireContext(), url)

        clawdClient?.connectionState?.onEach { state ->
            activity?.runOnUiThread {
                handleConnectionState(state)
            }
        }?.launchIn(lifecycleScope)

        clawdClient?.events?.onEach { event ->
            activity?.runOnUiThread {
                handleEvent(event)
            }
        }?.launchIn(lifecycleScope)

        clawdClient?.connect(token)
    }

    private fun handleConnectionState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Disconnected -> {
                showForm()
                binding.progressBar.visibility = View.GONE
                binding.connectButton.isEnabled = true
            }
            is ConnectionState.Connecting -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = "Connecting..."
            }
            is ConnectionState.Connected -> {
                binding.statusText.text = "Connected, authenticating..."
            }
            is ConnectionState.WaitingForPairing -> {
                // Hide form fields to make room for pairing info
                binding.urlInputLayout.visibility = View.GONE
                binding.nameInputLayout.visibility = View.GONE
                binding.tokenInputLayout.visibility = View.GONE
                binding.ntfyTopicInputLayout.visibility = View.GONE
                binding.connectButton.visibility = View.GONE
                binding.titleText.text = "Pairing Required"

                // Re-anchor progressBar to center of screen
                val pbParams = binding.progressBar.layoutParams as ConstraintLayout.LayoutParams
                pbParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
                pbParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                binding.progressBar.layoutParams = pbParams

                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.visibility = View.VISIBLE
                val code = state.requestId ?: "???"
                binding.statusText.text = "Code:\n$code\n\nRun:\nopenclaw nodes approve $code"
                binding.statusText.textSize = 18f
            }
            is ConnectionState.Ready -> {
                binding.progressBar.visibility = View.GONE
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = "Connected!"
                pendingAgent?.let { onConnected?.invoke(it) }
            }
            is ConnectionState.Error -> {
                showForm()
                binding.progressBar.visibility = View.GONE
                binding.connectButton.isEnabled = true
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = "Error: ${state.message}"
            }
        }
    }

    private fun showForm() {
        binding.titleText.text = getString(R.string.add_agent_title)
        binding.urlInputLayout.visibility = View.VISIBLE
        binding.nameInputLayout.visibility = View.VISIBLE
        binding.tokenInputLayout.visibility = View.VISIBLE
        binding.ntfyTopicInputLayout.visibility = View.VISIBLE
        binding.connectButton.visibility = View.VISIBLE
        binding.statusText.textSize = 14f

        // Restore progressBar constraint
        val pbParams = binding.progressBar.layoutParams as ConstraintLayout.LayoutParams
        pbParams.topToTop = ConstraintLayout.LayoutParams.UNSET
        pbParams.topToBottom = R.id.connectButton
        binding.progressBar.layoutParams = pbParams
    }

    private fun handleEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Paired -> {
                binding.statusText.text = "Device paired successfully!"
            }
            is GatewayEvent.PairingRequired -> {
                // Already handled in connection state
            }
            is GatewayEvent.Chat -> {
                // Ignore chat events in setup
            }
        }
    }

    fun getClient(): ClawdClient? = clawdClient

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
