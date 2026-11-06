package org.francasio.sing.util

import android.content.Context
import android.os.Build
import android.util.Log

object DeviceHelper {
    private const val TAG = "AppleSing_Device"

    /**
     * Regex matching only Qualcomm SoCs featuring an Hexagon Tensor Processor (HTP):
     * - SM8350 .. SM8850+ (888 through 8 Elite and beyond)
     * - SM7325 .. SM7675+ (778G, 7 Gen 1/2/3, 7s Gen 2/3, 7+ Gen 2/3)
     * - SM6450 .. SM6475+ (6 Gen 1, 6 Gen 3)
     * - SC8380XP .. SC8480XP+ (Snapdragon X Elite / X2 Elite)
     *
     *  Rejects Hexagon DSP chips:
     * - SM8150 (855), SM8250 (865)
     * - SM7150 (732G), SM7250 (765G), SM7225 (750G)
     * - SM6375 (Snapdragon 6s Gen 3 / 695)
     * - SM6225 (Snapdragon 680 / 685)
     * - SM4350, SM4375, SM4450 (Snapdragon 4 Gen 1 / 4 Gen 2 / 4s Gen 2)
     *
     * Will definitely need to be updated in the future, as new Qualcomm SoCs will likely use an incompatible schema.
     */
    private val HTP_SOC_REGEX = Regex(
        """\b(?:S[MC]8[3-9]\d{2}|SM7[3-9]\d{2}|SM6[4-9]\d{2}|SC8[3-9]\d{2}|QC[SM][68]\d{3})""",
        RegexOption.IGNORE_CASE
    )

    fun getSystemProperty(key: String): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            "v${packageInfo.versionName ?: "1.0"}"
        } catch (_: Throwable) {
            "v1.0"
        }
    }

    val isQualcommWithHexagonHtp: Boolean by lazy {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.lowercase()
        } else {
            getSystemProperty("ro.soc.manufacturer").lowercase()
        }

        val isQualcomm = hardware.contains("qcom") ||
                board.contains("qcom") ||
                socManufacturer.contains("qti") ||
                socManufacturer.contains("qualcomm") ||
                getSystemProperty("ro.hardware").lowercase().contains("qcom")

        if (!isQualcomm) return@lazy false

        // Check the standard API and fallback vendor properties
        val candidateProperties = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            getSystemProperty("ro.soc.model"),
            getSystemProperty("ro.vendor.qti.soc_model"),
            getSystemProperty("ro.hardware.chipname"),
            getSystemProperty("ro.chipname"),
            Build.BOARD,
            Build.HARDWARE
        )

        val detected = candidateProperties.any { value ->
            value.isNotEmpty() && HTP_SOC_REGEX.containsMatchIn(value)
        }

        Log.i(TAG, "Hardware check: isQualcomm=$isQualcomm -> isHexagonHtp=$detected")
        detected
    }
}