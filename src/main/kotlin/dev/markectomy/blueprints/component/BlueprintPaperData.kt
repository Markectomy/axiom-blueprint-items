package dev.markectomy.blueprints.component

import dev.markectomy.blueprints.domain.BlueprintReference
import net.minecraft.core.component.DataComponentGetter
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemInstance
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.component.CustomData
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.Optional

/** Vanilla custom data keeps library links portable without a server-side component registry. */
object BlueprintPaperData {
    private const val NAMESPACE = "axiom_blueprint_items"
    private const val CACHE_LIMIT = 1024

    private class IdentityKey(data: CustomData) {
        private val hash = System.identityHashCode(data)
        private val data = WeakReference(data)

        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            other is IdentityKey && data.get()?.let { it === other.data.get() } == true
    }

    private val references = object : LinkedHashMap<IdentityKey, Optional<BlueprintReference>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdentityKey, Optional<BlueprintReference>>): Boolean =
            size > CACHE_LIMIT
    }

    fun create(reference: BlueprintReference): CustomData {
        val root = CompoundTag()
        root.put(NAMESPACE, BlueprintReferenceCodecs.CODEC.encodeStart(NbtOps.INSTANCE, reference).orThrow)
        return CustomData.of(root).also { data ->
            synchronized(references) { references[IdentityKey(data)] = Optional.of(reference) }
        }
    }

    fun reference(data: CustomData): BlueprintReference? = synchronized(references) {
        val key = IdentityKey(data)
        references[key]?.let { return@synchronized it.orElse(null) }
        val nested = data.copyTag().get(NAMESPACE)
        val reference = nested?.let { BlueprintReferenceCodecs.CODEC.parse(NbtOps.INSTANCE, it).result().orElse(null) }
        references[key] = Optional.ofNullable(reference)
        reference
    }

    /** Creative packet boundary only; unknown integrations are left to their own server compatibility checks. */
    fun hasPortablePayload(stack: ItemStack): Boolean = PortablePayload().stack(stack, 0)

    private class PortablePayload {
        private var remainingItems = 512
        private var remainingTags = 16_384

        fun stack(stack: ItemStack, depth: Int): Boolean = stack.isEmpty ||
            item(stack, stack.componentsPatch, depth)

        private fun template(template: ItemStackTemplate, depth: Int): Boolean =
            item(template, template.components(), depth)

        private fun item(item: ItemInstance, patch: DataComponentPatch, depth: Int): Boolean {
            if (depth > 16 || --remainingItems < 0 ||
                BuiltInRegistries.ITEM.getKey(item.typeHolder().value()).namespace == NAMESPACE ||
                patch.entrySet().any { BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(it.key)?.namespace == NAMESPACE }
            ) return false
            if (item.get(DataComponents.ITEM_MODEL)?.namespace == NAMESPACE ||
                item.get(DataComponents.TOOLTIP_DISPLAY)?.hiddenComponents()?.any {
                    BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(it)?.namespace == NAMESPACE
                } == true ||
                item.get(DataComponents.POT_DECORATIONS)?.ordered()?.any {
                    BuiltInRegistries.ITEM.getKey(it).namespace == NAMESPACE
                } == true ||
                item.get(DataComponents.REPAIRABLE)?.items()?.any {
                    BuiltInRegistries.ITEM.getKey(it.value()).namespace == NAMESPACE
                } == true
            ) return false
            if (!templates(item.get(DataComponents.CONTAINER)?.nonEmptyItems(), depth) ||
                !templates(item.get(DataComponents.BUNDLE_CONTENTS)?.items(), depth) ||
                !templates(item.get(DataComponents.CHARGED_PROJECTILES)?.items(), depth)
            ) return false
            val remainder = item.get(DataComponents.USE_REMAINDER)?.convertInto()
            if (remainder != null && !template(remainder, depth + 1)) return false
            val absorbed = item.get(DataComponents.SULFUR_CUBE_CONTENT)?.absorbedBlockItemStack()
            if (absorbed != null && !template(absorbed, depth + 1)) return false
            return rawData(item, depth)
        }

        private fun templates(items: Iterable<ItemStackTemplate>?, depth: Int): Boolean =
            items == null || items.all { template(it, depth + 1) }

        private fun rawData(item: DataComponentGetter, depth: Int): Boolean {
            val entity = item.get(DataComponents.ENTITY_DATA)?.copyTagWithoutId()
            if (entity != null && !tag(entity, depth + 1)) return false
            val blockEntity = item.get(DataComponents.BLOCK_ENTITY_DATA)?.copyTagWithoutId()
            if (blockEntity != null && !tag(blockEntity, depth + 1)) return false
            val bucket = item.get(DataComponents.BUCKET_ENTITY_DATA)
            if (bucket != null && !tag(bucket.copyTag(), depth + 1)) return false
            val data = item.get(DataComponents.CUSTOM_DATA)
            return data == null || tag(data.copyTag(), depth + 1)
        }

        private fun tag(value: Tag, depth: Int): Boolean {
            if (depth > 16 || --remainingTags < 0) return false
            return when (value) {
                is CompoundTag -> value.entrySet().all { (key, child) ->
                    !key.removePrefix("!").startsWith("$NAMESPACE:") &&
                        (key != "id" || child !is StringTag || !child.value().startsWith("$NAMESPACE:")) &&
                        tag(child, depth + 1)
                }
                is ListTag -> value.all { tag(it, depth + 1) }
                else -> true
            }
        }
    }
}
