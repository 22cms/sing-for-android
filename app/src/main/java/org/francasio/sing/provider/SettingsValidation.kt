package org.francasio.sing.provider

/** Shared validation for IPC and file paths; deliberately independent of Android APIs. */
internal object SettingsValidation {
    private val booleanKeys = setOf(
        SingSettings.KEY_PRESERVE_SIDE, SingSettings.KEY_TWO_WAY_SLIDER,
        SingSettings.KEY_CENTER_GROUP_LYRICS, SingSettings.KEY_BLUR_UNFOCUSED_LYRICS,
        SingSettings.KEY_ENGLISH_TRANSLATION_FALLBACK, SingSettings.KEY_ECO_MODE,
        SingSettings.KEY_FORCE_CPU, SingSettings.KEY_ALLOW_ZERO_FLOOR,
        SingSettings.KEY_FORCE_SONG_ELIGIBILITY, SingSettings.KEY_MATERIAL_YOU,
        SingSettings.KEY_BACK_TIP_LEARNED
    )

    fun isValidSetting(key: String, value: Any?): Boolean = when (key) {
        SingSettings.KEY_SELECTED_MODEL -> value is String &&
            (value.isEmpty() || value.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        SingSettings.KEY_HARDWARE_TARGET -> value in setOf(
            SingSettings.TARGET_CPU, SingSettings.TARGET_GPU, SingSettings.TARGET_NPU, SingSettings.TARGET_NNAPI)
        SingSettings.KEY_THREAD_COUNT -> value is Int && value in 1..64
        else -> key in booleanKeys && value is Boolean
    }

    fun isModelFileName(name: String): Boolean = name == "models.json" ||
        name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*\\.(onnx|mnn|vinet)"))
}
