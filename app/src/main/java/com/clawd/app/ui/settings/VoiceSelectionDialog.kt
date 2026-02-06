package com.clawd.app.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clawd.app.R
import com.clawd.app.speech.PiperTtsManager
import com.clawd.app.speech.PiperVoice
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class VoiceSelectionDialog(
    private val ttsManager: PiperTtsManager,
    private val onVoiceSelected: (String) -> Unit
) : DialogFragment() {

    private var adapter: VoiceAdapter? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_voice_selection, null)

        val recyclerView = view.findViewById<RecyclerView>(R.id.voiceList)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val currentVoice = ttsManager.getCurrentVoice()

        adapter = VoiceAdapter(
            voices = ttsManager.getAvailableVoices(),
            currentVoice = currentVoice,
            isDownloaded = { ttsManager.isVoiceDownloaded(it) },
            onPreview = { voice -> handlePreview(voice) },
            onSelect = { voice -> handleSelect(voice) }
        )
        recyclerView.adapter = adapter

        // Observe download progress
        ttsManager.downloadProgress.onEach { progress ->
            activity?.runOnUiThread {
                adapter?.updateDownloadProgress(progress)
            }
        }.launchIn(lifecycleScope)

        ttsManager.isLoading.onEach { loading ->
            activity?.runOnUiThread {
                adapter?.setLoading(loading)
            }
        }.launchIn(lifecycleScope)

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Voice")
            .setView(view)
            .setNegativeButton("Done", null)
            .create()
    }

    private fun handlePreview(voice: PiperVoice) {
        lifecycleScope.launch {
            ttsManager.stop()
            val loaded = ttsManager.loadVoice(voice)
            if (loaded) {
                ttsManager.speak(voice.testPhrase)
            }
        }
    }

    private fun handleSelect(voice: PiperVoice) {
        lifecycleScope.launch {
            ttsManager.stop()
            val loaded = ttsManager.loadVoice(voice)
            if (loaded) {
                activity?.runOnUiThread {
                    adapter?.setCurrentVoice(voice)
                }
                onVoiceSelected(voice.name)
            }
        }
    }
}

private class VoiceAdapter(
    private val voices: List<PiperVoice>,
    private var currentVoice: PiperVoice?,
    private val isDownloaded: (PiperVoice) -> Boolean,
    private val onPreview: (PiperVoice) -> Unit,
    private val onSelect: (PiperVoice) -> Unit
) : RecyclerView.Adapter<VoiceAdapter.ViewHolder>() {

    private var downloadingVoice: PiperVoice? = null
    private var downloadPercent: Int = 0
    private var isLoading: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_voice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(voices[position])
    }

    override fun getItemCount() = voices.size

    fun setCurrentVoice(voice: PiperVoice) {
        val oldIndex = voices.indexOf(currentVoice)
        val newIndex = voices.indexOf(voice)
        currentVoice = voice
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    fun updateDownloadProgress(progress: Pair<PiperVoice, Int>?) {
        val oldVoice = downloadingVoice
        downloadingVoice = progress?.first
        downloadPercent = progress?.second ?: 0

        if (oldVoice != null && oldVoice != downloadingVoice) {
            val idx = voices.indexOf(oldVoice)
            if (idx >= 0) notifyItemChanged(idx)
        }
        if (downloadingVoice != null) {
            val idx = voices.indexOf(downloadingVoice)
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    fun setLoading(loading: Boolean) {
        if (isLoading != loading) {
            isLoading = loading
            // Only update items that need button state changes
            notifyItemRangeChanged(0, voices.size, "loading")
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.voiceName)
        private val descText: TextView = itemView.findViewById(R.id.voiceDescription)
        private val statusText: TextView = itemView.findViewById(R.id.voiceStatus)
        private val selectedIndicator: View = itemView.findViewById(R.id.selectedIndicator)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.downloadProgress)
        private val previewButton: Button = itemView.findViewById(R.id.previewButton)
        private val selectButton: Button = itemView.findViewById(R.id.selectButton)

        fun bind(voice: PiperVoice) {
            nameText.text = voice.displayName
            descText.text = voice.description

            val isSelected = voice == currentVoice
            val downloaded = isDownloaded(voice)
            val isDownloading = voice == downloadingVoice

            selectedIndicator.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE

            when {
                isDownloading -> {
                    statusText.text = "Downloading... $downloadPercent%"
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = downloadPercent
                    previewButton.isEnabled = false
                    selectButton.isEnabled = false
                }
                isSelected -> {
                    statusText.text = "Current voice"
                    progressBar.visibility = View.GONE
                    previewButton.isEnabled = !isLoading
                    selectButton.isEnabled = false
                    selectButton.text = "Selected"
                }
                downloaded -> {
                    statusText.text = "Ready"
                    progressBar.visibility = View.GONE
                    previewButton.isEnabled = !isLoading
                    selectButton.isEnabled = !isLoading
                    selectButton.text = "Select"
                }
                else -> {
                    statusText.text = "Not downloaded (~25MB)"
                    progressBar.visibility = View.GONE
                    previewButton.isEnabled = !isLoading
                    selectButton.isEnabled = !isLoading
                    selectButton.text = "Select"
                }
            }

            previewButton.setOnClickListener { onPreview(voice) }
            selectButton.setOnClickListener { onSelect(voice) }
        }
    }
}
