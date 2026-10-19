package org.francasio.sing.util

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.francasio.sing.provider.SingSettings

object XposedStatus {
    @Volatile
    private var boundService: XposedService? = null

    @Volatile
    private var serviceFrameworkName: String? = null

    @Volatile
    private var serviceFrameworkVersion: String? = null

    @Volatile
    private var isListenerRegistered = false

    @JvmStatic
    fun isModuleLoaded(): Boolean {
        return false
    }

    @JvmStatic
    fun getXposedManagerInfo(): String {
        return ""
    }

    fun initServiceListener(onStatusChanged: () -> Unit = {}) {
        if (isListenerRegistered) return
        isListenerRegistered = true
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    serviceFrameworkName = try { service.frameworkName } catch (_: Throwable) { null }
                    serviceFrameworkVersion = try { service.frameworkVersion } catch (_: Throwable) { null }
                    onStatusChanged()
                }

                override fun onServiceDied(service: XposedService) {
                    boundService = null
                    onStatusChanged()
                }
            })
        } catch (_: Throwable) {
            // LibXposed service helper registration failed or not supported
        }
    }

    fun isBound(): Boolean = boundService != null

    fun getBoundFrameworkInfo(): String? {
        val name = serviceFrameworkName ?: boundService?.frameworkName
        val ver = serviceFrameworkVersion ?: boundService?.frameworkVersion
        return if (!name.isNullOrEmpty() && !ver.isNullOrEmpty()) {
            "$name $ver"
        } else if (!name.isNullOrEmpty()) {
            name
        } else null
    }

    /**
     * Checks if the module is active via any mechanism:
     * 1. LibXposed service binding
     * 2. Hooked isModuleLoaded()
     * 3. Heartbeat from hooked Apple Music process
     */
    fun isModuleActive(context: Context): Boolean {
        if (isBound()) return true
        if (isModuleLoaded()) return true
        return SingSettings.isHeartbeatActive(context)
    }

    fun getEffectiveManagerInfo(context: Context): String {
        // Priority 1: Real LibXposed service framework name & version
        getBoundFrameworkInfo()?.let { return it }

        // Priority 2: Hooked manager info (from SingXposedEntry hook)
        val legacyInfo = getXposedManagerInfo()
        if (legacyInfo.isNotEmpty() && legacyInfo != "Xposed v0") {
            return legacyInfo
        }

        // Priority 3: Heartbeat info from Apple Music
        val heartbeatInfo = SingSettings.getHeartbeatManagerInfo(context)
        if (!heartbeatInfo.isNullOrEmpty()) {
            return heartbeatInfo
        }

        if (isModuleLoaded() && legacyInfo.isNotEmpty()) {
            return legacyInfo
        }

        return "Xposed Framework"
    }
}

