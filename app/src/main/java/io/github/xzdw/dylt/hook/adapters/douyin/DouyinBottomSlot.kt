package io.github.xzdw.dylt.hook.adapters.douyin

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import io.github.xzdw.dylt.config.GlassConfig
import io.github.xzdw.dylt.hook.GlassHostLayout
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * The pill is always 首页 / 朋友 / 消息 / 我. 「显示加号」 shows or hides the original
 * publish circle. 「右侧按钮」 adds a second circle of that same size to its right;
 * today that circle is the music-disc proxy.
 *
 * The real MusicCoverView stays in the feed item. Reparenting it into the glass host
 * pulls it out of the recycled page that loads the cover. While the right circle is
 * the disc, the column slot that holds it stays VISIBLE and attached so the cover
 * drawable keeps loading, but that slot measures to height 0 with no vertical margin
 * or padding, and the buttons above it move down. The circle spins on its own.
 */
internal object DouyinBottomSlot {
    private const val LOOK_MS = 400L
    private const val ART_PX = 160
    private const val WALK_BUDGET = 1_600
    private const val SPIN_MS = 8_000L
    /** A live disc moves about this far between frames. Smaller changes are noise. */
    private const val SYNC_DEG = 0.2f
    /** Same hold as the feed's static-video detector. Longer than one dropped frame. */
    private const val PAUSE_HOLD_MS = 250L

    /** The original publish circle. Driven by 显示加号 (`show_post`). */
    @Volatile var showPlus: Boolean = true
        private set

    /** The rightmost circle. Today it is the music disc. */
    @Volatile var showRight: Boolean = false
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
    private var loggedPlaying: Boolean? = null
    private var lastLiveRotation = Float.NaN
    private var spinView: View? = null
    private var watchPosted = false
    private var hooksInstalled = false
    private var videoStamp = Long.MIN_VALUE
    private var videoStampAt = 0L
    private var hostRef: WeakReference<View>? = null
    private val hiddenBadges = WeakHashMap<View, Int>()
    private val badgeLogged = HashSet<String>()
    /** Discs we are keeping transparent. Identity lookup from the alpha hook. */
    private val forced = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )
    private val held = ArrayList<HeldDisc>(4)

    /**
     * Column slots currently forced to a 0 measured height. Replaced as a whole so the
     * measure hook can scan it without taking a lock.
     */
    @Volatile private var collapsedSlots: Array<View> = emptyArray()
    private var collapseLayoutAt = 0L
    private var collapseSeekAt = 0L
    private var collapseNudges = 0
    private var loggedCollapseMiss = false
    private var zeroingLayout = false
    private val measuredDimension: java.lang.reflect.Method? by lazy {
        runCatching {
            View::class.java.getDeclaredMethod(
                "setMeasuredDimension",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * Our own 8-second turn. The feed view's rotation only seeks this; a frozen
     * original must not freeze the circle.
     */
    private val spin: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
    }

    private val spinWatch = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!showRight) {
                watchPosted = false
                return
            }
            val playing = videoPlaying(SystemClock.uptimeMillis())
            if (loggedPlaying != playing) {
                loggedPlaying = playing
                Xposed.log("[OfflineGlass][BottomSlot] disc spin=${if (playing) "run" else "paused"}")
            }
            if (playing) {
                if (!spin.isStarted) spin.start()
                else if (spin.isPaused) spin.resume()
                val host = hostRef?.get()
                if (host != null && host.isShown) host.invalidate()
            } else if (spin.isStarted && !spin.isPaused) {
                spin.pause()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun discArt(): Bitmap? = art?.takeIf { !it.isRecycled && it.width > 0 }

    /**
     * Angle of the bottom circle. Always the local animator while the video is
     * playing (or when playback cannot be read). The real view's rotation is a
     * phase hint only, and only while that value is actually changing.
     */
    fun discRotation(): Float {
        ensureWatch()
        val live = discRef?.get()?.rotation
        if (live != null && !live.isNaN() && spin.isStarted) {
            val delta = if (lastLiveRotation.isNaN()) 360f else abs(wrap(live - lastLiveRotation))
            if (delta > SYNC_DEG) {
                lastLiveRotation = live
                spin.currentPlayTime = (wrap(live) / 360f * SPIN_MS.toFloat()).toLong()
            }
        }
        if (!spin.isStarted) return if (lastLiveRotation.isNaN()) 0f else wrap(lastLiveRotation)
        val value = spin.animatedValue
        return if (value is Float) value else 0f
    }

    /** Keep setAlpha(0) / INVISIBLE from sticking on the disc we are showing. */
    fun installHooks() {
        if (hooksInstalled) return
        hooksInstalled = true
        runCatching {
            Xposed.hookAllMethods(View::class.java, "setAlpha", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHook.MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!forced.contains(view)) return
                    val alpha = param.args.getOrNull(0) as? Float ?: return
                    if (alpha != 0f) param.args[0] = 0f
                }
            })
            Xposed.hookAllMethods(View::class.java, "setVisibility", object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHook.MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!forced.contains(view)) return
                    val visibility = param.args.getOrNull(0) as? Int ?: return
                    // INVISIBLE makes isShown() false and Douyin pauses cover loading.
                    // GONE is Douyin collapsing the slot itself; leave that alone.
                    if (visibility == View.INVISIBLE) param.args[0] = View.VISIBLE
                }
            })
            // After the slot measures its cover at the real size, report height 0
            // so the action column closes the gap. Children keep the size they just
            // measured, which is what the cover loader and view.draw() use.
            Xposed.hookAllMethods(View::class.java, "measure", object : MethodHook() {
                override fun afterHookedMethod(param: MethodHook.MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val slots = collapsedSlots
                    var hit = false
                    for (slot in slots) {
                        if (slot === view) {
                            hit = true
                            break
                        }
                    }
                    if (!hit || view.measuredHeight == 0) return
                    runCatching { setMeasuredDimension(view, view.measuredWidth, 0) }
                }
            })
            Xposed.hookAllMethods(View::class.java, "setLayoutParams", object : MethodHook() {
                override fun afterHookedMethod(param: MethodHook.MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (zeroingLayout) return
                    val item = held.firstOrNull { it.collapse && it.view === view } ?: return
                    squashSlot(item, rememberIncoming = true)
                }
            })
        }.onFailure { error ->
            Xposed.log("[OfflineGlass][BottomSlot] disc hide hook failed ${error.javaClass.simpleName}")
        }
    }

    private fun setMeasuredDimension(view: View, width: Int, height: Int) {
        val method = measuredDimension
        if (method != null) {
            method.invoke(view, width, height)
            return
        }
        org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
            View::class.java, view, "setMeasuredDimension", width, height,
        )
    }

    /** Called before the host rebuilds its path, so the circles match this config. */
    fun sync(config: GlassConfig) {
        val plus = config.showPostButton
        val right = config.showRightSlot && config.rightSlotType == GlassConfig.RIGHT_SLOT_MUSIC
        if (plus == showPlus && right == showRight && config.hideShootSame == hideBadge) return
        showPlus = plus
        showRight = right
        hideBadge = config.hideShootSame
        if (showRight) ensureWatch() else {
            stopSpin()
            releaseHeld()
        }
        val label = "$plus:$right:$hideBadge"
        if (loggedMode != label) {
            loggedMode = label
            val rightName = if (right) config.rightSlotType else "off"
            Xposed.log(
                "[OfflineGlass][BottomSlot] plus=$plus right=$rightName hideShootSame=$hideBadge",
            )
        }
    }

    fun refresh(host: View, root: View, config: GlassConfig) {
        hostRef = WeakReference(host)
        sync(config)
        val needDisc = showRight || hideBadge
        if (!needDisc) {
            restoreBadges()
            releaseHeld()
            return
        }
        val now = SystemClock.uptimeMillis()
        val cached = discRef?.get()
        // A collapsed disc has no height, so the on-screen test would drop it and
        // the next page's cover would steal the circle. Stay with it while attached.
        val keepCached = cached != null && cached.isAttachedToWindow &&
            (placed(cached) || forced.contains(cached))
        if (!keepCached) {
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
        if (showRight) {
            if (view !== spinView) {
                spinView = view
                lastLiveRotation = Float.NaN
            }
            val parent = view.parent as? View
            val parentNeeded = parent?.javaClass?.simpleName == "MusicCoverContainerLayout"
            val slot = columnSlot(view)
            val heldView = held.any { it.view === view }
            val heldParent = !parentNeeded || held.any { it.view === parent }
            val heldSlot = slot != null && held.any { it.view === slot }
            // The column may not have a size on the first pass. Keep looking until it does.
            val retrySlot = slot == null && held.none { it.collapse } && now - collapseSeekAt >= LOOK_MS
            if (retrySlot) collapseSeekAt = now
            if (!heldView || !heldParent || (slot != null && !heldSlot) || retrySlot) holdCurrent(view)
            ensureArt(view, now)
        } else {
            releaseHeld()
        }
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

    /**
     * Alpha is applied by the parent, not by [View.draw], so a disc we forced to
     * alpha 0 still paints its cover into the proxy bitmap.
     */
    private fun canDraw(view: View): Boolean =
        view.visibility != View.GONE && view.width > 0 && view.height > 0

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

    private fun ensureWatch() {
        if (watchPosted || !showRight) return
        watchPosted = true
        Choreographer.getInstance().postFrameCallback(spinWatch)
    }

    private fun stopSpin() {
        watchPosted = false
        Choreographer.getInstance().removeFrameCallback(spinWatch)
        if (spin.isStarted) spin.cancel()
        loggedPlaying = null
    }

    /**
     * True while frames are arriving. No registered player means we cannot tell,
     * and the circle keeps turning rather than freezing.
     */
    private fun videoPlaying(now: Long): Boolean {
        val stamp = DouyinPlayerTextureHooks.currentFrameStamp() ?: return true
        if (stamp != videoStamp) {
            videoStamp = stamp
            videoStampAt = now
            return true
        }
        if (videoStampAt == 0L) videoStampAt = now
        return now - videoStampAt < PAUSE_HOLD_MS
    }

    private fun wrap(degrees: Float): Float {
        val mod = degrees % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    /**
     * The direct child of the right-hand action column that contains [disc].
     * That is the view whose height is the empty gap.
     */
    private fun columnSlot(disc: View): View? {
        var node: View = disc
        var depth = 0
        while (depth++ < 8) {
            val parent = node.parent as? ViewGroup ?: return null
            if (parent is GlassHostLayout) return null
            if (isActionColumn(parent)) return node
            node = parent
        }
        return null
    }

    /** A tall, narrow stack on the right of the screen: the like/comment/share column. */
    private fun isActionColumn(group: ViewGroup): Boolean {
        if (group.childCount < 3) return false
        val width = group.width
        val height = group.height
        if (width <= 0 || height < width * 2) return false
        val loc = IntArray(2)
        group.getLocationOnScreen(loc)
        val center = loc[0] + width / 2
        return center > group.resources.displayMetrics.widthPixels * 0.62f
    }

    /**
     * Transparent, still VISIBLE, still attached. The column slot measures to
     * height 0 so the buttons above it move down. GONE is not used: that makes
     * isShown() false and the cover loader stops.
     */
    private fun holdCurrent(view: View) {
        val slot = columnSlot(view)
        val targets = ArrayList<View>(3)
        targets.add(view)
        val parent = view.parent
        if (parent is View && parent.javaClass.simpleName == "MusicCoverContainerLayout") {
            targets.add(parent)
        }
        if (slot != null && targets.none { it === slot }) targets.add(slot)
        val keep = ArrayList<HeldDisc>(held.size)
        for (item in held) {
            if (targets.any { it === item.view }) keep.add(item) else restore(item)
        }
        held.clear()
        held.addAll(keep)
        for (target in targets) {
            if (held.any { it.view === target }) continue
            val listener = keepAwake(target)
            val attach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    watchDraw(v, listener, add = true)
                    held.firstOrNull { it.collapse && it.view === v }?.let { squashSlot(it, rememberIncoming = false) }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    watchDraw(v, listener, add = false)
                }
            }
            val lp = target.layoutParams as? ViewGroup.MarginLayoutParams
            val collapse = slot != null && target === slot
            val item = HeldDisc(
                view = target,
                alpha = target.alpha,
                visibility = target.visibility,
                listener = listener,
                attach = attach,
                collapse = collapse,
                paddingTop = target.paddingTop,
                paddingBottom = target.paddingBottom,
                marginTop = lp?.topMargin ?: 0,
                marginBottom = lp?.bottomMargin ?: 0,
                layoutHeight = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            forced.add(target)
            held.add(item)
            target.addOnAttachStateChangeListener(attach)
            if (target.isAttachedToWindow) watchDraw(target, listener, add = true)
            if (target.visibility == View.INVISIBLE) target.visibility = View.VISIBLE
            if (target.alpha != 0f) target.alpha = 0f
            if (collapse) squashSlot(item, rememberIncoming = false)
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc hide=alpha0 ${target.javaClass.simpleName} " +
                    "wasVis=${item.visibility} wasAlpha=${item.alpha}" +
                    if (collapse) " collapse=height0" else "",
            )
        }
        publishCollapsed()
        if (slot == null) {
            if (!loggedCollapseMiss) {
                loggedCollapseMiss = true
                Xposed.log(
                    "[OfflineGlass][BottomSlot] disc collapse=miss parent=${view.parent?.javaClass?.simpleName}",
                )
            }
        } else {
            loggedCollapseMiss = false
            val parentName = slot.parent?.javaClass?.simpleName
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc collapse=height0 ${slot.javaClass.simpleName} " +
                    "parent=$parentName margins=${slotMargin(slot)}",
            )
        }
    }

    /** Drop the slot's vertical footprint. The view stays VISIBLE and attached. */
    private fun squashSlot(item: HeldDisc, rememberIncoming: Boolean) {
        val view = item.view
        if (view.paddingTop != 0 || view.paddingBottom != 0) {
            view.setPadding(view.paddingLeft, 0, view.paddingRight, 0)
        }
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (rememberIncoming && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
            item.marginTop = lp.topMargin
            item.marginBottom = lp.bottomMargin
        }
        // Leave layout height as wrap_content. Forcing it to 0 makes the parent
        // measure the cover at 0 before the image can load. The measure hook
        // reports 0 only after that pass, which is what closes the gap.
        val marginChanged = lp.topMargin != 0 || lp.bottomMargin != 0
        if (!marginChanged) {
            if (view.measuredHeight == 0 && view.height == 0) collapseNudges = 0
            else requestCollapseLayout(view)
            return
        }
        zeroingLayout = true
        try {
            lp.topMargin = 0
            lp.bottomMargin = 0
        } finally {
            zeroingLayout = false
        }
        view.requestLayout()
    }

    private fun requestCollapseLayout(view: View) {
        if (collapseNudges > 8) {
            if (collapseNudges == 9) {
                collapseNudges = 10
                Xposed.log(
                    "[OfflineGlass][BottomSlot] disc collapse=stuck " +
                        "${view.javaClass.simpleName} laid=${view.width}x${view.height} " +
                        "measured=${view.measuredWidth}x${view.measuredHeight}",
                )
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - collapseLayoutAt < 50L) return
        collapseLayoutAt = now
        collapseNudges++
        view.requestLayout()
    }

    private fun slotMargin(view: View): String {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return "0,0"
        return "${lp.topMargin},${lp.bottomMargin}"
    }

    private fun publishCollapsed() {
        collapsedSlots = held.filter { it.collapse }.map { it.view }.toTypedArray()
    }

    private fun keepAwake(view: View) = ViewTreeObserver.OnPreDrawListener {
        if (!forced.contains(view)) return@OnPreDrawListener true
        if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
        if (view.alpha != 0f) view.alpha = 0f
        val item = held.firstOrNull { it.collapse && it.view === view }
        if (item != null) squashSlot(item, rememberIncoming = false)
        true
    }

    private fun watchDraw(view: View, listener: ViewTreeObserver.OnPreDrawListener, add: Boolean) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        if (add) observer.addOnPreDrawListener(listener) else observer.removeOnPreDrawListener(listener)
    }

    private fun releaseHeld() {
        if (held.isEmpty() && collapsedSlots.isEmpty()) return
        val copy = ArrayList(held)
        held.clear()
        collapsedSlots = emptyArray()
        spinView = null
        loggedCollapseMiss = false
        collapseNudges = 0
        for (item in copy) restore(item)
    }

    private fun restore(item: HeldDisc) {
        val view = item.view
        forced.remove(view)
        view.removeOnAttachStateChangeListener(item.attach)
        watchDraw(view, item.listener, add = false)
        if (view.alpha == 0f) view.alpha = item.alpha
        if (view.visibility == View.VISIBLE && item.visibility == View.INVISIBLE) {
            view.visibility = item.visibility
        }
        if (!item.collapse) return
        if (view.paddingTop != item.paddingTop || view.paddingBottom != item.paddingBottom) {
            view.setPadding(view.paddingLeft, item.paddingTop, view.paddingRight, item.paddingBottom)
        }
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            zeroingLayout = true
            try {
                lp.topMargin = item.marginTop
                lp.bottomMargin = item.marginBottom
                lp.height = item.layoutHeight
            } finally {
                zeroingLayout = false
            }
        }
        view.requestLayout()
        Xposed.log(
            "[OfflineGlass][BottomSlot] disc collapse=restore ${view.javaClass.simpleName}",
        )
    }

    private class HeldDisc(
        val view: View,
        val alpha: Float,
        val visibility: Int,
        val listener: ViewTreeObserver.OnPreDrawListener,
        val attach: View.OnAttachStateChangeListener,
        val collapse: Boolean,
        val paddingTop: Int,
        val paddingBottom: Int,
        var marginTop: Int,
        var marginBottom: Int,
        var layoutHeight: Int,
    )

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
