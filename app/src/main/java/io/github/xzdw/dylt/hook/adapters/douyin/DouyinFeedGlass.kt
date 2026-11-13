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
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Frosted glass for two Douyin surfaces the bars do not cover: the feed's right-hand
 * action column, and the comment sheet.
 *
 * Both are drawn with the same material as the bars (replayed display lists under
 * [NativeBackdropBlur], then the shared rounded-rect refraction shader, a tint, an
 * outline and a highlight). The column is found by the stable class
 * `FeedRightScaleView`, with a fallback that walks up from `VideoDiggView` until the
 * narrow stack also holds the avatar, share or music-cover view. The sheet is
 * `CommentNestedLayout`. Those names are present on 41.0.0 and 41.1.0.
 *
 * The glass is a background of the target, so it paints under the buttons and the
 * comment text and never changes the target's measured size. A real child would
 * reflow a vertical column the same way the bottom bar was collapsed.
 */
internal object DouyinFeedGlass {
    private const val WALK_BUDGET = 2_800
    private const val RESCAN_MS = 240L

    private const val COLUMN_CLASS = "FeedRightScaleView"
    private const val SHEET_CLASS = "CommentNestedLayout"
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
    private var sheetRef: WeakReference<View>? = null
    private var inputRef: WeakReference<View>? = null
    private var columnBackdrop: SurfaceBackdrop? = null
    private var sheetBackdrop: SurfaceBackdrop? = null
    private var inputBackdrop: SurfaceBackdrop? = null
    private var lastScanAt = 0L
    private var loggedColumn = false
    private var loggedSheet = false

    fun update(root: ViewGroup, config: GlassConfig?) {
        if (config == null || (!config.actionGlassEnabled && !config.commentGlassEnabled)) {
            release()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastScanAt >= RESCAN_MS) {
            lastScanAt = now
            scan(root)
        }
        val dark = isDark(config, root)
        if (config.actionGlassEnabled) {
            val column = columnRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown }
            if (column is ViewGroup) bindColumn(column, config, dark) else releaseColumn()
        } else {
            releaseColumn()
        }
        if (config.commentGlassEnabled) {
            val sheet = sheetRef?.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.height > 0 }
            if (sheet is ViewGroup) bindSheet(sheet, root, config, dark) else releaseSheet()
        } else {
            releaseSheet()
        }
    }

    fun dispose() = release()

    private fun scan(root: ViewGroup) {
        var column: ViewGroup? = null
        var sheet: ViewGroup? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET && (column == null || sheet == null)) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (view.visibility != View.VISIBLE) continue
            val name = view.javaClass.simpleName
            if (column == null && view is ViewGroup && name == COLUMN_CLASS && onRight(view, root)) {
                column = view
            }
            if (sheet == null && view is ViewGroup && name == SHEET_CLASS &&
                view.width >= root.width * 0.75f && view.height >= root.height * 0.22f
            ) {
                sheet = view
            }
            if (view is ViewGroup && view.childCount > 0) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        if (column == null) column = columnFromMarkers(root)
        columnRef = column?.let(::WeakReference)
        sheetRef = sheet?.let(::WeakReference)
        if (column != null && !loggedColumn) {
            loggedColumn = true
            Xposed.log("[OfflineGlass][ActionGlass] column ${column.javaClass.name} ${column.width}x${column.height}")
        }
        if (sheet != null && !loggedSheet) {
            loggedSheet = true
            Xposed.log("[OfflineGlass][CommentGlass] sheet ${sheet.javaClass.name} ${sheet.width}x${sheet.height}")
        }
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
        return right >= root.width * 0.82f && viewPos[0] - rootPos[0] >= root.width * 0.55f
    }

    private fun bindColumn(column: ViewGroup, config: GlassConfig, dark: Boolean) {
        val backdrop = columnBackdrop ?: SurfaceBackdrop(column.context, capsule = true).also {
            columnBackdrop = it
        }
        val density = column.resources.displayMetrics.density
        val pad = config.actionPaddingDp * density
        val scale = config.actionScale
        backdrop.configure(
            host = column,
            blurPx = config.actionBlurRadius * density,
            cornerPx = if (config.actionGlassStyle == GlassConfig.ACTION_GLASS_BUTTONS) {
                0f
            } else {
                config.actionCornerRadiusDp * density
            },
            tint = tint(if (dark) 0xFF161311.toInt() else 0xFFF4F0E8.toInt(), config.actionTintAlpha),
            outline = config.actionOutlineEnabled,
            highlight = config.actionHighlight,
            circles = if (config.actionGlassStyle == GlassConfig.ACTION_GLASS_BUTTONS) {
                buttonCircles(column, pad, scale)
            } else {
                emptyList()
            },
            inset = pad,
            offsetY = config.actionOffsetYDp * density,
            scale = scale,
        )
        install(column, backdrop)
    }

    private fun buttonCircles(column: ViewGroup, pad: Float, scale: Float): List<RectF> {
        val circles = ArrayList<RectF>(8)
        for (index in 0 until column.childCount) {
            val child = column.getChildAt(index)
            if (!child.isShown || child.width < 24 || child.height < 24) continue
            if (child.width > column.width * 1.35f) continue
            val radius = max(child.width, child.height) / 2f * scale
            val cx = child.left + child.width / 2f
            val cy = child.top + child.height / 2f
            circles += RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        }
        if (circles.isEmpty()) return circles
        // The drawable is drawn in the host's bounds. Padding grows the glass, not the view,
        // so the circles stay centred on the buttons.
        if (pad != 0f) circles.forEach { it.inset(-pad * 0.15f, -pad * 0.15f) }
        return circles
    }

    private fun bindSheet(sheet: ViewGroup, root: View, config: GlassConfig, dark: Boolean) {
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
        val input = if (config.commentInputGlass) findInput(sheet) else null
        inputRef = input?.let(::WeakReference)
        if (input != null && input !== sheet) {
            val inputGlass = inputBackdrop ?: SurfaceBackdrop(input.context, capsule = true).also {
                inputBackdrop = it
            }
            inputGlass.configure(
                host = input,
                blurPx = config.commentBlurRadius * density,
                cornerPx = 14f * density,
                tint = tint(if (dark) config.commentDarkTint else config.commentLightTint, config.commentTintAlpha),
                outline = config.commentOutlineEnabled,
                highlight = config.commentHighlight * 0.6f,
                circles = emptyList(),
                inset = 0f,
                offsetY = 0f,
                scale = 1f,
            )
            install(input, inputGlass)
        } else {
            releaseInput()
        }
    }

    /** Opaque cards inside the sheet would hide the glass. Lists and text are left alone. */
    private fun clearCoveringBackgrounds(sheet: ViewGroup) {
        val minWidth = sheet.width * 0.7f
        val minHeight = sheet.height * 0.45f
        for (index in 0 until sheet.childCount) {
            val child = sheet.getChildAt(index)
            if (child.width < minWidth || child.height < minHeight) continue
            val name = child.javaClass.simpleName
            if (name.contains("Recycler") || name.contains("List") || name.contains("Edit")) continue
            if (child.background != null && child.background !is SurfaceBackdrop) {
                rememberBackground(child)
                child.background = null
            }
        }
    }

    private fun findInput(sheet: View): View? {
        var found: View? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack.add(sheet)
        val sheetPos = IntArray(2).also(sheet::getLocationOnScreen)
        while (stack.isNotEmpty() && visited++ < 400) {
            val view = stack.removeLast()
            val name = view.javaClass.simpleName
            val pos = IntArray(2).also(view::getLocationOnScreen)
            val nearBottom = pos[1] >= sheetPos[1] + sheet.height * 0.62f
            if (view !== sheet && view.isShown && view.width >= sheet.width * 0.6f && nearBottom &&
                (name.contains("Edit") || name.contains("Input") || name.contains("BottomBar") ||
                    name.contains("Keyboard"))
            ) {
                found = view
            }
            if (view is ViewGroup && view.childCount > 0 && visited < 400) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        // The box that holds the field, one level up, is the surface the user sees.
        val parent = found?.parent as? View
        return if (parent != null && parent !== sheet && parent.width >= sheet.width * 0.6f) parent else found
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
        columnBackdrop?.let { restore(it) }
        columnBackdrop = null
    }

    private fun releaseSheet() {
        sheetBackdrop?.let { restore(it) }
        sheetBackdrop = null
        releaseInput()
        // Covering backgrounds cleared for the sheet are restored with everyone else.
        restoreAll()
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
    private val path = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val location = IntArray(2)
    private val childLocation = IntArray(2)
    private var renderField: java.lang.reflect.Field? = null

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
        if (width <= 0 || height <= 0 || !canvas.isHardwareAccelerated) {
            fill.color = tint
            canvas.drawRect(bounds, fill)
            return
        }
        ensureEffect(width, height)
        val now = SystemClock.uptimeMillis()
        if (!sceneNode.hasDisplayList() || width != sceneW || height != sceneH ||
            now - sceneAt >= DouyinFeedGlassRefresh
        ) {
            if (record(host, width, height)) {
                sceneAt = now
                sceneW = width
                sceneH = height
            }
        }
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        clipShape(width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipPath(path)
        if (sceneNode.hasDisplayList()) {
            sceneNode.setRenderEffect(effect)
            canvas.drawRenderNode(sceneNode)
        }
        fill.color = tint
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
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

    private fun record(host: View, width: Int, height: Int): Boolean {
        val recording = sceneNode.beginRecording(width, height)
        recording.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        host.getLocationInWindow(location)
        var drawn = 0
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
        sceneNode.endRecording()
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

    override fun setAlpha(alpha: Int) = Unit
    @Deprecated("Drawable.setColorFilter is deprecated; the glass tint is its own paint.")
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable.getOpacity is deprecated.")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Scene re-record interval, kept out of the drawable so the draw path stays allocation-free. */
private const val DouyinFeedGlassRefresh = 700L
