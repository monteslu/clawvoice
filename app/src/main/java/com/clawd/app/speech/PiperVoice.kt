package com.clawd.app.speech

/**
 * Sherpa-ONNX compatible Piper voice models.
 * These are pre-packaged models from k2-fsa/sherpa-onnx releases.
 */
enum class PiperVoice(
    val displayName: String,
    val description: String,
    val downloadUrl: String,
    val modelFileName: String,
    val sampleRate: Int = 22050,
    val speakerId: Int = 0,
    val testPhrase: String = "Hello, this is how I sound. Is this voice good for you?"
) {
    // MEDIUM quality - good balance of speed and quality
    LESSAC(
        displayName = "Lessac",
        description = "Expressive American female (~25MB, fast)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        modelFileName = "en_US-lessac-medium.onnx",
        testPhrase = "Hi there. I'm here to help you with anything you need."
    ),
    JENNY(
        displayName = "Jenny",
        description = "Clear British female (~25MB, fast)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-jenny_dioco-medium.tar.bz2",
        modelFileName = "en_GB-jenny_dioco-medium.onnx",
        testPhrase = "Good day, I'm Jenny. Lovely to meet you."
    ),
    KRISTIN(
        displayName = "Kristin",
        description = "Natural American female (~25MB, fast)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-kristin-medium.tar.bz2",
        modelFileName = "en_US-kristin-medium.onnx",
        testPhrase = "Hello, I'm Kristin. Nice to meet you."
    ),
    CORI(
        displayName = "Cori",
        description = "Soft British female (medium, ~25MB)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-cori-medium.tar.bz2",
        modelFileName = "en_GB-cori-medium.onnx",
        testPhrase = "Hello, I'm Cori. How may I assist you today?"
    ),
    // HIGH quality option for those who want best sound
    LESSAC_HD(
        displayName = "Lessac HD",
        description = "Best quality American female (~63MB, slower)",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-high.tar.bz2",
        modelFileName = "en_US-lessac-high.onnx",
        testPhrase = "Hi there. I'm the high definition version. Notice the quality."
    );

    val folderName: String get() = name.lowercase()
    val tokensFileName: String get() = "tokens.txt"

    companion object {
        const val ESPEAK_DATA_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"
    }
}
