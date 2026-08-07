package com.openclaw.assistant.chat

/**
 * Strips inbound metadata injected by the OpenClaw gateway before displaying messages.
 * Keeps in sync with iOS ChatMarkdownPreprocessor.swift and
 * `src/auto-reply/reply/strip-inbound-meta.ts`.
 */
object ChatMarkdownPreprocessor {

    private val inboundContextHeaders = listOf(
        "Conversation info (untrusted metadata):",
        "Sender (untrusted metadata):",
        "Thread starter (untrusted, for context):",
        "Replied message (untrusted, for context):",
        "Forwarded message context (untrusted metadata):",
        "Chat history since last reply (untrusted, for context):",
    )

    /** Pattern matching `[Mon 2026-02-23 21:54 GMT+9]` at start of a line */
    private val timestampPrefixRegex = Regex(
        """(?m)^\[[A-Za-z]{3}\s+\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?\s+(?:GMT|UTC)[+-]?\d{0,2}\]\s*"""
    )

    private val leadingNewlinesRegex = Regex("^\\n+")

    fun preprocess(raw: String): String {
        val withoutContextBlocks = stripInboundContextBlocks(raw)
        val withoutTimestamps = stripPrefixedTimestamps(withoutContextBlocks)
        return normalize(withoutTimestamps)
    }

    private fun stripInboundContextBlocks(raw: String): String {
        if (inboundContextHeaders.none { raw.contains(it) }) return raw

        val normalized = raw.replace("\r\n", "\n")
        // ⚡ Bolt Optimization: Replaced split("\n") with lineSequence() to avoid allocating an intermediate list.
        // Replaced mutableListOf<String>().joinToString("\n") with StringBuilder to reduce string allocation overhead.
        val outputBuilder = java.lang.StringBuilder(raw.length)
        var inMetaBlock = false
        var inFencedJson = false
        var isFirstLine = true

        for (line in normalized.lineSequence()) {
            if (!inMetaBlock && inboundContextHeaders.any { line.startsWith(it) }) {
                inMetaBlock = true
                inFencedJson = false
                continue
            }

            if (inMetaBlock) {
                if (!inFencedJson && line.trim() == "```json") {
                    inFencedJson = true
                    continue
                }
                if (inFencedJson) {
                    if (line.trim() == "```") {
                        inMetaBlock = false
                        inFencedJson = false
                    }
                    continue
                }
                if (line.trim().isEmpty()) {
                    continue
                }
                inMetaBlock = false
            }

            if (!isFirstLine) {
                outputBuilder.append("\n")
            }
            outputBuilder.append(line)
            isFirstLine = false
        }

        // ⚡ Bolt Optimization: Replaced regex replacement for leading newlines with native `trimStart('\n')`
        return outputBuilder.toString().trimStart('\n')
    }

    private fun stripPrefixedTimestamps(raw: String): String =
        timestampPrefixRegex.replace(raw, "")

    private fun normalize(raw: String): String {
        var out = raw.replace("\r\n", "\n")
        out = out.replace("\n\n\n", "\n\n")
        out = out.replace("\n\n\n", "\n\n")
        return out.trim()
    }
}
