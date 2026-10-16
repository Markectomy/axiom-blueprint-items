package dev.markectomy.blueprints.component

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.markectomy.blueprints.domain.BlueprintReference
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec

object BlueprintReferenceCodecs {
    private data class StoredReference(val version: Int, val path: String, val name: String)

    private val STORED: Codec<StoredReference> = RecordCodecBuilder.create { instance ->
        instance.group(
            Codec.INT.fieldOf("version").forGetter(StoredReference::version),
            Codec.STRING.fieldOf("path").forGetter(StoredReference::path),
            Codec.STRING.fieldOf("name").forGetter(StoredReference::name),
        ).apply(instance, ::StoredReference)
    }

    val CODEC: Codec<BlueprintReference> = STORED.comapFlatMap({ stored ->
        try {
            DataResult.success(BlueprintReference(stored.version, stored.path, stored.name))
        } catch (exception: IllegalArgumentException) {
            DataResult.error { exception.message ?: "Invalid blueprint reference" }
        }
    }, { reference -> StoredReference(reference.version, reference.path, reference.name) })

    val STREAM_CODEC: StreamCodec<ByteBuf, BlueprintReference> = ByteBufCodecs.fromCodec(CODEC)
}
