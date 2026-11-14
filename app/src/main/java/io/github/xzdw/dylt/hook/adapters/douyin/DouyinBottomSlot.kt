package io.github.xzdw.dylt.hook.adapters.douyin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hookx.Xposed
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * The bottom-right circle can stand in for the publish button, the profile tab, or the
 * video's music disc. The disc is not reparented: it stays inside the recycled feed item
 * (often a different window from the glass host) and keeps spinning there. The circle
 * draws a copy and forwards the click.
 *
 * The host has one leading circle. Whichever control left the right-hand circle uses it.
 * A second left-hand control (the disc and the plus at the same time) does not fit.
 */
internal object DouyinBottomSlot {
    const val PUBLISH = GlassConfig.BOTTOM_SLOT_PUBLISH
    const val MUSIC = GlassConfig.BOTTOM_SLOT_MUSIC
    const val ME = GlassConfig.BOTTOM_SLOT_ME

    /** Native row index of 我. Slot 2 is the publish action. */
    const val ME_CELL_INDEX = 4

    private const val LOOK_MS = 400L
    private const val CAPTURE_MS = 120L
    private const val WALK_BUDGET = 1_200

    @Volatile var mode: String = PUBLISH
        private set

    private var hideBadge = true
    private var discRef: WeakReference<View>? = null
    private var hiddenDisc: View? = null
    private var hiddenVisibility = View.VISIBLE
    private var lookedAt = 0L
    private var capturedAt = 0L
    private var badgeAt = 0L
    private var art: Bitmap? = null
    private var loggedMode = ""
    private var loggedDisc = false
    private val hiddenBadges = WeakHashMap<View, Int>()
    private val badgeLogged = HashSet<String>()

    fun leftGapDp(): Float = if (mode == PUBLISH) 0f else 12f

    /** 我 leaves the pill only while it occupies the right-hand circle. */
    fun suppressMeTab(): Boolean = mode == ME

    fun discArt(): Bitmap? = art?.takeIf { !it.isRecycled && it.width > 0 }

    fun discRotation(): Float = discRef?.get()?.rotation ?: 0f

    fun refresh(root: View, config: GlassConfig) {
        val slot = when (config.bottomSlot) {
            MUSIC, ME -> config.bottomSlot
            else -> PUBLISH
        }
        // 去除发布按钮 removes the circle entirely, so the swap has nowhere to land.
        val next = if (config.showPostButton) slot else PUBLISH
        if (next != mode || config.hideShootSame != hideBadge) {
            mode = next
            hideBadge = config.hideShootSame
            if (loggedMode != "$mode:$hideBadge") {
                loggedMode = "$mode:$hideBadge"
                Xposed.log("[OfflineGlass][BottomSlot] mode=$mode hideShootSame=$hideBadge")
            }
        }
        if (mode != MUSIC && !hideBadge) {
            restoreDisc()
            restoreBadges()
            return
        }
        val now = SystemClock.uptimeMillis()
        val cached = discRef?.get()
        val usable = cached != null && cached.isAttachedToWindow && onScreen(cached, root)
        if (!usable && now - lookedAt >= LOOK_MS) {
            lookedAt = now
            val found = findDisc(root)
            if (found != null && found !== cached) {
                restoreDisc()
                discRef = WeakReference(found)
                loggedDisc = false
            }
        }
        val view = discRef?.get() ?: return
        if (!loggedDisc) {
            loggedDisc = true
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc ${view.javaClass.name} " +
                    "${view.width}x${view.height} parent=${view.parent?.javaClass?.simpleName}",
            )
        }
        if (!hideBadge) {
            restoreBadges()
        } else if (now - badgeAt >= LOOK_MS) {
            badgeAt = now
            hideBadges(view)
        }
        if (mode == MUSIC) {
            if (hiddenDisc !== view) {
                restoreDisc()
                hiddenVisibility = view.visibility
                hiddenDisc = view
                view.visibility = View.INVISIBLE
                Xposed.log("[OfflineGlass][BottomSlot] disc hidden, clicks forwarded from the bar")
            }
            if (now - capturedAt >= CAPTURE_MS) {
                capturedAt = now
                capture(view)
            }
        } else {
            restoreDisc()
        }
    }

    fun clickDisc(): Boolean {
        val view = discRef?.get() ?: return false
        if (click(view)) return true
        val parent = view.parent as? View ?: return false
        if (!parent.javaClass.name.contains("MusicCover")) return false
        return click(parent)
    }

    private fun click(view: View): Boolean {
        if (runCatching { view.performClick() }.getOrDefault(false)) return true
        if (view.width <= 0 || view.height <= 0) return false
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 48L, android.view.MotionEvent.ACTION_UP, x, y, 0)
        val was = view.visibility
        // An invisible disc is not hit-tested, but a synthetic tap still has to reach it.
        view.visibility = View.VISIBLE
        val handled = runCatching { view.dispatchTouchEvent(down) && view.dispatchTouchEvent(up) }
            .getOrDefault(false)
        view.visibility = was
        down.recycle()
        up.recycle()
        return handled
    }

    private fun restoreDisc() {
        val view = hiddenDisc ?: return
        if (view.visibility == View.INVISIBLE) view.visibility = hiddenVisibility
        hiddenDisc = null
    }

    private fun capture(view: View) {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return
        var bitmap = art
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            art = bitmap
        } else {
            bitmap.eraseColor(Color.TRANSPARENT)
        }
        val canvas = Canvas(bitmap)
        val was = view.visibility
        view.visibility = View.VISIBLE
        runCatching { view.draw(canvas) }
        view.visibility = was
    }

    private fun findDisc(root: View): View? {
        val stack = ArrayDeque<View>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET) {
            val view = stack.removeLast()
            if (view is GlassHostLayout || view.visibility == View.GONE) continue
            if (view.javaClass.simpleName == "MusicCoverView" && onScreen(view, root)) return view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    view.getChildAt(index)?.let(stack::add)
                }
            }
        }
        return null
    }

    private fun onScreen(view: View, root: View): Boolean {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return false
        if (view.visibility == View.GONE) return false
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        view.getLocationOnScreen(loc)
        root.getLocationOnScreen(rootLoc)
        val cx = loc[0] - rootLoc[0] + view.width / 2f
        val cy = loc[1] - rootLoc[1] + view.height / 2f
        return cx >= root.width * 0.55f && cx <= root.width && cy >= 0f && cy <= root.height
    }

    /**
     * The 拍同款 bubble is a popover beside the disc in the same cover layout
     * (`DuxPopoverLayout` / a text view reading 拍同款). Only that overlay is hidden.
     */
    private fun hideBadges(disc: View) {
        var parent = disc.parent as? ViewGroup ?: return
        var depth = 1
        while (depth <= 4) {
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index) ?: continue
                if (child === disc || isAncestorOfDisc(child, disc)) continue
                hideIfBadge(child, disc, depth)
                if (child is ViewGroup) {
                    for (inner in 0 until child.childCount) {
                        hideIfBadge(child.getChildAt(inner) ?: continue, disc, depth)
                    }
                }
            }
            parent = parent.parent as? ViewGroup ?: return
            depth++
        }
    }

    private fun isAncestorOfDisc(view: View, disc: View): Boolean {
        var node = disc.parent
        while (node is View) {
            if (node === view) return true
            node = node.parent
        }
        return false
    }

    private fun hideIfBadge(view: View, disc: View, depth: Int) {
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val described = view.contentDescription?.toString().orEmpty()
        val named = text.contains("拍同款") || described.contains("拍同款")
        // The cover layout's popover is the bubble. A popover further up the tree,
        // or one much larger than the disc, belongs to some other surface.
        val popover = depth <= 2 && view.javaClass.name.contains("Popover") &&
            (view.width <= 0 || view.width <= disc.width * 3)
        if (!named && !popover) return
        if (view.visibility == View.GONE) return
        hiddenBadges[view] = view.visibility
        view.visibility = View.GONE
        val name = view.javaClass.name
        if (badgeLogged.add(name)) {
            Xposed.log("[OfflineGlass][ShootSame] hide $name text=${text.ifEmpty { described }}")
        }
    }

    private fun restoreBadges() {
        if (hiddenBadges.isEmpty()) return
        for ((view, visibility) in hiddenBadges) {
            if (view.visibility == View.GONE) view.visibility = visibility
        }
        hiddenBadges.clear()
    }
}
