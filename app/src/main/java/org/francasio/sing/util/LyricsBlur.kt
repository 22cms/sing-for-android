package org.francasio.sing.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.francasio.sing.LyricsBlurCascade
import kotlin.math.roundToInt

object BlurRenderer {
    private val currentBlurRadii = java.util.WeakHashMap<View, Float>()
    private val targetBlurRadii = java.util.WeakHashMap<View, Float>()
    private val blurAnimators = java.util.WeakHashMap<View, android.animation.ValueAnimator>()
    private val blurEffects = mutableMapOf<Int, RenderEffect>()
    private val blurInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun setCurrentRadius(view: View, value: Float) { currentBlurRadii[view] = value }
    fun setTargetRadius(view: View, value: Float) { targetBlurRadii[view] = value }

    private fun renderBlur(view: View, radius: Float, scale: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val step = (radius / scale).roundToInt().coerceIn(0, 12)
        val effectKey = (step * scale * 4f).roundToInt()
        val effectiveRadius = effectKey / 4f
        val current = currentBlurRadii[view]
        if (current == effectiveRadius) return
        if (step == 0) {
            currentBlurRadii[view] = 0f
            if (current != null && current > 0f) view.setRenderEffect(null)
        } else {
            currentBlurRadii[view] = effectiveRadius
            view.setRenderEffect(blurEffects.getOrPut(effectKey) {
                RenderEffect.createBlurEffect(effectiveRadius, effectiveRadius, Shader.TileMode.CLAMP)
            })
        }
    }

    fun setBlur(view: View, targetRadius: Float, scale: Float) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        if (targetBlurRadii[view] == targetRadius) return
        targetBlurRadii[view] = targetRadius
        val previous = currentBlurRadii[view]
        blurAnimators.remove(view)?.cancel()
        if (previous == null || previous == targetRadius) {
            renderBlur(view, targetRadius, scale)
            return
        }
        val animator = android.animation.ValueAnimator.ofFloat(previous, targetRadius).apply {
            duration = 250L
            interpolator = blurInterpolator
            addUpdateListener { renderBlur(view, it.animatedValue as Float, scale) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (blurAnimators[view] !== animation) return
                    blurAnimators.remove(view)
                    renderBlur(view, targetRadius, scale)
                }
            })
        }
        blurAnimators[view] = animator
        animator.start()
    }

    fun resetViewBlur(view: View) {
        blurAnimators.remove(view)?.cancel()
        targetBlurRadii.remove(view)
        if ((currentBlurRadii.remove(view) ?: 0f) > 0f &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
        ) {
            view.setRenderEffect(null)
        }
    }
}

class LyricsResourceIds {
    var karaokeFlexId: Int = 0
    var karaokeBgFlexId: Int = 0
    var karaokeTranslationId: Int = 0
    var karaokeBgTranslationId: Int = 0
    var songLyricsLineId: Int = 0
    var lyricsLineTextSizeId: Int = 0
    var songLyricsLineContainerId: Int = 0
    var writersCreditsLineId: Int = 0
    var instrumentalRootId: Int = 0
    var creditsBtnContainerId: Int = 0
    var lyricsMainContentId: Int = 0
    var staticLyricsLineId: Int = 0
}

object LyricsRowClassifier {
    fun resolveKaraokeResourceIds(ids: LyricsResourceIds, res: android.content.res.Resources) {
        if (ids.karaokeFlexId == 0) ids.karaokeFlexId = res.getIdentifier("karaoke_line_flexbox", "id", "com.apple.android.music")
        if (ids.karaokeBgFlexId == 0) ids.karaokeBgFlexId = res.getIdentifier("bg_line_flexbox", "id", "com.apple.android.music")
        if (ids.karaokeTranslationId == 0) ids.karaokeTranslationId = res.getIdentifier("lyrics_translation_line", "id", "com.apple.android.music")
        if (ids.karaokeBgTranslationId == 0) ids.karaokeBgTranslationId = res.getIdentifier("lyrics_bg_translation_line", "id", "com.apple.android.music")
        if (ids.songLyricsLineId == 0) ids.songLyricsLineId = res.getIdentifier("song_lyrics_line", "id", "com.apple.android.music")
        if (ids.lyricsLineTextSizeId == 0) ids.lyricsLineTextSizeId = res.getIdentifier("lyrics_line_text_size", "dimen", "com.apple.android.music")
        if (ids.songLyricsLineContainerId == 0) ids.songLyricsLineContainerId = res.getIdentifier("song_lyrics_line_container", "id", "com.apple.android.music")
        if (ids.writersCreditsLineId == 0) ids.writersCreditsLineId = res.getIdentifier("writers_credits_line", "id", "com.apple.android.music")
        if (ids.instrumentalRootId == 0) ids.instrumentalRootId = res.getIdentifier("lyrics_instrumental_root", "id", "com.apple.android.music")
        if (ids.creditsBtnContainerId == 0) ids.creditsBtnContainerId = res.getIdentifier("credits_lyrics_button_container", "id", "com.apple.android.music")
        if (ids.lyricsMainContentId == 0) ids.lyricsMainContentId = res.getIdentifier("lyrics_main_content", "id", "com.apple.android.music")
        if (ids.staticLyricsLineId == 0) ids.staticLyricsLineId = res.getIdentifier("static_lyrics_line", "id", "com.apple.android.music")
    }

    fun isStaticLyrics(ids: LyricsResourceIds, rv: ViewGroup): Boolean {
        try { resolveKaraokeResourceIds(ids, rv.resources) } catch (_: Throwable) {}
        val rvTag = rv.tag?.toString() ?: ""
        if (rvTag.contains("static", ignoreCase = true)) return true
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val tag = child.tag?.toString() ?: ""
            if (tag.contains("static", ignoreCase = true)) return true
            if (isStaticRow(ids, child)) return true
        }
        return false
    }

    fun isStaticRow(ids: LyricsResourceIds, view: View): Boolean {
        val tag = view.tag?.toString()
        if (view is TextView && view.id == ids.songLyricsLineId && view.text?.contains('\n') == true) return true
        if (tag?.contains("lyrics_line_static", ignoreCase = true) == true ||
            tag?.contains("lyrics_body", ignoreCase = true) == true ||
            (ids.staticLyricsLineId != 0 && view.id == ids.staticLyricsLineId)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isStaticRow(ids, view.getChildAt(i))) return true
            }
        }
        return false
    }

    fun isInstrumentalRow(ids: LyricsResourceIds, view: View): Boolean {
        try { resolveKaraokeResourceIds(ids, view.resources) } catch (_: Throwable) {}
        val tagStr = view.tag?.toString() ?: ""
        if (tagStr.contains("instrumental", ignoreCase = true)) return true
        if (ids.instrumentalRootId != 0 && (view.id == ids.instrumentalRootId || view.findViewById<View>(ids.instrumentalRootId) != null)) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i) ?: continue
                val cName = try { view.resources.getResourceEntryName(c.id) } catch (_: Throwable) { "" }
                if (cName.startsWith("dot")) return true
            }
        }
        return false
    }

    fun isEligibleLyricsRow(ids: LyricsResourceIds, view: View): Boolean {
        if (isInstrumentalRow(ids, view)) return false
        try { resolveKaraokeResourceIds(ids, view.resources) } catch (_: Throwable) {}
        val tagStr = view.tag?.toString() ?: ""
        if (isStaticRow(ids, view) || tagStr.contains("static", ignoreCase = true) ||
            tagStr.contains("credit", ignoreCase = true) ||
            tagStr.contains("writer", ignoreCase = true)) return false
        if (ids.staticLyricsLineId != 0 && (view.id == ids.staticLyricsLineId || view.findViewById<View>(ids.staticLyricsLineId) != null)) return false
        if (ids.writersCreditsLineId != 0 && (view.id == ids.writersCreditsLineId || view.findViewById<View>(ids.writersCreditsLineId) != null)) return false
        if (ids.creditsBtnContainerId != 0 && (view.id == ids.creditsBtnContainerId || view.findViewById<View>(ids.creditsBtnContainerId) != null)) return false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i) ?: continue
                val cName = try { view.resources.getResourceEntryName(c.id) } catch (_: Throwable) { "" }
                if (cName.contains("credit") || cName.contains("writer")) return false
            }
        }
        if (ids.songLyricsLineContainerId != 0 && view.id == ids.songLyricsLineContainerId) return true
        if (ids.songLyricsLineId != 0 && view.findViewById<View>(ids.songLyricsLineId) != null) return true
        if (ids.karaokeFlexId != 0 && view.findViewById<View>(ids.karaokeFlexId) != null) return true
        if (tagStr.contains("lyrics_line", ignoreCase = true)) return true
        return false
    }
}

object LyricsRowPosition {
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()

    fun rowPosition(rv: ViewGroup, child: View): Int = try {
        val method = cache.getOrPut(rv.javaClass) {
            try { rv.javaClass.getMethod("getChildAdapterPosition", View::class.java) }
            catch (_: NoSuchMethodException) { rv.javaClass.getMethod("M", View::class.java) }
        }
        (method.invoke(if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else rv, child) as? Number)?.toInt() ?: -1
    } catch (_: Throwable) { -1 }
}

data class AdapterFields(
    val ttml: java.lang.reflect.Field?,
    val songInfo: java.lang.reflect.Field?,
    val sparseArray: java.lang.reflect.Field?
)

object LyricsAdapterFields {
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, AdapterFields>()

    fun resolve(adapterClass: Class<*>): AdapterFields = cache.getOrPut(adapterClass) {
        var ttml: java.lang.reflect.Field? = null
        var songInfo: java.lang.reflect.Field? = null
        var sparseArray: java.lang.reflect.Field? = null
        var cur: Class<*>? = adapterClass
        while (cur != null && cur != Any::class.java) {
            for (f in cur.declaredFields) {
                val typeName = f.type.name
                if (sparseArray == null && android.util.SparseArray::class.java.isAssignableFrom(f.type)) {
                    sparseArray = f.apply { isAccessible = true }
                } else if (songInfo == null && typeName.contains("SongInfoPtr")) {
                    songInfo = f.apply { isAccessible = true }
                } else if (ttml == null && typeName.contains("com.apple.android.music.ttml")) {
                    ttml = f.apply { isAccessible = true }
                }
            }
            cur = cur.superclass
        }
        AdapterFields(ttml, songInfo, sparseArray)
    }
}

object ActivePositionsResolver {
    private val cache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()

    fun getActivePositions(adapter: Any?): Set<Int>? {
        if (adapter == null) return null
        try {
            val adapterClass = adapter.javaClass
            var method = cache[adapterClass]
            if (method == null) {
                var cur: Class<*>? = adapterClass
                while (cur != null && cur != Any::class.java) {
                    for (m in cur.declaredMethods) {
                        if (m.parameterTypes.isEmpty() && java.util.Set::class.java.isAssignableFrom(m.returnType)) {
                            m.isAccessible = true
                            cache[adapterClass] = m
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
                    if (item is Number) result.add(item.toInt())
                }
                return result
            }
        } catch (_: Throwable) {}
        return null
    }
}

class LyricsBlurState {
    var adapter: Any? = null
    var song: Any? = null
    var manual = false
    var lastFocused: Int? = null
    var tapped: Int? = null
    var downX = 0f
    var downY = 0f
    var moved = false
    var updateScheduled = false
}

object LyricsBlurHelper {
    fun clearAllChildrenBlur(rv: ViewGroup, keepZeroState: Boolean = false) {
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            BlurRenderer.resetViewBlur(child)
            if (keepZeroState) {
                BlurRenderer.setCurrentRadius(child, 0f)
                BlurRenderer.setTargetRadius(child, 0f)
            }
        }
    }
}

class LyricsBlurController(val ids: LyricsResourceIds) {
    private val states = java.util.WeakHashMap<ViewGroup, LyricsBlurState>()

    fun blurState(rv: ViewGroup): LyricsBlurState = states.getOrPut(rv) { LyricsBlurState() }

    fun scheduleBlurUpdate(rv: ViewGroup) {
        val state = blurState(rv)
        if (state.updateScheduled) return
        state.updateScheduled = true
        rv.postOnAnimation {
            state.updateScheduled = false
            if (rv.isAttachedToWindow) updateLyricsBlur(rv)
        }
    }

    fun updateLyricsBlur(rv: ViewGroup) {
        val blurEnabled = org.francasio.sing.provider.SingSettings.blurUnfocusedLyrics
        val state = blurState(rv)
        val adapter = try { ReflectionUtils.callMethod(rv, "getAdapter") } catch (_: Throwable) { null }
        val staticAdapter = adapter?.javaClass?.name == "com.apple.android.music.player.I"
        if (!blurEnabled || staticAdapter || LyricsRowClassifier.isStaticLyrics(ids, rv)) {
            LyricsBlurHelper.clearAllChildrenBlur(rv)
            return
        }
        if (state.manual) {
            LyricsBlurHelper.clearAllChildrenBlur(rv, keepZeroState = true)
            return
        }
        if (adapter == null) return
        if (state.adapter !== adapter) {
            state.adapter = adapter
            state.song = null
            state.lastFocused = null
            state.tapped = null
        }
        val song = try { LyricsAdapterFields.resolve(adapter.javaClass).songInfo?.get(adapter) } catch (_: Throwable) { null }
        if (song != null && state.song !== song) {
            state.song = song
            state.lastFocused = null
            state.tapped = null
        }
        val activePositions = ActivePositionsResolver.getActivePositions(adapter) ?: emptySet()
        val rows = mutableListOf<Int>()
        val visible = mutableSetOf<Int>()
        val attached = mutableListOf<Pair<View, Int>>()
        var dots: Int? = null
        val rvHeight = rv.height
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val pos = LyricsRowPosition.rowPosition(rv, child)
            if (pos < 0) continue
            attached.add(child to pos)
            if (LyricsRowClassifier.isInstrumentalRow(ids, child)) dots = pos
            if (LyricsRowClassifier.isEligibleLyricsRow(ids, child)) {
                rows.add(pos)
                if (rvHeight > 0 && child.bottom > 0 && child.top < rvHeight) visible.add(pos)
            }
        }
        if (rows.isEmpty()) {
            LyricsBlurHelper.clearAllChildrenBlur(rv)
            return
        }
        val focusedLyrics = rows.filterTo(mutableSetOf()) { pos ->
            val lineId = when {
                dots != null && pos > dots -> pos - 1
                else -> pos
            }
            lineId in activePositions
        }
        if (state.tapped != null && state.tapped in focusedLyrics) state.tapped = null
        if (focusedLyrics.isNotEmpty()) state.lastFocused = focusedLyrics.maxOrNull()
        val reference = state.tapped ?: state.lastFocused ?: (if (dots != null) rows.minOrNull()!! - 1 else rows.minOrNull()!!)
        val instrumentalBreak = state.tapped == null && dots != null && dots >= reference && focusedLyrics.isEmpty()
        val targets = LyricsBlurCascade.radii(rows, visible,
            if (instrumentalBreak) emptySet() else focusedLyrics,
            reference, if (instrumentalBreak) dots else null,
            blurPreviousReference = instrumentalBreak && state.lastFocused != null)
        val eligible = rows.toHashSet()
        val textSizePx = if (ids.lyricsLineTextSizeId != 0) {
            try { rv.resources.getDimension(ids.lyricsLineTextSizeId) }
            catch (_: Throwable) { 32f }
        } else 32f
        val blurScale = LyricsBlurCascade.scaleForTextSize(textSizePx)
        for ((child, pos) in attached) {
            if (pos in eligible) {
                BlurRenderer.setBlur(child, (targets[pos] ?: 0f) * blurScale, blurScale)
            } else {
                BlurRenderer.resetViewBlur(child)
            }
        }
    }
}
