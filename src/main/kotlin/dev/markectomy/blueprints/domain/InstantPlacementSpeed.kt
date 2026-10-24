package dev.markectomy.blueprints.domain

enum class InstantPlacementSpeed(val serializedName: String, val intervalTicks: Int) {
    NORMAL("normal", 6),
    FAST("fast", 4),
    SLOW("slow", 20);

    companion object {
        fun fromSerializedName(value: String?): InstantPlacementSpeed =
            entries.firstOrNull { it.serializedName == value } ?: NORMAL
    }
}
