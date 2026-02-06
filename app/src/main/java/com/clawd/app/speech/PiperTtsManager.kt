package com.clawd.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Piper TTS manager using Sherpa-ONNX for high-quality neural voice synthesis.
 */
class PiperTtsManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var currentVoice: PiperVoice? = null
    private var speakJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Pair<PiperVoice, Int>?>(null)
    val downloadProgress: StateFlow<Pair<PiperVoice, Int>?> = _downloadProgress.asStateFlow()

    private val voicesDir: File get() = File(context.filesDir, "piper_voices")
    private val espeakDataDir: File get() = File(voicesDir, "espeak-ng-data")

    fun getAvailableVoices(): List<PiperVoice> = PiperVoice.entries

    fun initialize(voiceName: String? = null, onReady: () -> Unit = {}) {
        val voice = voiceName?.let {
            try { PiperVoice.valueOf(it) } catch (e: Exception) { null }
        } ?: PiperVoice.LESSAC

        scope.launch {
            val success = loadVoice(voice)
            withContext(Dispatchers.Main) {
                if (success) {
                    Log.d(TAG, "TTS initialized successfully with voice: ${voice.displayName}")
                } else {
                    Log.w(TAG, "TTS initialization failed, will try again on first use")
                }
                onReady()
            }
        }
    }

    suspend fun loadVoice(voice: PiperVoice): Boolean = withContext(Dispatchers.IO) {
        if (currentVoice == voice && tts != null) return@withContext true

        _isLoading.value = true
        try {
            // Ensure espeak-ng-data is downloaded
            if (!isEspeakDataDownloaded()) {
                Log.d(TAG, "Downloading espeak-ng-data...")
                val downloaded = downloadEspeakData()
                if (!downloaded) {
                    Log.e(TAG, "Failed to download espeak-ng-data")
                    return@withContext false
                }
            }

            // Download voice if needed
            if (!isVoiceDownloaded(voice)) {
                val downloaded = downloadVoice(voice)
                if (!downloaded) {
                    Log.e(TAG, "Failed to download voice: ${voice.name}")
                    return@withContext false
                }
            }

            // Release previous
            tts?.release()
            tts = null

            // Get paths
            val voiceDir = File(voicesDir, voice.folderName)
            val modelPath = File(voiceDir, voice.modelFileName).absolutePath
            val tokensPath = File(voiceDir, voice.tokensFileName).absolutePath
            val dataDir = espeakDataDir.absolutePath

            Log.d(TAG, "Loading model: $modelPath")
            Log.d(TAG, "Loading tokens: $tokensPath")
            Log.d(TAG, "Data dir: $dataDir")

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = tokensPath,
                dataDir = dataDir,
                dictDir = ""
            )

            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 4,
                debug = false,
                provider = "cpu"
            )

            val ttsConfig = OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 2
            )

            tts = OfflineTts(assetManager = null, config = ttsConfig)
            currentVoice = voice
            Log.d(TAG, "Loaded voice: ${voice.displayName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice: ${voice.name}", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    private fun isEspeakDataDownloaded(): Boolean {
        return espeakDataDir.exists() && espeakDataDir.isDirectory &&
               (espeakDataDir.listFiles()?.isNotEmpty() == true)
    }

    fun isVoiceDownloaded(voice: PiperVoice): Boolean {
        val voiceDir = File(voicesDir, voice.folderName)
        val modelFile = File(voiceDir, voice.modelFileName)
        val tokensFile = File(voiceDir, voice.tokensFileName)
        return modelFile.exists() && modelFile.length() > 1000 &&
               tokensFile.exists() && tokensFile.length() > 100
    }

    private suspend fun downloadEspeakData(): Boolean = withContext(Dispatchers.IO) {
        try {
            voicesDir.mkdirs()
            val tempFile = File(voicesDir, "espeak-ng-data.tar.bz2")

            Log.d(TAG, "Downloading espeak-ng-data...")
            downloadFile(PiperVoice.ESPEAK_DATA_URL, tempFile) { }

            Log.d(TAG, "Extracting espeak-ng-data...")
            extractTarBz2(tempFile, voicesDir)
            tempFile.delete()

            Log.d(TAG, "espeak-ng-data ready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download espeak-ng-data", e)
            false
        }
    }

    suspend fun downloadVoice(voice: PiperVoice): Boolean = withContext(Dispatchers.IO) {
        try {
            voicesDir.mkdirs()
            val voiceDir = File(voicesDir, voice.folderName)
            voiceDir.mkdirs()

            _downloadProgress.value = voice to 0
            val tempFile = File(voicesDir, "${voice.folderName}.tar.bz2")

            Log.d(TAG, "Downloading voice: ${voice.downloadUrl}")
            downloadFile(voice.downloadUrl, tempFile) { percent ->
                _downloadProgress.value = voice to (percent * 0.9).toInt()
            }

            Log.d(TAG, "Extracting voice model...")
            _downloadProgress.value = voice to 90
            extractTarBz2(tempFile, voiceDir)
            tempFile.delete()

            // Move files from nested directory if needed
            moveNestedFiles(voiceDir)

            _downloadProgress.value = voice to 100
            _downloadProgress.value = null
            Log.d(TAG, "Downloaded voice: ${voice.displayName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${voice.name}", e)
            _downloadProgress.value = null
            false
        }
    }

    private fun moveNestedFiles(voiceDir: File) {
        // The tar might extract to a subdirectory, move files up if needed
        val files = voiceDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                val nestedFiles = file.listFiles() ?: continue
                for (nested in nestedFiles) {
                    val dest = File(voiceDir, nested.name)
                    if (!dest.exists()) {
                        nested.renameTo(dest)
                    }
                }
                file.deleteRecursively()
            }
        }
    }

    private fun downloadFile(urlString: String, destFile: File, onProgress: (Float) -> Unit) {
        Log.d(TAG, "Downloading: $urlString")
        val url = URL(urlString)
        val connection = url.openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.connect()

        val totalSize = connection.contentLengthLong
        var downloadedSize = 0L
        var lastReportedPercent = -1

        connection.getInputStream().use { input ->
            destFile.outputStream().use { output ->
                val buffer = ByteArray(65536)  // Larger buffer for faster downloads
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedSize += bytesRead
                    if (totalSize > 0) {
                        val percent = (downloadedSize * 100 / totalSize).toInt()
                        // Only report every 2% to avoid UI flood
                        if (percent >= lastReportedPercent + 2) {
                            lastReportedPercent = percent
                            onProgress(percent.toFloat())
                        }
                    }
                }
            }
        }
        Log.d(TAG, "Downloaded ${destFile.name}: ${destFile.length()} bytes")
    }

    private fun extractTarBz2(tarBz2File: File, destDir: File) {
        destDir.mkdirs()

        tarBz2File.inputStream().buffered().use { fileIn ->
            BZip2CompressorInputStream(fileIn).use { bzIn ->
                TarArchiveInputStream(bzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        val engine = tts
        if (engine == null) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        speakJob?.cancel()
        stop()

        speakJob = scope.launch {
            _isSpeaking.value = true
            try {
                Log.d(TAG, "Generating speech for: ${text.take(50)}...")
                val sid = currentVoice?.speakerId ?: 0
                val audio = engine.generate(text, sid = sid, speed = 1.0f)

                if (audio.samples.isEmpty()) {
                    Log.w(TAG, "No audio generated")
                    return@launch
                }

                Log.d(TAG, "Generated ${audio.samples.size} samples at ${audio.sampleRate}Hz")
                playAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "Speech generation failed", e)
            } finally {
                _isSpeaking.value = false
            }
        }
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        // Convert float [-1, 1] to 16-bit PCM
        val pcmData = ShortArray(samples.size)
        for (i in samples.indices) {
            val sample = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcmData[i] = sample.toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack?.release()

        val completionLatch = java.util.concurrent.CountDownLatch(1)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize * 2, pcmData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track

        // Set up completion listener using marker at end of audio
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                Log.d(TAG, "Playback marker reached - audio complete")
                completionLatch.countDown()
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })

        // Write all data and set marker at end
        track.write(pcmData, 0, pcmData.size)
        track.setNotificationMarkerPosition(pcmData.size)
        track.play()

        // Wait for actual playback completion (with timeout fallback)
        val maxWaitMs = (pcmData.size * 1000L / sampleRate) + 2000
        val completed = completionLatch.await(maxWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)

        if (!completed) {
            Log.w(TAG, "Playback completion timeout, stopping anyway")
        }

        try {
            track.stop()
        } catch (e: Exception) {
            // Ignore - may already be stopped
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        _isSpeaking.value = false
    }

    fun shutdown() {
        stop()
        audioTrack?.release()
        audioTrack = null
        tts?.release()
        tts = null
    }

    fun getCurrentVoice(): PiperVoice? = currentVoice

    fun getCurrentVoiceId(): String? = currentVoice?.name

    companion object {
        private const val TAG = "PiperTtsManager"
    }
}
