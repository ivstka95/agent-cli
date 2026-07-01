package org.example.rag.retrieve

/**
 * Which Day-21 index a retrieval query targets. The [fileName] is the chunking-strategy name baked
 * into the on-disk index file (`index-<fileName>.json`) and into each chunk's `metadata.strategy`.
 *
 * [STRUCTURAL] is the default — semantically coherent chunks retrieve better than fixed windows.
 */
enum class IndexStrategy(val fileName: String) {
    STRUCTURAL("structural"),
    FIXED("fixed-size"),
    ;

    companion object {
        /**
         * Parses a user/env token to a strategy, or `null` if unrecognized (the nullable-validation
         * idiom mirrors the app's `TransitionMode.parse`). Accepts `structural` / `fixed` / `fixed-size`,
         * case- and whitespace-insensitive.
         */
        fun parse(raw: String): IndexStrategy? = when (raw.trim().lowercase()) {
            "structural" -> STRUCTURAL
            "fixed", "fixed-size" -> FIXED
            else -> null
        }
    }
}
