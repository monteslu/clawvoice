package com.clawd.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.clawd.app.R
import com.clawd.app.databinding.FragmentChatBinding
import com.clawd.app.network.ClawdClient
import com.clawd.app.network.protocol.ConnectionState
import com.clawd.app.network.protocol.GatewayEvent
import com.clawd.app.speech.PiperTtsManager
import com.clawd.app.speech.SpeechRecognizerManager
import com.clawd.app.ui.settings.VoiceSelectionDialog
import com.clawd.app.util.MarkdownUtils
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private var clawdClient: ClawdClient? = null
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var speechRecognizer: SpeechRecognizerManager
    private lateinit var tts: PiperTtsManager
    private var usePiperTts = true // Can fall back to Android TTS if Piper fails

    private var autoSpeak = true
    private var handsFreeActive = false
    private var lastSpokenContent = ""
    private var pulseAnimation: Animation? = null
    private var pendingListen = false

    private enum class VoiceState { IDLE, LISTENING, SPEAKING }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceInput()
        } else {
            Toast.makeText(requireContext(), "Microphone permission required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    fun setClient(client: ClawdClient) {
        clawdClient = client
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSpeech()
        setupClickListeners()
        observeClient()
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.messagesRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
    }

    private fun setupSpeech() {
        speechRecognizer = SpeechRecognizerManager(requireContext())
        speechRecognizer.initialize()

        tts = PiperTtsManager(requireContext())
        // Load saved voice preference
        val savedVoiceId = getSavedVoiceId()
        tts.initialize(savedVoiceId) {
            android.util.Log.d("ChatFragment", "TTS initialized with voice: ${tts.getCurrentVoiceId()}")
        }

        // Load pulse animation
        pulseAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse_animation)

        speechRecognizer.isListening.onEach { isListening ->
            activity?.runOnUiThread {
                if (isListening) {
                    showVoiceOverlay(VoiceState.LISTENING)
                } else if (!tts.isSpeaking.value) {
                    hideVoiceOverlay()
                }
                updateVoiceButtonState()
            }
        }.launchIn(lifecycleScope)

        // Handle speech recognition errors - retry in hands-free mode
        speechRecognizer.error.onEach { error ->
            if (error != null && handsFreeActive) {
                android.util.Log.d("ChatFragment", "Speech error in hands-free: $error, will retry")
                // Brief delay then retry
                kotlinx.coroutines.delay(500)
                if (handsFreeActive && !tts.isSpeaking.value) {
                    activity?.runOnUiThread { startVoiceInput() }
                }
            }
        }.launchIn(lifecycleScope)

        // Show speaking overlay and auto-listen after TTS finishes
        var wasSpeaking = false
        tts.isSpeaking.onEach { isSpeaking ->
            if (isSpeaking && handsFreeActive) {
                // Stop any pending listen and stop mic if it somehow started
                pendingListen = false
                if (speechRecognizer.isListening.value) {
                    speechRecognizer.stopListening()
                }
                activity?.runOnUiThread {
                    showVoiceOverlay(VoiceState.SPEAKING)
                }
            } else if (wasSpeaking && !isSpeaking) {
                if (handsFreeActive && autoSpeak) {
                    // TTS just finished - small delay for audio system latency
                    android.util.Log.d("ChatFragment", "TTS finished, starting listen")
                    pendingListen = true
                    kotlinx.coroutines.delay(200) // Small buffer for audio system
                    if (pendingListen && handsFreeActive && !tts.isSpeaking.value) {
                        pendingListen = false
                        android.util.Log.d("ChatFragment", "Starting listen after TTS")
                        activity?.runOnUiThread { startVoiceInput() }
                    }
                } else {
                    activity?.runOnUiThread { hideVoiceOverlay() }
                }
            }
            wasSpeaking = isSpeaking
        }.launchIn(lifecycleScope)

        // Tap overlay to interrupt and listen
        binding.voiceOverlay.setOnClickListener {
            if (tts.isSpeaking.value) {
                // Stop speaking - the flow will trigger listening after delay
                tts.stop()
                if (!handsFreeActive) {
                    hideVoiceOverlay()
                }
                // If hands-free, the isSpeaking flow will handle starting listen
            } else if (speechRecognizer.isListening.value) {
                // Stop listening
                stopHandsFree()
            }
        }
    }

    private fun showVoiceOverlay(state: VoiceState) {
        binding.voiceOverlay.visibility = View.VISIBLE

        val (bubbleDrawable, statusText, iconRes) = when (state) {
            VoiceState.LISTENING -> Triple(
                R.drawable.pulse_circle_blue,
                getString(R.string.listening),
                android.R.drawable.ic_btn_speak_now
            )
            VoiceState.SPEAKING -> Triple(
                R.drawable.pulse_circle_red,
                getString(R.string.speaking),
                android.R.drawable.ic_lock_silent_mode_off
            )
            VoiceState.IDLE -> return
        }

        binding.pulseBubble.setBackgroundResource(bubbleDrawable)
        binding.pulseRing.setBackgroundResource(bubbleDrawable)
        binding.voiceStatusText.text = statusText
        binding.pulseIcon.setImageResource(iconRes)

        // Start animations
        pulseAnimation?.let { anim ->
            binding.pulseBubble.startAnimation(anim)
            binding.pulseRing.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.pulse_animation).apply {
                startOffset = 200 // Offset for ripple effect
            })
        }
    }

    private fun hideVoiceOverlay() {
        binding.pulseBubble.clearAnimation()
        binding.pulseRing.clearAnimation()
        binding.voiceOverlay.visibility = View.GONE
    }

    private fun setupClickListeners() {
        binding.sendButton.setOnClickListener {
            sendMessage()
        }

        binding.voiceButton.setOnClickListener {
            if (handsFreeActive || speechRecognizer.isListening.value) {
                // Stop hands-free mode
                stopHandsFree()
            } else {
                // Start hands-free conversation
                handsFreeActive = true
                checkMicrophonePermission()
            }
        }

        binding.messageInput.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }

        binding.modeToggle.setOnClickListener {
            autoSpeak = !autoSpeak
            updateModeIcon()
            val mode = if (autoSpeak) "Voice + Text" else "Text Only"
            Toast.makeText(requireContext(), mode, Toast.LENGTH_SHORT).show()
        }

        binding.voiceSettingsButton.setOnClickListener {
            showVoiceSelectionDialog()
        }

        updateModeIcon()
    }

    private fun showVoiceSelectionDialog() {
        VoiceSelectionDialog(tts) { voiceId ->
            saveVoiceId(voiceId)
        }.show(parentFragmentManager, "voice_selection")
    }

    private fun getSavedVoiceId(): String? {
        val prefs = requireContext().getSharedPreferences("clawvoice_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("selected_voice", null)
    }

    private fun saveVoiceId(voiceId: String) {
        val prefs = requireContext().getSharedPreferences("clawvoice_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("selected_voice", voiceId).apply()
    }

    private fun updateModeIcon() {
        val iconRes = if (autoSpeak) {
            android.R.drawable.ic_lock_silent_mode_off  // Speaker on
        } else {
            android.R.drawable.ic_lock_silent_mode      // Speaker off/muted
        }
        binding.modeToggle.setImageResource(iconRes)
    }

    private fun observeClient() {
        clawdClient?.let { client ->
            client.connectionState.onEach { state ->
                activity?.runOnUiThread {
                    updateConnectionStatus(state)
                }
            }.launchIn(lifecycleScope)

            client.messages.onEach { messages ->
                activity?.runOnUiThread {
                    messageAdapter.submitList(messages.toList()) {
                        if (messages.isNotEmpty()) {
                            binding.messagesRecycler.scrollToPosition(messages.size - 1)
                        }
                    }

                    // Auto-speak new assistant messages
                    val lastMessage = messages.lastOrNull()
                    if (autoSpeak && lastMessage?.role == "assistant") {
                        val content = lastMessage.content
                        if (content != lastSpokenContent && content.isNotBlank()) {
                            // Only speak when message is complete (no more streaming)
                            // We detect this by checking if content hasn't changed in a short time
                            // For simplicity, we'll speak complete messages only
                        }
                    }
                }
            }.launchIn(lifecycleScope)

            client.events.onEach { event ->
                if (event is GatewayEvent.Chat && (event.type == "textComplete" || event.type == "final")) {
                    // Text is complete, speak it (textComplete is faster than final)
                    val content = event.content ?: client.messages.value.lastOrNull()?.content ?: ""
                    android.util.Log.d("ChatFragment", "${event.type} event received, autoSpeak=$autoSpeak, content=${content.take(50)}")
                    if (autoSpeak && content.isNotBlank() && content != lastSpokenContent) {
                        lastSpokenContent = content
                        // Strip markdown formatting for natural TTS reading
                        val speakableText = MarkdownUtils.stripForTts(content)
                        android.util.Log.d("ChatFragment", "Speaking: ${speakableText.take(50)}")
                        activity?.runOnUiThread {
                            tts.speak(speakableText)
                        }
                    }
                }
            }.launchIn(lifecycleScope)
        }
    }

    private fun updateConnectionStatus(state: ConnectionState) {
        val (text, color) = when (state) {
            is ConnectionState.Disconnected -> "Disconnected" to android.R.color.holo_red_dark
            is ConnectionState.Connecting -> "Connecting..." to android.R.color.holo_orange_dark
            is ConnectionState.Connected -> "Connected" to android.R.color.holo_orange_light
            is ConnectionState.WaitingForPairing -> "Waiting for pairing..." to android.R.color.holo_orange_dark
            is ConnectionState.Ready -> "Connected" to android.R.color.holo_green_dark
            is ConnectionState.Error -> "Error: ${state.message}" to android.R.color.holo_red_dark
        }

        binding.connectionStatus.text = text
        binding.statusBar.setBackgroundColor(
            ContextCompat.getColor(requireContext(), color)
        )
    }

    private fun sendMessage() {
        val text = binding.messageInput.text?.toString()?.trim()
        android.util.Log.d("ChatFragment", "sendMessage called, text='$text', client=${clawdClient != null}")
        if (text.isNullOrBlank()) {
            android.util.Log.d("ChatFragment", "Text is blank, returning")
            return
        }

        binding.messageInput.text?.clear()

        lifecycleScope.launch {
            android.util.Log.d("ChatFragment", "Launching coroutine to send message")
            val result = clawdClient?.sendMessage(text)
            android.util.Log.d("ChatFragment", "sendMessage result: $result")
        }
    }

    private fun checkMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startVoiceInput()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceInput() {
        // Never start mic while TTS is playing
        if (tts.isSpeaking.value) {
            android.util.Log.d("ChatFragment", "Blocked mic start - TTS still speaking")
            return
        }
        speechRecognizer.startListening { recognizedText ->
            activity?.runOnUiThread {
                binding.messageInput.setText(recognizedText)
                sendMessage()
            }
        }
    }

    private fun stopHandsFree() {
        handsFreeActive = false
        pendingListen = false
        tts.stop()
        speechRecognizer.stopListening()
        hideVoiceOverlay()
        updateVoiceButtonState()
        Toast.makeText(requireContext(), "Voice conversation stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateVoiceButtonState() {
        val isActive = handsFreeActive || speechRecognizer.isListening.value
        binding.voiceButton.alpha = if (isActive) 1.0f else 0.6f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.pulseBubble.clearAnimation()
        binding.pulseRing.clearAnimation()
        speechRecognizer.destroy()
        tts.shutdown()
        _binding = null
    }
}
