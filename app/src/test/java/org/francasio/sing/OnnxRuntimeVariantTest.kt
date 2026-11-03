package org.francasio.sing

import org.francasio.sing.util.OnnxRuntimeVariant
import org.junit.Assert.assertEquals
import org.junit.Test

class OnnxRuntimeVariantTest {
    @Test
    fun qualcommHtpArm64UsesQnn() {
        assertEquals(OnnxRuntimeVariant.QNN, OnnxRuntimeVariant.select("arm64-v8a", true))
    }

    @Test
    fun nonQnnArm64UsesStandard() {
        // MediaTek, Exynos, Tensor and older Qualcomm without HTP.
        assertEquals(OnnxRuntimeVariant.STANDARD, OnnxRuntimeVariant.select("arm64-v8a", false))
    }

    @Test
    fun otherArchitecturesAlwaysUseStandard() {
        for (abi in listOf("x86_64", "x86", "armeabi-v7a")) {
            for (hasHtp in listOf(false, true)) {
                assertEquals(OnnxRuntimeVariant.STANDARD, OnnxRuntimeVariant.select(abi, hasHtp))
            }
        }
    }
}
