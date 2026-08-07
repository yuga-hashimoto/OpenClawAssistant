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

        // ⚡ Bolt Optimization: Removed string allocation overhead from split("\n") and joinToString("\n").
        // Used StringBuilder and lineSequence() directly to reduce GC pressure for frequent large Markdown blobs.
        val sb = StringBuilder(raw.length)
        var inMetaBlock = false
        var inFencedJson = false
        var firstLine = true

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")

            if (!inMetaBlock && inboundContextHeaders.any { line.startsWith(it) }) {
                inMetaBlock = true
                inFencedJson = false
                return@forEach
            }

            if (inMetaBlock) {
                val trimmed = line.trim()
                if (!inFencedJson && trimmed == "```json") {
                    inFencedJson = true
                    return@forEach
                }
                if (inFencedJson) {
                    if (trimmed == "```") {
                        inMetaBlock = false
                        inFencedJson = false
                    }
                    return@forEach
                }
                if (trimmed.isEmpty()) {
                    return@forEach
                }
                inMetaBlock = false
            }

            if (firstLine && line.isEmpty()) return@forEach

            if (!firstLine) {
                sb.append('\n')
            }
            sb.append(line)
            firstLine = false
        }

        return sb.toString().replace(leadingNewlinesRegex, "")
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
