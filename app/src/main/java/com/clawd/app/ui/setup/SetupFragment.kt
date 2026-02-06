package com.clawd.app.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.clawd.app.R
import com.clawd.app.data.ClawdConfig
import com.clawd.app.data.SecureStorage
import com.clawd.app.databinding.FragmentSetupBinding
import com.clawd.app.network.ClawdClient
import com.clawd.app.network.protocol.ConnectionState
import com.clawd.app.network.protocol.GatewayEvent
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!

    private var clawdClient: ClawdClient? = null
    private var onConnected: (() -> Unit)? = null

    fun setOnConnectedListener(listener: () -> Unit) {
        onConnected = listener
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

        // Load saved values
        SecureStorage.getGatewayUrl(requireContext())?.let {
            binding.urlInput.setText(it)
        }
        SecureStorage.getNtfyTopic(requireContext())?.let {
            binding.ntfyTopicInput.setText(it)
        }

        binding.connectButton.setOnClickListener {
            connect()
        }
    }

    private fun connect() {
        val url = binding.urlInput.text?.toString()?.trim()
        val token = binding.tokenInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        if (url.isNullOrBlank()) {
            binding.urlInputLayout.error = "URL is required"
            return
        }

        binding.urlInputLayout.error = null
        binding.connectButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.visibility = View.GONE

        // Save URL and ntfy topic
        SecureStorage.saveGatewayUrl(requireContext(), url)
        ClawdConfig.gatewayUrl = url

        val ntfyTopic = binding.ntfyTopicInput.text?.toString()?.trim()
        if (!ntfyTopic.isNullOrBlank()) {
            SecureStorage.saveNtfyTopic(requireContext(), ntfyTopic)
            ClawdConfig.ntfyTopic = ntfyTopic
        }

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
                binding.progressBar.visibility = View.VISIBLE
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.waiting_for_pairing) + "\n\n" +
                        getString(R.string.pairing_instructions)
            }
            is ConnectionState.Ready -> {
                binding.progressBar.visibility = View.GONE
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = "Connected!"
                onConnected?.invoke()
            }
            is ConnectionState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.connectButton.isEnabled = true
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = "Error: ${state.message}"
            }
        }
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
