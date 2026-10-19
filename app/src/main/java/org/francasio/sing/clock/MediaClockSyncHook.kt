package org.francasio.sing.clock

import org.francasio.sing.SingXposedEntry
import org.francasio.sing.util.ReflectionUtils

object MediaClockSyncHook {
    private const val TAG = "AppleSing_ClockHook"

    @Volatile
    var latencyOffsetUs: Long = 0L

    @Volatile
    var isBuffering: Boolean = false

    @Volatile
    var bufferingOffsetUs: Long = 0L

    fun hook(entry: SingXposedEntry, classLoader: ClassLoader) {
        try {
            val rendererClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                classLoader
            ) ?: return

            val method = ReflectionUtils.findMethod(rendererClass, "getPositionUs") ?: return

            entry.hookMethod(method) { chain ->
                val originalPos = chain.proceed() as Long
                val offset = if (isBuffering) bufferingOffsetUs else latencyOffsetUs
                if (offset > 0) {
                    maxOf(0L, originalPos - offset)
                } else {
                    originalPos
                }
            }
            entry.logInfo("$TAG: SVAudioRendererV2.getPositionUs hooked for lyrics sync")
        } catch (e: Throwable) {
            entry.logError("$TAG: Failed to hook getPositionUs", e)
        }
    }
}
