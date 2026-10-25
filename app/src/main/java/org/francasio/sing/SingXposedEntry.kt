package org.francasio.sing

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import org.francasio.sing.audio.AudioProcessorBridge
import org.francasio.sing.audio.SingAudioProcessor
import org.francasio.sing.clock.MediaClockSyncHook
import org.francasio.sing.hooks.AudioPipelineHooks
import org.francasio.sing.hooks.LyricsHooks
import org.francasio.sing.hooks.VocalControlHooks
import org.francasio.sing.util.ReflectionUtils
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.lang.reflect.Field
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

    private val audioProcessorBridge = AudioProcessorBridge(
        resolveModuleApkPath = ::resolveModuleApkPath,
        resolveManagerInfo = {
            detectedManagerInfo ?: detectXposedManager()
        },
        onManagerInfoResolved = { detectedManagerInfo = it },
        onEngineReady = { onEngineReady() },
        onSettingsChanged = { onSettingsChanged() },
        logInfo = ::logInfo,
        logError = ::logError
    )

    private val vocalControlHooks by lazy {
        VocalControlHooks(
            mainHandler = mainHandler,
            audioProcessor = { audioProcessor },
            onVocalControlSeen = { currentVocalCtrl = WeakReference(it) },
            currentVocalControl = { currentVocalCtrl?.get() },
            attachScrollWheelHandler = ::attachScrollWheelHandler,
            getSliderFraction = ::getVocalSliderFraction,
            setSliderFraction = ::setVocalSliderFractionField,
            updateSliderIcon = ::updateSliderIcon,
            defaultVocalFloor = { defaultM0 },
            onSingModeChanged = ::onSingModeChanged,
            logInfo = ::logInfo,
            logError = ::logError
        )
    }

    private val audioPipelineHooks by lazy {
        AudioPipelineHooks(
            audioProcessorProvider = { audioProcessor },
            onSingModeChanged = ::onSingModeChanged,
            registerHook = { executable, hooker -> hookMethod(executable, hooker = hooker) },
            logInfo = ::logInfo,
            logError = ::logError
        )
    }

    private val lyricsHooks by lazy {
        LyricsHooks(
            mainHandler = mainHandler,
            audioProcessorProvider = { audioProcessor },
            registerHook = { executable, hooker -> hookMethod(executable, hooker = hooker) },
            onVocalControlSeen = { currentVocalCtrl = WeakReference(it) },
            currentVocalControlProvider = { currentVocalCtrl?.get() },
            updateVocalAttenuationFloor = ::updateVocalAttenuationFloor,
            updateSliderIconCallback = ::updateSliderIcon,
            attachScrollWheelHandler = ::attachScrollWheelHandler,
            installVocalControlHooks = { vocalClass, classLoader ->
                vocalControlHooks.install(vocalClass, classLoader) { executable, hooker ->
                    hookMethod(executable, hooker = hooker)
                }
            },
            onSingModeChanged = ::onSingModeChanged,
            losslessBadgeSuppressed = { isLosslessBadgeSuppressed },
            logInfo = ::logInfo,
            logError = ::logError
        )
    }

    init {
        instance = this
    }

    companion object {
        var instance: SingXposedEntry? = null
        private const val TAG = "AppleMusicSing"
        private const val TARGET_PACKAGE = "com.apple.android.music"
        private val audioProcessor: SingAudioProcessor?
            get() = instance?.audioProcessorBridge?.processor
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
        var currentVocalCtrl: WeakReference<View>? = null

        private var loadingBlinkAnimator: android.animation.ValueAnimator? = null

        fun startLoadingBlink(view: View) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { startLoadingBlink(view) }
                return
            }
            if (loadingBlinkAnimator != null) return
            // Animate only the inner icon so we don't fight the app's own
            // alpha animations on the whole vocal_ctrl container.
            val iconView = view.findViewById<View>(
                view.resources.getIdentifier("vocal_off", "id", "com.apple.android.music")
            ) ?: view
            loadingBlinkAnimator = android.animation.ValueAnimator.ofFloat(0.3f, 0.7f).apply {
                duration = 1000L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener { va ->
                    iconView.alpha = va.animatedValue as Float
                }
                start()
            }
            Log.i(TAG, "Started loading blink on vocal icon")
        }

        fun stopLoadingBlink(view: View?) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mainHandler.post { stopLoadingBlink(view) }
                return
            }
            loadingBlinkAnimator?.cancel()
            loadingBlinkAnimator = null
            val target = view?.let {
                it.findViewById<View>(it.resources.getIdentifier("vocal_off", "id", "com.apple.android.music")) ?: it
            }
            target?.alpha = 1.0f
        }

        fun onEngineReady() {
            mainHandler.post {
                Log.i(TAG, "Neural engine is ready!")
                val frag = instance?.lyricsHooks?.currentLyricsFragment?.get()
                if (frag != null && org.francasio.sing.provider.SingSettings.forceSongEligibility) {
                    // Only force-reveal on tracks that are eligible for Sing;
                    // never override the host's own hide decision otherwise.
                    try {
                        ReflectionUtils.callMethod(frag, "D2", true)
                        Log.i(TAG, "Called D2(true) on PlayerLyricsViewFragment (forceSongEligibility)")
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
            instance?.lyricsHooks?.onSettingsChanged()
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
            instance?.vocalControlHooks?.syncPlayerVocalLevel(fraction)
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
            instance?.lyricsHooks?.currentLosslessBadge?.get()?.let { return it }
            instance?.lyricsHooks?.currentLyricsView?.get()?.let { lyricsView ->
                val badgeId = lyricsView.resources.getIdentifier("audio_badge_with_text", "id", TARGET_PACKAGE)
                if (badgeId != 0) {
                    lyricsView.findViewById<View>(badgeId)?.let {
                        instance?.lyricsHooks?.rememberLosslessBadge(it)
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

        private fun getInstrumentalDrawable(context: Context): Drawable? {
            if (cachedInstrumentalDrawable != null) return cachedInstrumentalDrawable

            // Load instrumental icon from module drawable resource
            try {
                val moduleContext = try {
                    context.createPackageContext("org.francasio.sing", Context.CONTEXT_IGNORE_SECURITY)
                } catch (_: Throwable) { null }
                val res = moduleContext?.resources
                val resId = res?.getIdentifier("ic_slider_instrumental", "drawable", "org.francasio.sing") ?: 0
                if (res != null && resId != 0) {
                    val rawDrawable = androidx.core.content.res.ResourcesCompat.getDrawable(res, resId, moduleContext!!.theme)
                    if (rawDrawable != null) {
                        val targetW = defaultMicDrawable?.intrinsicWidth?.takeIf { it > 0 }
                            ?: (36 * context.resources.displayMetrics.density).toInt()
                        val targetH = defaultMicDrawable?.intrinsicHeight?.takeIf { it > 0 }
                            ?: (36 * context.resources.displayMetrics.density).toInt()
                        cachedInstrumentalDrawable = ExactSizeDrawable(rawDrawable, targetW, targetH)
                        Log.i(TAG, "Loaded ic_slider_instrumental from module drawable (${targetW}x${targetH})")
                        return cachedInstrumentalDrawable
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load ic_slider_instrumental: ${t.message}")
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
        hookApplicationInit(cl)
        audioPipelineHooks.install(cl)
        hookPlaybackController(cl)
        lyricsHooks.install(cl)
        hookCrossFade(cl)
        MediaClockSyncHook.hook(this, cl)
    }

    /**
     * Hook Application / Context initialization to instantiate SingAudioProcessor.
     */
    private fun hookApplicationInit(cl: ClassLoader) {
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
                                    onApplicationContextReady(hostAppContext!!)
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
                            onApplicationContextReady(ctx)
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
    private fun onApplicationContextReady(context: Context) {
        if (applicationContextReady) return
        applicationContextReady = true

        val appContext = context.applicationContext ?: context
        hostAppContext = appContext
        audioProcessorBridge.initializeWhenIdle(appContext, mainHandler)
    }

    private fun resolveModuleApkPath(context: Context): String? {
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
     * 4. Intercept user's slider changes from LocalMediaPlayerController.
     */
    private fun hookPlaybackController(cl: ClassLoader) =
        vocalControlHooks.installPlaybackController(cl) { executable, hooker ->
            hookMethod(executable, hooker = hooker)
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


}
