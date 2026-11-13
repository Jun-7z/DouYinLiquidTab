package io.github.xzdw.dylt.hook.adapters.douyin

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hookx.Xposed
import io.github.xzdw.dylt.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Liquid glass for the feed's right-hand icons themselves: the like heart, comment,
 * collect star, share arrow, avatar ring and music disc. The glyph's alpha masks a
 * blurred, refracted sample of the video, so there is no plate behind the buttons
 * and the count text under each icon is never touched.
 *
 * The original [ImageView] stays in place. Its pixels are hidden with image alpha
 * (not view alpha), which keeps the click target and lets a Lottie heart keep
 * animating; each frame is redrawn into the mask, so the glass shape follows the
 * animation. An overlay drawable does not receive touches.
 *
 * Class names were checked against the 41.0.0 and 41.1.0 arm64 packages.
 */
internal object DouyinIconGlass {
    private val GLYPHS = setOf(
        "DiggAnimationViewV2",
        "DiggAnimationView",
        "FeedCollectAnimationView",
        "FeedShareImageView",
        "FeedCommentImageView",
        "MusicCoverView",
        "HollowBorderCircleImageView",
        "StoryColorRingView",
    )
    private val CONTAINERS = setOf(
        "VideoDiggView",
        "VideoCommentCountView",
        "VideoShareView",
        "FeedAvatarView",
        "MusicCoverContainerLayout",
    )

    private val sceneNode = RenderNode("dylt-icon-scene")

    internal fun backdrop(): RenderNode = sceneNode
    private val bound = LinkedHashMap<View, GlyphGlassDrawable>()
    private var columnRef: WeakReference<ViewGroup>? = null
    private var sceneAt = 0L
    private var sceneW = 0
    private var sceneH = 0
    private var sceneBlurKey = Int.MIN_VALUE
    private var sceneReady = false
    private var loggedGlyphs = ""

    fun bind(column: ViewGroup, config: GlassConfig) {
        if (column.background is SurfaceBackdrop) column.background = null
        val previous = columnRef?.get()
        if (previous !== column) {
            sceneAt = 0L
            sceneReady = false
        }
        columnRef = WeakReference(column)
        val glyphs = collect(column)
        val keep = glyphs.toSet()
        val stale = bound.keys.filter { it !in keep || !it.isAttachedToWindow }
        for (view in stale) releaseOne(view)
        for (view in glyphs) {
            val overlay = bound[view]
            if (overlay == null) {
                val created = GlyphGlassDrawable(view)
                created.configure(config, column)
                created.setBounds(0, 0, view.width, view.height)
                view.overlay.add(created)
                bound[view] = created
            } else {
                overlay.configure(config, column)
            }
        }
        val names = glyphs.joinToString(",") { it.javaClass.simpleName }
        if (names != loggedGlyphs) {
            loggedGlyphs = names
            Xposed.log("[OfflineGlass][ActionGlass] glyphs ${glyphs.size}: $names")
        }
    }

    fun release() {
        for (view in bound.keys.toList()) releaseOne(view)
        sceneNode.setRenderEffect(null)
        sceneNode.discardDisplayList()
        sceneAt = 0L
        sceneReady = false
        sceneBlurKey = Int.MIN_VALUE
        columnRef = null
        loggedGlyphs = ""
    }

    /**
     * Records the column's backdrop when it is stale and blurs that recording.
     * Returns whether a scene is available for the glyphs to mask.
     */
    fun prepareScene(column: ViewGroup, blurPx: Float): Boolean {
        val width = column.width
        val height = column.height
        if (width <= 0 || height <= 0) return false
        val now = SystemClock.uptimeMillis()
        if (!sceneNode.hasDisplayList() || width != sceneW || height != sceneH || now - sceneAt >= 480L) {
            val drew = runCatching { BackdropRecorder.record(column, sceneNode, width, height) }.getOrDefault(false)
            if (drew) {
                sceneAt = now
                sceneW = width
                sceneH = height
            }
        }
        val blurKey = (blurPx * 10f).roundToInt()
        if (blurKey != sceneBlurKey) {
            sceneNode.setRenderEffect(NativeBackdropBlur.effect(blurPx, 1.45f))
            sceneBlurKey = blurKey
        }
        sceneReady = sceneNode.hasDisplayList()
        return sceneReady
    }

    fun offsetOf(column: View, view: View): FloatArray {
        var x = 0f
        var y = 0f
        var current: View? = view
        var guard = 0
        while (current != null && current !== column && guard++ < 14) {
            val parent = current.parent as? View ?: break
            x += current.left - parent.scrollX
            y += current.top - parent.scrollY
            current = parent
        }
        if (current === column) return floatArrayOf(x, y)
        val columnPos = IntArray(2)
        val viewPos = IntArray(2)
        column.getLocationInWindow(columnPos)
        view.getLocationInWindow(viewPos)
        return floatArrayOf((viewPos[0] - columnPos[0]).toFloat(), (viewPos[1] - columnPos[1]).toFloat())
    }

    private fun releaseOne(view: View) {
        val overlay = bound.remove(view) ?: return
        overlay.detach()
        runCatching { view.overlay.remove(overlay) }
    }

    private fun collect(column: ViewGroup): List<View> {
        val glyphs = ArrayList<View>(8)
        val containers = ArrayList<ViewGroup>(6)
        val stack = ArrayDeque<View>()
        stack.add(column)
        var seen = 0
        while (stack.isNotEmpty() && seen++ < 500 && glyphs.size < 12) {
            val view = stack.removeLast()
            if (view !== column && !view.isShown) continue
            val glyph = isGlyph(view)
            if (glyph) glyphs.add(view)
            val name = view.javaClass.simpleName
            if (view is ViewGroup && name in CONTAINERS) containers.add(view)
            if (view is ViewGroup && !glyph) {
                for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
            }
        }
        for (container in containers) {
            if (glyphs.size >= 12) break
            if (contains(container, glyphs)) continue
            fallback(container)?.let { glyphs.add(it) }
        }
        return glyphs
    }

    private fun isGlyph(view: View): Boolean {
        if (view !is ImageView || !fits(view)) return false
        val name = view.javaClass.simpleName
        if (name in GLYPHS) return true
        return name.contains("ColorRing") || name.contains("HollowBorder")
    }

    private fun fits(view: View): Boolean {
        if (view.width < 16 || view.height < 16 || view.alpha <= 0.05f) return false
        val cap = 128f * view.resources.displayMetrics.density
        return view.width <= cap && view.height <= cap
    }

    private fun contains(container: View, glyphs: List<View>): Boolean {
        for (glyph in glyphs) {
            var view: View? = glyph
            while (view != null) {
                if (view === container) return true
                view = view.parent as? View
            }
        }
        return false
    }

    /** Last resort when a release renames the icon class but keeps the container. */
    private fun fallback(container: ViewGroup): ImageView? {
        val avatar = container.javaClass.simpleName.contains("Avatar")
        var best: ImageView? = null
        var bestArea = 0
        val stack = ArrayDeque<View>()
        stack.add(container)
        var seen = 0
        while (stack.isNotEmpty() && seen++ < 48) {
            val view = stack.removeFirst()
            if (view !== container && view is ImageView && view.isShown && fits(view)) {
                val name = view.javaClass.simpleName
                val ring = name.contains("Ring") || name.contains("Hollow") || name.contains("Border")
                if (!avatar || ring) {
                    val ratio = view.width.toFloat() / view.height.coerceAtLeast(1)
                    val area = view.width * view.height
                    if (ratio in 0.55f..1.8f && area > bestArea) {
                        best = view
                        bestArea = area
                    }
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack.add(view.getChildAt(index))
            }
        }
        return best
    }
}

/**
 * Draws one icon-shaped glass glyph over [icon] and hides the original pixels.
 * [detach] puts the original icon back.
 */
private class GlyphGlassDrawable(private val icon: View) : Drawable() {
    private var config = GlassConfig()
    private var columnRef: WeakReference<ViewGroup>? = null
    private var hidden = false
    private var savedAlpha = 255
    private var disabled = false
    private var capturing = false
    private var retries = 0
    private val masks = arrayOfNulls<Bitmap>(2)
    private var slot = 0
    private var mask: Bitmap? = null
    private var maskStamp = 0
    private var sample = GlyphSample.EMPTY
    private var shader: RuntimeShader? = null
    private var shaderTried = false
    private var shaderBroken = false
    private var effect: RenderEffect? = null
    private val glyphNode = RenderNode("dylt-icon-glyph")
    private var loggedDraw = false
    private var rim: Bitmap? = null
    private var rimKey = 0
    private var observer: ViewTreeObserver? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        val view = icon
        if (!disabled && hidden && view is ImageView && view.isAttachedToWindow) {
            if (view.imageAlpha != 0) view.imageAlpha = 0
            if (view.drawable != null && view.drawable.alpha != 0) view.drawable.alpha = 0
        }
        true
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val flat = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    fun configure(config: GlassConfig, column: ViewGroup) {
        this.config = config
        columnRef = WeakReference(column)
        invalidateSelf()
    }

    fun detach() {
        disabled = true
        hidden = false
        val obs = observer
        if (obs != null && obs.isAlive) obs.removeOnPreDrawListener(preDraw)
        observer = null
        val view = icon
        if (view is ImageView) {
            view.imageAlpha = savedAlpha
            view.drawable?.alpha = savedAlpha
        }
        glyphNode.discardDisplayList()
    }

    override fun draw(canvas: Canvas) {
        if (disabled || capturing) return
        val view = icon
        if (view.width <= 0 || view.height <= 0) return
        if (bounds.width() != view.width || bounds.height() != view.height) {
            setBounds(0, 0, view.width, view.height)
        }
        try {
            val column = columnRef?.get()
            val mask = capture()
            if (mask == null || column == null) {
                if (retries++ < 8) view.postDelayed({ view.invalidate() }, 280L)
                return
            }
            val offset = DouyinIconGlass.offsetOf(column, view)
            val blurPx = config.actionBlurRadius * view.resources.displayMetrics.density
            val scene = DouyinIconGlass.prepareScene(column, blurPx)
            // The blurred plate masked by the icon is always drawn. The contour shader is
            // painted over it and only wins where it actually covers the glyph, so a shader
            // that comes out empty cannot blank the icon.
            drawFallback(canvas, mask, offset[0], offset[1], scene)
            if (view.isShown) view.postInvalidateOnAnimation()
        } catch (error: Throwable) {
            if (!loggedDraw) {
                loggedDraw = true
                Xposed.log(
                    "[OfflineGlass][ActionGlass] draw failed ${view.javaClass.simpleName}: ${error.message}",
                )
            }
            shaderBroken = true
            detach()
        }
    }

    private fun capture(): Bitmap? {
        val view = icon as? ImageView ?: return null
        val width = view.width
        val height = view.height
        val stamp = stamp(view)
        val cached = mask
        if (stamp == maskStamp && cached != null && !cached.isRecycled) return cached
        slot = 1 - slot
        var bitmap = masks[slot]
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            masks[slot] = bitmap
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        drawIcon(canvas, view)
        var read = GlyphColor.sample(bitmap)
        if (read.opaque < 6) {
            bitmap.eraseColor(Color.TRANSPARENT)
            if (!drawSynthetic(canvas, view)) return null
            read = GlyphColor.sample(bitmap)
            if (read.opaque < 6) return null
        }
        mask = bitmap
        maskStamp = stamp
        sample = read
        if (!hidden) hide(view)
        return bitmap
    }

    /** Image alpha only: view alpha would also hide this overlay and drop the click target. */
    private fun hide(view: ImageView) {
        if (!hidden) {
            savedAlpha = view.imageAlpha.takeIf { it > 0 } ?: 255
            val obs = view.viewTreeObserver
            if (obs.isAlive) {
                obs.addOnPreDrawListener(preDraw)
                observer = obs
            }
            hidden = true
        }
        view.imageAlpha = 0
        view.drawable?.alpha = 0
    }

    private fun drawIcon(canvas: Canvas, view: ImageView) {
        val drawable = view.drawable ?: return
        val previous = drawable.alpha
        val bounds = drawable.copyBounds()
        val empty = bounds.width() <= 0 || bounds.height() <= 0
        if (empty) {
            drawable.setBounds(
                0,
                0,
                view.width - view.paddingLeft - view.paddingRight,
                view.height - view.paddingTop - view.paddingBottom,
            )
        }
        drawable.alpha = 255
        capturing = true
        try {
            canvas.save()
            val scale = config.actionScale
            if (scale != 1f) canvas.scale(scale, scale, view.width / 2f, view.height / 2f)
            canvas.translate(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
            val matrix = view.imageMatrix
            if (matrix != null && !matrix.isIdentity) canvas.concat(matrix)
            drawable.draw(canvas)
            canvas.restore()
        } finally {
            capturing = false
            drawable.alpha = if (hidden) 0 else previous
            if (empty) drawable.bounds = bounds
        }
    }

    /** Disc and ring still become glass when their drawable will not draw into a bitmap. */
    private fun drawSynthetic(canvas: Canvas, view: View): Boolean {
        val name = view.javaClass.simpleName
        val disc = name == "MusicCoverView" || name.contains("MusicCover")
        val ring = name.contains("Ring") || name.contains("Hollow") || name.contains("Border")
        if (!disc && !ring) return false
        val scale = config.actionScale
        val cx = view.width / 2f
        val cy = view.height / 2f
        val radius = min(view.width, view.height) / 2f * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (ring && !disc) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * view.resources.displayMetrics.density * scale
            canvas.drawCircle(cx, cy, (radius - paint.strokeWidth / 2f).coerceAtLeast(1f), paint)
        } else {
            canvas.drawCircle(cx, cy, radius.coerceAtLeast(1f), paint)
        }
        return true
    }

    private fun drawShader(
        canvas: Canvas,
        mask: Bitmap,
        dx: Float,
        dy: Float,
        blurPx: Float,
    ): Boolean {
        val shader = shader() ?: return false
        val width = mask.width
        val height = mask.height
        val tint = tintColor()
        shader.setInputShader(
            "mask",
            BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        shader.setFloatUniform("size", (width - 1).coerceAtLeast(1).toFloat(), (height - 1).coerceAtLeast(1).toFloat())
        shader.setFloatUniform("refraction", (blurPx * 0.45f).coerceIn(0f, min(width, height) * 0.2f))
        shader.setFloatUniform(
            "tint",
            Color.red(tint) / 255f,
            Color.green(tint) / 255f,
            Color.blue(tint) / 255f,
            Color.alpha(tint) / 255f,
        )
        shader.setFloatUniform("highlight", config.actionHighlight.coerceIn(0f, 1f))
        val outlinePx = config.actionOutlineWidthDp * icon.resources.displayMetrics.density
        shader.setFloatUniform("outlineWidth", outlinePx.coerceAtLeast(1f))
        shader.setFloatUniform("outlineOn", if (config.actionOutlineEnabled) 1f else 0f)
        glyphNode.setRenderEffect(null)
        glyphNode.setPosition(0, 0, width, height)
        val recording = glyphNode.beginRecording(width, height)
        try {
            recording.translate(-dx, -dy)
            recording.drawRenderNode(DouyinIconGlass.backdrop())
        } finally {
            glyphNode.endRecording()
        }
        if (effect == null) effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        glyphNode.setRenderEffect(effect)
        canvas.drawRenderNode(glyphNode)
        return true
    }

    private fun drawFallback(canvas: Canvas, mask: Bitmap, dx: Float, dy: Float, scene: Boolean) {
        val width = mask.width.toFloat()
        val height = mask.height.toFloat()
        val tint = tintColor()
        var shaded = false
        if (scene && canvas.isHardwareAccelerated) {
            val layer = canvas.saveLayer(0f, 0f, width, height, null)
            canvas.save()
            // Mild convex lens. The contour shader below bends the silhouette itself.
            canvas.scale(1.05f, 1.05f, width / 2f, height / 2f)
            canvas.translate(-dx, -dy)
            canvas.drawRenderNode(DouyinIconGlass.backdrop())
            canvas.restore()
            fill.color = tint
            canvas.drawRect(0f, 0f, width, height, fill)
            if (!shaderBroken) {
                shaded = runCatching {
                    drawShader(canvas, mask, dx, dy, config.actionBlurRadius * icon.resources.displayMetrics.density)
                }.getOrElse { error ->
                    shaderBroken = true
                    if (!loggedDraw) {
                        loggedDraw = true
                        Xposed.log("[OfflineGlass][ActionGlass] shader skipped: ${error.message}")
                    }
                    false
                }
            }
            canvas.drawBitmap(mask, 0f, 0f, maskPaint)
            canvas.restoreToCount(layer)
        } else {
            flat.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(mask, 0f, 0f, flat)
        }
        if (shaded) return
        val highlight = config.actionHighlight
        if (highlight <= 0.01f && !config.actionOutlineEnabled) return
        val rim = rimOf(mask) ?: return
        val alpha = if (config.actionOutlineEnabled) {
            (180 + highlight * 60f).roundToInt()
        } else {
            (highlight * 200f).roundToInt()
        }
        val edge = canvas.saveLayer(0f, 0f, width, height, null)
        rimPaint.color = Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)
        canvas.drawBitmap(rim, 0f, 0f, rimPaint)
        shadowPaint.color = Color.argb(90, 0, 0, 0)
        val density = icon.resources.displayMetrics.density
        canvas.drawBitmap(rim, density, density * 1.4f, shadowPaint)
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        canvas.restoreToCount(edge)
    }

    private fun rimOf(mask: Bitmap): Bitmap? = runCatching {
        val radius = (config.actionOutlineWidthDp * icon.resources.displayMetrics.density).coerceIn(1f, 8f)
        val key = maskStamp * 31 + (radius * 10f).roundToInt()
        rim?.takeIf { rimKey == key && !it.isRecycled }?.let { return@runCatching it }
        val created = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(created)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(mask, 0f, 0f, paint)
        val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        canvas.save()
        val sx = ((mask.width - 2f * radius) / mask.width).coerceIn(0.45f, 1f)
        val sy = ((mask.height - 2f * radius) / mask.height).coerceIn(0.45f, 1f)
        canvas.scale(sx, sy, mask.width / 2f, mask.height / 2f)
        canvas.drawBitmap(mask, 0f, 0f, cut)
        canvas.restore()
        this.rim = created
        rimKey = key
        created
    }.getOrNull()

    private fun tintColor(): Int {
        val view = icon
        val liked = config.actionLikedTint && isDigg(view) && sample.red
        val rgb = when {
            liked -> GlassConfig.LIKED_RED
            config.actionTintMode == GlassConfig.ACTION_TINT_AUTO -> sample.rgb
            else -> 0xFFFFFF
        }
        val amount = if (liked) config.actionTintAlpha.coerceIn(0.62f, 0.80f) else config.actionTintAlpha
        val alpha = (amount.coerceIn(0f, 0.80f) * 255f).roundToInt().coerceIn(0, 255)
        return (rgb and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun shader(): RuntimeShader? {
        if (shaderBroken) return null
        shader?.let { return it }
        if (shaderTried) return null
        shaderTried = true
        shader = runCatching { RuntimeShader(GLYPH_SHADER) }.onFailure { error ->
            shaderBroken = true
            Xposed.log("[OfflineGlass][ActionGlass] shader unavailable: ${error.message}")
        }.getOrNull()
        return shader
    }

    private fun stamp(view: ImageView): Int {
        val drawable = view.drawable
        var value = view.width * 31 + view.height
        value = value * 31 + (config.actionScale * 100f).roundToInt()
        value = value * 31 + (drawable?.let { System.identityHashCode(it) } ?: 0)
        value = value * 31 + (drawable?.colorFilter?.hashCode() ?: 0)
        value = value * 31 + (drawable?.level ?: 0)
        value = value * 31 + (view.imageTintList?.hashCode() ?: 0)
        if (animates(view)) value = value * 31 + (SystemClock.uptimeMillis() / 32L).toInt()
        return value
    }

    private fun animates(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        var depth = 0
        while (type != null && depth++ < 6) {
            val name = type.name
            if (name.contains("Lottie") || name.contains("AnimationView")) return true
            type = type.superclass
        }
        return false
    }

    private fun isDigg(view: View): Boolean {
        if (view.javaClass.simpleName.contains("Digg")) return true
        var parent = view.parent
        var depth = 0
        while (parent is View && depth++ < 6) {
            if (parent.javaClass.simpleName == "VideoDiggView") return true
            parent = parent.parent
        }
        return false
    }

    override fun setAlpha(alpha: Int) = Unit
    @Deprecated("Drawable.setColorFilter is deprecated; the glass tint is its own paint.")
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Drawable.getOpacity is deprecated.")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private data class GlyphSample(val opaque: Int, val red: Boolean, val rgb: Int) {
    companion object {
        val EMPTY = GlyphSample(0, false, 0xFFFFFF)
    }
}

private object GlyphColor {
    fun sample(bitmap: Bitmap): GlyphSample {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return GlyphSample.EMPTY
        val step = max(1, min(width, height) / 8)
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var count = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) >= 80) {
                    redSum += (pixel shr 16) and 255
                    greenSum += (pixel shr 8) and 255
                    blueSum += pixel and 255
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return GlyphSample.EMPTY
        val red = (redSum / count).toInt()
        val green = (greenSum / count).toInt()
        val blue = (blueSum / count).toInt()
        val maxChannel = max(red, max(green, blue)).coerceAtLeast(1)
        val minChannel = min(red, min(green, blue))
        val saturation = (maxChannel - minChannel).toFloat() / maxChannel
        val rgb = if (saturation < 0.18f) {
            0xFFFFFF
        } else {
            val hsv = FloatArray(3)
            Color.RGBToHSV(red, green, blue, hsv)
            hsv[1] = hsv[1].coerceAtMost(0.85f)
            hsv[2] = 1f
            Color.HSVToColor(hsv) and 0x00FFFFFF
        }
        val isRed = red > 170 && red > green * 1.45f && red > blue * 1.2f
        return GlyphSample(count, isRed, rgb)
    }
}

/**
 * Edge refraction from the mask's alpha slope, then a white or red tint, an inner
 * shadow and a specular rim. Outside the glyph the alpha is 0, so the buttons
 * have no background.
 */
private const val GLYPH_SHADER = """
    uniform shader content;
    uniform shader mask;
    uniform float2 size;
    uniform float refraction;
    uniform float4 tint;
    uniform float highlight;
    uniform float outlineWidth;
    uniform float outlineOn;

    half4 main(float2 coord) {
        half4 coverage = mask.eval(coord);
        if (coverage.a < 0.02) return half4(0.0, 0.0, 0.0, 0.0);
        float dx = max(outlineWidth, 1.0);
        float left = mask.eval(coord + float2(-dx, 0.0)).a;
        float right = mask.eval(coord + float2(dx, 0.0)).a;
        float up = mask.eval(coord + float2(0.0, -dx)).a;
        float down = mask.eval(coord + float2(0.0, dx)).a;
        float2 normal = float2(left - right, up - down);
        float span = length(normal);
        float2 sampleAt = clamp(coord + normal * refraction, float2(0.0), size);
        half4 backdrop = content.eval(sampleAt);
        half3 color = mix(backdrop.rgb, half3(tint.r, tint.g, tint.b), tint.a);
        float shade = clamp(dot(normal, float2(0.55, 0.80)) * 2.4, 0.0, 1.0);
        color *= 1.0 - shade * 0.34;
        float rim = smoothstep(0.06, 0.42, span);
        float spec = 0.0;
        if (span > 0.001) {
            float2 dir = normal / span;
            spec = pow(clamp(dot(dir, float2(-0.28, -0.86)), 0.0, 1.0), 1.5);
        }
        color += half3(spec * rim * highlight);
        if (outlineOn > 0.5) {
            color = mix(color, half3(1.0), rim * 0.82);
        }
        return half4(color, coverage.a);
    }
"""
