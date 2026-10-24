package org.francasio.sing.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object FlexboxCentering {
    private data class OriginalLayout(
        val justifyContent: Int?,
        val horizontalBias: Float?,
        val pivotX: Float
    )

    private val originalLayouts = WeakHashMap<View, OriginalLayout>()

    fun center(view: View) {
        if (!originalLayouts.containsKey(view)) {
            originalLayouts[view] = OriginalLayout(
                justifyContent = try {
                    (ReflectionUtils.callMethod(view, "getJustifyContent") as? Number)?.toInt()
                } catch (_: Throwable) { null },
                horizontalBias = readHorizontalBias(view),
                pivotX = view.pivotX
            )
        }
        try {
            ReflectionUtils.callMethod(view, "setJustifyContent", 2)
        } catch (_: Throwable) {}

        val applyPivot = {
            if (originalLayouts.containsKey(view) && view.width > 0) {
                view.pivotX = view.width * 0.5f
            }
        }
        applyPivot()
        view.post { applyPivot() }
        android.view.Choreographer.getInstance().postFrameCallback { _ -> applyPivot() }

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

    fun restore(view: View) {
        val original = originalLayouts.remove(view) ?: return
        original.justifyContent?.let {
            try { ReflectionUtils.callMethod(view, "setJustifyContent", it) } catch (_: Throwable) {}
        }
        val lp = view.layoutParams
        if (lp != null && original.horizontalBias != null) {
            try {
                findHorizontalBiasField(lp)?.set(lp, original.horizontalBias)
                view.layoutParams = lp
                view.requestLayout()
            } catch (_: Throwable) {}
        }
        view.pivotX = original.pivotX
    }

    private fun readHorizontalBias(view: View): Float? {
        val lp = view.layoutParams ?: return null
        return try { findHorizontalBiasField(lp)?.getFloat(lp) } catch (_: Throwable) { null }
    }

    private fun findHorizontalBiasField(layoutParams: Any): java.lang.reflect.Field? =
        ReflectionUtils.findField(layoutParams.javaClass, "horizontalBias")
            ?: ReflectionUtils.findField(layoutParams.javaClass, "E")
            ?: layoutParams.javaClass.fields.firstOrNull {
                it.name == "horizontalBias" ||
                    (it.type == java.lang.Float.TYPE && it.modifiers and java.lang.reflect.Modifier.STATIC == 0)
            }

    fun find(viewGroup: ViewGroup, out: MutableList<View>) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i) ?: continue
            if (child.javaClass.name.contains("FlexboxLayout")) {
                out.add(child)
            } else if (child is ViewGroup) {
                find(child, out)
            }
        }
    }
}

class KaraokeCentering {
    private data class Listeners(
        val preDraw: ViewTreeObserver.OnPreDrawListener,
        val attach: View.OnAttachStateChangeListener,
        val originalPivotX: Float
    )

    private val centeredRows = WeakHashMap<View, Listeners>()
    private val originalGravities = WeakHashMap<TextView, Int>()

    fun centerKaraokeRow(row: ViewGroup, translationId: Int, bgTranslationId: Int) {
        if (centeredRows[row] == null) {
            val rowRef = WeakReference(row)
            val listener = ViewTreeObserver.OnPreDrawListener {
                val view = rowRef.get() ?: return@OnPreDrawListener true
                val center = view.width * 0.5f
                if (view.width > 0) view.pivotX = center
                view.post {
                    if (centeredRows.containsKey(view) && view.width > 0) {
                        view.pivotX = view.width * 0.5f
                    }
                }
                android.view.Choreographer.getInstance().postFrameCallback { _ ->
                    val v = rowRef.get()
                    if (v != null && centeredRows.containsKey(v) && v.width > 0) {
                        v.pivotX = v.width * 0.5f
                    }
                }
                centerKaraokeTranslations(view, translationId, bgTranslationId)
                true
            }
            val attach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    view.viewTreeObserver.addOnPreDrawListener(listener)
                }
                override fun onViewDetachedFromWindow(view: View) {
                    view.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                }
            }
            centeredRows[row] = Listeners(listener, attach, row.pivotX)
            row.addOnAttachStateChangeListener(attach)
            if (row.isAttachedToWindow) row.viewTreeObserver.addOnPreDrawListener(listener)
        }
        row.pivotX = row.width * 0.5f
        centerKaraokeTranslations(row, translationId, bgTranslationId)
    }

    fun stopCenteringKaraokeRow(row: View, translationId: Int, bgTranslationId: Int) {
        val listeners = centeredRows.remove(row)
        if (listeners != null) {
            row.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listeners.preDraw)
            row.removeOnAttachStateChangeListener(listeners.attach)
            row.pivotX = listeners.originalPivotX
        }
        if (row is ViewGroup) {
            for (id in intArrayOf(translationId, bgTranslationId)) {
                if (id == 0) continue
                val text = row.findViewById<TextView>(id) ?: continue
                originalGravities.remove(text)?.let { text.gravity = it }
            }
        }
    }

    fun centerKaraokeTranslations(row: ViewGroup, translationId: Int, bgTranslationId: Int) {
        for (id in intArrayOf(translationId, bgTranslationId)) {
            if (id == 0) continue
            val text = row.findViewById<TextView>(id) ?: continue
            if (!originalGravities.containsKey(text)) {
                originalGravities[text] = text.gravity
            }
            val gravity = (text.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK.inv()) or
                android.view.Gravity.CENTER_HORIZONTAL
            if (text.gravity != gravity) text.gravity = gravity
        }
    }
}
