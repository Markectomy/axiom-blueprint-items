package dev.markectomy.blueprints.data.preview

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Accessed exclusively by the preview worker; construction performs no filesystem work. */
class PreviewDiskCache(private val directory: Path) {
    private data class CachedFile(val path: Path, val size: Long, val modified: FileTime)
    private var initialized = false

    fun keyFor(source: Path): String {
        val normalized = source.toAbsolutePath().normalize()
        val attributes = Files.readAttributes(normalized, BasicFileAttributes::class.java)
        require(attributes.isRegularFile)
        val modified = attributes.lastModifiedTime().toInstant()
        val identity = "$SCHEMA\u0000$normalized\u0000${attributes.size()}\u0000${modified.epochSecond}\u0000${modified.nano}"
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }

    fun read(key: String): IntArray? {
        if (!KEY.matches(key)) return null
        initialize()
        return runCatching {
            val path = directory.resolve(key + SUFFIX)
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() !in 1..MAX_FILE_BYTES.toLong()) return@runCatching null
            val compressed = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_FILE_BYTES + 1) }
            if (compressed.size > MAX_FILE_BYTES) return@runCatching null
            val pixels = DataInputStream(GZIPInputStream(ByteArrayInputStream(compressed))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != SCHEMA || input.readInt() != PIXEL_COUNT) {
                    return@runCatching null
                }
                val result = IntArray(PIXEL_COUNT) { input.readInt() }
                if (input.read() != -1) return@runCatching null
                result
            }
            runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())) }
            pixels
        }.getOrNull()
    }

    fun write(key: String, pixels: IntArray) {
        if (!KEY.matches(key) || pixels.size != PIXEL_COUNT) return
        initialize()
        runCatching {
            val compressed = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(GZIPOutputStream(bytes)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(SCHEMA)
                    output.writeInt(PIXEL_COUNT)
                    pixels.forEach(output::writeInt)
                }
            }.toByteArray()
            if (compressed.size > MAX_FILE_BYTES) return@runCatching
            val target = directory.resolve(key + SUFFIX)
            val temporary = directory.resolve("$key.preview-${UUID.randomUUID()}.tmp")
            try {
                Files.write(temporary, compressed, CREATE_NEW, WRITE)
                try {
                    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            trim()
        }
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            Files.createDirectories(directory)
            Files.newDirectoryStream(directory).use { entries ->
                entries.forEach { path ->
                    if (TEMPORARY.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            }
            trim()
        }
    }

    private fun trim() {
        val files = mutableListOf<CachedFile>()
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { path ->
                if (CACHE_FILE.matches(path.fileName.toString())) {
                    runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                        .getOrNull()?.takeIf { it.isRegularFile }?.let { attributes ->
                            files.add(CachedFile(path, attributes.size(), attributes.lastModifiedTime()))
                        }
                }
            }
        }
        var bytes = files.sumOf { it.size }
        var count = files.size
        for (file in files.sortedWith(compareBy<CachedFile> { it.modified }.thenBy { it.path.toString() })) {
            if (bytes <= MAX_DISK_BYTES && count <= MAX_FILES) break
            if (runCatching { Files.deleteIfExists(file.path) }.getOrDefault(false)) {
                bytes -= file.size
                count--
            }
        }
    }

    companion object {
        private const val MAGIC = 0x41425049
        private const val SCHEMA = 1
        private const val PIXEL_COUNT = 16 * 64 * 64
        private const val MAX_FILE_BYTES = 300 * 1024
        private const val MAX_DISK_BYTES = 32L * 1024 * 1024
        private const val MAX_FILES = 512
        private const val SUFFIX = ".preview.gz"
        private val KEY = Regex("[0-9a-f]{64}")
        private val CACHE_FILE = Regex("[0-9a-f]{64}\\.preview\\.gz")
        private val TEMPORARY = Regex("[0-9a-f]{64}\\.preview-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
    }
}
