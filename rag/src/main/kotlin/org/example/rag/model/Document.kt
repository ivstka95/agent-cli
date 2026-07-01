package org.example.rag.model

/** The kinds of source we index. Drives which structural splitter [StructuralChunking] applies. */
enum class DocType { MD, KT }

/**
 * One source file loaded for indexing.
 *
 * @param path repo-relative path (used as chunk `source` metadata, e.g. `app/.../Foo.kt`).
 * @param fileName the bare file name (e.g. `Foo.kt`).
 * @param type MD or KT — selects the structural chunking rule.
 * @param content the full file text.
 */
data class Document(
    val path: String,
    val fileName: String,
    val type: DocType,
    val content: String,
)
