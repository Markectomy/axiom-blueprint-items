package dev.markectomy.blueprints.domain

enum class PreviewStyle(val serializedName: String) {
    STATIC("static"),
    LIGHTWEIGHT("lightweight"),
    HIGH_QUALITY("high_quality");

    companion object {
        fun fromSerializedName(value: String?): PreviewStyle? = entries.firstOrNull { it.serializedName == value }
    }
}
