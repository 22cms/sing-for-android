package org.francasio.sing.hooks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.audio.SingAudioProcessor
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.ReflectionUtils
import java.lang.ref.WeakReference
import java.lang.reflect.Executable

/** Hooks user interaction behavior belonging specifically to VocalAttenuationControl. */
class VocalControlHooks(
    private val mainHandler: Handler,
    private val audioProcessor: () -> SingAudioProcessor?,
    private val onVocalControlSeen: (View) -> Unit,
    private val currentVocalControl: () -> View?,
    private val attachScrollWheelHandler: (View) -> Unit,
    private val getSliderFraction: (View) -> Float,
    private val setSliderFraction: (View, Float) -> Unit,
    private val updateSliderIcon: (View, Float?) -> Unit,
    private val defaultVocalFloor: () -> Float?,
    private val onSingModeChanged: (Boolean) -> Unit,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    private var lastShortcutLaunchTime = 0L
    private var currentPlayerController: WeakReference<Any>? = null

    fun installPlaybackController(
        classLoader: ClassLoader,
        hookMethod: (Executable, (XposedInterface.Chain) -> Any?) -> Unit
    ) {
        try {
            val controllerClass = ReflectionUtils.findClass(
                "com.apple.android.music.playback.controller.LocalMediaPlayerController",
                classLoader
            ) ?: return

            controllerClass.declaredConstructors.forEach { constructor ->
                constructor.isAccessible = true
                hookMethod(constructor) { chain ->
                    val result = chain.proceed()
                    currentPlayerController = WeakReference(chain.thisObject)
                    logInfo("Captured LocalMediaPlayerController instance from constructor")
                    result
                }
            }

            ReflectionUtils.findMethod(
                controllerClass,
                "setVocalAttenuationLevel",
                java.lang.Float.TYPE
            )?.let { method ->
                hookMethod(method) { chain ->
                    currentPlayerController = WeakReference(chain.thisObject)
                    val rawLevel = (chain.getArg(0) as? Number)?.toFloat() ?: 0f
                    val floor = defaultVocalFloor()
                    val level = if (rawLevel.isNaN() || rawLevel.isInfinite()) {
                        0.0f
                    } else if (SingSettings.allowZeroFloor) {
                        when {
                            rawLevel <= 0.005f || (floor != null && rawLevel <= floor + 0.002f) -> 0.0f
                            rawLevel >= 0.995f -> 1.0f
                            else -> rawLevel.coerceIn(0.0f, 1.0f)
                        }
                    } else rawLevel.coerceIn(0.0f, 1.0f)
                    audioProcessor()?.vocalLevel = level
                    currentVocalControl()?.let { updateSliderIcon(it, level) }
                    logInfo("Vocal level changed to: $level (raw: $rawLevel, allowZeroFloor=${SingSettings.allowZeroFloor})")
                    chain.proceed(arrayOf(level))
                }
            }

            ReflectionUtils.findMethod(
                controllerClass,
                "setVocalAttenuationMode",
                java.lang.Integer.TYPE
            )?.let { method ->
                hookMethod(method) { chain ->
                    val controller = chain.thisObject
                    currentPlayerController = WeakReference(controller)
                    val mode = chain.getArg(0) as Int
                    val enabled = mode == 1
                    val processor = audioProcessor()
                    val wasSingActive = processor?.isEnabled == true
                    val positionBeforeDisable = if (wasSingActive && !enabled) {
                        try {
                            ReflectionUtils.callMethod(controller, "getCurrentPosition") as? Long ?: -1L
                        } catch (_: Throwable) { -1L }
                    } else -1L
                    val cushionMs = if (wasSingActive && !enabled) processor?.bufferedCushionMs ?: 0L else 0L

                    processor?.isEnabled = enabled
                    onSingModeChanged(enabled)
                    logInfo("LocalMediaPlayerController.setVocalAttenuationMode: mode=$mode (enabled=$enabled, pos=$positionBeforeDisable ms, cushion=$cushionMs ms)")
                    val result = chain.proceed()

                    // Sing's inference path can have seconds of decoded-ahead audio. Do not
                    // wait for that cushion to drain: switch to PCM bypass immediately, then
                    // seek back to the position captured at toggle time to keep lyrics aligned.
                    if (wasSingActive && !enabled && positionBeforeDisable >= 0L && cushionMs > 240L) {
                        try {
                            ReflectionUtils.callMethod(controller, "seekToPosition", positionBeforeDisable)
                            logInfo("Sing disabled: immediately sought back to ${positionBeforeDisable} ms to restore audio/lyrics sync")
                        } catch (t: Throwable) {
                            logError("Failed to invoke seekToPosition after Sing disable", t)
                        }
                    }
                    result
                }
            }
            logInfo("Hooked LocalMediaPlayerController slider listeners")
        } catch (t: Throwable) {
            logError("Failed to hook LocalMediaPlayerController", t)
        }
    }

    fun syncPlayerVocalLevel(fraction: Float) {
        val safeFraction = if (fraction.isNaN() || fraction.isInfinite()) 0.0f else fraction.coerceIn(0.0f, 1.0f)
        try {
            currentPlayerController?.get()?.let {
                ReflectionUtils.callMethod(it, "setVocalAttenuationLevel", safeFraction)
            }
        } catch (_: Throwable) {}
    }

    fun install(
        vocalControlClass: Class<*>,
        classLoader: ClassLoader,
        hookMethod: (Executable, (XposedInterface.Chain) -> Any?) -> Unit
    ) {
        try {
            val touchListenerClass = ReflectionUtils.findClass(
                "com.apple.android.music.common.views.VocalAttenuationControl\$e",
                classLoader
            )
            if (touchListenerClass != null) {
                val onTouch = ReflectionUtils.findMethod(
                    touchListenerClass,
                    "onTouch",
                    View::class.java,
                    MotionEvent::class.java
                )
                if (onTouch != null) installTouchHook(onTouch, hookMethod)
            }

            ReflectionUtils.findMethod(vocalControlClass, "q", java.lang.Boolean.TYPE)?.let { method ->
                hookMethod(method) { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let {
                        onVocalControlSeen(it)
                        updateSliderIcon(it, null)
                    }
                    result
                }
            }

            ReflectionUtils.findMethod(vocalControlClass, "r", java.lang.Boolean.TYPE)?.let { method ->
                hookMethod(method) { chain ->
                    if (SystemClock.uptimeMillis() - lastShortcutLaunchTime < 1000L) {
                        logInfo("Suppressed VocalAttenuationControl.r after shortcut launch")
                        null
                    } else {
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let {
                            onVocalControlSeen(it)
                            updateSliderIcon(it, null)
                        }
                        result
                    }
                }
            }

            logInfo("Hooked VocalAttenuationControl long-press / right-click settings launcher")
        } catch (t: Throwable) {
            logError("Failed to hook VocalAttenuationControl interactions", t)
        }
    }

    private fun installTouchHook(
        onTouch: Executable,
        hookMethod: (Executable, (XposedInterface.Chain) -> Any?) -> Unit
    ) {
        var isLongPressed = false
        var isRightClickActive = false
        var isMoved = false
        var downX = 0f
        var downY = 0f
        var longPressRunnable: Runnable? = null

        hookMethod(onTouch) { chain ->
            val vocalCtrl = chain.getArg(0) as? View ?: return@hookMethod chain.proceed()
            onVocalControlSeen(vocalCtrl)
            attachScrollWheelHandler(vocalCtrl)
            val event = chain.getArg(1) as MotionEvent

            if (isRightClick(event)) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                        isRightClickActive = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        longPressRunnable = null
                        isLongPressed = false
                        resetScale(vocalCtrl)
                        vocalCtrl.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        logInfo("Right click detected on VocalAttenuationControl -> launching Sing settings")
                        launchSettings(vocalCtrl.context, "mouse right click")
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

            val isSingOn = isSingOn(vocalCtrl)
            if (isSingOn) {
                longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                longPressRunnable = null
                isLongPressed = false
                resetScale(vocalCtrl)

                if (SingSettings.allowZeroFloor &&
                    (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                ) {
                    val isDragging = try {
                        ReflectionUtils.getBooleanField(vocalCtrl, "l")
                    } catch (_: Throwable) { false }

                    if (isDragging && getSliderFraction(vocalCtrl) >= 0.99f) {
                        setSliderFraction(vocalCtrl, 0.999f)
                        val result = chain.proceed()
                        setSliderFraction(vocalCtrl, 1.0f)
                        audioProcessor()?.vocalLevel = 1.0f
                        try {
                            ReflectionUtils.getObjectField(vocalCtrl, "L")?.let {
                                ReflectionUtils.callMethod(it, "invoke", 1.0f)
                            }
                        } catch (_: Throwable) {}
                        updateSliderIcon(vocalCtrl, 1.0f)
                        logInfo("Guarded Sing mode at 100% vocal level, collapsing slider while remaining ON")
                        return@hookMethod result
                    }
                }
                return@hookMethod chain.proceed()
            }

            // Sing is OFF:
            val isReady = audioProcessor()?.isEngineReady == true
            val hasModels = SingSettings.hasDownloadedModels || isReady

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    isMoved = false
                    downX = event.x
                    downY = event.y
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    resetScale(vocalCtrl)
                    val runnable = Runnable {
                        val stillSingOff = audioProcessor()?.isEnabled != true && !try {
                            ReflectionUtils.getBooleanField(vocalCtrl, "l")
                        } catch (_: Throwable) { false }
                        if (stillSingOff) {
                            isLongPressed = true
                            vocalCtrl.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            launchSettings(vocalCtrl.context, "long press")
                        }
                    }
                    longPressRunnable = runnable
                    mainHandler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    return@hookMethod chain.proceed()
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(vocalCtrl.context).scaledTouchSlop
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                        isMoved = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                    if (isLongPressed) return@hookMethod true
                    if (!hasModels || !isReady) return@hookMethod true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    if (isLongPressed) {
                        isLongPressed = false
                        return@hookMethod true
                    }
                    if (isMoved) {
                        isMoved = false
                        resetScale(vocalCtrl)
                        if (!hasModels || !isReady) return@hookMethod true
                        return@hookMethod chain.proceed()
                    }
                    if (!hasModels) {
                        logInfo("Tap on VocalAttenuationControl with no models downloaded -> toasting & redirecting")
                        showToast(
                            vocalCtrl.context,
                            "msg_download_first_toast",
                            fallbackEn = "Please download a neural model first",
                            fallbackIt = "Scarica prima un modello neurale"
                        )
                        launchSettings(vocalCtrl.context, "no models downloaded", screen = "model_selection")
                        resetScale(vocalCtrl)
                        return@hookMethod true
                    }
                    if (!isReady) {
                        logInfo("Tap on VocalAttenuationControl while model is loading -> toasting")
                        showToast(
                            vocalCtrl.context,
                            "msg_model_loading_toast",
                            fallbackEn = "Neural model is loading…",
                            fallbackIt = "Caricamento del modello neurale in corso…"
                        )
                        resetScale(vocalCtrl)
                        return@hookMethod true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    isLongPressed = false
                    isMoved = false
                    resetScale(vocalCtrl)
                    if (!hasModels || !isReady) return@hookMethod true
                }
            }
            chain.proceed()
        }
    }

    private fun isSingOn(vocalCtrl: View): Boolean =
        audioProcessor()?.isEnabled == true || try {
            val binding = ReflectionUtils.getObjectField(vocalCtrl, "e")
            (ReflectionUtils.getObjectField(binding, "c0") as? View)?.isActivated == true
        } catch (_: Throwable) { false } || try {
            ReflectionUtils.getBooleanField(vocalCtrl, "l")
        } catch (_: Throwable) { false }

    private fun isRightClick(event: MotionEvent): Boolean =
        (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                event.actionButton == MotionEvent.BUTTON_SECONDARY)

    private fun resetScale(view: View) {
        try {
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150L).start()
        } catch (_: Throwable) {
            if (view.scaleX != 1.0f) view.scaleX = 1.0f
            if (view.scaleY != 1.0f) view.scaleY = 1.0f
        }
    }

    private var lastToastTime = 0L

    private fun showToast(context: Context, resIdName: String, fallbackEn: String, fallbackIt: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastTime < 2000L) return
        lastToastTime = now
        mainHandler.post {
            try {
                val isItalian = java.util.Locale.getDefault().language == "it"
                val text = try {
                    val modCtx = context.createPackageContext("org.francasio.sing", Context.CONTEXT_IGNORE_SECURITY)
                    val id = modCtx.resources.getIdentifier(resIdName, "string", "org.francasio.sing")
                    if (id != 0) modCtx.getString(id) else if (isItalian) fallbackIt else fallbackEn
                } catch (_: Throwable) {
                    if (isItalian) fallbackIt else fallbackEn
                }
                android.widget.Toast.makeText(context.applicationContext ?: context, text, android.widget.Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                logError("Failed to show toast", t)
            }
        }
    }

    private fun launchSettings(context: Context, reason: String, screen: String? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShortcutLaunchTime < 500L) {
            logInfo("Suppressed duplicate settings launch ($reason, debounce)")
            return
        }
        lastShortcutLaunchTime = now
        try {
            context.startActivity(Intent().apply {
                component = ComponentName("org.francasio.sing", "org.francasio.sing.ui.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("from_apple_music", true)
                if (screen != null) {
                    putExtra("open_screen", screen)
                }
            })
            logInfo("Launched Sing settings ($reason, screen=$screen)")
        } catch (t: Throwable) {
            logError("Failed to launch Sing settings: ${t.message}", t)
        }
    }
}
