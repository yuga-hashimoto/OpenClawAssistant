package com.openclaw.assistant.chat

/**
 * Strips inbound metadata injected by the OpenClaw gateway before displaying messages.
 * Keeps in sync with iOS ChatMarkdownPreprocessor.swift and
 * `src/auto-reply/reply/strip-inbound-meta.ts`.
 */
object ChatMarkdownPreprocessor {

    // ⚡ Bolt: using arrayOf() instead of listOf() to avoid hidden iterator allocations during iteration
    private val inboundContextHeaders = arrayOf(
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

    fun preprocess(raw: String): String {
        val withoutContextBlocks = stripInboundContextBlocks(raw)
        val withoutTimestamps = stripPrefixedTimestamps(withoutContextBlocks)
        return normalize(withoutTimestamps)
    }

    private fun stripInboundContextBlocks(raw: String): String {
        if (inboundContextHeaders.none { raw.contains(it) }) return raw

        val normalized = raw.replace("\r\n", "\n")
        val outputLines = mutableListOf<String>()
        var inMetaBlock = false
        var inFencedJson = false

        for (line in normalized.split("\n")) {
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

            outputLines.add(line)
        }

        return outputLines.joinToString("\n")
            // ⚡ Bolt: Using trimStart is faster and allocates less memory than Regex("^\\n+")
            .trimStart('\n')
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
