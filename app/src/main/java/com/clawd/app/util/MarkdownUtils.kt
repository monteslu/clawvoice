package com.clawd.app.util

/**
 * Utilities for handling markdown text.
 */
object MarkdownUtils {

    /**
     * Strip markdown formatting for TTS reading.
     * Removes formatting characters while preserving readable text.
     */
    fun stripForTts(markdown: String): String {
        var text = markdown

        // Remove code blocks (``` ... ```)
        text = text.replace(Regex("```[\\s\\S]*?```"), " code block ")

        // Remove inline code (`code`)
        text = text.replace(Regex("`([^`]+)`")) { it.groupValues[1] }

        // Remove images ![alt](url)
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]+\\)")) {
            it.groupValues[1].ifEmpty { "image" }
        }

        // Remove links [text](url) -> keep text
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)")) { it.groupValues[1] }

        // Remove bold **text** or __text__
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
        text = text.replace(Regex("__([^_]+)__")) { it.groupValues[1] }

        // Remove italic *text* or _text_
        text = text.replace(Regex("\\*([^*]+)\\*")) { it.groupValues[1] }
        text = text.replace(Regex("(?<![\\w])_([^_]+)_(?![\\w])")) { it.groupValues[1] }

        // Remove strikethrough ~~text~~
        text = text.replace(Regex("~~([^~]+)~~")) { it.groupValues[1] }

        // Remove headers (# ## ### etc)
        text = text.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")

        // Remove horizontal rules
        text = text.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")

        // Replace tables with "table" - reading cells aloud is confusing
        // Match consecutive lines starting with |
        text = text.replace(Regex("(^\\|.+\\|\\s*\\n?)+", RegexOption.MULTILINE), " table ")

        // Remove blockquotes >
        text = text.replace(Regex("^>\\s*", RegexOption.MULTILINE), "")

        // Remove list markers (* - + or 1.)
        text = text.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")

        // Clean up multiple spaces and newlines
        text = text.replace(Regex("\\n{3,}"), "\n\n")
        text = text.replace(Regex(" {2,}"), " ")

        return text.trim()
    }
}
