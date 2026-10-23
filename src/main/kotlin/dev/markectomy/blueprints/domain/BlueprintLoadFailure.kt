package dev.markectomy.blueprints.domain

import java.io.FileNotFoundException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

enum class BlueprintLoadFailure(val description: String) {
    MISSING_SOURCE("The source file is missing. Refresh the library or restore the file."),
    ACCESS_DENIED("The source file cannot be read. Check its file permissions."),
    UNAVAILABLE_BLOCKS("It uses blocks unavailable in this Minecraft instance. Check the client log for their names."),
    INVALID_OR_INCOMPATIBLE("The file is invalid, incompatible, or could not be read. Check the client log for details.");

    companion object {
        fun classify(error: Throwable): BlueprintLoadFailure {
            val causes = generateSequence(error) { it.cause }.take(8).toList()
            return when {
                causes.any { it is NoSuchFileException || it is FileNotFoundException } -> MISSING_SOURCE
                causes.any { it is AccessDeniedException } -> ACCESS_DENIED
                causes.any { cause ->
                    cause.message?.let { "Unknown registry key" in it && "minecraft:block" in it } == true
                } -> UNAVAILABLE_BLOCKS
                else -> INVALID_OR_INCOMPATIBLE
            }
        }
    }
}
