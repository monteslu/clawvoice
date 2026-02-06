package com.clawd.app.network.protocol

/**
 * Filters protocol markers from assistant messages.
 * These markers are for internal gateway communication and should not be displayed.
 */
object MessageFilter {

    data class ProcessedMessage(
        val text: String,
        val mediaPath: String? = null,
        val shouldDisplay: Boolean = true
    )

    /**
     * Process an assistant message, filtering out protocol markers.
     */
    fun processAssistantMessage(rawContent: String): ProcessedMessage {
        val trimmed = rawContent.trim()

        // Check for silent markers (entire message is just the marker)
        if (trimmed == "HEARTBEAT_OK" || trimmed == "NO_REPLY") {
            return ProcessedMessage(text = "", shouldDisplay = false)
        }

        // Process line by line for MEDIA: markers and other filters
        val lines = rawContent.lines()
        val displayLines = mutableListOf<String>()
        var mediaPath: String? = null

        for (line in lines) {
            val lineTrimmed = line.trim()
            when {
                lineTrimmed.startsWith("MEDIA:") -> {
                    mediaPath = lineTrimmed.removePrefix("MEDIA:").trim()
                }
                // Skip HEARTBEAT_OK or NO_REPLY lines mixed in
                lineTrimmed == "HEARTBEAT_OK" || lineTrimmed == "NO_REPLY" -> {
                    // skip
                }
                else -> displayLines.add(line)
            }
        }

        // Strip reply tags
        var finalText = displayLines.joinToString("\n")
        finalText = finalText
            .replace(Regex("""\[\[\s*reply_to_current\s*]]"""), "")
            .replace(Regex("""\[\[\s*reply_to:\s*[^\]]+\s*]]"""), "")
            .trim()

        return ProcessedMessage(
            text = finalText,
            mediaPath = mediaPath,
            shouldDisplay = finalText.isNotEmpty() || mediaPath != null
        )
    }

    /**
     * Quick check if a message should be displayed at all.
     */
    fun shouldDisplay(message: String): Boolean {
        val trimmed = message.trim()
        return trimmed != "HEARTBEAT_OK" && trimmed != "NO_REPLY" && trimmed.isNotEmpty()
    }
}
