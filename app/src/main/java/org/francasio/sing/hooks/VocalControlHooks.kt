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
                    val rawLevel = chain.getArg(0) as Float
                    val floor = defaultVocalFloor()
                    val level = if (SingSettings.allowZeroFloor) {
                        when {
                            rawLevel <= 0.005f || (floor != null && rawLevel <= floor + 0.002f) -> 0.0f
                            rawLevel >= 0.995f -> 1.0f
                            else -> rawLevel.coerceIn(0.0f, 1.0f)
                        }
                    } else rawLevel
                    audioProcessor()?.vocalLevel = level
                    currentVocalControl()?.let { updateSliderIcon(it, rawLevel) }
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
                    val currentPosition = if (wasSingActive && !enabled) {
                        try {
                            ReflectionUtils.callMethod(controller, "getCurrentPosition") as? Long ?: -1L
                        } catch (_: Throwable) { -1L }
                    } else -1L
                    val cushionMs = if (wasSingActive && !enabled) processor?.bufferedCushionMs ?: 0L else 0L

                    processor?.isEnabled = enabled
                    onSingModeChanged(enabled)
                    logInfo("LocalMediaPlayerController.setVocalAttenuationMode: mode=$mode (enabled=$enabled, wasSingActive=$wasSingActive, pos=$currentPosition ms, cushion=$cushionMs ms)")
                    val result = chain.proceed()

                    if (wasSingActive && !enabled && currentPosition >= 0L && cushionMs > 240L) {
                        val delayMs = cushionMs - 240L
                        mainHandler.postDelayed({
                            try {
                                val activeController = currentPlayerController?.get() ?: controller
                                val nowPosition = try {
                                    ReflectionUtils.callMethod(activeController, "getCurrentPosition") as? Long
                                        ?: currentPosition
                                } catch (_: Throwable) { currentPosition }
                                val targetMs = if (nowPosition >= currentPosition + delayMs / 2) {
                                    maxOf(0L, nowPosition - delayMs)
                                } else {
                                    maxOf(0L, currentPosition - 300L)
                                }
                                logInfo("Sing disabled: nowPos=$nowPosition ms, currentPos=$currentPosition ms, delayMs=$delayMs ms -> targetMs=$targetMs ms")
                                ReflectionUtils.callMethod(activeController, "seekToPosition", targetMs)
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
        } catch (t: Throwable) {
            logError("Failed to hook LocalMediaPlayerController", t)
        }
    }

    fun syncPlayerVocalLevel(fraction: Float) {
        try {
            currentPlayerController?.get()?.let {
                ReflectionUtils.callMethod(it, "setVocalAttenuationLevel", fraction)
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

            val isReady = audioProcessor()?.isEngineReady == true
            if (!isReady && !isSingOn(vocalCtrl)) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Keep long-press / right-click settings launch working
                        // while the engine is still loading.
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val slop = ViewConfiguration.get(vocalCtrl.context).scaledTouchSlop
                        if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                            longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            longPressRunnable = null
                        }
                        return@hookMethod true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        longPressRunnable = null
                        // Ignore taps to enable sing while engine is loading
                        logInfo("Ignoring tap on VocalAttenuationControl: engine is not ready yet")
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

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
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
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(vocalCtrl.context).scaledTouchSlop
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        longPressRunnable = null
                    }
                    if (isLongPressed) return@hookMethod true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    if (isLongPressed) {
                        isLongPressed = false
                        return@hookMethod true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    isLongPressed = false
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
        if (view.scaleX != 1.0f) view.scaleX = 1.0f
        if (view.scaleY != 1.0f) view.scaleY = 1.0f
    }

    private fun launchSettings(context: Context, reason: String) {
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
            })
            logInfo("Launched Sing settings ($reason)")
        } catch (t: Throwable) {
            logError("Failed to launch Sing settings: ${t.message}", t)
        }
    }
}
