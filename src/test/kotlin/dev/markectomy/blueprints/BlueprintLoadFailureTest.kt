package dev.markectomy.blueprints

import dev.markectomy.blueprints.domain.BlueprintLoadFailure
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BlueprintLoadFailureTest {
    @Test
    fun `missing files and file permissions have distinct explanations`() {
        assertEquals(BlueprintLoadFailure.MISSING_SOURCE, BlueprintLoadFailure.classify(NoSuchFileException("private/library/a.bp")))
        assertEquals(BlueprintLoadFailure.ACCESS_DENIED, BlueprintLoadFailure.classify(AccessDeniedException("private/library/a.bp")))
    }

    @Test
    fun `missing blocks are not reported as missing files`() {
        val error = IOException("Blueprint failed", IllegalStateException(
            "(Unknown registry key in ResourceKey[minecraft:root / minecraft:block]: example:missing -> using default)"
        ))
        assertEquals(BlueprintLoadFailure.UNAVAILABLE_BLOCKS, BlueprintLoadFailure.classify(error))
    }

    @Test
    fun `other parsing failures keep private exception text out of the explanation`() {
        val privateDetail = "/private/library/blueprint.bp"
        val failure = BlueprintLoadFailure.classify(IOException(privateDetail))
        assertEquals(BlueprintLoadFailure.INVALID_OR_INCOMPATIBLE, failure)
        assertFalse(failure.description.contains(privateDetail))
    }
}
