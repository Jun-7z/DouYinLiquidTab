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
 * than the transparent dismiss area. The input pill (`CommentMentionEditText`, or the
 * wrapper `CommentInputFragment.onViewCreated` paints via `setBackgroundResource`) stays
 * a capsule and is itself frosted glass. The input always floats: the full-width strip
 * behind it (`CommentBottomAreaLayout`, `InterceptTouchFrameLayout`,
 * `mExternalInputContainer`) and every ancestor between the capsule and the list lose
 * their background, foreground and shadow. Only the capsule and the image / @ / emoji
 * circles stay frosted, at their own opacity.
 * List containers such as `CommentRecyclerView` are cleared so the header and the
 * list share one surface.
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
    private var pillBackdrop: SurfaceBackdrop? = null
    private var dockBackdrop: SurfaceBackdrop? = null
    /** Circle glass on the image / @ / emoji buttons while the strip is hidden. */
    private val iconBackdrops = WeakHashMap<View, SurfaceBackdrop>()
    private val loggedIcons = WeakHashMap<View, Boolean>()
    private var loggedIconNone = false
    /** Paint saved from the strip and its ancestors so the float-off path can put it back. */
    private val floatPaint = WeakHashMap<View, SavedPaint>()
    private var latestConfig: GlassConfig? = null
    private var latestRoot: WeakReference<ViewGroup>? = null
    private var lastScanAt = 0L
    private var loggedColumn = false
    private var loggedSheet = false
    private var loggedInput = false
    private var loggedFloat = false
    private var loggedFloatMiss = false
    private var nestedHooked = false
    private var inputHooked = false
    /** Layout values captured the first time the input dock is pulled up over the list. */
    private var floatDockRef: WeakReference<View>? = null
    private var floatListRef: WeakReference<View>? = null
    private var savedDockTop = Int.MIN_VALUE
    private var savedListPad = Int.MIN_VALUE
    private var savedListClip = true
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
        val sheet = sheetRef?.get()
        val field = composerField(view)
        val pill = resolvePill(field)
        openInputDock(field, pill, resolveDock(pill, sheet), sheet, config, isDark(config, view))
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
        // Resolve the pill before clearing. 1.7.9 treated the capsule's own drawable as a
        // full-width fill and set it to null, which is why the field lost its shape.
        val field = findInput(nestedRef?.get() ?: sheet)
            ?.takeIf { it !== sheet }
            ?.let { composerField(it) }
        val pill = field?.let { resolvePill(it) }
        val dock = pill?.let { resolveDock(it, sheet) }
        clearCoveringBackgrounds(sheet, pill, dock)
        if (!config.commentInputGlass || field == null || pill == null) {
            val held = inputRef?.get()
            if (!config.commentInputGlass || held == null || !held.isAttachedToWindow || !held.isShown) {
                releaseInput()
            }
        } else {
            openInputDock(field, pill, dock, sheet, config, dark)
        }
    }

    /**
     * Capsule glass on the pill, at [GlassConfig.commentInputTintAlpha]. The strip
     * behind it is always invisible: its background, foreground and shadow are cleared,
     * and so is every ancestor between the capsule and the list. The image / @ / emoji
     * buttons keep a small circle of the same glass. Layout height is left alone so
     * the list padding still clears the dock.
     */
    private fun openInputDock(
        field: View,
        pill: View,
        dock: View?,
        sheet: View?,
        config: GlassConfig,
        dark: Boolean,
    ) {
        val density = pill.resources.displayMetrics.density
        val blurPx = config.commentBlurRadius * density
        val color = tint(if (dark) config.commentDarkTint else config.commentLightTint, config.commentInputTintAlpha)
        val corner = (pill.height.takeIf { it > 0 } ?: field.height) / 2f
        if (corner > 0f) {
            // A solid fill on the field would hide the wrapper's capsule. Leave a field
            // that still has its own rounded drawable alone; that drawable is the pill.
            if (field !== pill && roundedCorner(field) < 8f * density) {
                clearBackground(field, "pill-fill", force = true)
            }
            pillBackdrop = bindGlass(
                existing = pillBackdrop,
                host = pill,
                blurPx = blurPx,
                cornerPx = corner,
                bottomCornerPx = corner,
                tint = color,
                outline = config.commentOutlineEnabled,
                highlight = config.commentHighlight,
                live = true,
            )
            inputRef = WeakReference(pill)
        }
        val strip = dock?.takeIf { it !== pill && it !== sheet }
        // The frosted strip is the lighter band comments show through. Drop it,
        // and every container between the capsule and the list, before drawing.
        releaseDockGlass()
        val scope = strip ?: pill.parent as? View
        if (scope != null) bindIconCircles(scope, pill, blurPx, color, config)
        hideFloatStrip(pill, strip, sheet)
        val floatHost = strip ?: pill.takeIf { it.width >= (sheet?.width ?: it.rootView.width) * 0.9f }
        if (floatHost != null && floatHost !== sheet) applyFloat(floatHost, sheet)
        else restoreFloat()
        if (!loggedInput && corner > 0f) {
            loggedInput = true
            val dockName = strip?.javaClass?.name ?: "none"
            Xposed.log(
                "[OfflineGlass][CommentGlass] pill ${pill.javaClass.name} id=${resourceId(pill)} " +
                    "${pill.width}x${pill.height} cornerPx=${corner.toInt()} " +
                    "field ${field.javaClass.name} id=${resourceId(field)} " +
                    "dock $dockName id=${strip?.let { resourceId(it) } ?: "none"} " +
                    "${strip?.width ?: 0}x${strip?.height ?: 0} inputAlpha=${config.commentInputTintAlpha} strip=hidden",
            )
        }
    }

    private fun bindGlass(
        existing: SurfaceBackdrop?,
        host: View,
        blurPx: Float,
        cornerPx: Float,
        bottomCornerPx: Float,
        tint: Int,
        outline: Boolean,
        highlight: Float,
        live: Boolean,
    ): SurfaceBackdrop {
        val backdrop = if (existing != null && existing.host !== host) {
            restore(existing)
            SurfaceBackdrop(host.context, capsule = true)
        } else {
            existing ?: SurfaceBackdrop(host.context, capsule = true)
        }
        backdrop.configure(
            host = host,
            blurPx = blurPx,
            cornerPx = cornerPx,
            bottomCornerPx = bottomCornerPx,
            tint = tint,
            outline = outline,
            highlight = highlight,
            circles = emptyList(),
            inset = 0f,
            offsetY = 0f,
            scale = 1f,
            live = live,
        )
        install(host, backdrop)
        return backdrop
    }

    private fun releaseDockGlass() {
        dockBackdrop?.let { restore(it) }
        dockBackdrop = null
    }

    /**
     * While the input floats, nothing may paint behind the capsule: not our glass,
     * not Douyin's drawable, not a divider or an elevation shadow. Ancestors stop at
     * the sheet, so the panel glass stays. Height and padding are not touched.
     */
    private fun hideFloatStrip(pill: View, dock: View?, sheet: View?) {
        var node = pill.parent as? View
        var guard = 0
        while (node != null && guard++ < 10) {
            if (node === sheet || node.javaClass.simpleName == SHEET_CLASS) break
            if (insideList(node)) break
            clearPaint(node, "float-strip")
            clearFloatSiblings(node, pill, sheet)
            node = node.parent as? View
        }
        if (dock != null && dock !== pill) {
            clearPaint(dock, "float-strip")
            clearFloatSiblings(dock, pill, sheet)
            clearDockPlates(dock, pill)
        }
    }

    /** Full-bleed plates, hairlines and shadow siblings of each ancestor. Not the list. */
    private fun clearFloatSiblings(node: View, pill: View, sheet: View?) {
        val parent = node.parent as? ViewGroup ?: return
        if (parent === sheet || parent.javaClass.simpleName == SHEET_CLASS) return
        val density = node.resources.displayMetrics.density
        val rootWidth = (sheet?.width ?: node.rootView.width).coerceAtLeast(1)
        val maxHeight = (320f * density).toInt()
        for (index in 0 until parent.childCount) {
            val sibling = parent.getChildAt(index)
            if (sibling === node || sibling === pill || isUnder(pill, sibling)) continue
            if (sibling.background is SurfaceBackdrop) continue
            if (sibling.javaClass.name.contains("Recycler")) continue
            val name = sibling.javaClass.name
            val fullBleed = sibling.width >= rootWidth * 0.90f && sibling.height in 1..maxHeight
            val hairline = sibling.height <= (12f * density) && sibling.width >= rootWidth * 0.72f
            val shadow = name.contains("Shadow") || name.contains("Divider") || sibling.elevation > 1f
            if (fullBleed || hairline || shadow) clearPaint(sibling, "float-strip")
        }
    }

    /** Childless or full-width fills inside the dock. The capsule and its icons stay. */
    private fun clearDockPlates(dock: View, pill: View) {
        val minWidth = dock.width * 0.72f
        val density = dock.resources.displayMetrics.density
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(dock)
        while (stack.isNotEmpty() && visited++ < 80) {
            val view = stack.removeLast()
            val underPill = view === pill || isUnder(view, pill)
            if (view !== dock && !underPill && view.background !is SurfaceBackdrop) {
                val wide = view.width >= minWidth
                val hairline = view.height <= (12f * density) && view.width >= minWidth
                if (wide || hairline) clearPaint(view, "float-strip")
            }
            if (view is ViewGroup && !underPill && visited < 80) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
    }

    /**
     * Drop background, foreground and the elevation shadow. A childless plate is also
     * faded out, in case it paints its colour in `onDraw` rather than as a background.
     * The first values are kept so releasing the comment glass can restore them.
     */
    private fun clearPaint(view: View, reason: String) {
        val background = view.background
        val foreground = view.foreground
        val animator = view.stateListAnimator
        val paints = background != null && background !is SurfaceBackdrop ||
            foreground != null ||
            view.elevation > 0f ||
            view.translationZ > 0f ||
            animator != null
        val plate = (view !is ViewGroup || view.childCount == 0) && view !is android.widget.TextView &&
            view !is android.widget.ImageView && view.alpha > 0f && view.background !is SurfaceBackdrop
        if (!paints && !plate) return
        if (!floatPaint.containsKey(view)) {
            floatPaint[view] = SavedPaint(
                background = if (background is SurfaceBackdrop) null else background,
                foreground = foreground,
                elevation = view.elevation,
                translationZ = view.translationZ,
                alpha = view.alpha,
                animator = animator,
            )
        }
        if (background !is SurfaceBackdrop && background != null) view.background = null
        if (foreground != null) view.foreground = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
        if (animator != null) view.stateListAnimator = null
        if (plate && view.alpha != 0f) view.alpha = 0f
        if (clearedViews.put(view, reason) == null) {
            val saved = floatPaint[view]
            Xposed.log(
                "[OfflineGlass][CommentGlass] clear $reason ${view.javaClass.name} " +
                    "id=${resourceId(view)} ${view.width}x${view.height} " +
                    "bg=${saved?.background != null} fg=${saved?.foreground != null} " +
                    "elevation=${saved?.elevation ?: 0f} alpha=${saved?.alpha ?: view.alpha}",
            )
        }
    }

    private fun restoreFloatPaint() {
        if (floatPaint.isEmpty()) return
        for ((view, saved) in floatPaint) {
            if (view.background == null) view.background = saved.background
            if (view.foreground == null) view.foreground = saved.foreground
            view.elevation = saved.elevation
            view.translationZ = saved.translationZ
            view.alpha = saved.alpha
            view.stateListAnimator = saved.animator
        }
        floatPaint.clear()
    }

    /** Image / @ / emoji buttons sit on the strip, so they need their own circle once it is gone. */
    private fun bindIconCircles(scope: View, pill: View, blurPx: Float, color: Int, config: GlassConfig) {
        val icons = findDockIcons(scope, pill)
        val keep = HashSet<View>(icons.size)
        for (icon in icons) {
            val side = minOf(icon.width, icon.height).toFloat()
            if (side <= 0f) continue
            keep.add(icon)
            val backdrop = bindGlass(
                existing = iconBackdrops[icon],
                host = icon,
                blurPx = blurPx,
                cornerPx = side / 2f,
                bottomCornerPx = side / 2f,
                tint = color,
                outline = config.commentOutlineEnabled,
                highlight = config.commentHighlight,
                live = true,
            )
            iconBackdrops[icon] = backdrop
            if (loggedIcons.put(icon, true) == null) {
                Xposed.log(
                    "[OfflineGlass][CommentGlass] icon circle ${icon.javaClass.name} " +
                        "id=${resourceId(icon)} ${icon.width}x${icon.height}",
                )
            }
        }
        val stale = iconBackdrops.keys.filter { it !in keep }
        for (icon in stale) iconBackdrops.remove(icon)?.let { restore(it) }
        if (icons.isEmpty() && !loggedIconNone) {
            loggedIconNone = true
            Xposed.log(
                "[OfflineGlass][CommentGlass] icon circle none under ${scope.javaClass.name} " +
                    "id=${resourceId(scope)} ${scope.width}x${scope.height}",
            )
        }
    }

    private fun findDockIcons(scope: View, pill: View): List<View> {
        val density = scope.resources.displayMetrics.density
        val min = (16f * density).toInt()
        val max = (56f * density).toInt()
        val found = ArrayList<View>(4)
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(scope)
        while (stack.isNotEmpty() && visited++ < 120) {
            val view = stack.removeLast()
            if (view !== scope && view !== pill && !isUnder(view, pill) && !insideList(view) &&
                view.isShown && view.width in min..max && view.height in min..max &&
                view.width <= view.height * 1.6f && view.height <= view.width * 1.6f &&
                isIconLike(view)
            ) {
                found.add(view)
            }
            if (view is ViewGroup && view !== pill && !isUnder(view, pill) && visited < 120) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        return found
    }

    private fun isIconLike(view: View): Boolean {
        if (view is android.widget.ImageView) return true
        val name = view.javaClass.name
        if (name.contains("Image") || name.contains("Icon") || name.contains("Emoji") || name.contains("Emoticon")) {
            return true
        }
        val id = resourceId(view)
        return id.contains("emoji") || id.contains("emoticon") || id.contains("image") ||
            id.contains("pic") || id.contains("icon") || id.contains("mention") || id.contains("at")
    }

    private fun releaseIconCircles() {
        if (iconBackdrops.isEmpty()) return
        for (backdrop in iconBackdrops.values) restore(backdrop)
        iconBackdrops.clear()
    }

    /**
     * One glass surface sits on the panel. Header and list have to let it show through.
     * `CommentRecyclerView` used to keep its own fill, so the title looked frosted and
     * the list did not. The pill is skipped so its capsule stays. The input strip is
     * skipped here and cleared after its original paint has been saved.
     */
    private fun clearCoveringBackgrounds(sheet: ViewGroup, pill: View?, dock: View?) {
        val minWidth = sheet.width * 0.82f
        val minHeight = 8f * sheet.resources.displayMetrics.density
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(sheet)
        while (stack.isNotEmpty() && visited++ < 1_600) {
            val view = stack.removeLast()
            val recycler = view.javaClass.name.contains("Recycler")
            val wide = view.width >= minWidth || (recycler && view.width >= sheet.width * 0.6f)
            val protected = view === pill || view === dock
            if (!protected && view !== sheet && wide && view.height >= minHeight && isSheetFill(view)) {
                clearBackground(view, if (recycler) "list" else "sheet")
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 1_600) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
    }

    /** The composer field, even when the hooked view is the bar that contains it. */
    private fun composerField(anchor: View): View {
        val name = anchor.javaClass.name
        if (anchor is android.widget.EditText || name.contains("EditText") || name.contains("MentionEdit")) {
            return anchor
        }
        return findEditOutsideList(anchor) ?: anchor
    }

    /**
     * The view that actually draws the capsule. `CommentInputFragment.onViewCreated`
     * calls `setBackgroundResource` on the field group and on `mExternalInputContainer`,
     * not only on the `MentionEditText`, so the rounded drawable often sits on a parent.
     * Fall back to the tight box around the field when that drawable is already gone.
     */
    private fun resolvePill(anchor: View): View {
        val density = anchor.resources.displayMetrics.density
        val minRadius = 8f * density
        val maxHeight = (88f * density).toInt()
        var best = anchor
        var bestRadius = roundedCorner(anchor)
        var node = anchor.parent as? View
        var guard = 0
        while (node != null && guard++ < 6) {
            if (node.javaClass.simpleName == SHEET_CLASS || insideList(node)) break
            val radius = roundedCorner(node)
            if (radius >= minRadius && node.height in 1..maxHeight && radius >= bestRadius) {
                best = node
                bestRadius = radius
            }
            node = node.parent as? View
        }
        if (bestRadius >= minRadius) return best
        return tightBox(anchor)
    }

    /** Outermost full-width bar behind the pill: the strip the capsule sits on. */
    private fun resolveDock(pill: View, sheet: View?): View? {
        val density = pill.resources.displayMetrics.density
        val rootWidth = (sheet?.width ?: pill.rootView.width).coerceAtLeast(1)
        val maxHeight = (320f * density).toInt()
        var dock: View? = null
        var node = pill.parent as? View
        var guard = 0
        while (node != null && guard++ < 8) {
            if (node === sheet || node.javaClass.simpleName == SHEET_CLASS || insideList(node)) break
            val fullBleed = node.width >= rootWidth * 0.90f
            if (fullBleed && node.height in 1..maxHeight && node.height + 1 >= pill.height) dock = node
            node = node.parent as? View
        }
        if (dock != null) return dock
        val parent = pill.parent as? ViewGroup ?: return null
        if (parent === sheet || parent.javaClass.simpleName == SHEET_CLASS) return null
        var sibling: View? = null
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === pill || child.height <= 0) continue
            if (child.width >= rootWidth * 0.90f && child.height in 1..maxHeight) sibling = child
        }
        return sibling
    }

    private fun tightBox(anchor: View): View {
        var pill = anchor
        val density = anchor.resources.displayMetrics.density
        val rootWidth = anchor.rootView.width.coerceAtLeast(1)
        var guard = 0
        while (guard++ < 3) {
            val parent = pill.parent as? View ?: break
            if (parent.javaClass.simpleName == SHEET_CLASS || insideList(parent)) break
            if (parent.height > pill.height + (10f * density)) break
            if (parent.width > pill.width + (36f * density)) break
            if (parent.width >= rootWidth * 0.97f) break
            pill = parent
        }
        return pill
    }

    /** Largest corner of the background drawable, unwrapping ripple / layer / state lists. */
    private fun roundedCorner(view: View): Float {
        val background = view.background ?: return 0f
        if (background is SurfaceBackdrop) return 0f
        return drawableCorner(background)
    }

    private fun drawableCorner(drawable: android.graphics.drawable.Drawable): Float {
        var current = drawable
        var guard = 0
        while (guard++ < 5) {
            when (current) {
                is android.graphics.drawable.GradientDrawable -> {
                    val radii = current.cornerRadii
                    if (radii != null && radii.isNotEmpty()) return radii.maxOrNull() ?: 0f
                    return current.cornerRadius
                }
                is android.graphics.drawable.StateListDrawable -> {
                    val next = current.current
                    if (next === current) return 0f
                    current = next
                }
                is android.graphics.drawable.InsetDrawable -> {
                    current = current.drawable ?: return 0f
                }
                is android.graphics.drawable.LayerDrawable -> {
                    var best = 0f
                    for (index in 0 until current.numberOfLayers) {
                        val child = current.getDrawable(index) ?: continue
                        best = maxOf(best, drawableCorner(child))
                    }
                    return best
                }
                else -> return 0f
            }
        }
        return 0f
    }

    /**
     * The dock stops taking a row: a negative top margin hands that height to the weighted
     * list (or shrinks a wrap-content parent onto the list) and the dock draws over the
     * list's bottom. Extra bottom padding with clipToPadding off lets the last comment
     * rest above the dock and slide under it while scrolling.
     */
    private fun applyFloat(dock: View, sheet: View?) {
        val list = findCommentList(sheet, dock)
        if (list == null) {
            if (!loggedFloatMiss) {
                loggedFloatMiss = true
                Xposed.log(
                    "[OfflineGlass][CommentGlass] float skipped, no list under " +
                        "${dock.javaClass.name} id=${resourceId(dock)}",
                )
            }
            return
        }
        val margin = dock.layoutParams as? ViewGroup.MarginLayoutParams
        if (floatDockRef?.get() !== dock || floatListRef?.get() !== list) {
            restoreFloat()
            savedDockTop = margin?.topMargin ?: 0
            savedListPad = list.paddingBottom
            savedListClip = list.clipToPadding
            floatDockRef = WeakReference(dock)
            floatListRef = WeakReference(list)
        }
        if (margin != null && dock.height > 0) {
            val target = savedDockTop - dock.height
            if (margin.topMargin != target) {
                margin.topMargin = target
                dock.layoutParams = margin
            }
        }
        if (dock.height > 0) {
            val pad = savedListPad + dock.height
            if (list.paddingBottom != pad) {
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, pad)
            }
        }
        list.clipToPadding = false
        val parent = dock.parent as? ViewGroup
        if (parent != null && parent.indexOfChild(dock) != parent.childCount - 1) dock.bringToFront()
        if (!loggedFloat && dock.height > 0) {
            loggedFloat = true
            val top = (dock.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
            Xposed.log(
                "[OfflineGlass][CommentGlass] float list ${list.javaClass.name} id=${resourceId(list)} " +
                    "${list.width}x${list.height} padBottom=${list.paddingBottom} clip=false " +
                    "dock ${dock.javaClass.name} id=${resourceId(dock)} ${dock.width}x${dock.height} topMargin=$top",
            )
        }
    }

    private fun restoreFloat() {
        val dock = floatDockRef?.get()
        val list = floatListRef?.get() as? ViewGroup
        var restored = false
        if (dock != null && savedDockTop != Int.MIN_VALUE) {
            val margin = dock.layoutParams as? ViewGroup.MarginLayoutParams
            if (margin != null && margin.topMargin != savedDockTop) {
                margin.topMargin = savedDockTop
                dock.layoutParams = margin
                restored = true
            }
        }
        if (list != null && savedListPad != Int.MIN_VALUE) {
            if (list.paddingBottom != savedListPad) {
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, savedListPad)
                restored = true
            }
            if (list.clipToPadding != savedListClip) {
                list.clipToPadding = savedListClip
                restored = true
            }
        }
        if (restored) {
            Xposed.log(
                "[OfflineGlass][CommentGlass] float restored dock ${dock?.javaClass?.name ?: "none"} " +
                    "topMargin=$savedDockTop list padBottom=$savedListPad clip=$savedListClip",
            )
        }
        floatDockRef = null
        floatListRef = null
        savedDockTop = Int.MIN_VALUE
        savedListPad = Int.MIN_VALUE
        loggedFloat = false
        loggedFloatMiss = false
    }

    /** Tallest comment list in the sheet. Recyclers inside the dock (emoji rows) are not it. */
    private fun findCommentList(sheet: View?, dock: View): ViewGroup? {
        val scope = sheet?.takeIf { it.isShown && it.width > 0 } ?: dock.rootView
        var best: ViewGroup? = null
        var bestHeight = 0
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(scope)
        while (stack.isNotEmpty() && visited++ < 900) {
            val view = stack.removeLast()
            val recycler = view.javaClass.name.contains("Recycler")
            if (view !== scope && view !== dock && !isUnder(view, dock) && recycler &&
                view.isShown && view.height > bestHeight && view is ViewGroup
            ) {
                best = view
                bestHeight = view.height
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 900) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        return best?.takeIf { it.height >= dock.height }
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

    private fun clearBackground(view: View, reason: String, force: Boolean = false) {
        val background = view.background ?: return
        if (background is SurfaceBackdrop) return
        if (!force && keepsOwnBackground(view)) return
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
        restoreFloat()
        releaseIconCircles()
        restoreFloatPaint()
        pillBackdrop?.let { restore(it) }
        pillBackdrop = null
        releaseDockGlass()
        inputRef = null
        loggedInput = false
        loggedIconNone = false
        loggedIcons.clear()
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

    /** Drawables and shadow values taken off the floating strip, so they can be put back. */
    private class SavedPaint(
        val background: Drawable?,
        val foreground: Drawable?,
        val elevation: Float,
        val translationZ: Float,
        val alpha: Float,
        val animator: android.animation.StateListAnimator?,
    )
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
    /** Re-record every draw. The input dock blurs comments scrolling behind it. */
    private var live = false

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
        live: Boolean = false,
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
        this.live = live
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
            if (live || !sceneNode.hasDisplayList() || width != sceneW || height != sceneH || moved ||
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
