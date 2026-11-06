package org.francasio.sing.util

import android.content.Context
import android.os.Build
import android.util.Log

object DeviceHelper {
    private const val TAG = "AppleSing_Device"

    /**
     * Matches part numbers with dedicated Hexagon Tensor Processors (HTP):
     * - SM8350 .. SM8975 (888 through 8 Elite Gen 5/6 and Extreme)
     * - SM7325 .. SM7675 (778G up to 7+ Gen 3)
     * - SM6450 .. SM6475 (6 Gen 1, 6 Gen 3; excludes SM6375 6s Gen 3!)
     * - SC8380XP .. SC8480XP (Snapdragon X Elite / X2 Elite)
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

        val candidateStrings = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            getSystemProperty("ro.soc.model"),
            getSystemProperty("ro.vendor.qti.soc_model"),
            getSystemProperty("ro.board.platform"),   
            getSystemProperty("ro.hardware.chipname"),
            getSystemProperty("ro.chipname"),
            Build.BOARD,
            Build.HARDWARE
        )

        val detected = candidateStrings.any { candidate ->
            candidate.isNotEmpty() && HTP_SOC_REGEX.containsMatchIn(candidate)
        }

        Log.i(TAG, "Hardware check: isQualcomm=$isQualcomm -> isHexagonHtp=$detected")
        detected
    }
}