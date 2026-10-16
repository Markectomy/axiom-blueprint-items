package dev.markectomy.blueprints.client.library

import dev.markectomy.blueprints.client.integration.Axiom613Adapter
import dev.markectomy.blueprints.data.LibraryPaths
import dev.markectomy.blueprints.domain.BlueprintEntry
import dev.markectomy.blueprints.domain.BlueprintFilters
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class LibrarySnapshot(
    val entries: List<BlueprintEntry> = emptyList(),
    val loading: Boolean = false,
    val errorCount: Int = 0,
    val error: String? = null,
    val revision: Long = 0,
)

/** Client-thread snapshots, one background scan at a time; the library is never rewritten. */
object BlueprintLibrary : AutoCloseable {
    private val logger = LoggerFactory.getLogger("AxiomBlueprintItems/Library")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "blueprint-library-index").apply { isDaemon = true }
    }
    var snapshot: LibrarySnapshot = LibrarySnapshot()
        private set
    private var scanning = false
    private var refreshAgain = false
    private var closed = false
    private var activeRoot: Path? = null
    @Volatile private var namesByPath: Map<String, String> = emptyMap()
    var onRefresh: () -> Unit = {}

    fun name(path: String): String? = namesByPath[path]

    fun refresh() {
        if (closed) return
        if (scanning) {
            refreshAgain = true
            return
        }
        val root = Axiom613Adapter.libraryRoot()
        if (root != activeRoot) {
            snapshot = LibrarySnapshot(revision = snapshot.revision + 1)
            namesByPath = emptyMap()
            activeRoot = root
        }
        scanning = true
        snapshot = snapshot.copy(loading = true, error = null, revision = snapshot.revision + 1)
        onRefresh()
        try {
            worker.execute {
                val result = runCatching {
                    val scanned = scan(root)
                    scanned to scanned.first.associate { it.reference.path to it.name }
                }
                Minecraft.getInstance().execute completion@ {
                    if (closed) return@completion
                    scanning = false
                    result.fold(
                        onSuccess = { (scanned, names) ->
                            val (entries, errors) = scanned
                            namesByPath = names
                            snapshot = LibrarySnapshot(entries, false, errors, null, snapshot.revision + 1)
                        },
                        onFailure = { error ->
                            logger.warn("Unable to scan blueprint library", error)
                            namesByPath = emptyMap()
                            snapshot = LibrarySnapshot(error = "Cannot read the Axiom blueprint directory.", revision = snapshot.revision + 1)
                        },
                    )
                    if (refreshAgain || Axiom613Adapter.libraryRoot() != root) {
                        refreshAgain = false
                        refresh()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            scanning = false
        }
    }

    private fun scan(root: Path): Pair<List<BlueprintEntry>, Int> {
        require(Files.isDirectory(root)) { "Axiom blueprint directory is missing" }
        val scanRoot = root.toRealPath()
        val paths = LibraryPaths(scanRoot)
        val entries = mutableListOf<BlueprintEntry>()
        var errors = 0
        Files.walkFileTree(scanRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                return if (Thread.currentThread().isInterrupted) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Thread.currentThread().isInterrupted) return FileVisitResult.TERMINATE
                if (!file.fileName.toString().endsWith(".bp", ignoreCase = true)) return FileVisitResult.CONTINUE
                try {
                    val reference = paths.reference(file, file.fileName.toString().removeSuffix(".bp"))
                    val resolved = paths.resolve(reference)
                    val metadata = Axiom613Adapter.readHeader(resolved)
                    val name = metadata.name.ifBlank { reference.name }
                    entries += BlueprintEntry(reference.copy(name = name), name, metadata.tags)
                } catch (error: Exception) {
                    errors++
                    logger.debug("Skipping unreadable blueprint {}", file, error)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                errors++
                logger.debug("Skipping unreadable blueprint path {}", file, exc)
                return FileVisitResult.CONTINUE
            }
        })
        return entries.sortedWith(BlueprintFilters.ORDER) to errors
    }

    override fun close() {
        closed = true
        worker.shutdownNow()
    }
}
