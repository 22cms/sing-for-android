package org.francasio.sing

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.francasio.sing.audio.SingAudioProcessor
import org.francasio.sing.clock.MediaClockSyncHook
import org.francasio.sing.util.ReflectionUtils
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.util.zip.ZipFile

private class ExactSizeDrawable(
    private val delegate: Drawable,
    private val intrinsicW: Int,
    private val intrinsicH: Int
) : Drawable() {
    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH
    override fun draw(canvas: Canvas) {
        delegate.bounds = bounds
        delegate.draw(canvas)
    }
    override fun setAlpha(alpha: Int) { delegate.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { delegate.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.UNKNOWN", "android.graphics.PixelFormat"))
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = delegate.opacity
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        delegate.bounds = bounds
    }
}

/**
 * Modern LibXposed API 102 module entry point for Apple Music Sing.
 * Compatible with Vector 2.2, LSPosed, and non-root LSPatch 1.2.
 */
class SingXposedEntry : XposedModule() {

    init {
        instance = this
    }

    companion object {
        var instance: SingXposedEntry? = null
        var lyricsRecyclerView: WeakReference<ViewGroup>? = null
        private const val TAG = "AppleMusicSing"
        private const val TARGET_PACKAGE = "com.apple.android.music"
        private var audioProcessor: SingAudioProcessor? = null
        var resolvedModulePath: String?
            get() = org.francasio.sing.provider.SingSettings.resolvedModulePath
            set(value) {
                org.francasio.sing.provider.SingSettings.resolvedModulePath = value
            }
        var hostAppContext: Context? = null
        var detectedManagerInfo: String? = null
        @Volatile
        var applicationContextReady = false
        @Volatile
        var isAppleMusicAppCreated = false

        private var _mainHandler: Handler? = null
        val mainHandler: Handler
            get() {
                val cached = _mainHandler
                if (cached != null) return cached
                val looper = Looper.getMainLooper() ?: Looper.myLooper()
                val h = if (looper != null) Handler(looper) else Handler(Looper.getMainLooper())
                if (looper == Looper.getMainLooper()) {
                    _mainHandler = h
                }
                return h
            }
        var currentLyricsView: WeakReference<View>? = null
        var currentLosslessBadge: WeakReference<View>? = null
        var currentVocalCtrl: WeakReference<View>? = null
        var currentLocalPlayerController: WeakReference<Any>? = null
        var currentLyricsFragment: WeakReference<Any>? = null

        fun onEngineReady() {
            mainHandler.post {
                Log.i(TAG, "Neural engine is ready! Revealing Sing button...")
                val frag = currentLyricsFragment?.get()
                if (frag != null) {
                    try {
                        ReflectionUtils.callMethod(frag, "D2", true)
                        Log.i(TAG, "Called D2(true) on PlayerLyricsViewFragment")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to call D2(true) on lyrics fragment: ${t.message}")
                    }
                }
                val vocalCtrl = currentVocalCtrl?.get()
                if (vocalCtrl != null && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                    if (vocalCtrl.visibility != View.VISIBLE) {
                        vocalCtrl.alpha = 0f
                        vocalCtrl.visibility = View.VISIBLE
                        vocalCtrl.animate().alpha(1f).setDuration(300L).start()
                        Log.i(TAG, "Animated vocal_ctrl visibility to VISIBLE")
                    }
                }
            }
        }

        private var defaultMicDrawable: Drawable? = null
        private var defaultOffDrawable: Drawable? = null
        private var cachedInstrumentalDrawable: Drawable? = null
        private var lastProviderSyncTime: Long = 0L
        var isLosslessBadgeSuppressed: Boolean = false
        var cachedVacClass: Class<*>? = null
        private var defaultM0: Float? = null
        private var lastLongPressTime: Long = 0L
        private var lastSettingsLaunchTime: Long = 0L

        private var cachedFloorFieldName: String? = null

        fun findVocalFloorField(vacClass: Class<*>): Field? {
            cachedFloorFieldName?.let { name ->
                ReflectionUtils.findField(vacClass, name)?.let { return it }
            }

            // 1. If application context is ready and <clinit> has executed, dynamically inspect static float values.
            // lyrics_vocal_attenuation_drag_value_min / dimension is ~0.0641f (stock min floor ~6.4%).
            if (isAppleMusicAppCreated) {
                for (field in vacClass.declaredFields) {
                    if (field.type == java.lang.Float.TYPE && java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                        field.isAccessible = true
                        val v = try { field.getFloat(null) } catch (_: Throwable) { 0f }
                        if (v in 0.01f..0.20f) {
                            cachedFloorFieldName = field.name
                            if (defaultM0 == null) defaultM0 = v
                            Log.i(TAG, "Found vocal floor field dynamically by value: ${field.name} = $v")
                            return field
                        }
                    }
                }
            }

            // 2. Structural metadata fallback (no <clinit> invocation):
            // In VocalAttenuationControl.<clinit>, lyrics_vocal_attenuation_drag_value_min / dimension
            // is always the final static float computed and stored before return.
            val staticFloats = vacClass.declaredFields.filter {
                it.type == java.lang.Float.TYPE && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            if (staticFloats.isNotEmpty()) {
                val last = staticFloats.last()
                cachedFloorFieldName = last.name
                Log.i(TAG, "Found vocal floor field dynamically from VocalAttenuationControl structural metadata: ${last.name}")
                return last
            }

            return null
        }

        fun updateVocalAttenuationFloor(vacClass: Class<*>) {
            try {
                cachedVacClass = vacClass
                if (!isAppleMusicAppCreated) {
                    // Postpone until AppleMusicApplication.onCreate has completed
                    return
                }
                val floorField = findVocalFloorField(vacClass)
                if (floorField == null) {
                    Log.e(TAG, "Could not find vocal floor field in ${vacClass.name}")
                    return
                }
                val fieldName = floorField.name
                val currentFloor = ReflectionUtils.getStaticFloatField(vacClass, fieldName)
                if (defaultM0 == null && currentFloor > 0.0f) {
                    defaultM0 = currentFloor
                }
                val targetFloor = if (org.francasio.sing.provider.SingSettings.allowZeroFloor) 0.0f else (defaultM0 ?: 0.06410257f)
                val success = ReflectionUtils.setStaticFloatField(vacClass, fieldName, targetFloor)
                val verifiedFloor = ReflectionUtils.getStaticFloatField(vacClass, fieldName)
                Log.i(TAG, "updateVocalAttenuationFloor: field=$fieldName, target=$targetFloor, verified=$verifiedFloor, success=$success (allowZeroFloor=${org.francasio.sing.provider.SingSettings.allowZeroFloor}, defaultM0=$defaultM0, currentFloor=$currentFloor)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update VocalAttenuationControl floor: ${t.message}", t)
            }
        }

        fun onSettingsChanged() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { onSettingsChanged() }
                return
            }
            cachedVacClass?.let { updateVocalAttenuationFloor(it) }
            currentVocalCtrl?.get()?.let { updateSliderIcon(it) }
            lyricsRecyclerView?.get()?.let { rv ->
                rv.post { instance?.updateLyricsBlur(rv) }
            }
        }

        fun getVocalSliderFraction(vocalCtrl: View): Float {
            try {
                (ReflectionUtils.callMethod(vocalCtrl, "getSliderValue") as? Number)?.let { return it.toFloat() }
            } catch (_: Throwable) {}
            try {
                return ReflectionUtils.getFloatField(vocalCtrl, "H")
            } catch (_: Throwable) {}
            try {
                return ReflectionUtils.getFloatField(vocalCtrl, "sliderFraction")
            } catch (_: Throwable) {}
            return audioProcessor?.vocalLevel ?: 0f
        }

        fun setVocalSliderFractionField(vocalCtrl: View, value: Float) {
            try {
                ReflectionUtils.setFloatField(vocalCtrl, "H", value)
            } catch (_: Throwable) {}
            try {
                ReflectionUtils.setFloatField(vocalCtrl, "sliderFraction", value)
            } catch (_: Throwable) {}
        }

        private var pendingVocalFraction: Float? = null
        private val debouncedPlayerSyncRunnable = Runnable {
            val fraction = pendingVocalFraction ?: return@Runnable
            try {
                currentLocalPlayerController?.get()?.let { ctrl ->
                    ReflectionUtils.callMethod(ctrl, "setVocalAttenuationLevel", fraction)
                }
            } catch (_: Throwable) {}
            try {
                currentVocalCtrl?.get()?.let { vocalCtrl ->
                    val lCallback = ReflectionUtils.getObjectField(vocalCtrl, "L")
                    if (lCallback != null) {
                        ReflectionUtils.callMethod(lCallback, "invoke", fraction)
                    }
                }
            } catch (_: Throwable) {}
        }

        fun updateVocalSliderUi(vocalCtrl: View, fraction: Float) {
            try {
                ReflectionUtils.callMethod(vocalCtrl, "setSliderFraction", fraction)
            } catch (_: Throwable) {}

            setVocalSliderFractionField(vocalCtrl, fraction)

            try {
                val binding = ReflectionUtils.getObjectField(vocalCtrl, "e")
                val maskingView = (ReflectionUtils.getObjectField(binding, "c0") as? View)
                    ?: run {
                        val id = vocalCtrl.resources.getIdentifier("vocal_on", "id", TARGET_PACKAGE)
                        if (id != 0) vocalCtrl.findViewById<View>(id) else null
                    }
                val gradient = ReflectionUtils.getObjectField(vocalCtrl, "f") as? GradientDrawable
                val isDragging = try { ReflectionUtils.getBooleanField(vocalCtrl, "l") } catch (_: Throwable) { false }
                val colors = try { ReflectionUtils.callMethod(vocalCtrl, "n", isDragging) as? IntArray } catch (_: Throwable) { null }
                if (gradient != null && colors != null) {
                    gradient.setColors(colors)
                }
                maskingView?.invalidate()
            } catch (_: Throwable) {}

            vocalCtrl.invalidate()
        }

        private val attachedScrollViews = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<View, Boolean>()
        )

        fun attachScrollWheelHandler(root: View) {
            if (attachedScrollViews.contains(root)) return

            val scrollListener = View.OnGenericMotionListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    val vocalCtrl = currentVocalCtrl?.get()
                        ?: (if (v.javaClass.name.contains("VocalAttenuationControl")) v else v.parent as? View)
                        ?: v
                    handleSliderScroll(vocalCtrl, event)
                    return@OnGenericMotionListener true
                }
                false
            }

            fun applyToView(view: View) {
                if (!attachedScrollViews.add(view)) return
                view.setOnGenericMotionListener(scrollListener)
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) {
                        applyToView(view.getChildAt(i))
                    }
                }
            }

            applyToView(root)
        }

        fun handleSliderScroll(vocalCtrl: View, event: MotionEvent) {
            try {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL).let {
                    if (it != 0f) it else event.getAxisValue(MotionEvent.AXIS_SCROLL)
                }
                if (vScroll == 0f) return

                val isSingOn = (audioProcessor?.isEnabled == true) || try {
                    val binding = ReflectionUtils.getObjectField(vocalCtrl, "e")
                    val c0 = ReflectionUtils.getObjectField(binding, "c0") as? View
                    c0?.isActivated == true
                } catch (_: Throwable) {
                    false
                } || try {
                    ReflectionUtils.getBooleanField(vocalCtrl, "l")
                } catch (_: Throwable) { false }

                if (!isSingOn) {
                    // Sing is disabled: scroll event is consumed so lyrics don't scroll, but slider is not moved
                    return
                }

                val currentFraction = audioProcessor?.vocalLevel?.takeIf { it > 0f }
                    ?: getVocalSliderFraction(vocalCtrl)
                val step = 0.05f
                val delta = vScroll * step
                val minFloor = if (org.francasio.sing.provider.SingSettings.allowZeroFloor) 0.0f else (defaultM0 ?: 0.06410257f)
                val newFraction = (currentFraction + delta).coerceIn(minFloor, 1.0f)

                if (Math.abs(newFraction - currentFraction) < 0.001f) {
                    return
                }

                // 1. Immediately update audio processor vocal level directly (zero latency)
                audioProcessor?.vocalLevel = newFraction

                // 2. Immediately update VocalAttenuationControl UI and redraw
                updateVocalSliderUi(vocalCtrl, newFraction)
                updateSliderIcon(vocalCtrl, newFraction)

                // 3. Debounce notification to Apple Music's player controller and listener
                pendingVocalFraction = newFraction
                mainHandler.removeCallbacks(debouncedPlayerSyncRunnable)
                mainHandler.postDelayed(debouncedPlayerSyncRunnable, 100L)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle slider scroll", t)
            }
        }

        fun getLosslessBadge(): View? {
            currentLosslessBadge?.get()?.let { return it }
            currentLyricsView?.get()?.let { lyricsView ->
                val badgeId = lyricsView.resources.getIdentifier("audio_badge_with_text", "id", TARGET_PACKAGE)
                if (badgeId != 0) {
                    lyricsView.findViewById<View>(badgeId)?.let {
                        currentLosslessBadge = WeakReference(it)
                        return it
                    }
                }
            }
            return null
        }

        val losslessReappearRunnable = Runnable {
            try {
                val isSingActive = audioProcessor?.isEnabled == true
                if (!isSingActive) {
                    isLosslessBadgeSuppressed = false
                    val badge = getLosslessBadge()
                    if (badge != null) {
                        badge.animate()?.cancel()
                        badge.animate()
                            .alpha(1f)
                            .setDuration(300L)
                            .start()
                    }
                    Log.i(TAG, "Restored Lossless badge (alpha -> 1f) after 4.5s delay")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error restoring Lossless badge: ${t.message}")
            }
        }

        fun onSingModeChanged(enabled: Boolean) {
            mainHandler.post {
                mainHandler.removeCallbacks(losslessReappearRunnable)
                val badge = getLosslessBadge()
                if (enabled) {
                    isLosslessBadgeSuppressed = true
                    badge?.animate()?.cancel()
                    badge?.animate()
                        ?.alpha(0f)
                        ?.setDuration(250L)
                        ?.start()
                    Log.i(TAG, "Sing enabled -> Lossless badge fading out (alpha -> 0f)")
                } else {
                    isLosslessBadgeSuppressed = true
                    badge?.animate()?.cancel()
                    badge?.alpha = 0f
                    mainHandler.postDelayed(losslessReappearRunnable, 3500L)
                    Log.i(TAG, "Sing disabled -> scheduled Lossless badge restore in 3.5s")
                }
                currentVocalCtrl?.get()?.let { updateSliderIcon(it) }
            }
        }

        fun isAllowedAudioFormat(mimeType: String?): Boolean {
            if (mimeType.isNullOrEmpty()) return false
            val m = mimeType.trim().lowercase()
            // Exclude Dolby Audio (AC-3, E-AC-3, AC-4) and Dolby Atmos (E-AC-3 JOC)
            if (m.contains("ac3") || m.contains("ac-3") || m.contains("ec-3") || m.contains("joc")) {
                return false
            }
            // Allow AAC, ALAC, and AAC-HE
            return m == "audio/mp4a-latm" ||
                   m == "audio/alac" ||
                   m == "audio/aac" ||
                   m == "audio/he-aac" ||
                   m == "audio/aac-he" ||
                   m == "alac" ||
                   m.contains("alac") ||
                   m.contains("mp4a") ||
                   m.contains("aac")
        }

        private fun getInstrumentalDrawable(context: Context): Drawable? {
            if (cachedInstrumentalDrawable != null) return cachedInstrumentalDrawable

            // Decode embedded Base64 PNG directly into ExactSizeDrawable (no external file, asset, or theme dependencies)
            try {
                val bytes = android.util.Base64.decode(
                    org.francasio.sing.util.InstrumentalIconData.PNG_BASE64,
                    android.util.Base64.DEFAULT
                )
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    val baseDrawable = BitmapDrawable(context.resources, bitmap)
                    val targetW = defaultMicDrawable?.intrinsicWidth?.takeIf { it > 0 }
                        ?: (36 * context.resources.displayMetrics.density).toInt()
                    val targetH = defaultMicDrawable?.intrinsicHeight?.takeIf { it > 0 }
                        ?: (36 * context.resources.displayMetrics.density).toInt()
                    cachedInstrumentalDrawable = ExactSizeDrawable(baseDrawable, targetW, targetH)
                    Log.i(TAG, "Loaded ic_instrumental via embedded Base64 PNG (${targetW}x${targetH})")
                    return cachedInstrumentalDrawable
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Base64 decode failed: ${t.message}")
            }

            return cachedInstrumentalDrawable
        }

        fun updateSliderIcon(vocalCtrl: View, sliderFraction: Float? = null) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { updateSliderIcon(vocalCtrl, sliderFraction) }
                return
            }
            try {
                val fraction = sliderFraction ?: try {
                    ReflectionUtils.getFloatField(vocalCtrl, "sliderFraction")
                } catch (_: Throwable) {
                    1.0f
                }

                val binding = try {
                    ReflectionUtils.getObjectField(vocalCtrl, "e")
                } catch (_: Throwable) { null } ?: return

                val maskingView = try {
                    ReflectionUtils.getObjectField(binding, "c0") as? View
                } catch (_: Throwable) { null } ?: run {
                    val id = vocalCtrl.resources.getIdentifier("vocal_on", "id", TARGET_PACKAGE)
                    if (id != 0) vocalCtrl.findViewById<View>(id) else null
                } ?: return

                if (defaultMicDrawable == null || defaultMicDrawable === cachedInstrumentalDrawable) {
                    val current = try {
                        ReflectionUtils.getObjectField(maskingView, "drawableMask") as? Drawable
                    } catch (_: Throwable) { null }
                    if (current != null && current !== cachedInstrumentalDrawable) {
                        defaultMicDrawable = current
                    } else {
                        val resId = vocalCtrl.resources.getIdentifier("ic_nowplaying_vocal", "drawable", TARGET_PACKAGE)
                        if (resId != 0) {
                            defaultMicDrawable = vocalCtrl.resources.getDrawable(resId, vocalCtrl.context.theme)
                        } else {
                            defaultMicDrawable = current
                        }
                    }
                }

                val vocalOff = try {
                    ReflectionUtils.getObjectField(binding, "b0") as? ImageView
                } catch (_: Throwable) { null } ?: run {
                    val id = vocalCtrl.resources.getIdentifier("vocal_off", "id", TARGET_PACKAGE)
                    if (id != 0) vocalCtrl.findViewById<ImageView>(id) else null
                }

                if (defaultOffDrawable == null || defaultOffDrawable === cachedInstrumentalDrawable) {
                    val currentOff = vocalOff?.drawable
                    if (currentOff != null && currentOff !== cachedInstrumentalDrawable) {
                        defaultOffDrawable = currentOff
                    }
                }

                val isSingOn = (audioProcessor?.isEnabled == true) || try {
                    ReflectionUtils.getBooleanField(vocalCtrl, "l")
                } catch (_: Throwable) { false }

                if (!org.francasio.sing.provider.SingSettings.twoWaySlider &&
                    (SystemClock.uptimeMillis() - lastProviderSyncTime > 3000L)) {
                    lastProviderSyncTime = SystemClock.uptimeMillis()
                    org.francasio.sing.provider.SingSettings.syncSettingsFromProvider(vocalCtrl.context)
                }
                val isTwoWay = org.francasio.sing.provider.SingSettings.twoWaySlider
                val isHigherRange = fraction > 0.5f

                val targetMask = if (isTwoWay && isSingOn && isHigherRange) {
                    getInstrumentalDrawable(vocalCtrl.context) ?: defaultMicDrawable
                } else {
                    defaultMicDrawable
                }

                val currentMask = try {
                    ReflectionUtils.getObjectField(maskingView, "drawableMask") as? Drawable
                } catch (_: Throwable) { null }

                if (targetMask != null && currentMask !== targetMask) {
                    ReflectionUtils.callMethod(maskingView, "setMask", targetMask)
                    Log.i(TAG, "Swapped slider icon (isInstrumental=${targetMask === cachedInstrumentalDrawable}, fraction=$fraction, isTwoWay=$isTwoWay)")
                }

                val targetOff = if (isTwoWay && isSingOn && isHigherRange) {
                    getInstrumentalDrawable(vocalCtrl.context) ?: defaultOffDrawable ?: defaultMicDrawable
                } else {
                    defaultOffDrawable ?: defaultMicDrawable
                }

                if (vocalOff != null && targetOff != null && vocalOff.drawable !== targetOff) {
                    vocalOff.setImageDrawable(targetOff)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error updating slider icon: ${t.message}")
            }
        }
    }

    @Volatile
    private var processName: String? = null
    private val hookedPackages = mutableSetOf<String>()

    fun hookMethod(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hooker: (XposedInterface.Chain) -> Any?
    ) {
        hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
    }

    fun logInfo(msg: String) {
        Log.i(TAG, msg)
        try {
            log(Log.INFO, TAG, msg)
        } catch (_: Throwable) {}
    }

    fun logError(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
        try {
            if (tr != null) log(Log.ERROR, TAG, msg, tr) else log(Log.ERROR, TAG, msg)
        } catch (_: Throwable) {}
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        try {
            processName = param.processName
            val appInfo = try { moduleApplicationInfo } catch (_: Throwable) { null }
            if (appInfo != null && !appInfo.sourceDir.isNullOrEmpty()) {
                resolvedModulePath = appInfo.sourceDir
            }
            val fwName = try { frameworkName } catch (_: Throwable) { "Unknown" }
            val fwVer = try { frameworkVersion } catch (_: Throwable) { "" }
            detectedManagerInfo = if (fwVer.isNotEmpty()) "$fwName $fwVer" else fwName
            logInfo("Loaded via API 102 in process=$processName, framework=$detectedManagerInfo, modulePath=$resolvedModulePath")
        } catch (t: Throwable) {
            Log.e(TAG, "onModuleLoaded failed", t)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        preparePackage(param.packageName, param.defaultClassLoader, param.applicationInfo, "onPackageLoaded")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        preparePackage(param.packageName, param.classLoader, param.applicationInfo, "onPackageReady")
    }

    @Synchronized
    private fun preparePackage(
        packageName: String,
        classLoader: ClassLoader?,
        appInfo: ApplicationInfo?,
        phase: String
    ) {
        if (classLoader == null) return
        if (hookedPackages.contains(packageName)) return

        val proc = processName ?: packageName

        if (packageName == "org.francasio.sing") {
            hookedPackages.add(packageName)
            hookSelfModuleCheck(classLoader)
            return
        }

        if (!packageName.startsWith(TARGET_PACKAGE)) {
            return
        }

        // Satellite process filter (skip telemetry / push / sandboxed processes)
        if (isSkipProcess(packageName, proc)) {
            logInfo("Skipping satellite process: $proc")
            return
        }

        hookedPackages.add(packageName)
        logInfo("Hooking into $packageName ($phase, proc=$proc)")

        if (detectedManagerInfo.isNullOrEmpty()) {
            detectedManagerInfo = detectXposedManager()
        }

        installHooks(classLoader, appInfo)
    }

    private fun isSkipProcess(packageName: String, processName: String): Boolean {
        val suffix = if (processName.startsWith(packageName)) {
            processName.substring(packageName.length)
        } else ""
        val skip = listOf(
            ":push", ":pushservice", ":pushcore", ":remote",
            ":crashpad_handler", ":crash_handler", ":bugly", ":sentry",
            ":sandboxed_process", ":isolated_process"
        )
        return skip.contains(suffix)
    }

    private fun installHooks(cl: ClassLoader, appInfo: ApplicationInfo?) {
        hookApplicationInit(cl, appInfo)
        hookVocalAttenuationState(cl)
        hookTrackEligibility(cl)
        hookAudioProcessorScaffolding(cl)
        hookPlaybackController(cl)
        hookLyricsUI(cl)
        hookCrossFade(cl)
        MediaClockSyncHook.hook(this, cl)
    }

    /**
     * Hook Application / Context initialization to instantiate SingAudioProcessor.
     */
    private fun hookApplicationInit(cl: ClassLoader, appInfo: ApplicationInfo?) {
        try {
            val attachMethod = ReflectionUtils.findMethod(Application::class.java, "attach", Context::class.java)
            if (attachMethod != null) {
                hookMethod(attachMethod, priority = XposedInterface.PRIORITY_HIGHEST) { chain ->
                    val result = chain.proceed()
                    try {
                        val thiz = chain.thisObject
                        val ctx = (thiz as? Context) ?: (chain.getArg(0) as? Context)
                        if (ctx != null && hostAppContext == null) {
                            hostAppContext = ctx.applicationContext ?: ctx
                            // Fallback safety: if AppleMusicApplication.onCreate never fires, init after 5s
                            mainHandler.postDelayed({
                                if (!applicationContextReady && hostAppContext != null) {
                                    onApplicationContextReady(hostAppContext!!, appInfo)
                                }
                            }, 5000L)
                        }
                    } catch (t: Throwable) {
                        logError("Application.attach post-hook failed", t)
                    }
                    result
                }
                logInfo("Hooked Application.attach")
            }
        } catch (t: Throwable) {
            logError("Could not hook Application.attach", t)
        }

        try {
            val appClass = ReflectionUtils.findClass("com.apple.android.music.AppleMusicApplication", cl)
            if (appClass != null) {
                ReflectionUtils.findMethod(appClass, "onCreate")?.let { m ->
                    hookMethod(m) { chain ->
                        val result = chain.proceed()
                        isAppleMusicAppCreated = true
                        (chain.thisObject as? Context)?.let { ctx ->
                            onApplicationContextReady(ctx, appInfo)
                        }
                        cachedVacClass?.let { updateVocalAttenuationFloor(it) }
                        result
                    }
                    logInfo("Hooked AppleMusicApplication.onCreate")
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook AppleMusicApplication.onCreate", t)
        }
    }

    @Synchronized
    private fun onApplicationContextReady(context: Context, appInfo: ApplicationInfo?) {
        if (applicationContextReady) return
        applicationContextReady = true

        val appContext = context.applicationContext ?: context
        hostAppContext = appContext
        try {
            val prefs = appContext.getSharedPreferences("apple_sing_cache", Context.MODE_PRIVATE)
            org.francasio.sing.provider.SingSettings.allowZeroFloor = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_ALLOW_ZERO_FLOOR, true)
            org.francasio.sing.provider.SingSettings.twoWaySlider = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_TWO_WAY_SLIDER, false)
            org.francasio.sing.provider.SingSettings.preserveSide = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_PRESERVE_SIDE, false)
            org.francasio.sing.provider.SingSettings.forceSongEligibility = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_FORCE_SONG_ELIGIBILITY, true)
            org.francasio.sing.provider.SingSettings.centerGroupLyrics = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_CENTER_GROUP_LYRICS, true)
            org.francasio.sing.provider.SingSettings.blurUnfocusedLyrics = prefs.getBoolean(org.francasio.sing.provider.SingSettings.KEY_BLUR_UNFOCUSED_LYRICS, true)
        } catch (_: Throwable) {}

        // Schedule async initialization when the main looper is idle or after cold-start UI renders
        val initAction = Runnable {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    r.run()
                }, "AppleSing_XposedInit")
            }.execute {
                try {
                    val path = resolveModuleApkPath(appContext, appInfo)
                    val manager = detectedManagerInfo ?: detectXposedManager().also { detectedManagerInfo = it }
                    org.francasio.sing.provider.SingSettings.sendHeartbeat(appContext, manager)

                    if (path != null) {
                        resolvedModulePath = path
                        try {
                            appContext.getSharedPreferences("apple_sing_cache", Context.MODE_PRIVATE)
                                .edit()
                                .putString("resolved_module_apk_path", path)
                                .apply()
                        } catch (_: Throwable) {}
                        val processor = SingAudioProcessor(appContext, path)
                        processor.onEngineReadyListener = {
                            onEngineReady()
                        }
                        audioProcessor = processor
                        if (processor.isEngineReady) {
                            onEngineReady()
                        }
                        logInfo("SingAudioProcessor initialized in background from $path (manager=$manager)")
                    } else {
                        logError("Failed to resolve module APK path")
                    }
                } catch (e: Throwable) {
                    logError("Failed to initialize SingAudioProcessor", e)
                }
            }
        }

        var executed = false
        val safeRunner = Runnable {
            if (!executed) {
                executed = true
                initAction.run()
            }
        }
        try {
            Looper.getMainLooper().queue.addIdleHandler {
                safeRunner.run()
                false
            }
            mainHandler.postDelayed(safeRunner, 1500L)
        } catch (_: Throwable) {
            safeRunner.run()
        }
    }

    private fun resolveModuleApkPath(context: Context, appInfo: ApplicationInfo?): String? {
        // 0. Cached path in SharedPreferences (instant, 0ms)
        try {
            val cached = context.getSharedPreferences("apple_sing_cache", Context.MODE_PRIVATE)
                .getString("resolved_module_apk_path", null)
            if (!cached.isNullOrEmpty() && File(cached).exists()) {
                resolvedModulePath = cached
                return cached
            }
        } catch (_: Throwable) {}

        // 1. LibXposed API 102 moduleApplicationInfo
        try {
            val path = moduleApplicationInfo?.sourceDir
            if (!path.isNullOrEmpty() && File(path).exists()) {
                return path
            }
        } catch (_: Throwable) {}

        // 2. Cached resolved path
        if (!resolvedModulePath.isNullOrEmpty() && File(resolvedModulePath!!).exists()) {
            return resolvedModulePath
        }

        // 3. Package manager (if module APK is installed as standard app)
        try {
            val pmPath = context.packageManager.getApplicationInfo("org.francasio.sing", 0).sourceDir
            if (!pmPath.isNullOrEmpty() && File(pmPath).exists()) {
                return pmPath
            }
        } catch (_: Throwable) {}

        // 4. Embedded LSPatch module inside target APK assets
        val embeddedPath = findEmbeddedLSPatchApk(context)
        if (embeddedPath != null) {
            return embeddedPath
        }

        return null
    }

    private fun findEmbeddedLSPatchApk(context: Context): String? {
        try {
            val outApk = File(context.cacheDir, "lspatch_embedded_sing.apk")
            if (outApk.exists() && outApk.length() > 1024 * 1024) {
                logInfo("Reusing already extracted LSPatch module: ${outApk.absolutePath} (${outApk.length()} bytes)")
                return outApk.absolutePath
            }
            val moduleFiles = context.assets.list("lspatch/modules")
            if (!moduleFiles.isNullOrEmpty()) {
                val targetName = moduleFiles.firstOrNull { it.contains("sing") || it.endsWith(".apk") } ?: moduleFiles[0]
                val tmpApk = File(context.cacheDir, "lspatch_embedded_sing.tmp")
                context.assets.open("lspatch/modules/$targetName").use { input ->
                    FileOutputStream(tmpApk).use { output ->
                        input.copyTo(output)
                    }
                }
                if (tmpApk.exists() && tmpApk.length() > 0) {
                    if (outApk.exists()) outApk.delete()
                    tmpApk.renameTo(outApk)
                    logInfo("Extracted embedded LSPatch module to: ${outApk.absolutePath} (${outApk.length()} bytes)")
                    return outApk.absolutePath
                }
            }
        } catch (t: Throwable) {
            logError("Error extracting embedded LSPatch module", t)
        }
        return null
    }

    /**
     * 1. Hook VocalAttenuationStateProvider checks:
     *    - isDeviceSupported() -> true
     *    - isCodecInputSupported(mimeType) -> true for AAC, ALAC, AAC-HE; false for Dolby Audio & Atmos
     */
    private fun hookVocalAttenuationState(cl: ClassLoader) {
        try {
            val companionClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.player.VocalAttenuationStateProvider\$Companion",
                cl
            )
            if (companionClass != null) {
                ReflectionUtils.findMethod(companionClass, "isDeviceSupported")?.let { m ->
                    hookMethod(m) { true }
                }
                ReflectionUtils.findMethod(companionClass, "isCodecInputSupported", String::class.java)?.let { m ->
                    hookMethod(m) { chain ->
                        val mime = chain.getArg(0) as? String
                        val allowed = isAllowedAudioFormat(mime)
                        logInfo("isCodecInputSupported(mime=$mime) -> $allowed")
                        allowed
                    }
                }
                logInfo("Hooked VocalAttenuationStateProvider.Companion (device=true, codec allowed on AAC/ALAC/AAC-HE only)")
            }
        } catch (e: Throwable) {
            logError("Failed to hook VocalAttenuationStateProvider.Companion", e)
        }
    }

    /**
     * 2. Hook playback/queue items to report isVocalAttenuationAllowed based on forceSongEligibility setting
     */
    private fun hookTrackEligibility(cl: ClassLoader) {
        val mediaItemClasses = listOf(
            "com.apple.android.music.model.BasePlaybackItem",
            "com.apple.android.music.playback.model.StoreMediaItem",
            "com.apple.android.music.playback.model.LibraryMediaItem",
            "com.apple.android.music.playback.model.RadioMediaItem"
        )
        for (clsName in mediaItemClasses) {
            try {
                val cls = ReflectionUtils.findClass(clsName, cl)
                if (cls != null) {
                    ReflectionUtils.findMethod(cls, "isVocalAttenuationAllowed")?.let { m ->
                        hookMethod(m) { chain ->
                            val isReady = audioProcessor?.isEngineReady == true
                            if (isReady && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                                true
                            } else if (!isReady) {
                                false
                            } else {
                                chain.proceed()
                            }
                        }
                        logInfo("Hooked isVocalAttenuationAllowed on $clsName (forceSongEligibility togglable, guarded by isEngineReady)")
                    }
                }
            } catch (e: Throwable) {
                logError("Could not hook isVocalAttenuationAllowed on $clsName", e)
            }
        }
    }

    /**
     * 3. Hook VocalsAudioProcessor:
     *    - Force FORCE_DISABLE to false
     *    - Route audio frames through SingAudioProcessor (ONNX + STFT)
     *    - Neutralize PremResampleAudioProcessor so 24-bit Lossless never throws UnhandledAudioFormatException
     *    - Filter SVAudioRendererV2.createAudioProcessors to return [VocalsAudioProcessor] only
     */
    private fun hookAudioProcessorScaffolding(cl: ClassLoader) {
        try {
            val resampleClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.processors.PremResampleAudioProcessor",
                cl
            )
            val audioFormatClass = ReflectionUtils.findClass(
                "com.google.android.exoplayer2.audio.AudioProcessor\$AudioFormat",
                cl
            )
            if (resampleClass != null && audioFormatClass != null) {
                val notSetFormat = ReflectionUtils.getStaticObjectField(audioFormatClass, "NOT_SET")
                val configureMethod = ReflectionUtils.findMethod(resampleClass, "configure", audioFormatClass)
                if (configureMethod != null) {
                    hookMethod(configureMethod) { notSetFormat }
                    logInfo("Successfully neutralized PremResampleAudioProcessor.configure")
                }
            }
        } catch (e: Throwable) {
            logError("Failed to neutralize PremResampleAudioProcessor", e)
        }

        try {
            val rendererClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.renderer.SVAudioRendererV2",
                cl
            )
            if (rendererClass != null) {
                ReflectionUtils.findMethod(rendererClass, "createAudioProcessors")?.let { m ->
                    hookMethod(m) { chain ->
                        val array = chain.proceed() as? Array<*>
                        val vocalsProcessor = array?.firstOrNull { it != null && it.javaClass.name.contains("VocalsAudioProcessor") }
                        if (vocalsProcessor != null) {
                            val audioProcessorClass = ReflectionUtils.findClass(
                                "com.google.android.exoplayer2.audio.AudioProcessor",
                                cl
                            )
                            if (audioProcessorClass != null) {
                                @Suppress("UNCHECKED_CAST")
                                val newArray = java.lang.reflect.Array.newInstance(audioProcessorClass, 1) as Array<Any>
                                newArray[0] = vocalsProcessor
                                logInfo("SVAudioRendererV2.createAudioProcessors filtered to [VocalsAudioProcessor] only")
                                return@hookMethod newArray
                            }
                        }
                        array
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.createAudioProcessors()")
                }

                ReflectionUtils.findMethod(rendererClass, "onPositionReset", java.lang.Long.TYPE, java.lang.Boolean.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        logInfo("SVAudioRendererV2.onPositionReset triggered reset")
                        audioProcessor?.reset()
                        res
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.onPositionReset")
                }

                ReflectionUtils.findMethod(rendererClass, "render", java.lang.Long.TYPE, java.lang.Long.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        val processor = audioProcessor
                        if (processor?.needsBurst == true) {
                            processor.maybeBurstDecode(chain.thisObject)
                        }
                        res
                    }
                    logInfo("Successfully hooked SVAudioRendererV2.render for burst decoding")
                }
            }
        } catch (e: Throwable) {
            logError("Failed to hook SVAudioRendererV2", e)
        }

        try {
            val sinkClass = ReflectionUtils.findClass(
                "com.google.android.exoplayer2.audio.DefaultAudioSink",
                cl
            )
            if (sinkClass != null) {
                sinkClass.declaredConstructors.forEach { ctor ->
                    ctor.isAccessible = true
                    hookMethod(ctor) { chain ->
                        val res = chain.proceed()
                        try {
                            val sinkObj = chain.thisObject
                            val toIntProcessors = ReflectionUtils.getObjectField(sinkObj, "toIntPcmAvailableAudioProcessors") as? Array<*>
                            val vocalsProcessor = toIntProcessors?.firstOrNull { it != null && it.javaClass.name.contains("VocalsAudioProcessor") }
                            if (vocalsProcessor != null) {
                                val toFloatProcessors = ReflectionUtils.getObjectField(sinkObj, "toFloatPcmAvailableAudioProcessors") as? Array<*>
                                val floatResampler = toFloatProcessors?.firstOrNull { it != null && it.javaClass.name.contains("FloatResamplingAudioProcessor") }
                                val audioProcessorClass = ReflectionUtils.findClass("com.google.android.exoplayer2.audio.AudioProcessor", cl)
                                if (audioProcessorClass != null) {
                                    @Suppress("UNCHECKED_CAST")
                                    val newToFloat = if (floatResampler != null) {
                                        val arr = java.lang.reflect.Array.newInstance(audioProcessorClass, 2) as Array<Any>
                                        arr[0] = floatResampler
                                        arr[1] = vocalsProcessor
                                        arr
                                    } else {
                                        val arr = java.lang.reflect.Array.newInstance(audioProcessorClass, 1) as Array<Any>
                                        arr[0] = vocalsProcessor
                                        arr
                                    }
                                    ReflectionUtils.setObjectField(sinkObj, "toFloatPcmAvailableAudioProcessors", newToFloat)
                                    logInfo("Successfully patched DefaultAudioSink.toFloatPcmAvailableAudioProcessors with [FloatResampler, VocalsAudioProcessor]")
                                }
                            }
                        } catch (t: Throwable) {
                            logError("Error patching DefaultAudioSink toFloat processors", t)
                        }
                        res
                    }
                }
                logInfo("Successfully hooked DefaultAudioSink constructors")
            }
        } catch (e: Throwable) {
            logError("Failed to hook DefaultAudioSink constructors", e)
        }

        val processorClass = ReflectionUtils.findClass(
            "com.apple.android.music.playback.processors.VocalsAudioProcessor",
            cl
        )
        if (processorClass == null) {
            logInfo("Could not find VocalsAudioProcessor")
            return
        }

        try {
            processorClass.declaredConstructors.forEach { ctor ->
                ctor.isAccessible = true
                hookMethod(ctor) { chain ->
                    val res = chain.proceed()
                    try {
                        ReflectionUtils.setBooleanField(chain.thisObject, "FORCE_DISABLE", false)
                        logInfo("Set VocalsAudioProcessor.FORCE_DISABLE = false")
                    } catch (e: Throwable) {
                        logError("Could not set FORCE_DISABLE", e)
                    }
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook VocalsAudioProcessor constructors", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "queueInput", ByteBuffer::class.java)?.let { m ->
                hookMethod(m) { chain ->
                    val processorObj = chain.thisObject
                    val inputBuffer = chain.getArg(0) as ByteBuffer
                    val processor = audioProcessor
                    if (processor != null) {
                        processor.processQueueInput(processorObj, inputBuffer)
                    } else {
                        SingAudioProcessor.passThrough(processorObj, inputBuffer)
                    }
                    null
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook queueInput", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "onFlush")?.let { m ->
                hookMethod(m) { chain ->
                    val res = chain.proceed()
                    audioProcessor?.reset()
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook onFlush", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "onQueueEndOfStream")?.let { m ->
                hookMethod(m) { chain ->
                    val res = chain.proceed()
                    audioProcessor?.onEndOfStream()
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook onQueueEndOfStream", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "getOutput")?.let { m ->
                hookMethod(m) { chain ->
                    val processor = audioProcessor
                    if (processor != null && processor.isEnabled && chain.thisObject.javaClass.name.contains("VocalsAudioProcessor")) {
                        val currentOutput = ReflectionUtils.getObjectField(chain.thisObject, "outputBuffer") as? ByteBuffer
                        if (currentOutput == null || !currentOutput.hasRemaining()) {
                            val drainBuf = processor.drainPendingOutput(chain.thisObject)
                            if (drainBuf != null) {
                                return@hookMethod chain.proceed()
                            }
                        }
                    }
                    chain.proceed()
                }
            }
            logInfo("Successfully hooked VocalsAudioProcessor.getOutput for stream draining")
        } catch (t: Throwable) {
            logError("Failed to hook getOutput", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "isEnded")?.let { m ->
                hookMethod(m) { chain ->
                    val processor = audioProcessor
                    if (processor != null && processor.isEnabled) {
                        processor.isEnded()
                    } else {
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook isEnded", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "setLevel", java.lang.Float.TYPE)?.let { m ->
                hookMethod(m) { chain ->
                    val rawLevel = chain.getArg(0) as Float
                    audioProcessor?.vocalLevel = rawLevel
                    logInfo("VocalsAudioProcessor.setLevel: $rawLevel")
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook setLevel", t)
        }

        try {
            ReflectionUtils.findMethod(processorClass, "setMode", java.lang.Integer.TYPE)?.let { m ->
                hookMethod(m) { chain ->
                    val mode = chain.getArg(0) as Int
                    val enabled = (mode == 1)
                    val processor = audioProcessor
                    val wasSingActive = (processor?.isEnabled == true)
                    if (processor != null) {
                        processor.isEnabled = enabled
                    }
                    onSingModeChanged(enabled)
                    logInfo("VocalsAudioProcessor.setMode: mode=$mode (enabled=$enabled)")
                    chain.proceed()
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook setMode", t)
        }

        logInfo("Hooked VocalsAudioProcessor successfully")
    }

    /**
     * 4. Intercept user's slider changes from LocalMediaPlayerController.
     */
    private fun hookPlaybackController(cl: ClassLoader) {
        try {
            val controllerClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.controller.LocalMediaPlayerController",
                cl
            )
            if (controllerClass != null) {
                controllerClass.declaredConstructors.forEach { ctor ->
                    ctor.isAccessible = true
                    hookMethod(ctor) { chain ->
                        val res = chain.proceed()
                        currentLocalPlayerController = WeakReference(chain.thisObject)
                        logInfo("Captured LocalMediaPlayerController instance from constructor")
                        res
                    }
                }

                ReflectionUtils.findMethod(controllerClass, "setVocalAttenuationLevel", java.lang.Float.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        currentLocalPlayerController = WeakReference(chain.thisObject)
                        val rawLevel = chain.getArg(0) as Float
                        val level = if (org.francasio.sing.provider.SingSettings.allowZeroFloor) {
                            if (rawLevel <= 0.005f || (defaultM0 != null && rawLevel <= defaultM0!! + 0.002f)) {
                                0.0f
                            } else if (rawLevel >= 0.995f) {
                                1.0f
                            } else {
                                rawLevel.coerceIn(0.0f, 1.0f)
                            }
                        } else {
                            rawLevel
                        }
                        audioProcessor?.vocalLevel = level
                        currentVocalCtrl?.get()?.let { updateSliderIcon(it, rawLevel) }
                        logInfo("Vocal level changed to: $level (raw: $rawLevel, allowZeroFloor=${org.francasio.sing.provider.SingSettings.allowZeroFloor})")
                        chain.proceed(arrayOf(level))
                    }
                }

                ReflectionUtils.findMethod(controllerClass, "setVocalAttenuationMode", java.lang.Integer.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val controller = chain.thisObject
                        currentLocalPlayerController = WeakReference(controller)
                        val mode = chain.getArg(0) as Int
                        val enabled = (mode == 1)
                        val processor = audioProcessor
                        val wasSingActive = (processor?.isEnabled == true)

                        val currentPos = if (wasSingActive && !enabled) {
                            try {
                                ReflectionUtils.callMethod(controller, "getCurrentPosition") as? Long ?: -1L
                            } catch (_: Throwable) { -1L }
                        } else -1L
                        val cushionMs = if (wasSingActive && !enabled) {
                            processor?.bufferedCushionMs ?: 0L
                        } else 0L

                        if (processor != null) {
                            processor.isEnabled = enabled
                        }
                        onSingModeChanged(enabled)
                        logInfo("LocalMediaPlayerController.setVocalAttenuationMode: mode=$mode (enabled=$enabled, wasSingActive=$wasSingActive, pos=$currentPos ms, cushion=$cushionMs ms)")

                        val result = chain.proceed()

                        if (wasSingActive && !enabled && currentPos >= 0L) {
                            val delayMs = if (cushionMs > 0L) cushionMs - 240L else 10800L
                            mainHandler.postDelayed({
                                try {
                                    val ctrl = currentLocalPlayerController?.get() ?: controller
                                    val nowPos = try {
                                        ReflectionUtils.callMethod(ctrl, "getCurrentPosition") as? Long ?: currentPos
                                    } catch (_: Throwable) { currentPos }

                                    val targetMs = if (nowPos >= currentPos + (delayMs / 2)) {
                                        maxOf(0L, nowPos - delayMs)
                                    } else {
                                        maxOf(0L, currentPos - 300L)
                                    }

                                    logInfo("Sing disabled: nowPos=$nowPos ms, currentPos=$currentPos ms, delayMs=$delayMs ms -> targetMs=$targetMs ms")
                                    ReflectionUtils.callMethod(ctrl, "seekToPosition", targetMs)
                                    logInfo("Triggered controller.seekToPosition($targetMs ms) to restore sync on Sing disable")
                                } catch (t: Throwable) {
                                    logError("Failed to invoke seekToPosition after Sing disable", t)
                                }
                            }, 120L)
                        }
                        result
                    }
                }

                logInfo("Hooked LocalMediaPlayerController slider listeners")
            }
        } catch (e: Throwable) {
            logError("Failed to hook LocalMediaPlayerController", e)
        }
    }

    /**
     * 5. Hook Lyrics UI and ViewModels:
     *    - Force D2(true) on songs with lyrics so PlayerLyricsViewFragment reveals VocalAttenuationControl
     *    - Force Y0.b0 = true so the player ViewModel considers vocal attenuation supported
     *    - Un-hide vocal_ctrl during onCreateView
     *    - Hide Lossless badge when Sing mode is ON, restore exactly 3s after turning Sing OFF
     *    - Dynamic microphone / ic_instrumental icon swapping on the slider
     *    - Long-press / mouse right-click on VocalAttenuationControl to launch Sing settings
     */
    private fun isRightClick(event: MotionEvent): Boolean {
        return (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton == MotionEvent.BUTTON_SECONDARY)
    }

    private fun launchMainActivity(context: Context, reason: String = "shortcut") {
        val now = SystemClock.uptimeMillis()
        if (now - lastSettingsLaunchTime < 500L) {
            logInfo("Suppressed duplicate launchMainActivity ($reason, debounce)")
            return
        }
        lastSettingsLaunchTime = now
        lastLongPressTime = now
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "org.francasio.sing",
                    "org.francasio.sing.ui.MainActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("from_apple_music", true)
            }
            context.startActivity(intent)
            logInfo("Launched Sing settings ($reason)")
        } catch (t: Throwable) {
            logError("Failed to launch Sing settings: ${t.message}")
        }
    }

    private fun hookLyricsUI(cl: ClassLoader) {
        try {
            val y0Class = ReflectionUtils.findClass("com.apple.android.music.player.Y0", cl)
            if (y0Class != null) {
                y0Class.declaredConstructors.forEach { ctor ->
                    ctor.isAccessible = true
                    hookMethod(ctor) { chain ->
                        val res = chain.proceed()
                        val isReady = audioProcessor?.isEngineReady == true
                        if (isReady && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                            try {
                                ReflectionUtils.setBooleanField(chain.thisObject, "b0", true)
                                logInfo("Set Y0.b0 = true")
                            } catch (_: Throwable) {}
                        }
                        res
                    }
                }
            }
        } catch (e: Throwable) {
            logError("Failed to hook Y0 constructor", e)
        }

        try {
            val lyricsFragClass = ReflectionUtils.findClass(
                "com.apple.android.music.player.fragment.PlayerLyricsViewFragment",
                cl
            )
            if (lyricsFragClass != null) {
                ReflectionUtils.findMethod(lyricsFragClass, "D2", java.lang.Boolean.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        currentLyricsFragment = WeakReference(chain.thisObject)
                        val isReady = audioProcessor?.isEngineReady == true
                        if (!isReady) {
                            // Engine is still loading in background: keep Sing button strictly hidden
                            return@hookMethod chain.proceed(arrayOf(false))
                        }
                        val origArg = chain.getArg(0) as Boolean
                        if (origArg) {
                            return@hookMethod chain.proceed(arrayOf(true))
                        }
                        if (org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                            val frag = chain.thisObject
                            val songInfoPtr = try {
                                ReflectionUtils.getObjectField(frag, "h1")
                            } catch (_: Throwable) { null }
                            if (songInfoPtr != null || audioProcessor != null) {
                                return@hookMethod chain.proceed(arrayOf(true))
                            }
                        }
                        chain.proceed(arrayOf(false))
                    }
                }

                val onCreateView = ReflectionUtils.findMethod(
                    lyricsFragClass,
                    "onCreateView",
                    LayoutInflater::class.java,
                    ViewGroup::class.java,
                    Bundle::class.java
                )
                if (onCreateView != null) {
                    hookMethod(onCreateView) { chain ->
                        currentLyricsFragment = WeakReference(chain.thisObject)
                        val view = chain.proceed() as? View
                        val frag = chain.thisObject
                        try {
                            for (field in frag.javaClass.declaredFields) {
                                field.isAccessible = true
                                val value = field.get(frag) ?: continue
                                if (value is androidx.recyclerview.widget.RecyclerView.Adapter<*>) {
                                    hookLyricsAdapter(value.javaClass)
                                } else {
                                    var cur: Class<*>? = value.javaClass
                                    while (cur != null && cur != Any::class.java) {
                                        if (cur.name.contains("Adapter")) {
                                            hookLyricsAdapter(value.javaClass)
                                            break
                                        }
                                        cur = cur.superclass
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                        if (view != null) {
                            currentLyricsView = WeakReference(view)
                            view.post {
                                try {
                                    val rv = findLyricsRecyclerView(view)
                                    logInfo("findLyricsRecyclerView in onCreateView: rv=$rv")
                                    if (rv != null) {
                                        hookLyricsRecyclerView(rv)
                                    }
                                } catch (t: Throwable) {
                                    logInfo("Failed to resolve lyrics adapter from RecyclerView: ${t.message}")
                                }
                            }
                            val vocalId = view.resources.getIdentifier("vocal_ctrl", "id", TARGET_PACKAGE)
                            if (vocalId != 0) {
                                val vocalCtrl = view.findViewById<View>(vocalId)
                                if (vocalCtrl != null) {
                                    currentVocalCtrl = WeakReference(vocalCtrl)
                                    updateVocalAttenuationFloor(vocalCtrl.javaClass)
                                    val isReady = audioProcessor?.isEngineReady == true
                                    if (isReady && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                                        vocalCtrl.visibility = View.VISIBLE
                                        logInfo("Forced vocal_ctrl visibility to VISIBLE in onCreateView (engine ready)")
                                    } else {
                                        vocalCtrl.visibility = View.GONE
                                        logInfo("Engine not ready: vocal_ctrl set to GONE in onCreateView")
                                    }
                                    updateSliderIcon(vocalCtrl)
                                }
                            }

                            val badgeId = view.resources.getIdentifier("audio_badge_with_text", "id", TARGET_PACKAGE)
                            val badge = if (badgeId != 0) view.findViewById<View>(badgeId) else null
                            if (badge != null) {
                                currentLosslessBadge = WeakReference(badge)
                                val isSingActive = audioProcessor?.isEnabled == true
                                if (isSingActive || isLosslessBadgeSuppressed) {
                                    badge.animate()?.cancel()
                                    badge.alpha = 0f
                                    logInfo("Lossless badge alpha set to 0 in onCreateView (Sing is active or suppressed)")
                                } else {
                                    badge.alpha = 1f
                                }
                            }
                        }
                        view
                    }
                }

                try {
                    val vacDClass = ReflectionUtils.findClass(
                        "com.apple.android.music.common.views.VocalAttenuationControl\$d",
                        cl
                    )
                    if (vacDClass != null) {
                        val h2 = ReflectionUtils.findMethod(lyricsFragClass, "h2", lyricsFragClass, vacDClass)
                        if (h2 != null) {
                            hookMethod(h2) { chain ->
                                val res = chain.proceed()
                                val frag = chain.getArg(0)
                                val dVar = chain.getArg(1)
                                val dName = dVar?.toString() ?: ""
                                logInfo("PlayerLyricsViewFragment.h2 called with $dName")

                                if (currentLosslessBadge?.get() == null && frag != null) {
                                    try {
                                        val n0 = ReflectionUtils.getObjectField(frag, "n0")
                                        if (n0 != null) {
                                            val k0 = ReflectionUtils.getObjectField(n0, "k0")
                                            if (k0 != null) {
                                                val j0 = ReflectionUtils.getObjectField(k0, "j0")
                                                if (j0 != null) {
                                                    val b0 = ReflectionUtils.getObjectField(j0, "b0") as? View
                                                    if (b0 != null) {
                                                        currentLosslessBadge = WeakReference(b0)
                                                    }
                                                }
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }

                                if (dName == "ON") {
                                    onSingModeChanged(true)
                                } else if (dName == "OFF") {
                                    onSingModeChanged(false)
                                }
                                res
                            }
                            logInfo("Hooked PlayerLyricsViewFragment.h2")
                        }
                    }
                } catch (t: Throwable) {
                    logError("Failed to hook PlayerLyricsViewFragment.h2", t)
                }

                logInfo("Hooked PlayerLyricsViewFragment (D2 + onCreateView + h2)")
            }
        } catch (e: Throwable) {
            logError("Failed to hook PlayerLyricsViewFragment", e)
        }

        try {
            val x2Class = ReflectionUtils.findClass("q8.X2", cl)
            if (x2Class != null) {
                val iMethod = ReflectionUtils.findMethod(x2Class, "I", View::class.java, java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
                if (iMethod != null) {
                    hookMethod(iMethod) { chain ->
                        val res = chain.proceed()
                        val view = chain.getArg(0) as? View
                        if (view != null) {
                            val badge = getLosslessBadge()
                            if (view === badge) {
                                if (isLosslessBadgeSuppressed) {
                                    view.animate()?.cancel()
                                    view.alpha = 0f
                                } else if (view.alpha != 1f) {
                                    view.alpha = 1f
                                }
                            }
                        }
                        res
                    }
                    logInfo("Hooked q8.X2.I to guard Lossless badge opacity")
                }
            }
        } catch (t: Throwable) {
            logInfo("Note: q8.X2.I not hooked (${t.message})")
        }

        try {
            val vacClass = ReflectionUtils.findClass(
                "com.apple.android.music.common.views.VocalAttenuationControl",
                cl
            )
            if (vacClass != null) {
                cachedVacClass = vacClass
                updateVocalAttenuationFloor(vacClass)

                vacClass.declaredConstructors.forEach { ctor ->
                    hookMethod(ctor) { chain ->
                        val res = chain.proceed()
                        (chain.thisObject as? View)?.let { vocalCtrl ->
                            currentVocalCtrl = WeakReference(vocalCtrl)
                            attachScrollWheelHandler(vocalCtrl)
                        }
                        updateVocalAttenuationFloor(vacClass)
                        res
                    }
                }

                ReflectionUtils.findMethod(vacClass, "setSliderFraction", java.lang.Float.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val fraction = chain.getArg(0) as Float
                        val vocalCtrl = chain.thisObject as View
                        currentVocalCtrl = WeakReference(vocalCtrl)
                        val res = chain.proceed()
                        updateSliderIcon(vocalCtrl, fraction)
                        res
                    }
                }

                try {
                    val touchListenerClass = ReflectionUtils.findClass(
                        "com.apple.android.music.common.views.VocalAttenuationControl\$e",
                        cl
                    )
                    if (touchListenerClass != null) {
                        val onTouch = ReflectionUtils.findMethod(touchListenerClass, "onTouch", View::class.java, MotionEvent::class.java)
                        if (onTouch != null) {
                            var isLongPressed = false
                            var isRightClickActive = false
                            var downX = 0f
                            var downY = 0f
                            var longPressRunnable: Runnable? = null

                            hookMethod(onTouch) { chain ->
                                val vocalCtrl = chain.getArg(0) as? View ?: return@hookMethod chain.proceed()
                                attachScrollWheelHandler(vocalCtrl)
                                val event = chain.getArg(1) as MotionEvent

                                val rightClick = isRightClick(event)
                                if (rightClick) {
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                                            isRightClickActive = true
                                            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                            longPressRunnable = null
                                            isLongPressed = false
                                            if (vocalCtrl.scaleX != 1.0f) vocalCtrl.scaleX = 1.0f
                                            if (vocalCtrl.scaleY != 1.0f) vocalCtrl.scaleY = 1.0f
                                            vocalCtrl.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            logInfo("Right click detected on VocalAttenuationControl -> launching Sing settings")
                                            launchMainActivity(vocalCtrl.context, "mouse right click")
                                            return@hookMethod true
                                        }
                                    }
                                }

                                if (isRightClickActive) {
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_MOVE -> return@hookMethod true
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_CANCEL -> {
                                            isRightClickActive = false
                                            return@hookMethod true
                                        }
                                    }
                                }

                                val isSingOn = audioProcessor?.isEnabled == true || try {
                                    val binding = ReflectionUtils.getObjectField(vocalCtrl, "e")
                                    val c0 = ReflectionUtils.getObjectField(binding, "c0") as? View
                                    c0?.isActivated == true
                                } catch (_: Throwable) {
                                    false
                                } || try {
                                    ReflectionUtils.getBooleanField(vocalCtrl, "l")
                                } catch (_: Throwable) { false }

                                if (isSingOn) {
                                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                    longPressRunnable = null
                                    isLongPressed = false
                                    if (vocalCtrl.scaleX != 1.0f) vocalCtrl.scaleX = 1.0f
                                    if (vocalCtrl.scaleY != 1.0f) vocalCtrl.scaleY = 1.0f

                                    // Guard 100% release if allowZeroFloor ("Remove slider limits") is enabled
                                    if (org.francasio.sing.provider.SingSettings.allowZeroFloor &&
                                        (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                                    ) {
                                        val isDragging = try {
                                            ReflectionUtils.getBooleanField(vocalCtrl, "l")
                                        } catch (_: Throwable) { false }

                                        if (isDragging) {
                                            val currentFraction = getVocalSliderFraction(vocalCtrl)
                                            if (currentFraction >= 0.99f) {
                                                // Temporarily clamp H to 0.999f so Apple Music's onTouch takes the collapse branch (F0)
                                                // instead of turning off Sing mode (K0).
                                                setVocalSliderFractionField(vocalCtrl, 0.999f)
                                                val res = chain.proceed()
                                                // Restore sliderFraction to 1.0f (100%) and keep Sing mode ON
                                                setVocalSliderFractionField(vocalCtrl, 1.0f)
                                                audioProcessor?.vocalLevel = 1.0f
                                                try {
                                                    val lCallback = ReflectionUtils.getObjectField(vocalCtrl, "L")
                                                    if (lCallback != null) {
                                                        ReflectionUtils.callMethod(lCallback, "invoke", 1.0f)
                                                    }
                                                } catch (_: Throwable) {}
                                                updateSliderIcon(vocalCtrl, 1.0f)
                                                logInfo("Guarded Sing mode at 100% vocal level, collapsing slider while remaining ON")
                                                return@hookMethod res
                                            }
                                        }
                                    }

                                    return@hookMethod chain.proceed()
                                }

                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        isLongPressed = false
                                        downX = event.x
                                        downY = event.y
                                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                        if (vocalCtrl.scaleX != 1.0f) vocalCtrl.scaleX = 1.0f
                                        if (vocalCtrl.scaleY != 1.0f) vocalCtrl.scaleY = 1.0f

                                        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                                        val runnable = Runnable {
                                            val stillSingOff = !(audioProcessor?.isEnabled ?: false) && !try {
                                                ReflectionUtils.getBooleanField(vocalCtrl, "l")
                                            } catch (_: Throwable) { false }

                                            if (stillSingOff) {
                                                isLongPressed = true
                                                vocalCtrl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                launchMainActivity(vocalCtrl.context, "long press")
                                            }
                                        }
                                        longPressRunnable = runnable
                                        mainHandler.postDelayed(runnable, timeout)
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        val slop = ViewConfiguration.get(vocalCtrl.context).scaledTouchSlop
                                        val dx = Math.abs(event.x - downX)
                                        val dy = Math.abs(event.y - downY)
                                        if (dx > slop || dy > slop) {
                                            if (longPressRunnable != null) {
                                                mainHandler.removeCallbacks(longPressRunnable!!)
                                                longPressRunnable = null
                                            }
                                        }
                                        if (isLongPressed) {
                                            return@hookMethod true
                                        }
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (longPressRunnable != null) {
                                            mainHandler.removeCallbacks(longPressRunnable!!)
                                            longPressRunnable = null
                                        }
                                        if (isLongPressed) {
                                            isLongPressed = false
                                            return@hookMethod true
                                        }
                                    }
                                    MotionEvent.ACTION_CANCEL -> {
                                        if (longPressRunnable != null) {
                                            mainHandler.removeCallbacks(longPressRunnable!!)
                                            longPressRunnable = null
                                        }
                                        isLongPressed = false
                                    }
                                }
                                chain.proceed()
                            }
                        }
                    }

                    ReflectionUtils.findMethod(vacClass, "q", java.lang.Boolean.TYPE)?.let { m ->
                        hookMethod(m) { chain ->
                            val res = chain.proceed()
                            val vocalCtrl = chain.thisObject as? View
                            if (vocalCtrl != null) {
                                currentVocalCtrl = WeakReference(vocalCtrl)
                                updateSliderIcon(vocalCtrl)
                            }
                            res
                        }
                    }

                    ReflectionUtils.findMethod(vacClass, "r", java.lang.Boolean.TYPE)?.let { m ->
                        hookMethod(m) { chain ->
                            if (SystemClock.uptimeMillis() - lastLongPressTime < 1000L) {
                                logInfo("Suppressed VocalAttenuationControl.r after shortcut launch")
                                null
                            } else {
                                val res = chain.proceed()
                                val vocalCtrl = chain.thisObject as? View
                                if (vocalCtrl != null) {
                                    currentVocalCtrl = WeakReference(vocalCtrl)
                                    updateSliderIcon(vocalCtrl)
                                }
                                res
                            }
                        }
                    }

                    logInfo("Hooked VocalAttenuationControl long-press / right-click settings launcher")
                } catch (t: Throwable) {
                    logError("Failed to hook VocalAttenuationControl long press", t)
                }

                logInfo("Hooked VocalAttenuationControl (zero floor, setSliderFraction)")
            }
        } catch (e: Throwable) {
            logError("Failed to hook VocalAttenuationControl", e)
        }

        listOf("com.apple.android.music.player.x", "com.apple.android.music.player.y").forEach { name ->
            try {
                ReflectionUtils.findClass(name, cl)?.let { hookLyricsAdapter(it) }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Halts Apple Music crossfade while Sing is active.
     */
    private fun hookCrossFade(cl: ClassLoader) {
        try {
            val fadeControlClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.player.PlayerAudioFadeControl",
                cl
            )
            if (fadeControlClass != null) {
                ReflectionUtils.findMethod(fadeControlClass, "isCrossFadeEnabled")?.let { m ->
                    hookMethod(m) { chain ->
                        val isSingActive = audioProcessor?.isEnabled ?: false
                        if (isSingActive) {
                            false
                        } else {
                            chain.proceed()
                        }
                    }
                }
                logInfo("Successfully hooked PlayerAudioFadeControl.isCrossFadeEnabled (halt crossfade during Sing)")
            }
        } catch (e: Throwable) {
            logError("Could not hook PlayerAudioFadeControl", e)
        }
    }

    private fun hookSelfModuleCheck(cl: ClassLoader) {
        try {
            val statusClass = ReflectionUtils.findClass("org.francasio.sing.util.XposedStatus", cl) ?: return
            ReflectionUtils.findMethod(statusClass, "isModuleLoaded")?.let { m ->
                hookMethod(m) { true }
            }
            val managerInfo = detectedManagerInfo ?: detectXposedManager().also { detectedManagerInfo = it }
            ReflectionUtils.findMethod(statusClass, "getXposedManagerInfo")?.let { m ->
                hookMethod(m) { managerInfo }
            }
            logInfo("Successfully hooked XposedStatus in module app")
        } catch (e: Throwable) {
            logError("Failed to hook XposedStatus", e)
        }
    }

    private fun detectXposedManager(): String {
        try {
            val name = frameworkName
            val ver = frameworkVersion
            if (!name.isNullOrEmpty() && !ver.isNullOrEmpty()) {
                return "$name $ver"
            } else if (!name.isNullOrEmpty()) {
                return name
            }
        } catch (_: Throwable) {}

        // 1. LSPatch detection
        if (SingXposedEntry::class.java.classLoader?.toString()?.contains("lspatch", ignoreCase = true) == true) {
            return "LSPatch"
        }
        try {
            Class.forName("org.lsposed.lspatch.loader.Loader")
            return "LSPatch"
        } catch (_: Throwable) {}
        try {
            Class.forName("org.lsposed.lspatch.metaloader.MetaLoader")
            return "LSPatch"
        } catch (_: Throwable) {}

        // 2. System properties (LSPosed, EdXposed)
        val lspProps = listOf(
            "ro.lsposed.version",
            "org.lsposed.version",
            "lsposed.version",
            "persist.sys.lsposed.version"
        )
        for (prop in lspProps) {
            val value = getSystemProp(prop)
            if (value.isNotEmpty()) {
                return if (value.startsWith("LSPosed", ignoreCase = true)) value else "LSPosed $value"
            }
        }

        val edProps = listOf("ro.edxposed.version", "edxposed.version")
        for (prop in edProps) {
            val value = getSystemProp(prop)
            if (value.isNotEmpty()) {
                return if (value.startsWith("EdXposed", ignoreCase = true)) value else "EdXposed $value"
            }
        }

        return "LibXposed API 102"
    }

    private fun getSystemProp(key: String): String {
        return try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private var isRecyclerViewHooked = false
    private val hookedViews: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val hookedAdapters: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val adapterActiveMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()

    @Volatile
    private var isManualScrolling = false

    private val blurAnimators = java.util.WeakHashMap<View, android.animation.ValueAnimator>()
    private val currentBlurRadii = java.util.WeakHashMap<View, Float>()
    private val targetBlurRadii = java.util.WeakHashMap<View, Float>()
    private val blurRadiusPx = 3.0f
    private val blurTransitionMs = 150L

    private fun animateBlur(view: View, targetRadius: Float, durationMs: Long = blurTransitionMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val currentRadius = currentBlurRadii[view]
        if (currentRadius == null) {
            // First time this view is seen: snap immediately to avoid initial flash
            currentBlurRadii[view] = targetRadius
            targetBlurRadii[view] = targetRadius
            if (targetRadius <= 0.1f) {
                view.setRenderEffect(null)
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(targetRadius, targetRadius, Shader.TileMode.CLAMP))
            }
            return
        }

        // If an animation is already actively running towards this exact target radius, let it finish
        val existingTarget = targetBlurRadii[view]
        val existingAnimator = blurAnimators[view]
        if (existingTarget != null && kotlin.math.abs(existingTarget - targetRadius) < 0.05f) {
            if (existingAnimator?.isRunning == true) {
                return
            }
            if (kotlin.math.abs(currentRadius - targetRadius) < 0.05f) {
                return
            }
        }

        existingAnimator?.cancel()
        blurAnimators.remove(view)
        targetBlurRadii[view] = targetRadius

        if (durationMs <= 0L || kotlin.math.abs(currentRadius - targetRadius) < 0.05f) {
            currentBlurRadii[view] = targetRadius
            if (targetRadius <= 0.1f) {
                view.setRenderEffect(null)
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(targetRadius, targetRadius, Shader.TileMode.CLAMP))
            }
            return
        }

        val animator = android.animation.ValueAnimator.ofFloat(currentRadius, targetRadius).apply {
            duration = durationMs
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val r = animation.animatedValue as Float
                currentBlurRadii[view] = r
                if (r <= 0.1f) {
                    view.setRenderEffect(null)
                } else {
                    view.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    blurAnimators.remove(view)
                    currentBlurRadii[view] = targetRadius
                    if (targetRadius <= 0.1f) {
                        view.setRenderEffect(null)
                    } else {
                        view.setRenderEffect(RenderEffect.createBlurEffect(targetRadius, targetRadius, Shader.TileMode.CLAMP))
                    }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    blurAnimators.remove(view)
                }
            })
        }
        blurAnimators[view] = animator
        animator.start()
    }

    private fun applyBlur(view: View, animate: Boolean = true) {
        animateBlur(view, blurRadiusPx, if (animate) blurTransitionMs else 0L)
    }

    private fun clearBlur(view: View, animate: Boolean = true) {
        animateBlur(view, 0f, if (animate) blurTransitionMs else 0L)
    }

    private fun clearAllChildrenBlur(rv: ViewGroup, animate: Boolean = true) {
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            clearBlur(child, animate)
        }
    }

    private fun getActivePositions(adapter: Any?): Set<Int>? {
        if (adapter == null) return null
        try {
            val adapterClass = adapter.javaClass
            var method = adapterActiveMethods[adapterClass]
            if (method == null) {
                var cur: Class<*>? = adapterClass
                while (cur != null && cur != Any::class.java) {
                    for (m in cur.declaredMethods) {
                        if (m.parameterTypes.isEmpty() && java.util.Set::class.java.isAssignableFrom(m.returnType)) {
                            m.isAccessible = true
                            adapterActiveMethods[adapterClass] = m
                            method = m
                            break
                        }
                    }
                    if (method != null) break
                    cur = cur.superclass
                }
            }
            val set = method?.invoke(adapter) as? Set<*>
            if (set != null) {
                val result = mutableSetOf<Int>()
                for (item in set) {
                    if (item is Number) {
                        result.add(item.toInt())
                    }
                }
                return result
            }
        } catch (t: Throwable) {
            logError("Failed to get active positions from adapter: ${t.message}", t)
        }
        return null
    }

    private var karaokeFlexId = 0
    private var karaokeBgFlexId = 0
    private var songLyricsLineId = 0
    private var songLyricsLineContainerId = 0
    private var writersCreditsLineId = 0
    private var instrumentalRootId = 0
    private var creditsBtnContainerId = 0
    private var lyricsMainContentId = 0

    private fun resolveKaraokeResourceIds(res: android.content.res.Resources) {
        if (karaokeFlexId == 0) {
            karaokeFlexId = res.getIdentifier("karaoke_line_flexbox", "id", TARGET_PACKAGE)
        }
        if (karaokeBgFlexId == 0) {
            karaokeBgFlexId = res.getIdentifier("karaoke_line_flexbox_background_pronunciation", "id", TARGET_PACKAGE)
        }
        if (songLyricsLineId == 0) {
            songLyricsLineId = res.getIdentifier("song_lyrics_line", "id", TARGET_PACKAGE)
        }
        if (songLyricsLineContainerId == 0) {
            songLyricsLineContainerId = res.getIdentifier("song_lyrics_line_container", "id", TARGET_PACKAGE)
        }
        if (writersCreditsLineId == 0) {
            writersCreditsLineId = res.getIdentifier("writers_credits_line", "id", TARGET_PACKAGE)
        }
        if (instrumentalRootId == 0) {
            instrumentalRootId = res.getIdentifier("lyrics_instrumental_root", "id", TARGET_PACKAGE)
        }
        if (creditsBtnContainerId == 0) {
            creditsBtnContainerId = res.getIdentifier("credits_lyrics_button_container", "id", TARGET_PACKAGE)
        }
        if (lyricsMainContentId == 0) {
            lyricsMainContentId = res.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
        }
    }

    private fun isEligibleLyricsRow(view: View): Boolean {
        try {
            resolveKaraokeResourceIds(view.resources)
        } catch (_: Throwable) {}

        val tagStr = view.tag?.toString() ?: ""
        if (tagStr.contains("instrumental", ignoreCase = true) ||
            tagStr.contains("credit", ignoreCase = true) ||
            tagStr.contains("writer", ignoreCase = true)) {
            return false
        }
        if (instrumentalRootId != 0 && (view.id == instrumentalRootId || view.findViewById<View>(instrumentalRootId) != null)) return false
        if (writersCreditsLineId != 0 && (view.id == writersCreditsLineId || view.findViewById<View>(writersCreditsLineId) != null)) return false
        if (creditsBtnContainerId != 0 && (view.id == creditsBtnContainerId || view.findViewById<View>(creditsBtnContainerId) != null)) return false

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i) ?: continue
                val cName = try { view.resources.getResourceEntryName(c.id) } catch (_: Throwable) { "" }
                if (cName.startsWith("dot") || cName.contains("credit") || cName.contains("writer")) {
                    return false
                }
            }
        }

        if (songLyricsLineContainerId != 0 && view.id == songLyricsLineContainerId) return true
        if (songLyricsLineId != 0 && view.findViewById<View>(songLyricsLineId) != null) return true
        if (karaokeFlexId != 0 && view.findViewById<View>(karaokeFlexId) != null) return true
        if (tagStr.contains("lyrics_line", ignoreCase = true)) return true

        return false
    }

    private fun updateLyricsBlur(rv: ViewGroup) {
        val blurEnabled = org.francasio.sing.provider.SingSettings.blurUnfocusedLyrics
        if (!blurEnabled || isManualScrolling) {
            clearAllChildrenBlur(rv, animate = true)
            return
        }

        val adapter = try { ReflectionUtils.callMethod(rv, "getAdapter") } catch (t: Throwable) { null } ?: return
        val activePositions = getActivePositions(adapter) ?: emptySet()

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (!isEligibleLyricsRow(child)) {
                clearBlur(child, animate = false)
                continue
            }

            val pos = try {
                (ReflectionUtils.callMethod(rv, "getChildAdapterPosition", child) as? Number)?.toInt()
                    ?: (ReflectionUtils.callMethod(rv, "getChildLayoutPosition", child) as? Number)?.toInt()
                    ?: -1
            } catch (t: Throwable) { -1 }

            if (pos < 0) continue

            if (pos in activePositions) {
                clearBlur(child, animate = true)
            } else {
                applyBlur(child, animate = true)
            }
        }
    }

    private var adapterTtmlField: java.lang.reflect.Field? = null
    private var adapterSongInfoField: java.lang.reflect.Field? = null
    private var adapterSparseArrayField: java.lang.reflect.Field? = null

    private fun findLyricsRecyclerView(v: View): View? {
        if (lyricsMainContentId == 0) {
            try {
                lyricsMainContentId = v.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
            } catch (_: Throwable) {}
        }
        if (lyricsMainContentId != 0) {
            val found = v.findViewById<View>(lyricsMainContentId)
            if (found != null) return found
        }
        if (v.javaClass.name.contains("RecyclerView") || v.javaClass.superclass?.name?.contains("RecyclerView") == true) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val found = findLyricsRecyclerView(v.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun hookLyricsRecyclerView(rv: View) {
        logInfo("hookLyricsRecyclerView called: rv=${rv.javaClass.name}, id=${rv.id}")
        if (rv !is ViewGroup) return

        if (lyricsMainContentId == 0) {
            try {
                lyricsMainContentId = rv.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
            } catch (_: Throwable) {}
        }
        if (lyricsMainContentId != 0 && rv.id != lyricsMainContentId) {
            logInfo("Ignoring non-lyrics RecyclerView: id=${rv.id}, target=$lyricsMainContentId")
            return
        }

        lyricsRecyclerView = WeakReference(rv)
        val currentAdapter = ReflectionUtils.callMethod(rv, "getAdapter")
        if (currentAdapter != null) {
            hookLyricsAdapter(currentAdapter.javaClass)
        }

        if (!hookedViews.add(rv)) return

        rv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!isManualScrolling) {
                        isManualScrolling = true
                        rv.post { updateLyricsBlur(rv) }
                    }
                }
            }
            false
        }

        try {
            val setScrollStateMethod = ReflectionUtils.findMethod(rv.javaClass, "setScrollState", java.lang.Integer.TYPE)
            if (setScrollStateMethod != null) {
                hookMethod(setScrollStateMethod) { chain ->
                    val state = chain.getArg(0) as? Int ?: 0
                    if (state == 1) { // SCROLL_STATE_DRAGGING
                        if (!isManualScrolling) {
                            isManualScrolling = true
                            rv.post { updateLyricsBlur(rv) }
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {}

        if (rv is androidx.recyclerview.widget.RecyclerView) {
            rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                        if (!isManualScrolling) {
                            isManualScrolling = true
                            recyclerView.post { updateLyricsBlur(recyclerView) }
                        }
                    }
                }
            })
        }

        val scrollMethodNames = listOf("smoothScrollToPosition", "scrollToPosition", "smoothScrollBy")
        for (name in scrollMethodNames) {
            rv.javaClass.methods.filter { it.name == name }.forEach { m ->
                hookMethod(m) { chain ->
                    isManualScrolling = false
                    val res = chain.proceed()
                    rv.post { updateLyricsBlur(rv) }
                    res
                }
            }
        }

        try {
            val lm = ReflectionUtils.callMethod(rv, "getLayoutManager")
            if (lm != null) {
                hookLayoutManager(lm, rv)
            }
            val setLayoutManagerMethod = rv.javaClass.methods.firstOrNull {
                it.name == "setLayoutManager" && it.parameterTypes.size == 1
            }
            if (setLayoutManagerMethod != null) {
                hookMethod(setLayoutManagerMethod) { chain ->
                    val newLm = chain.getArg(0)
                    if (newLm != null) {
                        hookLayoutManager(newLm, rv)
                    }
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {}

        try {
            val setAdapterMethod = rv.javaClass.methods.firstOrNull { m ->
                m.name == "setAdapter" && m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0].name.contains("Adapter") || m.parameterTypes[0] == Any::class.java)
            }
            if (setAdapterMethod != null) {
                hookMethod(setAdapterMethod) { chain ->
                    val newAdapter = chain.getArg(0)
                    if (newAdapter != null) {
                        hookLyricsAdapter(newAdapter.javaClass)
                    }
                    val res = chain.proceed()
                    rv.post { updateLyricsBlur(rv) }
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook RecyclerView.setAdapter: ${t.message}", t)
        }

        rv.post { updateLyricsBlur(rv) }
    }

    private fun hookLayoutManager(lm: Any, rv: ViewGroup) {
        val lmScrollMethods = listOf("smoothScrollToPosition", "scrollToPosition", "scrollToPositionWithOffset", "startSmoothScroll")
        var cur: Class<*>? = lm.javaClass
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                if (m.name in lmScrollMethods) {
                    hookMethod(m) { chain ->
                        isManualScrolling = false
                        val res = chain.proceed()
                        rv.post { updateLyricsBlur(rv) }
                        res
                    }
                }
            }
            cur = cur.superclass
        }
    }

    private fun hookLyricsAdapter(adapterClass: Class<*>) {
        if (!hookedAdapters.add(adapterClass.name)) return
        logInfo("hookLyricsAdapter: successfully registered ${adapterClass.name}")

        // 1. Hook onBindViewHolder (blur and group centering)
        val bindMethods = adapterClass.declaredMethods.filter { m ->
            !java.lang.reflect.Modifier.isAbstract(m.modifiers) &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == java.lang.Integer.TYPE &&
                (m.name == "k" || m.name == "onBindViewHolder" || m.returnType == java.lang.Void.TYPE)
        }
        for (m in bindMethods) {
            hookMethod(m) { chain ->
                val res = chain.proceed()
                try {
                    val adapter = chain.thisObject
                    val holder = chain.getArg(0) ?: return@hookMethod res
                    val position = chain.getArg(1) as? Int ?: return@hookMethod res

                    val itemView = (ReflectionUtils.getObjectField(holder, "itemView")
                        ?: ReflectionUtils.getObjectField(holder, "a")) as? View ?: return@hookMethod res

                    // --- Blur logic (both line-by-line and word-by-word) ---
                    if (!isEligibleLyricsRow(itemView)) {
                        clearBlur(itemView, animate = false)
                    } else if (!org.francasio.sing.provider.SingSettings.blurUnfocusedLyrics || isManualScrolling) {
                        clearBlur(itemView, animate = false)
                    } else {
                        val activePositions = getActivePositions(adapter) ?: emptySet()
                        if (position in activePositions) {
                            clearBlur(itemView, animate = true)
                        } else {
                            applyBlur(itemView, animate = true)
                        }
                    }

                    // --- Group lyrics centering (word-by-word only) ---
                    if (org.francasio.sing.provider.SingSettings.centerGroupLyrics && itemView is ViewGroup) {
                        resolveKaraokeResourceIds(itemView.resources)
                        val flexboxes = mutableListOf<View>()
                        if (karaokeFlexId != 0) {
                            itemView.findViewById<View>(karaokeFlexId)?.let { flexboxes.add(it) }
                        }
                        if (karaokeBgFlexId != 0) {
                            itemView.findViewById<View>(karaokeBgFlexId)?.let { flexboxes.add(it) }
                        }
                        if (flexboxes.isEmpty()) {
                            findFlexboxViews(itemView, flexboxes)
                        }

                        // Only process word-synced karaoke lines containing flexboxes
                        if (flexboxes.isNotEmpty() && isLineGroupAgent(adapter, position)) {
                            for (flex in flexboxes) {
                                centerFlexboxView(flex)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logError("Failed to process lyrics row bind: ${t.message}", t)
                }
                res
            }
        }

        // 2. Hook line-advance method O(List, int, Array) and auto-scroll method Q(boolean)
        var cur: Class<*>? = adapterClass
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue

                if (m.name == "Q" && m.parameterTypes.size == 1 &&
                    (m.parameterTypes[0] == java.lang.Boolean.TYPE || m.parameterTypes[0] == java.lang.Boolean::class.java)) {
                    hookMethod(m) { chain ->
                        val autoScroll = chain.getArg(0) as? Boolean ?: false
                        if (autoScroll) {
                            isManualScrolling = false
                            lyricsRecyclerView?.get()?.let { rv ->
                                rv.post { updateLyricsBlur(rv) }
                            }
                        }
                        chain.proceed()
                    }
                }

                if (m.parameterTypes.size == 3 &&
                    java.util.List::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.parameterTypes[1] == java.lang.Integer.TYPE &&
                    m.parameterTypes[2].isArray) {
                    hookMethod(m) { chain ->
                        isManualScrolling = false
                        val res = chain.proceed()
                        val active = getActivePositions(chain.thisObject)
                        logInfo("Lyrics advance (O): activePositions=$active, isManualScrolling=$isManualScrolling")
                        lyricsRecyclerView?.get()?.let { rv ->
                            rv.post { updateLyricsBlur(rv) }
                        }
                        res
                    }
                }
            }
            cur = cur.superclass
        }

        // 4. Hook onAttachedToRecyclerView
        val attachMethods = adapterClass.methods.filter {
            it.name == "onAttachedToRecyclerView" ||
            (it.parameterTypes.size == 1 && it.parameterTypes[0].name.contains("RecyclerView"))
        }
        for (m in attachMethods) {
            hookMethod(m) { chain ->
                val rv = chain.getArg(0) as? ViewGroup
                if (rv != null) {
                    if (lyricsMainContentId == 0) {
                        try {
                            lyricsMainContentId = rv.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
                        } catch (_: Throwable) {}
                    }
                    if (lyricsMainContentId == 0 || rv.id == lyricsMainContentId) {
                        lyricsRecyclerView = WeakReference(rv)
                        hookLyricsRecyclerView(rv)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun resolveAdapterFields(adapterClass: Class<*>) {
        if (adapterTtmlField != null && adapterSongInfoField != null && adapterSparseArrayField != null) return
        var cur: Class<*>? = adapterClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                f.isAccessible = true
                val typeName = f.type.name
                if (adapterSparseArrayField == null && android.util.SparseArray::class.java.isAssignableFrom(f.type)) {
                    adapterSparseArrayField = f
                } else if (adapterSongInfoField == null && typeName.contains("SongInfoPtr")) {
                    adapterSongInfoField = f
                } else if (adapterTtmlField == null && typeName.contains("com.apple.android.music.ttml")) {
                    adapterTtmlField = f
                }
            }
            cur = cur.superclass
        }
    }

    private fun isLineGroupAgent(adapter: Any, position: Int): Boolean {
        try {
            resolveAdapterFields(adapter.javaClass)
            val ttmlData = adapterTtmlField?.get(adapter)
                ?: ReflectionUtils.callMethod(adapter, "x")
                ?: ReflectionUtils.getObjectField(adapter, "p")
            if (ttmlData != null) {
                val linePtr = ReflectionUtils.callMethod(ttmlData, "a", position)
                if (linePtr != null) {
                    val lineNative = ReflectionUtils.callMethod(linePtr, "get")
                    if (lineNative != null) {
                        val agentId = ReflectionUtils.callMethod(lineNative, "getAgent") as? String
                        val lineId = ReflectionUtils.callMethod(lineNative, "getLineId") as? Int ?: -1

                        var d0Type: String? = null
                        var d0Align: Int? = null
                        val d0 = (adapterSparseArrayField?.get(adapter)
                            ?: ReflectionUtils.getObjectField(adapter, "d0")) as? android.util.SparseArray<*>
                        if (d0 != null && lineId >= 0) {
                            val itemInfo = d0.get(lineId)
                            if (itemInfo != null) {
                                for (f in itemInfo.javaClass.declaredFields) {
                                    f.isAccessible = true
                                    if (f.type.isEnum) {
                                        d0Type = f.get(itemInfo)?.toString()
                                    } else if (f.type == java.lang.Integer.TYPE && d0Align == null) {
                                        d0Align = f.getInt(itemInfo)
                                    }
                                }
                            }
                        }

                        var songInfoType: String? = null
                        val songInfoPtr = adapterSongInfoField?.get(adapter)
                            ?: ReflectionUtils.callMethod(adapter, "y")
                            ?: ReflectionUtils.getObjectField(adapter, "o")
                        if (songInfoPtr != null && !agentId.isNullOrEmpty()) {
                            val songInfoNative = ReflectionUtils.callMethod(songInfoPtr, "get")
                            if (songInfoNative != null) {
                                val agentPtr = ReflectionUtils.callMethod(songInfoNative, "getAgent", agentId)
                                if (agentPtr != null) {
                                    val agentNative = ReflectionUtils.callMethod(agentPtr, "get")
                                    if (agentNative != null) {
                                        songInfoType = ReflectionUtils.callMethod(agentNative, "getType")?.toString()
                                    }
                                }
                            }
                        }

                        val isGroup = agentId == "v1000" ||
                            songInfoType.equals("Group", ignoreCase = true) ||
                            d0Type.equals("Group", ignoreCase = true) ||
                            d0Align == 1

                        return isGroup
                    }
                }
            }
        } catch (t: Throwable) {
            logError("isLineGroupAgent error at pos $position: ${t.message}", t)
        }
        return false
    }

    private fun centerFlexboxView(view: View) {
        try {
            ReflectionUtils.callMethod(view, "setJustifyContent", 2)
        } catch (_: Throwable) {}

        val lp = view.layoutParams
        if (lp != null) {
            try {
                val biasField = ReflectionUtils.findField(lp.javaClass, "horizontalBias")
                    ?: ReflectionUtils.findField(lp.javaClass, "E")
                    ?: lp.javaClass.fields.firstOrNull { it.name == "horizontalBias" || (it.type == java.lang.Float.TYPE && it.modifiers and java.lang.reflect.Modifier.STATIC == 0) }
                biasField?.set(lp, 0.5f)
                view.layoutParams = lp
                view.requestLayout()
            } catch (_: Throwable) {}
        }
    }

    private fun findFlexboxViews(viewGroup: ViewGroup, out: MutableList<View>) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i) ?: continue
            if (child.javaClass.name.contains("FlexboxLayout")) {
                out.add(child)
            } else if (child is ViewGroup) {
                findFlexboxViews(child, out)
            }
        }
    }
}
