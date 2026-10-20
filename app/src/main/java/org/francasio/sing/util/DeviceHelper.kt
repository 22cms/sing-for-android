package org.francasio.sing.util

import android.content.Context
import android.os.Build
import android.util.Log

object DeviceHelper {
    private const val TAG = "AppleSing_Device"

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

    /**
     * Determines whether the current device is a Qualcomm device equipped with
     * a Hexagon Tensor Processor (HTP NPU).
     *
     * HTP was introduced in Snapdragon 888 (SM8350, HTP V68) and 778G (SM7325),
     * followed by Snapdragon 8 Gen 1/2/3/Elite (SM8450, SM8550, SM8650, SM8750),
     * Snapdragon 8s Gen 3 (SM8635), Snapdragon 7+ Gen 2/3 (SM7475, SM7675),
     * Snapdragon 7 Gen 1/3 (SM7450, SM7550), and Snapdragon X Elite (SC8380XP).
     *
     * Older Qualcomm SoCs (e.g. Snapdragon 732G/SM7150, 765G/SM7250, 865/SM8250,
     * 855/SM8150, 680/SM6225) only have a classic CDSP/aDSP without HTP architecture.
     * Non-Qualcomm SoCs (MediaTek Dimensity, Google Tensor, Samsung Exynos) do not
     * have Hexagon HTP.
     */
    val isQualcommWithHexagonHtp: Boolean by lazy {
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.lowercase()
        } else {
            getSystemProperty("ro.soc.manufacturer").lowercase()
        }
        val roHardware = getSystemProperty("ro.hardware").lowercase()
        val bootHardware = getSystemProperty("ro.boot.hardware").lowercase()

        val isQualcomm = hardware.contains("qcom") ||
                board.contains("qcom") ||
                socManufacturer.contains("qti") ||
                socManufacturer.contains("qualcomm") ||
                roHardware.contains("qcom") ||
                bootHardware.contains("qcom")

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.uppercase()
        } else {
            getSystemProperty("ro.soc.model").uppercase()
        }
        val platform = getSystemProperty("ro.board.platform").lowercase()

        val detected = if (!isQualcomm) {
            false
        } else {
            // Known platform codenames with Hexagon HTP
            val htpPlatforms = setOf(
                "pineapple", "kalama", "taro", "cape", "cliffs",
                "sun", "lahaina", "yupik", "waipio"
            )
            if (htpPlatforms.contains(platform) || htpPlatforms.contains(board)) {
                true
            } else {
                // Regex for HTP chipsets:
                // SM8350, SM8450, SM8475, SM8550, SM8650, SM8635, SM8750... (SM83xx+)
                // SM7325, SM7450, SM7475, SM7550, SM7675... (SM73xx+)
                // SC8380XP... (Snapdragon X Elite)
                // Does NOT match SM7150 (732G), SM7250 (765G), SM8250 (865), SM8150 (855), SM6xxx, etc.
                val htpSocRegex = Regex("""^S[MC][78][3-9]\d\d.*""", RegexOption.IGNORE_CASE)
                socModel.isNotEmpty() && htpSocRegex.matches(socModel)
            }
        }

        Log.i(
            TAG,
            "Hardware check: isQualcomm=$isQualcomm, socModel='$socModel', platform='$platform', board='$board', hardware='$hardware' -> isHexagonHtp=$detected"
        )
        detected
    }
}
