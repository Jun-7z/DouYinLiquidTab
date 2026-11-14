package io.github.xzdw.dylt.hook.adapters.douyin

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Frosted glass for two Douyin surfaces the bars do not cover: the feed's right-hand
 * action icons, and the comment sheet.
 *
 * The icons (like, comment, collect, share, the avatar ring and the music disc) are
 * masked liquid glass: the glyph's own alpha samples the blurred video. Nothing is
 * painted behind the buttons, and the count text is left alone. See [DouyinIconGlass].
 * The column is found by the stable class `FeedRightScaleView`, with a fallback that
 * walks up from `VideoDiggView` until the narrow stack also holds the avatar, share
 * or music-cover view.
 *
 * The comment sheet is `com.ss.android.ugc.aweme.comment.widget.CommentNestedLayout`.
 * On 41.1 that layout is full screen, so the glass is applied to the panel child rather
 * than the transparent dismiss area. The rounded input pill keeps its own background.
 * The full-width strip it sits on (`CommentBottomAreaLayout`, `InterceptTouchFrameLayout`,
 * `mExternalInputContainer`) is cleared when the sheet glass is already behind it, or
 * given that same glass when the strip is a sibling of the panel. List containers such
 * as `CommentRecyclerView` are cleared so the header and the list share one surface.
 * These names are present on 41.0.0 and 41.1.0.
 *
 * Glass is installed from the view's own pre-draw (armed in the constructor) so the
 * first frame already has the tint. The blur catches up on that same draw.
 *
 * Comment glass is a background, so it paints under the text and never changes the
 * measured size.
 */
internal object DouyinFeedGlass {
    private const val WALK_BUDGET = 2_800
    private const val RESCAN_MS = 240L

    private const val COLUMN_CLASS = "FeedRightScaleView"
    private const val SHEET_CLASS = "CommentNestedLayout"
    private const val NESTED_CLASS = "com.ss.android.ugc.aweme.comment.widget.CommentNestedLayout"
    private const val INPUT_CLASS = "CommentBottomAreaLayout"
    private const val INPUT_CLASS_NAME = "com.ss.android.ugc.aweme.comment.ui.CommentBottomAreaLayout"
    private const val INPUT_EDIT = "CommentMentionEditText"
    private val ACTION_MARKERS = setOf(
        "VideoDiggView",
        "FeedAvatarView",
        "VideoShareView",
        "VideoCommentCountView",
        "MusicCoverView",
        "FeedCollectAnimationView",
        "FeedShareImageView",
        "FeedCommentImageView",
    )

    private val savedBackgrounds = HashMap<View, Drawable?>()
    private var columnRef: WeakReference<View>? = null
    private var nestedRef: WeakReference<View>? = null
    private var sheetRef: WeakReference<View>? = null
    private var inputRef: WeakReference<View>? = null
    private var sheetBackdrop: SurfaceBackdrop? = null
    private var inputBackdrop: SurfaceBackdrop? = null
    private var latestConfig: GlassConfig? = null
    private var latestRoot: WeakReference<ViewGroup>? = null
    private var lastScanAt = 0L
    private var loggedColumn = false
    private var loggedSheet = false
    private var loggedInput = false
    private var nestedHooked = false
    private var inputHooked = false
    /** Views whose background was cleared, so the log names each of them once. */
    private val clearedViews = WeakHashMap<View, String>()

    fun update(root: ViewGroup, config: GlassConfig?) {
        if (config == null || (!config.actionGlassEnabled && !config.commentGlassEnabled)) {
            latestConfig = null
            release()
            return
        }
        latestConfig = config
        latestRoot = WeakReference(root)
        if (config.commentGlassEnabled) ensureEarlyHook(root)
        val now = SystemClock.uptimeMillis()
        // While the sheet or the input bar is not bound yet, look on every pre-draw.
        // The 240ms rescan was long enough for the panel to draw transparent first.
        val sheetMissing = sheetRef?.get().let { it == null || !it.isShown || it.height <= 0 }
        val inputMissing = config.commentInputGlass &&
            inputRef?.get().let { it == null || !it.isShown || it.height <= 0 }
        if ((config.commentGlassEnabled && (sheetMissing || inputMissing)) || now - lastScanAt >= RESCAN_MS) {
            lastScanAt = now
            scan(root)
        }
        val dark = isDark(config, root)
        if (config.actionGlassEnabled) {
            val column = columnRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown }
            if (column is ViewGroup) bindColumn(column, config) else releaseColumn()
        } else {
            releaseColumn()
        }
        if (config.commentGlassEnabled) {
            val held = sheetRef?.get()
            val sheet = held?.takeIf { it.isAttachedToWindow && it.isShown && it.height > 0 }
            if (sheet is ViewGroup) bindSheet(sheet, root, config, dark)
            else if (held == null || !held.isAttachedToWindow || !held.isShown) releaseSheet()
        } else {
            releaseSheet()
        }
    }

    fun dispose() = release()

    /**
     * Arm the comment views at construction. The listener is registered on the floating
     * view-tree observer and runs on the first pre-draw after attach, which is before
     * that view draws. That is what removes the transparent frame.
     */
    private fun ensureEarlyHook(root: View) {
        if (nestedHooked && inputHooked) return
        val loader = root.context.classLoader
        if (!nestedHooked) {
            nestedHooked = hookConstructors(NESTED_CLASS, loader, ::onNestedPreDraw)
        }
        if (!inputHooked) {
            inputHooked = hookConstructors(INPUT_CLASS_NAME, loader, ::onInputPreDraw)
        }
    }

    private fun hookConstructors(className: String, loader: ClassLoader, onPreDraw: (View) -> Unit): Boolean {
        val type = runCatching { Xposed.findClass(className, loader) }.getOrNull() ?: return false
        Xposed.hookAllConstructors(type, object : MethodHook() {
            override fun afterHookedMethod(param: MethodHook.MethodHookParam) {
                val view = (param.thisObject as? View) ?: (param.result as? View) ?: return
                armPreDraw(view, onPreDraw)
            }
        })
        Xposed.log("[OfflineGlass][CommentGlass] early hook $className")
        return true
    }

    private fun armPreDraw(view: View, onPreDraw: (View) -> Unit) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val tree = view.viewTreeObserver
                if (!view.isAttachedToWindow) {
                    if (tree.isAlive) tree.removeOnPreDrawListener(this)
                    return true
                }
                runCatching { onPreDraw(view) }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    private fun onNestedPreDraw(view: View) {
        val config = latestConfig ?: return
        if (!config.commentGlassEnabled || view !is ViewGroup || !view.isShown || view.height <= 0) return
        val root = latestRoot?.get() ?: view.rootView as? ViewGroup ?: return
        if (view.width < root.width * 0.75f) return
        val panel = panelOf(view, root) ?: return
        nestedRef = WeakReference(view)
        sheetRef = WeakReference(panel)
        bindSheet(panel, root, config, isDark(config, view))
    }

    private fun onInputPreDraw(view: View) {
        val config = latestConfig ?: return
        if (!config.commentGlassEnabled || !config.commentInputGlass || !view.isShown || view.height <= 0) return
        // Comment cells reuse CommentBottomAreaLayout. Only the bar outside the list is the input dock.
        if (insideList(view)) return
        val root = latestRoot?.get()
        if (root != null && !verticallyOnScreen(view, root)) return
        openInputDock(view, sheetRef?.get(), config, isDark(config, view))
    }

    private fun verticallyOnScreen(view: View, root: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val viewPos = IntArray(2).also(view::getLocationOnScreen)
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val centerY = viewPos[1] - rootPos[1] + view.height / 2
        return centerY in 0..root.height
    }

    private fun scan(root: ViewGroup) {
        var column: ViewGroup? = null
        var nested: ViewGroup? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET && (column == null || nested == null)) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (view.visibility != View.VISIBLE) continue
            val name = view.javaClass.simpleName
            if (column == null && view is ViewGroup && name == COLUMN_CLASS && onRight(view, root)) {
                column = view
            }
            if (nested == null && view is ViewGroup && name == SHEET_CLASS &&
                view.width >= root.width * 0.75f && view.height >= root.height * 0.22f
            ) {
                nested = view
            }
            if (view is ViewGroup && view.childCount > 0) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        val sheet = nested?.let { panelOf(it, root) }
        if (column == null) column = columnFromMarkers(root)
        columnRef = column?.let(::WeakReference)
        nestedRef = nested?.let(::WeakReference)
        // A full-screen nested layout with no panel child yet is not the surface to frost.
        // Keep the previous panel until this scan actually finds the next one, so a
        // mid-animation frame cannot drop the glass.
        if (sheet != null) sheetRef = WeakReference(sheet)
        if (column != null && !loggedColumn) {
            loggedColumn = true
            Xposed.log("[OfflineGlass][ActionGlass] column ${column.javaClass.name} ${column.width}x${column.height}")
        }
        if (sheet != null && !loggedSheet) {
            loggedSheet = true
            Xposed.log(
                "[OfflineGlass][CommentGlass] sheet ${sheet.javaClass.name} id=${resourceId(sheet)} " +
                    "${sheet.width}x${sheet.height} nested ${nested.javaClass.name}",
            )
        }
    }

    /**
     * On 41.1 `CommentNestedLayout` is full screen (1080x2400): a transparent tap-to-dismiss
     * View fills the top and the real panel is a FrameLayout below it. Glassing the whole
     * layout greyed out the video above the panel, so use the panel child when there is one.
     *
     * Returns null while that child is not laid out yet. Callers keep looking on the next
     * pre-draw instead of frosting the dismiss area.
     */
    private fun panelOf(sheet: ViewGroup, root: View): ViewGroup? {
        if (sheet.height < root.height * 0.9f) {
            return if (sheet.height >= root.height * 0.22f) sheet else null
        }
        val density = sheet.resources.displayMetrics.density
        val minOpening = 48f * density
        val dockSlop = 32f * density
        var tallest: ViewGroup? = null
        var opening: ViewGroup? = null
        for (index in 0 until sheet.childCount) {
            val child = sheet.getChildAt(index) as? ViewGroup ?: continue
            if (child.visibility != View.VISIBLE || child.top <= 0) continue
            if (child.width < root.width * 0.75f) continue
            if (child.height >= root.height * 0.22f) {
                val current = tallest
                if (current == null || child.height > current.height) tallest = child
            } else if (child.height >= minOpening && child.bottom >= sheet.height - dockSlop) {
                // Still sliding up, but already docked to the bottom of the nested layout.
                val current = opening
                if (current == null || child.height > current.height) opening = child
            }
        }
        return tallest ?: opening
    }

    /** Walk up from a like/avatar/share button to the narrow stack that holds the column. */
    private fun columnFromMarkers(root: ViewGroup): ViewGroup? {
        var marker: View? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET && marker == null) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            if (view.javaClass.simpleName in ACTION_MARKERS && onRight(view, root)) marker = view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        var node = marker?.parent as? ViewGroup ?: return null
        var guard = 0
        while (guard++ < 6 && node !== root) {
            if (node.width <= root.width * 0.42f && markerCount(node) >= 2 && onRight(node, root)) {
                return node
            }
            node = node.parent as? ViewGroup ?: return null
        }
        return null
    }

    private fun markerCount(group: ViewGroup): Int {
        var count = 0
        for (index in 0 until group.childCount) {
            if (group.getChildAt(index).javaClass.simpleName in ACTION_MARKERS) count++
        }
        return count
    }

    private fun onRight(view: View, root: View): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        val viewPos = IntArray(2).also(view::getLocationOnScreen)
        val rootPos = IntArray(2).also(root::getLocationOnScreen)
        val right = viewPos[0] + view.width - rootPos[0]
        // The feed pager keeps the neighbouring items attached and "shown" one screen above
        // or below; only accept the one whose vertical centre is actually on screen.
        val centerY = viewPos[1] - rootPos[1] + view.height / 2
        return right >= root.width * 0.82f && viewPos[0] - rootPos[0] >= root.width * 0.55f &&
            centerY in 0..root.height
    }

    private fun bindColumn(column: ViewGroup, config: GlassConfig) {
        // 1.7.6 painted a capsule or circles behind the buttons. Drop that if a hot update
        // left it on the column; the icons themselves are the glass now.
        if (column.background is SurfaceBackdrop) column.background = null
        DouyinIconGlass.bind(column, config)
    }

    private fun bindSheet(sheet: ViewGroup, root: View, config: GlassConfig, dark: Boolean) {
        sheetBackdrop?.takeIf { it.host != null && it.host !== sheet }?.let { releaseSheet() }
        val backdrop = sheetBackdrop ?: SurfaceBackdrop(sheet.context, capsule = true).also {
            sheetBackdrop = it
        }
        val density = sheet.resources.displayMetrics.density
        val sheetBottom = IntArray(2).also(sheet::getLocationOnScreen)[1] + sheet.height
        val reachesScreen = sheetBottom >= root.height - (24f * density)
        val radius = config.commentCornerRadiusDp * density
        backdrop.configure(
            host = sheet,
            blurPx = config.commentBlurRadius * density,
            cornerPx = radius,
            bottomCornerPx = if (reachesScreen) 0f else radius,
            tint = tint(if (dark) config.commentDarkTint else config.commentLightTint, config.commentTintAlpha),
            outline = config.commentOutlineEnabled,
            highlight = config.commentHighlight,
            circles = emptyList(),
            inset = 0f,
            offsetY = 0f,
            scale = 1f,
        )
        install(sheet, backdrop)
        clearCoveringBackgrounds(sheet)
        if (!config.commentInputGlass) {
            releaseInput()
        } else {
            val anchor = findInput(nestedRef?.get() ?: sheet)
            if (anchor != null && anchor !== sheet) openInputDock(anchor, sheet, config, dark)
            else {
                val held = inputRef?.get()
                if (held == null || !held.isAttachedToWindow || !held.isShown) releaseInput()
            }
        }
    }

    /**
     * The rounded field keeps its own background. Wide containers behind it
     * (`CommentBottomAreaLayout`, `InterceptTouchFrameLayout`, the fragment's
     * `mExternalInputContainer`) are the disconnected strip: clear them when the
     * sheet glass is already underneath, otherwise paint that same glass on the
     * outermost strip.
     */
    private fun openInputDock(anchor: View, sheet: View?, config: GlassConfig, dark: Boolean) {
        // 1.7.8 put glass on the pill or on an inner bar. Put that background back
        // before deciding which strip to clear, so the original fill is what we remove.
        val host = inputBackdrop?.host
        if (host != null && (keepsOwnBackground(host) || (sheet != null && isUnder(host, sheet)))) {
            releaseInput()
        }
        val rootWidth = (sheet?.width ?: anchor.rootView.width).coerceAtLeast(1)
        val density = anchor.resources.displayMetrics.density
        val maxDock = 320f * density
        val outside = ArrayList<View>(4)
        var node: View? = anchor
        var guard = 0
        while (node != null && guard++ < 10) {
            if (node === sheet || node.javaClass.simpleName == SHEET_CLASS) break
            // The pill's own box is about as wide as the field. The disconnected strip
            // is the full-bleed parent behind that box, so only a view clearly wider
            // than the anchor (or full-bleed itself) is the strip.
            val widerThanAnchor = node.width >= anchor.width + (16f * density).toInt()
            val fullBleed = node.width >= rootWidth * 0.97f
            if ((widerThanAnchor || fullBleed) && !keepsOwnBackground(node)) {
                val inside = sheet != null && isUnder(node, sheet)
                if (inside) {
                    clearBackground(node, "input-dock")
                } else if (node.height in (24f * density).toInt()..maxDock.toInt()) {
                    outside.add(node)
                }
            }
            clearDockSiblings(node, sheet, rootWidth)
            node = node.parent as? View
        }
        if (outside.isEmpty()) {
            if (!loggedInput) {
                loggedInput = true
                Xposed.log(
                    "[OfflineGlass][CommentGlass] input dock cleared under sheet " +
                        "${anchor.javaClass.name} id=${resourceId(anchor)} ${anchor.width}x${anchor.height}",
                )
            }
            return
        }
        val glassHost = outside.last()
        for (index in 0 until outside.lastIndex) clearBackground(outside[index], "input-dock")
        val inputGlass = inputBackdrop ?: SurfaceBackdrop(glassHost.context, capsule = true).also {
            inputBackdrop = it
        }
        inputGlass.configure(
            host = glassHost,
            blurPx = config.commentBlurRadius * density,
            cornerPx = 0f,
            bottomCornerPx = 0f,
            tint = tint(if (dark) config.commentDarkTint else config.commentLightTint, config.commentTintAlpha),
            outline = false,
            highlight = config.commentHighlight * 0.6f,
            circles = emptyList(),
            inset = 0f,
            offsetY = 0f,
            scale = 1f,
        )
        install(glassHost, inputGlass)
        inputRef = WeakReference(glassHost)
        if (!loggedInput) {
            loggedInput = true
            Xposed.log(
                "[OfflineGlass][CommentGlass] input dock ${glassHost.javaClass.name} id=${resourceId(glassHost)} " +
                    "${glassHost.width}x${glassHost.height} anchor ${anchor.javaClass.name} id=${resourceId(anchor)}",
            )
        }
    }

    /** Full-width siblings of the dock: the opaque strip, its shadow, and hairline dividers. */
    private fun clearDockSiblings(node: View, sheet: View?, rootWidth: Int) {
        val parent = node.parent as? ViewGroup ?: return
        if (parent === sheet || parent.javaClass.simpleName == SHEET_CLASS) return
        val density = node.resources.displayMetrics.density
        for (index in 0 until parent.childCount) {
            val sibling = parent.getChildAt(index)
            if (sibling === node || keepsOwnBackground(sibling)) continue
            val background = sibling.background ?: continue
            if (background is SurfaceBackdrop) continue
            val fullBleed = sibling.width >= rootWidth * 0.97f
            val hairline = sibling.height <= (12f * density) && sibling.width >= rootWidth * 0.72f
            if (!fullBleed && !hairline) continue
            val inside = sheet != null && isUnder(sibling, sheet)
            if (inside || hairline) clearBackground(sibling, "input-sibling")
        }
    }

    /**
     * One glass surface sits on the panel. Header, list and the bottom dock all have to
     * let it show through. `CommentRecyclerView` and the full-width containers around it
     * used to keep their own fill, so the title looked frosted and the list did not.
     * The rounded input pill is not one of those fills.
     */
    private fun clearCoveringBackgrounds(sheet: ViewGroup) {
        val minWidth = sheet.width * 0.82f
        val minHeight = 8f * sheet.resources.displayMetrics.density
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(sheet)
        while (stack.isNotEmpty() && visited++ < 1_600) {
            val view = stack.removeLast()
            val recycler = view.javaClass.name.contains("Recycler")
            val wide = view.width >= minWidth || (recycler && view.width >= sheet.width * 0.6f)
            if (view !== sheet && wide && view.height >= minHeight && isSheetFill(view)) {
                clearBackground(view, if (recycler) "list" else "sheet")
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 1_600) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
    }

    /** Full-width solid containers and list surfaces. The input pill and pictures stay. */
    private fun isSheetFill(view: View): Boolean {
        if (keepsOwnBackground(view)) return false
        if (view is android.widget.TextView || view is android.widget.ImageView) return false
        val name = view.javaClass.name
        if (name.contains("Image") || name.contains("Button")) return false
        return view is ViewGroup || name.contains("Recycler") || view.javaClass == View::class.java
    }

    private fun keepsOwnBackground(view: View): Boolean {
        if (view is android.widget.EditText) return true
        val name = view.javaClass.name
        return name.contains("EditText") || name.contains("MentionEdit")
    }

    private fun insideList(view: View): Boolean {
        var node = view.parent
        var guard = 0
        while (node is View && guard++ < 14) {
            val name = node.javaClass.name
            if (name.contains("Recycler")) return true
            node = node.parent
        }
        return false
    }

    private fun clearBackground(view: View, reason: String) {
        val background = view.background ?: return
        if (background is SurfaceBackdrop) return
        if (keepsOwnBackground(view)) return
        rememberBackground(view)
        view.background = null
        if (clearedViews.put(view, reason) == null) {
            Xposed.log(
                "[OfflineGlass][CommentGlass] clear $reason ${view.javaClass.name} " +
                    "id=${resourceId(view)} ${view.width}x${view.height}",
            )
        }
    }

    private fun findInput(scope: View): View? {
        findEditOutsideList(scope)?.let { return it }
        findByName(scope, INPUT_CLASS, 0.55f)?.takeIf { !insideList(it) }?.let { return it }
        findByName(scope, "InterceptTouchFrameLayout", 0.55f)?.takeIf { !insideList(it) }?.let { return it }
        val edit = findByName(scope, INPUT_EDIT, 0.35f)
            ?: findByName(scope, "MentionEditText", 0.35f)
            ?: findHeuristic(scope)
        return edit?.takeIf { !insideList(it) }
    }

    /** The real composer field sits under the list, not inside a comment row. */
    private fun findEditOutsideList(scope: View): View? {
        var found: View? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(scope)
        while (stack.isNotEmpty() && visited++ < 800) {
            val view = stack.removeLast()
            val name = view.javaClass.simpleName
            if (view !== scope && view.isShown && view.width >= scope.width * 0.35f &&
                (name == INPUT_EDIT || name == "MentionEditText" || name == "FixedHintMentionEditText") &&
                !insideList(view)
            ) {
                found = view
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 800) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        return found
    }

    private fun findByName(scope: View, simple: String, minWidthFraction: Float): View? {
        var found: View? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(scope)
        val minWidth = scope.width * minWidthFraction
        while (stack.isNotEmpty() && visited++ < 500) {
            val view = stack.removeLast()
            if (view !== scope && view.isShown && view.height > 0 && view.width >= minWidth &&
                view.javaClass.simpleName == simple
            ) {
                found = view
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 500) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        return found
    }

    /** Name fallback for a build that renamed the bar. The field still sits at the bottom. */
    private fun findHeuristic(scope: View): View? {
        var found: View? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(scope)
        val scopePos = IntArray(2).also(scope::getLocationOnScreen)
        while (stack.isNotEmpty() && visited++ < 500) {
            val view = stack.removeLast()
            val name = view.javaClass.simpleName
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val nearBottom = pos[1] >= scopePos[1] + scope.height * 0.62f
            val id = resourceId(view)
            if (view !== scope && view.isShown && view.width >= scope.width * 0.45f && nearBottom &&
                (name.contains("Edit") || name.contains("Input") || name.contains("BottomBar") ||
                    name.contains("Keyboard") || id.contains("input") || id.contains("bottom"))
            ) {
                found = view
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 500) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        return found
    }

    private fun isUnder(view: View, ancestor: View): Boolean {
        if (view === ancestor) return true
        var node = view.parent
        var guard = 0
        while (node != null && guard++ < 12) {
            if (node === ancestor) return true
            node = node.parent
        }
        return false
    }

    private fun resourceId(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "none"
        return runCatching { view.resources.getResourceEntryName(id) }
            .getOrElse { "0x${Integer.toHexString(id)}" }
    }

    private fun install(host: View, backdrop: SurfaceBackdrop) {
        if (host.background !== backdrop) {
            if (host.background !is SurfaceBackdrop) rememberBackground(host)
            host.background = backdrop
        }
        host.postInvalidateOnAnimation()
    }

    private fun rememberBackground(view: View) {
        if (savedBackgrounds.containsKey(view)) return
        savedBackgrounds[view] = view.background
    }

    private fun release() {
        releaseColumn()
        releaseSheet()
    }

    private fun releaseColumn() {
        DouyinIconGlass.release()
    }

    private fun releaseSheet() {
        sheetBackdrop?.let { restore(it) }
        sheetBackdrop = null
        releaseInput()
        // Covering backgrounds cleared for the sheet are restored with everyone else.
        restoreAll()
        clearedViews.clear()
    }

    private fun releaseInput() {
        inputBackdrop?.let { restore(it) }
        inputBackdrop = null
    }

    private fun restore(backdrop: SurfaceBackdrop) {
        val host = backdrop.host ?: return
        if (host.background === backdrop) host.background = savedBackgrounds.remove(host)
        backdrop.dispose()
    }

    private fun restoreAll() {
        val leftover = savedBackgrounds.keys.toList()
        for (view in leftover) {
            if (view.background is SurfaceBackdrop) continue
            val saved = savedBackgrounds.remove(view) ?: continue
            if (view.background == null) view.background = saved
        }
    }

    private fun isDark(config: GlassConfig, view: View): Boolean = when (config.themeMode) {
        GlassConfig.THEME_LIGHT -> false
        GlassConfig.THEME_DARK -> true
        else -> config.systemDark ||
            (view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun tint(rgb: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (rgb and 0x00FFFFFF) or (a shl 24)
    }
}

/**
 * Background drawable: samples what is drawn behind [host], blurs it, and paints the
 * capsule or the per-button circles. Installed as the host's background so children
 * (buttons, comment text) stay above it.
 */
internal class SurfaceBackdrop(context: android.content.Context, private val capsule: Boolean) : Drawable() {
    var host: View? = null
        private set
    private var blurPx = 0f
    private var cornerPx = 0f
    private var bottomCornerPx = 0f
    private var tint = Color.TRANSPARENT
    private var outline = true
    private var highlight = 0f
    private var circles: List<RectF> = emptyList()
    private var inset = 0f
    private var offsetY = 0f
    private var scale = 1f

    private val sceneNode = RenderNode("dylt-feed-glass")
    private val lens = runCatching { RuntimeShader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER) }.getOrNull()
    private var effect: RenderEffect? = null
    private var effectKey = 0
    private var sceneAt = 0L
    private var sceneW = 0
    private var sceneH = 0
    // Where the host sat in its window when the scene was recorded. The comment sheet
    // slides up from below the screen; with only the 700 ms timer the scene stayed the
    // one recorded at the start position (off screen, empty), so during the open the
    // panel showed the sharp feed through the tint instead of frosted glass.
    private val hostAt = IntArray(2)
    private var sceneX = Int.MIN_VALUE
    private var sceneY = Int.MIN_VALUE
    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun configure(
        host: View,
        blurPx: Float,
        cornerPx: Float,
        tint: Int,
        outline: Boolean,
        highlight: Float,
        circles: List<RectF>,
        inset: Float,
        offsetY: Float,
        scale: Float,
        bottomCornerPx: Float = cornerPx,
    ) {
        this.host = host
        this.blurPx = blurPx
        this.cornerPx = cornerPx
        this.bottomCornerPx = bottomCornerPx
        this.tint = tint
        this.outline = outline
        this.highlight = highlight
        this.circles = circles
        this.inset = inset
        this.offsetY = offsetY
        this.scale = scale
        invalidateSelf()
    }

    fun dispose() {
        host = null
        sceneNode.discardDisplayList()
    }

    override fun draw(canvas: Canvas) {
        val host = host ?: return
        val width = bounds.width()
        val height = bounds.height()
        fill.color = tint
        // Tint is painted even when the blurred scene is not ready yet, so the first
        // frame is frosted colour rather than the view's transparent background.
        if (width <= 0 || height <= 0 || !canvas.isHardwareAccelerated) {
            if (!bounds.isEmpty) canvas.drawRect(bounds, fill)
            return
        }
        runCatching {
            ensureEffect(width, height)
            val now = SystemClock.uptimeMillis()
            host.getLocationInWindow(hostAt)
            val moved = hostAt[0] != sceneX || hostAt[1] != sceneY
            if (!sceneNode.hasDisplayList() || width != sceneW || height != sceneH || moved ||
                now - sceneAt >= DouyinFeedGlassRefresh
            ) {
                if (record(host, width, height)) {
                    sceneAt = now
                    sceneW = width
                    sceneH = height
                    sceneX = hostAt[0]
                    sceneY = hostAt[1]
                }
            }
        }
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        clipShape(width.toFloat(), height.toFloat())
        canvas.save()
        if (!path.isEmpty) canvas.clipPath(path)
        if (sceneNode.hasDisplayList()) {
            runCatching {
                sceneNode.setRenderEffect(effect)
                canvas.drawRenderNode(sceneNode)
            }
        }
        if (path.isEmpty) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        else canvas.drawPath(path, fill)
        canvas.restore()
        if (outline || highlight > 0.01f) {
            stroke.strokeWidth = host.resources.displayMetrics.density
            if (outline) {
                stroke.color = Color.argb(if (Color.alpha(tint) > 180) 70 else 110, 255, 255, 255)
                canvas.drawPath(path, stroke)
            }
            if (highlight > 0.01f) {
                stroke.strokeWidth = host.resources.displayMetrics.density * 1.4f
                stroke.color = Color.argb((highlight * 150f).roundToInt().coerceIn(0, 255), 255, 255, 255)
                canvas.drawPath(path, stroke)
            }
        }
        canvas.restoreToCount(save)
        if (host.isShown) host.postInvalidateOnAnimation()
    }

    private fun clipShape(width: Float, height: Float) {
        path.rewind()
        if (circles.isNotEmpty()) {
            for (circle in circles) {
                path.addOval(circle, Path.Direction.CW)
            }
            return
        }
        val grow = inset
        val dx = (width - width * scale) / 2f
        rect.set(dx - grow, offsetY - grow, width - dx + grow, height * scale + offsetY + grow)
        // The blurred scene only covers the host's own bounds; a shape that grows past them
        // showed an unblurred tinted strip and an outline running off the screen edge.
        rect.intersect(0f, 0f, width, height)
        val radii = floatArrayOf(
            cornerPx, cornerPx, cornerPx, cornerPx,
            bottomCornerPx, bottomCornerPx, bottomCornerPx, bottomCornerPx,
        )
        path.addRoundRect(rect, radii, Path.Direction.CW)
    }

    private fun ensureEffect(width: Int, height: Int) {
        val key = width * 31 + height + (blurPx * 10f).roundToInt() * 17 +
            (cornerPx * 10f).roundToInt() + circles.size * 131
        if (key == effectKey && effect != null) return
        effectKey = key
        val blurred = NativeBackdropBlur.effect(blurPx, 1.5f)
        val shader = if (capsule && circles.isEmpty()) lens else null
        effect = if (shader == null) blurred else {
            runCatching {
                shader.setFloatUniform("size", width.toFloat(), height.toFloat())
                shader.setFloatUniform("offset", 0f, 0f)
                shader.setFloatUniform("cornerRadii", cornerPx, cornerPx, bottomCornerPx, bottomCornerPx)
                val band = (12f * (host?.resources?.displayMetrics?.density ?: 1f)).coerceAtMost(height / 3f)
                shader.setFloatUniform("refractionHeight", band)
                shader.setFloatUniform("refractionAmount", -band * 0.8f)
                shader.setFloatUniform("depthEffect", 0f)
            }
            RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blurred)
        }
    }

    private fun record(host: View, width: Int, height: Int): Boolean =
        BackdropRecorder.record(host, sceneNode, width, height)

    override fun setAlpha(alpha: Int) = Unit
    @Deprecated("Drawable.setColorFilter is deprecated; the glass tint is its own paint.")
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable.getOpacity is deprecated.")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Scene re-record interval, kept out of the drawable so the draw path stays allocation-free. */
private const val DouyinFeedGlassRefresh = 700L

/**
 * Replays whatever is already drawn behind [host] into [sceneNode], in host-local pixels.
 * The node's position has to be set: a [RenderNode] drawn with `drawRenderNode` is clipped
 * to its position, and that rect defaults to 0×0, which is why a blur layer can be
 * recorded and still never appear.
 */
internal object BackdropRecorder {
    private val location = IntArray(2)
    private val childLocation = IntArray(2)
    private var renderField: java.lang.reflect.Field? = null

    fun record(host: View, sceneNode: RenderNode, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        sceneNode.setPosition(0, 0, width, height)
        val recording = sceneNode.beginRecording(width, height)
        var drawn = 0
        try {
            recording.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            host.getLocationInWindow(location)
            var node: View = host
            var guard = 0
            while (guard++ < 8) {
                val parent = node.parent as? ViewGroup ?: break
                val index = parent.indexOfChild(node)
                for (childIndex in 0 until index) {
                    val sibling = parent.getChildAt(childIndex) ?: continue
                    if (sibling is GlassHostLayout || sibling is DouyinTopTabMenu) continue
                    if (sibling.visibility != View.VISIBLE || sibling.alpha <= 0.01f) continue
                    if (sibling.width <= 0 || sibling.height <= 0) continue
                    val render = renderNode(sibling) ?: continue
                    if (!render.hasDisplayList()) continue
                    sibling.getLocationInWindow(childLocation)
                    recording.save()
                    recording.translate(
                        (childLocation[0] - location[0]).toFloat(),
                        (childLocation[1] - location[1]).toFloat(),
                    )
                    recording.drawRenderNode(render)
                    recording.restore()
                    drawn++
                }
                if (parent.id == android.R.id.content) break
                node = parent
            }
        } finally {
            sceneNode.endRecording()
        }
        return drawn > 0
    }

    private fun renderNode(view: View): RenderNode? {
        val field = renderField ?: View::class.java.declaredFields.firstOrNull {
            it.type.name == "android.graphics.RenderNode" && it.name.contains("mRenderNode")
        }?.also {
            it.isAccessible = true
            renderField = it
        }
        val retained = runCatching { field?.get(view) as? RenderNode }.getOrNull()
        if (retained?.hasDisplayList() == true) return retained
        return runCatching { Xposed.callMethod(view, "updateDisplayListIfDirty") as? RenderNode }.getOrNull()
            ?: retained
    }
}
