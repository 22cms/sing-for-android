package org.francasio.sing.hooks

import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import org.francasio.sing.audio.SingAudioProcessor
import org.francasio.sing.provider.SingSettings
import org.francasio.sing.util.*
import java.lang.ref.WeakReference
import java.lang.reflect.Executable

/** Owns Apple Music lyrics fragment, RecyclerView, adapter, blur, and centering hooks. */
class LyricsHooks(
    private val mainHandler: Handler,
    private val audioProcessorProvider: () -> SingAudioProcessor?,
    private val registerHook: (Executable, (XposedInterface.Chain) -> Any?) -> Unit,
    private val onVocalControlSeen: (View) -> Unit,
    private val currentVocalControlProvider: () -> View?,
    private val updateVocalAttenuationFloor: (Class<*>) -> Unit,
    private val updateSliderIconCallback: (View, Float?) -> Unit,
    private val attachScrollWheelHandler: (View) -> Unit,
    private val installVocalControlHooks: (Class<*>, ClassLoader) -> Unit,
    private val onSingModeChanged: (Boolean) -> Unit,
    private val losslessBadgeSuppressed: () -> Boolean,
    private val logInfo: (String) -> Unit,
    private val logError: (String, Throwable?) -> Unit
) {
    companion object { private const val TARGET_PACKAGE = "com.apple.android.music" }

    var lyricsRecyclerView: WeakReference<ViewGroup>? = null
        private set
    var currentLyricsView: WeakReference<View>? = null
        private set
    var currentLosslessBadge: WeakReference<View>? = null
        private set
    var currentLyricsFragment: WeakReference<Any>? = null
        private set

    fun rememberLosslessBadge(view: View) {
        currentLosslessBadge = WeakReference(view)
    }

    private val audioProcessor: SingAudioProcessor? get() = audioProcessorProvider()
    private val translationFallback by lazy {
        LyricsTranslationFallback(mainHandler, registerHook, logInfo, logError)
    }
    private val isLosslessBadgeSuppressed: Boolean get() = losslessBadgeSuppressed()
    private var currentVocalCtrl: WeakReference<View>?
        get() = currentVocalControlProvider()?.let(::WeakReference)
        set(value) { value?.get()?.let(onVocalControlSeen) }

    private fun hookMethod(executable: Executable, hooker: (XposedInterface.Chain) -> Any?) =
        registerHook(executable, hooker)

    private fun updateSliderIcon(view: View, fraction: Float? = null) =
        updateSliderIconCallback(view, fraction)

    private fun getLosslessBadge(): View? {
        currentLosslessBadge?.get()?.let { return it }
        currentLyricsView?.get()?.let { lyricsView ->
            val id = lyricsView.resources.getIdentifier("audio_badge_with_text", "id", TARGET_PACKAGE)
            if (id != 0) lyricsView.findViewById<View>(id)?.let {
                currentLosslessBadge = WeakReference(it)
                return it
            }
        }
        return null
    }

    fun onSettingsChanged() {
        translationFallback.onSettingsChanged()
        val recyclerView = lyricsRecyclerView?.get() ?: return
        if (!SingSettings.centerGroupLyrics) restoreCenteredLyricsRows(recyclerView)
        scheduleBlurUpdate(recyclerView)
    }

    fun install(cl: ClassLoader) {
        translationFallback.install(cl)

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
                val d2Method = ReflectionUtils.findMethod(lyricsFragClass, "D2", java.lang.Boolean.TYPE)
                    ?: ReflectionUtils.findMethodBySignature(lyricsFragClass, Void.TYPE, java.lang.Boolean.TYPE)
                d2Method?.let { m ->
                    hookMethod(m) { chain ->
                        currentLyricsFragment = WeakReference(chain.thisObject)
                        val isReady = audioProcessor?.isEngineReady == true
                        val origArg = chain.getArg(0) as Boolean

                        val eligible = if (!isReady) {
                            true
                        } else {
                            origArg || (org.francasio.sing.provider.SingSettings.forceSongEligibility && run {
                                val frag = chain.thisObject
                                val songInfoPtr = try { ReflectionUtils.getObjectField(frag, "h1") } catch (_: Throwable) { null }
                                songInfoPtr != null || audioProcessor != null
                            })
                        }

                        val vocalCtrl = currentVocalCtrl?.get()
                        if (vocalCtrl != null) {
                            if (eligible) {
                                vocalCtrl.visibility = View.VISIBLE
                                if (!isReady) {
                                    org.francasio.sing.SingXposedEntry.startLoadingBlink(vocalCtrl)
                                    mainHandler.postDelayed({
                                        logInfo("POST-D2: vis=${vocalCtrl.visibility} alpha=${vocalCtrl.alpha} parentVis=${(vocalCtrl.parent as? View)?.visibility}")
                                    }, 300L)
                                } else {
                                    org.francasio.sing.SingXposedEntry.stopLoadingBlink(vocalCtrl)
                                }
                            } else {
                                org.francasio.sing.SingXposedEntry.stopLoadingBlink(vocalCtrl)
                                vocalCtrl.visibility = View.GONE
                            }
                        }

                        if (eligible) {
                            return@hookMethod chain.proceed(arrayOf(true))
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
                                    if (org.francasio.sing.provider.SingSettings.forceSongEligibility || !isReady) {
                                        vocalCtrl.visibility = View.VISIBLE
                                        if (!isReady) {
                                            org.francasio.sing.SingXposedEntry.startLoadingBlink(vocalCtrl)
                                        } else {
                                            org.francasio.sing.SingXposedEntry.stopLoadingBlink(vocalCtrl)
                                        }
                                        logInfo("Forced vocal_ctrl visibility to VISIBLE in onCreateView (isReady=$isReady)")
                                    } else {
                                        vocalCtrl.visibility = View.GONE
                                        org.francasio.sing.SingXposedEntry.stopLoadingBlink(vocalCtrl)
                                        logInfo("vocal_ctrl set to GONE in onCreateView until D2 resolves eligibility")
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

                installVocalControlHooks(vacClass, cl)

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

    private var isRecyclerViewHooked = false
    private val hookedViews: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    private val hookedAdapters: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val hookedGenericMotionClasses: MutableSet<Class<*>> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val hookedAutoScrollMethods: MutableSet<java.lang.reflect.Method> = java.util.Collections.synchronizedSet(mutableSetOf())
    private val adapterActiveMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()


    private fun scheduleBlurUpdate(rv: ViewGroup) = lyricsBlurController.scheduleBlurUpdate(rv)

    private fun clearAllChildrenBlur(rv: ViewGroup, keepZeroState: Boolean = false) =
        LyricsBlurHelper.clearAllChildrenBlur(rv, keepZeroState)

    private fun beginManualScroll(rv: ViewGroup) {
        val state = lyricsBlurController.blurState(rv)
        if (state.manual) return
        state.manual = true
        state.tapped = null
        // Clear synchronously, before a scrolling frame can draw a stale last row.
        clearAllChildrenBlur(rv, keepZeroState = true)
    }

    private fun resumeAutoScroll(rv: ViewGroup) {
        lyricsBlurController.blurState(rv).manual = false
        scheduleBlurUpdate(rv)
    }

    private val lyricsIds = org.francasio.sing.util.LyricsResourceIds()
    private val lyricsBlurController = org.francasio.sing.util.LyricsBlurController(lyricsIds)
    private val boundBlurPositions = java.util.WeakHashMap<View, Int>()

    private fun updateLyricsBlur(rv: ViewGroup) = lyricsBlurController.updateLyricsBlur(rv)

    private fun findLyricsRecyclerView(v: View): View? {
        if (lyricsIds.lyricsMainContentId == 0) {
            try {
                lyricsIds.lyricsMainContentId = v.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
            } catch (_: Throwable) {}
        }
        if (lyricsIds.lyricsMainContentId != 0) {
            val found = v.findViewById<View>(lyricsIds.lyricsMainContentId)
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

        if (lyricsIds.lyricsMainContentId == 0) {
            try {
                lyricsIds.lyricsMainContentId = rv.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
            } catch (_: Throwable) {}
        }
        if (lyricsIds.lyricsMainContentId != 0 && rv.id != lyricsIds.lyricsMainContentId) {
            logInfo("Ignoring non-lyrics RecyclerView: id=${rv.id}, target=$lyricsIds.lyricsMainContentId")
            return
        }

        lyricsRecyclerView = WeakReference(rv)
        val currentAdapter = ReflectionUtils.callMethod(rv, "getAdapter")
        if (currentAdapter != null) {
            hookLyricsAdapter(currentAdapter.javaClass)
        }

        if (!hookedViews.add(rv)) return

        // The host's RecyclerView is loaded in a different class loader. An
        // interface proxy can subscribe without casting it to our own copy.
        try {
            val addListener = rv.javaClass.methods.first { method ->
                method.parameterTypes.size == 1 && method.returnType == java.lang.Void.TYPE &&
                    method.parameterTypes[0].isInterface && method.parameterTypes[0].methods.any { callback ->
                        callback.returnType == java.lang.Boolean.TYPE &&
                            callback.parameterTypes.any { it == MotionEvent::class.java }
                    }
            }
            val listenerType = addListener.parameterTypes[0]
            val touchSlop = ViewConfiguration.get(rv.context).scaledTouchSlop
            val listener = java.lang.reflect.Proxy.newProxyInstance(rv.javaClass.classLoader, arrayOf(listenerType)) { _, method, args ->
                if (method.returnType == java.lang.Boolean.TYPE &&
                    method.parameterTypes.any { it == MotionEvent::class.java }) {
                    val event = args?.getOrNull(1) as? MotionEvent
                    if (event != null) {
                        val state = lyricsBlurController.blurState(rv)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                state.downX = event.x; state.downY = event.y; state.moved = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (!state.moved && (kotlin.math.abs(event.x - state.downX) > touchSlop ||
                                        kotlin.math.abs(event.y - state.downY) > touchSlop)) {
                                    state.moved = true
                                    beginManualScroll(rv)
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!state.moved) {
                                    val child = try {
                                        (ReflectionUtils.callMethod(rv, "findChildViewUnder", event.x, event.y)
                                            ?: ReflectionUtils.callMethod(rv, "E", event.x, event.y)) as? View
                                    } catch (_: Throwable) {
                                        try { ReflectionUtils.callMethod(rv, "E", event.x, event.y) as? View }
                                        catch (_: Throwable) { null }
                                    }
                                    if (child != null && LyricsRowClassifier.isEligibleLyricsRow(lyricsIds, child)) {
                                        val pos = LyricsRowPosition.rowPosition(rv, child)
                                        if (pos >= 0) {
                                            state.tapped = pos; state.manual = false
                                            updateLyricsBlur(rv)
                                        }
                                    }
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> state.moved = false
                        }
                    }
                }
                if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
            addListener.invoke(rv, listener)
        } catch (t: Throwable) { logError("Failed to attach lyrics touch listener", t) }

        // Generic motion (wheel) bypasses touch listeners. Hook once per host
        // RecyclerView class, without replacing its own listeners.
        if (hookedGenericMotionClasses.add(rv.javaClass)) {
            try {
                hookMethod(rv.javaClass.getMethod("onGenericMotionEvent", MotionEvent::class.java)) { chain ->
                    val target = chain.thisObject as? ViewGroup
                    val event = chain.getArg(0) as? MotionEvent
                    if (target != null && target in hookedViews && event?.actionMasked == MotionEvent.ACTION_SCROLL &&
                        (event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f ||
                            event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f)) beginManualScroll(target)
                    chain.proceed()
                }
            } catch (t: Throwable) { logError("Failed to hook lyrics mouse wheel", t) }
            try {
                // SCROLL_STATE_DRAGGING can also be entered by Apple Music's
                // introductory auto-scroll. Only actual touch/wheel input may
                // suppress blur; those are handled above.
                ReflectionUtils.findMethod(rv.javaClass, "onScrolled", java.lang.Integer.TYPE, java.lang.Integer.TYPE)?.let { m ->
                    hookMethod(m) { chain ->
                        val result = chain.proceed()
                        val target = chain.thisObject as? ViewGroup
                        if (target != null && target in hookedViews) scheduleBlurUpdate(target)
                        result
                    }
                }
            } catch (t: Throwable) { logError("Failed to hook lyrics scroll state", t) }
        }

        val scrollMethodNames = listOf("smoothScrollToPosition", "scrollToPosition", "smoothScrollBy")
        for (name in scrollMethodNames) {
            rv.javaClass.methods.filter { it.name == name }.forEach { m ->
                hookMethod(m) { chain ->
                    val res = chain.proceed()
                    resumeAutoScroll(rv)
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
                    scheduleBlurUpdate(rv)
                    res
                }
            }
        } catch (t: Throwable) {
            logError("Failed to hook RecyclerView.setAdapter: ${t.message}", t)
        }

        scheduleBlurUpdate(rv)
    }

    private fun hookLayoutManager(lm: Any, rv: ViewGroup) {
        val lmScrollMethods = listOf("smoothScrollToPosition", "scrollToPosition", "scrollToPositionWithOffset", "startSmoothScroll")
        var cur: Class<*>? = lm.javaClass
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                // Apple Music's bundled, minified LinearLayoutManager uses u1
                // for scrollToPositionWithOffset. This is the call made by
                // PlayerLyricsViewFragment.K.b when it actually auto-scrolls,
                // including when the introductory three dots become active.
                if (m.name == "u1" && m.parameterTypes.contentEquals(arrayOf(java.lang.Integer.TYPE, java.lang.Integer.TYPE)) &&
                    hookedAutoScrollMethods.add(m)) {
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        val currentRv = lyricsRecyclerView?.get()
                        if (currentRv != null &&
                            ReflectionUtils.callMethod(currentRv, "getLayoutManager") === chain.thisObject) {
                            resumeAutoScroll(currentRv)
                        }
                        res
                    }
                }
                if (m.name in lmScrollMethods) {
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        resumeAutoScroll(rv)
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
        val bindMethods = mutableListOf<java.lang.reflect.Method>()
        var curCls: Class<*>? = adapterClass
        while (curCls != null && curCls != Any::class.java) {
            for (m in curCls.declaredMethods) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue
                if (m.parameterTypes.size != 2) continue
                if (m.parameterTypes[1] != java.lang.Integer.TYPE) continue
                if (!(m.parameterTypes[0].name.contains("ViewHolder") ||
                        m.parameterTypes[0].name.contains("RecyclerView\$ViewHolder") ||
                        m.name == "k" || m.name == "onBindViewHolder" || m.returnType == java.lang.Void.TYPE)) continue
                m.isAccessible = true
                if (bindMethods.none { it.name == m.name && it.parameterTypes.contentEquals(m.parameterTypes) }) {
                    bindMethods.add(m)
                }
            }
            curCls = curCls.superclass
        }
        for (m in bindMethods) {
            hookMethod(m) { chain ->
                // Remove only our previous centering before Apple Music rebinds
                // this recycled holder, allowing the host to apply the new
                // line's own left/right/center alignment from a clean state.
                val preBindView = try {
                    chain.getArg(0)?.let {
                        (ReflectionUtils.getObjectField(it, "itemView")
                            ?: ReflectionUtils.getObjectField(it, "a")) as? View
                    }
                } catch (_: Throwable) { null }
                if (preBindView != null) stopCenteringKaraokeRow(preBindView)

                val res = chain.proceed()
                try {
                    val adapter = chain.thisObject
                    val holder = chain.getArg(0) ?: return@hookMethod res
                    val position = chain.getArg(1) as? Int ?: return@hookMethod res

                    val itemView = (ReflectionUtils.getObjectField(holder, "itemView")
                        ?: ReflectionUtils.getObjectField(holder, "a")) as? View ?: return@hookMethod res

                    // The holder may have been a blurred synchronized line before
                    // being rebound as static lyrics or an instrumental row.
                    if (boundBlurPositions.put(itemView, position) != position || !LyricsRowClassifier.isEligibleLyricsRow(lyricsIds, itemView)) {
                        BlurRenderer.resetViewBlur(itemView)
                    }
                    lyricsRecyclerView?.get()?.let { scheduleBlurUpdate(it) }

                    // --- Group lyrics centering (word-by-word only) ---
                    if (org.francasio.sing.provider.SingSettings.centerGroupLyrics && itemView is ViewGroup) {
                        LyricsRowClassifier.resolveKaraokeResourceIds(lyricsIds, itemView.resources)
                        val flexboxes = mutableListOf<View>()
                        if (lyricsIds.karaokeFlexId != 0) {
                            itemView.findViewById<View>(lyricsIds.karaokeFlexId)?.let { flexboxes.add(it) }
                        }
                        if (lyricsIds.karaokeBgFlexId != 0) {
                            itemView.findViewById<View>(lyricsIds.karaokeBgFlexId)?.let { flexboxes.add(it) }
                        }
                        if (flexboxes.isEmpty()) {
                            findFlexboxViews(itemView, flexboxes)
                        }

                        // The instrumental-break (three-dot) row is not a real lyric
                        // line: never center it, otherwise positions shift by one.
                        if (LyricsRowClassifier.isInstrumentalRow(lyricsIds, itemView)) {
                            stopCenteringKaraokeRow(itemView)
                        } else if (flexboxes.isNotEmpty() && isLineGroupAgent(adapter, position)) {
                            for (flex in flexboxes) {
                                centerFlexboxView(flex)
                            }
                            centerKaraokeRow(itemView)
                        } else {
                            stopCenteringKaraokeRow(itemView)
                        }
                    } else {
                        stopCenteringKaraokeRow(itemView)
                    }
                } catch (t: Throwable) {
                    logError("Failed to process lyrics row bind: ${t.message}", t)
                }
                res
            }
        }

        // 2. Hook line-advance method O(List, int, Array). Q(boolean)
        // toggles an unrelated lyrics UI flag, not the host's auto-scroll.
        var cur: Class<*>? = adapterClass
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (java.lang.reflect.Modifier.isAbstract(m.modifiers)) continue

                if (m.parameterTypes.size == 3 &&
                    java.util.List::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    m.parameterTypes[1] == java.lang.Integer.TYPE &&
                    m.parameterTypes[2].isArray) {
                    hookMethod(m) { chain ->
                        val res = chain.proceed()
                        lyricsRecyclerView?.get()?.let { rv ->
                            scheduleBlurUpdate(rv)
                        }
                        res
                    }
                }
            }
            cur = cur.superclass
        }

        // 3. Hook onViewRecycled / onViewDetachedFromWindow (clear blur on views leaving window to save battery)
        val detachMethods = adapterClass.methods.filter {
            it.name == "onViewRecycled" || it.name == "onViewDetachedFromWindow"
        }
        for (m in detachMethods) {
            hookMethod(m) { chain ->
                val holder = chain.getArg(0)
                if (holder != null) {
                    val itemView = (ReflectionUtils.getObjectField(holder, "itemView")
                        ?: ReflectionUtils.getObjectField(holder, "a")) as? View
                    if (itemView != null) {
                        boundBlurPositions.remove(itemView)
                        BlurRenderer.resetViewBlur(itemView)
                        if (m.name == "onViewRecycled") stopCenteringKaraokeRow(itemView)
                    }
                }
                chain.proceed()
            }
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
                    if (lyricsIds.lyricsMainContentId == 0) {
                        try {
                            lyricsIds.lyricsMainContentId = rv.resources.getIdentifier("lyrics_main_content", "id", TARGET_PACKAGE)
                        } catch (_: Throwable) {}
                    }
                    if (lyricsIds.lyricsMainContentId == 0 || rv.id == lyricsIds.lyricsMainContentId) {
                        lyricsRecyclerView = WeakReference(rv)
                        hookLyricsRecyclerView(rv)
                    }
                }
                chain.proceed()
            }
        }
    }

    private fun isLineGroupAgent(adapter: Any, position: Int): Boolean {
        try {
            val fields = LyricsAdapterFields.resolve(adapter.javaClass)
            val ttmlData = fields.ttml?.get(adapter)
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
                        val d0 = (fields.sparseArray?.get(adapter)
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
                        val songInfoPtr = fields.songInfo?.get(adapter)
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

                        // Only treat a line as a centered group when we have a
                        // positive signal. `d0Align == 1` alone is unstable
                        // (field order in the obfuscated item-info class is not
                        // guaranteed), so require a corroborating Group type or
                        // the absence of any type field before trusting it.
                        // Only the v1000 agent (and lines explicitly typed "Group")
                        // are centered. v1 agent lines must never be treated as
                        // group lines, otherwise an off-by-one centering appears.
                        val isGroup = agentId == "v1000" ||
                            (agentId != "v1" && (
                                songInfoType.equals("Group", ignoreCase = true) ||
                                d0Type.equals("Group", ignoreCase = true)
                            ))

                        return isGroup
                    }
                }
            }
        } catch (t: Throwable) {
            logError("isLineGroupAgent error at pos $position: ${t.message}", t)
        }
        return false
    }

    private val karaokeCentering = org.francasio.sing.util.KaraokeCentering()

    private fun centerKaraokeRow(row: ViewGroup) =
        karaokeCentering.centerKaraokeRow(row, lyricsIds.karaokeTranslationId, lyricsIds.karaokeBgTranslationId)

    private fun centerKaraokeTranslations(row: ViewGroup) =
        karaokeCentering.centerKaraokeTranslations(row, lyricsIds.karaokeTranslationId, lyricsIds.karaokeBgTranslationId)

    private fun stopCenteringKaraokeRow(row: View) {
        karaokeCentering.stopCenteringKaraokeRow(row, lyricsIds.karaokeTranslationId, lyricsIds.karaokeBgTranslationId)
        if (row is ViewGroup) {
            val flexboxes = mutableListOf<View>()
            if (lyricsIds.karaokeFlexId != 0) row.findViewById<View>(lyricsIds.karaokeFlexId)?.let { flexboxes.add(it) }
            if (lyricsIds.karaokeBgFlexId != 0) row.findViewById<View>(lyricsIds.karaokeBgFlexId)?.let { flexboxes.add(it) }
            if (flexboxes.isEmpty()) findFlexboxViews(row, flexboxes)
            for (flex in flexboxes) FlexboxCentering.restore(flex)
        }
    }

    private fun restoreCenteredLyricsRows(rv: ViewGroup) {
        for (i in 0 until rv.childCount) {
            rv.getChildAt(i)?.let { stopCenteringKaraokeRow(it) }
        }
    }


    private fun centerFlexboxView(view: View) = FlexboxCentering.center(view)

    private fun findFlexboxViews(viewGroup: ViewGroup, out: MutableList<View>) =
        FlexboxCentering.find(viewGroup, out)
}
