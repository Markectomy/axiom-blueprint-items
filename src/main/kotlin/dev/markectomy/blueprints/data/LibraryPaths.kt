package dev.markectomy.blueprints.data

import dev.markectomy.blueprints.domain.BlueprintReference
import java.nio.file.Files
import java.nio.file.Path

/** Resolve existing files using real paths, rejecting symlink escapes as well as traversal. */
class LibraryPaths(private val root: Path) {
    fun resolve(reference: BlueprintReference): Path {
        val realRoot = root.toRealPath()
        val target = realRoot.resolve(reference.path).toRealPath()
        require(target.startsWith(realRoot) && target != realRoot) { "Blueprint is outside the library" }
        require(Files.isRegularFile(target) && Files.isReadable(target)) { "Blueprint is not a readable file" }
        return target
    }

    fun reference(file: Path, name: String): BlueprintReference {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absoluteFile = file.toAbsolutePath().normalize()
        require(absoluteFile.startsWith(absoluteRoot)) { "Blueprint is outside the library" }
        val relative = absoluteRoot.relativize(absoluteFile).joinToString("/") { it.toString() }
        return BlueprintReference(path = relative, name = name).also { resolve(it) }
    }
}
