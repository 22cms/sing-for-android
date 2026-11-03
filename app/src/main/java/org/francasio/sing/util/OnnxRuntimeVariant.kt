package org.francasio.sing.util

internal enum class OnnxRuntimeVariant {
    STANDARD,
    QNN;

    companion object {
        fun select(abi: String, hasQualcommHtp: Boolean): OnnxRuntimeVariant =
            if (abi.startsWith("arm64") && hasQualcommHtp) QNN else STANDARD
    }
}
