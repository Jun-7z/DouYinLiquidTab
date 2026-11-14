package io.github.xzdw.dylt.hook.adapters.douyin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hookx.Xposed
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.WeakHashMap
import kotlin.math.max

/**
 * The bottom-right circle stands in for the publish button, the profile tab, or the
 * video's music disc. In music and profile mode the plus is its own glass circle
 * between the pill and that right circle. Publish mode is the original four-tab
 * pill plus the one right-hand circle.
 *
 * The disc is never reparented and this object never changes its visibility, alpha,
 * or anything else in the right-hand action column. Jun hides the original disc
 * himself. The circle still has to draw and forward clicks when that view is already
 * transparent, INVISIBLE, or GONE.
 */
internal object DouyinBottomSlot {
    const val PUBLISH = GlassConfig.BOTTOM_SLOT_PUBLISH
    const val MUSIC = GlassConfig.BOTTOM_SLOT_MUSIC
    const val ME = GlassConfig.BOTTOM_SLOT_ME

    /** A pill tap that is not a page. The host must not move the selection onto it. */
    const val PILL_DISC = 2

    /** Diameter of the standalone plus, as a fraction of the bar height. Slightly under the right circle. */
    const val MIDDLE_BUTTON_FRACTION = 0.86f

    /** Native row index of 我. Slot 2 is the publish action. */
    const val ME_CELL_INDEX = 4

    /** One cell of the pill, in the order the host's capsule and this renderer share. */
    internal sealed class PillCell {
        data class Tab(val nativeIndex: Int, val labelIndex: Int) : PillCell()
        data object Disc : PillCell()
    }

    private const val LOOK_MS = 400L
    private const val ART_PX = 160
    private const val WALK_BUDGET = 1_600
    private const val SPIN_MS = 8_000f

    @Volatile var mode: String = PUBLISH
        private set

    private var hideBadge = true
    private var discRef: WeakReference<View>? = null
    private var lookedAt = 0L
    private var badgeAt = 0L
    private var art: Bitmap? = null
    private var artKey = 0
    private var artTriedKey = 0
    private var artTriedAt = 0L
    private var loggedMode = ""
    private var loggedDisc = ""
    private var lastLiveRotation = Float.NaN
    private var lastLiveAt = 0L
    private var spinBase = 0f
    private var spinAt = 0L
    private val hiddenBadges = WeakHashMap<View, Int>()
    private val badgeLogged = HashSet<String>()

    /** No leading circle in any mode. Publish mode keeps the pre-1.7.13 geometry. */
    fun leftGapDp(): Float = 0f

    /**
     * Music and profile put the plus in its own circle between the pill and the
     * right-hand button. Zero in publish mode, so that bar's path does not change.
     */
    fun middleButtonFraction(): Float =
        if (mode == MUSIC || mode == ME) MIDDLE_BUTTON_FRACTION else 0f

    /**
     * 我 stays a real page. Removing it from the host's enabled list mapped a profile
     * selection back onto 首页, so the pill layout drops it itself instead.
     */
    fun suppressMeTab(): Boolean = false

    /**
     * Visual cells for the current mode, with hidden native tabs removed.
     * Null in publish mode so the host and the renderer keep the original four-tab path.
     */
    fun pillCells(enabledNative: Collection<Int>): List<PillCell>? {
        val raw = when (mode) {
            // 发布 sits between the pill and the right circle, not in the pill.
            MUSIC -> listOf(
                PillCell.Tab(0, 0),
                PillCell.Tab(1, 1),
                PillCell.Tab(3, 2),
                PillCell.Tab(4, 3),
            )
            ME -> listOf(
                PillCell.Tab(0, 0),
                PillCell.Tab(1, 1),
                PillCell.Tab(3, 2),
                PillCell.Disc,
            )
            else -> return null
        }
        return raw.filter { cell -> cell !is PillCell.Tab || cell.nativeIndex in enabledNative }
    }

    fun discArt(): Bitmap? = art?.takeIf { !it.isRecycled && it.width > 0 }

    /**
     * The real view's rotation while it is actually spinning. A hidden disc often
     * freezes that property, so after a short stall the circle keeps turning from
     * the last angle instead of resampling the bitmap.
     */
    fun discRotation(): Float {
        val now = SystemClock.uptimeMillis()
        val live = discRef?.get()?.rotation
        if (live != null && live != lastLiveRotation) {
            lastLiveRotation = live
            spinBase = live
            spinAt = now
            lastLiveAt = now
            return live
        }
        if (now - lastLiveAt < 80L) return spinBase
        if (spinAt == 0L) spinAt = now
        return spinBase + (now - spinAt) * (360f / SPIN_MS)
    }

    /** Called before the host rebuilds its path, so the middle circle matches this config. */
    fun sync(config: GlassConfig) {
        val slot = when (config.bottomSlot) {
            MUSIC, ME -> config.bottomSlot
            else -> PUBLISH
        }
        // 去除发布按钮 removes the circle entirely, so the swap has nowhere to land.
        val next = if (config.showPostButton) slot else PUBLISH
        if (next == mode && config.hideShootSame == hideBadge) return
        mode = next
        hideBadge = config.hideShootSame
        val label = "$mode:$hideBadge"
        if (loggedMode != label) {
            loggedMode = label
            val middle = if (middleButtonFraction() > 0f) " middle=plus" else ""
            Xposed.log("[OfflineGlass][BottomSlot] mode=$mode pill=${pillName()}$middle hideShootSame=$hideBadge")
        }
    }

    fun refresh(root: View, config: GlassConfig) {
        sync(config)
        val needDisc = mode == MUSIC || mode == ME || hideBadge
        if (!needDisc) {
            restoreBadges()
            return
        }
        val now = SystemClock.uptimeMillis()
        val cached = discRef?.get()
        if (cached == null || !cached.isAttachedToWindow || !placed(cached)) {
            if (now - lookedAt >= LOOK_MS) {
                lookedAt = now
                val found = findDisc(root)
                if (found != null) discRef = WeakReference(found)
            }
        }
        val view = discRef?.get() ?: return
        val signature = "${view.javaClass.simpleName}:${view.visibility}:${view.alpha}"
        if (loggedDisc != signature) {
            loggedDisc = signature
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc ${view.javaClass.name} " +
                    "vis=${view.visibility} alpha=${view.alpha} " +
                    "${view.width}x${view.height} parent=${view.parent?.javaClass?.simpleName}",
            )
        }
        if (!hideBadge) {
            restoreBadges()
        } else if (now - badgeAt >= LOOK_MS) {
            badgeAt = now
            hideBadges(view)
        }
        if (mode == MUSIC || mode == ME) ensureArt(view, now)
    }

    /**
     * Forward a tap to the real disc. performClick does not need the view to be
     * visible, so a disc Jun already hid still receives it. Visibility is not touched.
     */
    fun clickDisc(): Boolean {
        val view = discRef?.get()
        if (view == null) {
            Xposed.log("[OfflineGlass][BottomSlot] disc click=miss")
            return false
        }
        if (runCatching { view.performClick() }.getOrDefault(false)) {
            Xposed.log("[OfflineGlass][BottomSlot] disc click=performClick")
            return true
        }
        var parent = view.parent
        while (parent is View) {
            if (parent.javaClass.name.contains("MusicCover") &&
                runCatching { parent.performClick() }.getOrDefault(false)
            ) {
                Xposed.log("[OfflineGlass][BottomSlot] disc click=parent")
                return true
            }
            parent = parent.parent
        }
        Xposed.log("[OfflineGlass][BottomSlot] disc click=miss")
        return false
    }

    private fun pillName(): String = when (mode) {
        MUSIC -> "首页/朋友/消息/我"
        ME -> "首页/朋友/消息/转盘"
        else -> "首页/朋友/消息/我"
    }

    private fun ensureArt(view: View, now: Long) {
        val drawable = coverDrawable(view)
        val key = System.identityHashCode(view) * 31 + System.identityHashCode(drawable)
        if (key == artKey && art != null) return
        if (key == artTriedKey && now - artTriedAt < LOOK_MS) return
        artTriedKey = key
        artTriedAt = now
        val drawn = if (canDraw(view)) renderView(view) else null
        val bitmap = drawn ?: drawable?.let(::renderDrawable) ?: return
        art = bitmap
        artKey = key
        val source = if (drawn != null) "draw" else "drawable"
        Xposed.log(
            "[OfflineGlass][BottomSlot] disc art=$source vis=${view.visibility} alpha=${view.alpha}",
        )
    }

    private fun canDraw(view: View): Boolean =
        view.visibility == View.VISIBLE && view.alpha > 0.01f && view.width > 0 && view.height > 0

    private fun renderView(view: View): Bitmap? {
        val bitmap = obtainArt()
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val scale = ART_PX.toFloat() / max(view.width, view.height).toFloat()
        canvas.save()
        canvas.scale(scale, scale)
        val drew = runCatching { view.draw(canvas) }.isSuccess
        canvas.restore()
        if (!drew || mostlyBlank(bitmap)) return null
        return bitmap
    }

    /** The cover bitmap, ignoring the view's own alpha and visibility. */
    private fun renderDrawable(drawable: Drawable): Bitmap? {
        val bitmap = obtainArt()
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        val bounds = drawable.bounds
        canvas.save()
        if (bounds.width() > 0 && bounds.height() > 0) {
            val scale = ART_PX.toFloat() / max(bounds.width(), bounds.height()).toFloat()
            val dx = (ART_PX - bounds.width() * scale) / 2f
            val dy = (ART_PX - bounds.height() * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            val drew = runCatching { drawable.draw(canvas) }.isSuccess
            canvas.restore()
            if (!drew || mostlyBlank(bitmap)) return null
            return bitmap
        }
        canvas.restore()
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: return null
        copy.setBounds(0, 0, ART_PX, ART_PX)
        val drew = runCatching { copy.draw(canvas) }.isSuccess
        if (!drew || mostlyBlank(bitmap)) return null
        return bitmap
    }

    private fun obtainArt(): Bitmap {
        val current = art
        if (current != null && !current.isRecycled && current.width == ART_PX && current.height == ART_PX) {
            return current
        }
        return Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888).also { art = it }
    }

    private fun mostlyBlank(bitmap: Bitmap): Boolean {
        var opaque = 0
        var y = 12
        while (y < bitmap.height) {
            var x = 12
            while (x < bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 16) opaque++
                if (opaque >= 4) return false
                x += 20
            }
            y += 20
        }
        return true
    }

    private fun coverDrawable(view: View): Drawable? {
        if (view is ImageView) view.drawable?.let { return it }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                coverDrawable(view.getChildAt(index) ?: continue)?.let { return it }
            }
        }
        return view.background
    }

    /**
     * A GONE disc is still in the tree. Skipping GONE nodes would miss the cover
     * Jun hid, and skipping their children would miss a cover inside a hidden parent.
     * Prefer a cover that is still laid out on the right of the screen, then one
     * whose container is, then any attached cover.
     */
    private fun findDisc(root: View): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        var placedCover: View? = null
        var covered: View? = null
        var attached: View? = null
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET && placedCover == null) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            val simple = view.javaClass.simpleName
            if (simple == "MusicCoverContainerLayout" && placed(view) && view is ViewGroup) {
                coverChild(view)?.let { covered = it }
            }
            if (simple == "MusicCoverView") {
                when {
                    placed(view) -> placedCover = view
                    attached == null && view.isAttachedToWindow -> attached = view
                }
            }
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    view.getChildAt(index)?.let(stack::add)
                }
            }
        }
        return placedCover ?: covered ?: attached
    }

    private fun coverChild(group: ViewGroup): View? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            if (child.javaClass.simpleName == "MusicCoverView") return child
            if (child is ViewGroup) coverChild(child)?.let { return it }
        }
        return null
    }

    /** On the right of the screen. INVISIBLE and alpha 0 still have a frame; GONE does not. */
    private fun placed(view: View): Boolean {
        if (!view.isAttachedToWindow || view.visibility == View.GONE) return false
        if (view.width <= 0 || view.height <= 0) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val metrics = view.resources.displayMetrics
        val cx = loc[0] + view.width / 2
        val cy = loc[1] + view.height / 2
        if (cx < metrics.widthPixels * 0.55f) return false
        return cy in 0 until metrics.heightPixels
    }

    /**
     * The 拍同款 bubble is a popover beside the disc in the same cover layout
     * (`DuxPopoverLayout` / a text view reading 拍同款). Only that overlay is hidden.
     * A disc with no size must not make every nearby popover match.
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
        val popover = depth <= 2 && disc.width > 0 && view.javaClass.name.contains("Popover") &&
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
