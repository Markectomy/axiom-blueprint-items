package dev.markectomy.blueprints.domain

/** Portable library link. Build contents and absolute paths never enter inventory data. */
data class BlueprintReference(val version: Int = 1, val path: String, val name: String) {
    init {
        require(version == 1) { "Unsupported blueprint reference version: $version" }
        require(path.length in 1..2048) { "Invalid blueprint path length" }
        require(!path.startsWith('/') && '\\' !in path && ':' !in path) { "Blueprint path must be relative and portable" }
        require(path.none { it.code < 32 || it.code == 127 }) { "Blueprint path contains control characters" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Blueprint path contains an invalid segment" }
        require(name.isNotBlank() && name.length <= 512 && name.none { it.code < 32 }) { "Invalid blueprint display name" }
    }
}
