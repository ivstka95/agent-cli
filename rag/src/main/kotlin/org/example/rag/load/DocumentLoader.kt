package org.example.rag.load

import org.example.rag.model.DocType
import org.example.rag.model.Document
import java.io.File

/**
 * Loads the repository documents to index: the top-level docs ([docFiles]) plus every `.kt` source
 * under [ktRoots], excluding build/output directories. Stored `path` is repo-relative (forward
 * slashes) so metadata is stable across platforms.
 */
class DocumentLoader(
    repoRoot: File,
    private val docFiles: List<String> = DEFAULT_DOC_FILES,
    private val ktRoots: List<String> = DEFAULT_KT_ROOTS,
) {
    private val root: File = repoRoot.absoluteFile.normalize()

    fun load(): List<Document> {
        val docs = mutableListOf<Document>()

        docFiles.forEach { rel ->
            val file = File(root, rel)
            if (file.isFile) docs += toDocument(file)
        }

        ktRoots.forEach { rel ->
            val dir = File(root, rel)
            if (dir.isDirectory) {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }
                    .mapNotNull { file ->
                        val relPath = root.toPath().relativize(file.absoluteFile.normalize().toPath())
                        if (relPath.any { seg -> seg.toString() in EXCLUDED_DIRS }) null
                        else toDocument(file, relPath.toString().replace(File.separatorChar, '/'))
                    }
                    .forEach { docs += it }
            }
        }
        return docs
    }

    private fun toDocument(file: File): Document {
        val rel = root.toPath().relativize(file.absoluteFile.normalize().toPath())
            .toString().replace(File.separatorChar, '/')
        return toDocument(file, rel)
    }

    private fun toDocument(file: File, relPath: String): Document {
        val type = if (file.extension.equals("kt", ignoreCase = true)) DocType.KT else DocType.MD
        return Document(path = relPath, fileName = file.name, type = type, content = file.readText())
    }

    companion object {
        val DEFAULT_DOC_FILES = listOf("README.md", "PLAN.md", "mcp/PLAN.md")
        val DEFAULT_KT_ROOTS = listOf("app", "mcp")
        private val EXCLUDED_DIRS = setOf("build", ".gradle", "out")
    }
}
