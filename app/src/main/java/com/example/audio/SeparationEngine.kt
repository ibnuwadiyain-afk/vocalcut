package com.example.audio

/**
 * Audio Separation Engine selector.
 *
 * Provides choice between:
 * 1. SPLEETER_FAST: Spleeter 2-Stem Multi-Core Neural Engine (Fast, battery efficient).
 * 2. UVR_MDXNET: Ultimate Vocal Remover (UVR) MDX-Net Engine (High-resolution, deep instrumental suppression).
 */
enum class SeparationEngine(
    val id: String,
    val titleAr: String,
    val titleEn: String,
    val subtitleAr: String,
    val badge: String
) {
    SPLEETER_FAST(
        id = "spleeter",
        titleAr = "محرك Spleeter 2-Stem السريع",
        titleEn = "Spleeter Fast (2-Stem)",
        subtitleAr = "معالجة فائقة السرعة وخفيفة على الذاكرة والبطارية",
        badge = "⚡ فائق السرعة"
    ),
    UVR_MDXNET(
        id = "uvr_mdxnet",
        titleAr = "محرك UVR MDX-Net الاستوديو",
        titleEn = "Ultimate Vocal Remover (MDX-Net)",
        subtitleAr = "عزل دقيق احترافي مستوحى من UVR لمنع تسرب الآلات الموسيقية",
        badge = "🎧 جودة استوديو"
    );

    val displayName: String get() = titleAr
}
