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
 * pulls it out of the recycled page that loads the cover. The feed pager keeps the
 * previous page's cover attached, so the proxy rebinds to whichever cover belongs
 * to the page currently on screen. While the right circle is the disc, that page's
 * column slot stays VISIBLE and attached so the cover drawable keeps loading, but
 * the slot measures to height 0. If the column then still leaves a hole, the
 * buttons above the disc shift down by the freed height. The circle spins on its own.
 */
internal object DouyinBottomSlot {
    private const val LOOK_MS = 400L
    /** How soon to walk again after the cached cover leaves the selected page. */
    private const val RESCAN_MS = 50L
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
    /** translationY this module added, so a later pass can remove just that part. */
    private val shiftApplied = WeakHashMap<View, Float>()
    /** Last shift logged per column, so a second page does not log every frame. */
    private val shiftLogged = HashMap<Int, Int>()
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
            // The height we overwrite is the disc row's natural height, kept so a
            // column that ignores measured height can still shift its siblings.
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
                    if (!hit) return
                    val natural = view.measuredHeight
                    if (natural > 0) rememberFreed(view, natural)
                    if (natural == 0) return
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
        pruneHeld()
        val cached = discRef?.get()
        // The collapsed cover has no height and the previous page stays attached,
        // so "still attached" is not "still the selected video". Re-resolve when
        // that page no longer owns the screen, and on a short timer otherwise.
        val pageChanged = cached == null || !cached.isAttachedToWindow || !ownsScreenCenter(cached)
        val due = if (pageChanged) now - lookedAt >= RESCAN_MS else now - lookedAt >= LOOK_MS
        if (due) {
            lookedAt = now
            val found = findDisc(root)
            if (found != null && found !== cached) rebindDisc(host, cached, found)
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
        hostRef?.get()?.invalidate()
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
     * Walks to the outermost column so an inner wrapper is not the thing that
     * collapses while the real slot keeps its height. If no column matches,
     * the disc row itself (the child of the first much taller parent) is used.
     */
    private fun columnSlot(disc: View): View? {
        var node: View = disc
        var chosen: View? = null
        var depth = 0
        while (depth++ < 10) {
            val parent = node.parent as? ViewGroup ?: break
            if (parent is GlassHostLayout) break
            if (isActionColumn(parent) && isDiscSlice(node, parent)) chosen = node
            node = parent
        }
        return chosen ?: discSlice(disc)
    }

    /** A vertical stack on the right of the screen: the avatar/like/comment/share column. */
    private fun isActionColumn(group: ViewGroup): Boolean {
        if (group.childCount < 2) return false
        val width = group.width
        val height = group.height
        if (width <= 0 || height < width * 1.4f) return false
        val loc = IntArray(2)
        group.getLocationOnScreen(loc)
        val screenW = group.resources.displayMetrics.widthPixels
        val center = loc[0] + width / 2
        if (center <= screenW * 0.55f || loc[0] >= screenW) return false
        val visible = ArrayList<View>(group.childCount)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            if (child.visibility == View.GONE || child.height <= 0) continue
            visible.add(child)
        }
        if (visible.size < 2) return false
        visible.sortBy { it.top }
        var stacked = 0
        for (index in 1 until visible.size) {
            val previous = visible[index - 1]
            if (visible[index].top >= previous.top + previous.height / 2) stacked++
        }
        return stacked >= 1
    }

    /** The disc row, not a child that is most of the column. */
    private fun isDiscSlice(node: View, parent: ViewGroup): Boolean {
        val childH = if (node.height > 0) node.height else node.measuredHeight
        val parentH = parent.height
        if (parentH <= 0 || childH <= 0) return true
        return childH < parentH * 0.6f
    }

    /**
     * The disc widget when the column heuristic misses. Stops at the child of
     * the first parent that is clearly taller than the disc row.
     */
    private fun discSlice(disc: View): View? {
        var node: View = disc
        var depth = 0
        while (depth++ < 8) {
            val parent = node.parent as? ViewGroup ?: return if (node === disc) null else node
            if (parent is GlassHostLayout) return if (node === disc) null else node
            val childH = if (node.height > 0) node.height else node.measuredHeight
            if (parent.height > 0 && childH > 0 &&
                parent.height > childH * 2 && parent.height > parent.width
            ) {
                return node
            }
            node = parent
        }
        return if (node === disc) null else node
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
        // Keep the previous page collapsed. Dropping it here opens the hole again
        // the moment a swipe starts, and the pager still has that page attached.
        // Detached pages are released by pruneHeld.
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
            if (collapse) rememberFreed(target, target.height)
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
            val column = slot.parent as? ViewGroup
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc collapse=height0 ${slot.javaClass.simpleName} " +
                    "parent=$parentName children=${column?.childCount ?: 0} " +
                    "slot=${slot.width}x${slot.height} margins=${slotMargin(slot)}",
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
        if (item != null) {
            squashSlot(item, rememberIncoming = false)
            settleShift(view)
        }
        true
    }

    private fun watchDraw(view: View, listener: ViewTreeObserver.OnPreDrawListener, add: Boolean) {
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        if (add) observer.addOnPreDrawListener(listener) else observer.removeOnPreDrawListener(listener)
    }

    private fun releaseHeld() {
        if (held.isEmpty() && collapsedSlots.isEmpty() && shiftApplied.isEmpty()) return
        val copy = ArrayList(held)
        held.clear()
        collapsedSlots = emptyArray()
        spinView = null
        loggedCollapseMiss = false
        collapseNudges = 0
        shiftLogged.clear()
        for (item in copy) restore(item)
        clearShift(parent = null, all = true)
    }

    /** Drop holds whose page has left the window. The visible page stays. */
    private fun pruneHeld() {
        if (held.isEmpty()) return
        var changed = false
        val keep = ArrayList<HeldDisc>(held.size)
        for (item in held) {
            if (item.view.isAttachedToWindow) keep.add(item) else {
                restore(item)
                changed = true
            }
        }
        if (!changed) return
        held.clear()
        held.addAll(keep)
        publishCollapsed()
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
        clearShift(view.parent, all = false)
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
        var naturalHeight: Int = 0,
    )

    /**
     * Point the proxy, the click target, and the spin/pause baseline at [to].
     * The previous cover stays in the tree; only the binding changes.
     */
    private fun rebindDisc(host: View, from: View?, to: View) {
        discRef = WeakReference(to)
        artKey = 0
        artTriedKey = 0
        artTriedAt = 0L
        lastLiveRotation = Float.NaN
        videoStamp = Long.MIN_VALUE
        videoStampAt = 0L
        spinView = to
        host.invalidate()
        val fromName = from?.let {
            "${it.javaClass.simpleName}#${System.identityHashCode(it).toString(16)}"
        } ?: "none"
        Xposed.log(
            "[OfflineGlass][BottomSlot] disc rebind=${to.javaClass.simpleName}" +
                "#${System.identityHashCode(to).toString(16)} ${to.width}x${to.height} " +
                "parent=${to.parent?.javaClass?.simpleName} from=$fromName",
        )
    }

    /** Remember a disc-row height. A column-sized measure is not the hole we close. */
    private fun rememberFreed(view: View, natural: Int) {
        val cap = (view.resources.displayMetrics.density * 160f).toInt().coerceAtLeast(1)
        if (natural <= 0 || natural > cap) return
        val parentH = (view.parent as? View)?.height ?: 0
        if (parentH > 0 && natural >= parentH * 0.6f) return
        for (item in held) {
            if (item.collapse && item.view === view && natural > item.naturalHeight) {
                item.naturalHeight = natural
                return
            }
        }
    }

    /**
     * Height 0 does not move siblings in a ConstraintLayout or FrameLayout that
     * pins each child. When the hole under the last button is still about the
     * disc row, shift those siblings down by that freed height.
     */
    private fun settleShift(slot: View) {
        val parent = slot.parent as? ViewGroup ?: return
        val item = held.firstOrNull { it.collapse && it.view === slot } ?: return
        val cap = (slot.resources.displayMetrics.density * 160f).toInt().coerceAtLeast(1)
        val freed = item.naturalHeight.coerceAtMost(cap)
        if (freed <= 0) return
        val above = ArrayList<View>(parent.childCount)
        var lowest = Int.MIN_VALUE
        val slotAnchor = if (slot.height > 0) slot.top + slot.height / 2 else slot.top
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) ?: continue
            if (child === slot || child.visibility == View.GONE) continue
            if (parent.height > 0 && child.height > parent.height * 0.6f) continue
            val center = (child.top + child.bottom) / 2
            if (center >= slotAnchor) continue
            above.add(child)
            if (child.bottom > lowest) lowest = child.bottom
        }
        val gap = if (lowest == Int.MIN_VALUE) 0 else parent.height - parent.paddingBottom - lowest
        val shift = if (above.isNotEmpty() && gap > 8 && gap + 4 >= freed / 2) minOf(gap, freed) else 0
        applyShift(parent, above, shift, freed)
    }

    private fun applyShift(parent: ViewGroup, above: List<View>, shift: Int, freed: Int) {
        val wanted = shift.toFloat()
        val keep = HashSet<View>(above.size)
        for (child in above) {
            keep.add(child)
            val already = shiftApplied[child] ?: 0f
            val base = child.translationY - already
            shiftApplied[child] = wanted
            val next = base + wanted
            if (child.translationY != next) child.translationY = next
        }
        val stale = ArrayList<View>()
        for (view in shiftApplied.keys) {
            if (view.parent === parent && !keep.contains(view)) stale.add(view)
        }
        for (view in stale) {
            val already = shiftApplied.remove(view) ?: continue
            view.translationY = view.translationY - already
        }
        val id = System.identityHashCode(parent)
        val previous = shiftLogged[id]
        if (shift > 0 && previous != shift) {
            shiftLogged[id] = shift
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc collapse=translate parent=${parent.javaClass.simpleName} " +
                    "siblings=${above.size} shift=$shift freed=$freed",
            )
        } else if (shift == 0 && previous != null && previous > 0) {
            shiftLogged.remove(id)
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc collapse=reflow parent=${parent.javaClass.simpleName} freed=$freed",
            )
        }
    }

    /** Remove the translation this module added. [all] clears every page. */
    private fun clearShift(parent: android.view.ViewParent?, all: Boolean) {
        val stale = ArrayList<View>()
        for (view in shiftApplied.keys) {
            if (all || view.parent === parent) stale.add(view)
        }
        for (view in stale) {
            val already = shiftApplied.remove(view) ?: continue
            view.translationY = view.translationY - already
        }
    }

    /**
     * Cover on the selected feed page. A collapsed disc has no height, so the
     * score uses the page's visible area and then how close the cover sits to
     * the middle of the screen. Adjacent pager pages lose.
     */
    private fun findDisc(root: View): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        var best: View? = null
        var bestScore = 0L
        var goneOnPage: View? = null
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (view.javaClass.simpleName == "MusicCoverView") {
                val score = pageScore(view)
                if (score > bestScore) {
                    best = view
                    bestScore = score
                } else if (goneOnPage == null && view.visibility == View.GONE &&
                    view.isAttachedToWindow && ownsScreenCenter(view)
                ) {
                    goneOnPage = view
                }
            }
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    view.getChildAt(index)?.let(stack::add)
                }
            }
        }
        return best ?: goneOnPage
    }

    /** Innermost near-fullscreen ancestor: the feed page, not the window decor. */
    private fun pageAncestor(view: View): View? {
        val metrics = view.resources.displayMetrics
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        if (sw <= 0 || sh <= 0) return null
        var node: View = view
        var depth = 0
        while (depth++ < 14) {
            val parent = node.parent as? View ?: return null
            if (parent is GlassHostLayout) return null
            if (parent.width >= sw * 0.75f && parent.height >= sh * 0.55f) return parent
            node = parent
        }
        return null
    }

    private fun ownsScreenCenter(view: View): Boolean {
        if (!view.isAttachedToWindow) return false
        val page = pageAncestor(view) ?: view
        val metrics = view.resources.displayMetrics
        val loc = IntArray(2)
        page.getLocationOnScreen(loc)
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        return cx >= loc[0] && cx < loc[0] + page.width &&
            cy >= loc[1] && cy < loc[1] + page.height
    }

    /**
     * Higher for the page that covers more of the screen. The disc's own height
     * is not required, so a collapsed cover on the selected page still wins.
     * Distance to the screen center breaks a tie when every cover shares one ancestor.
     */
    private fun pageScore(view: View): Long {
        if (!view.isAttachedToWindow || view.visibility == View.GONE) return 0L
        val page = pageAncestor(view) ?: view
        val metrics = view.resources.displayMetrics
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        if (sw <= 0 || sh <= 0) return 0L
        val loc = IntArray(2)
        page.getLocationOnScreen(loc)
        val visW = minOf(loc[0] + page.width, sw) - maxOf(loc[0], 0)
        val visH = minOf(loc[1] + page.height, sh) - maxOf(loc[1], 0)
        if (visW <= 0 || visH <= 0) return 0L
        val discLoc = IntArray(2)
        view.getLocationOnScreen(discLoc)
        val discW = max(view.width, 1)
        val discH = max(view.height, 1)
        val discCx = discLoc[0] + discW / 2
        val pageW = max(page.width, 1)
        if (discCx < loc[0] + pageW * 0.55f || discCx > loc[0] + pageW) return 0L
        val discTop = discLoc[1]
        if (discTop + discH < loc[1] || discTop > loc[1] + page.height) return 0L
        val discCy = discLoc[1] + discH / 2
        val dy = abs(discCy - sh / 2)
        return visW.toLong() * visH.toLong() * 1_000L - dy.toLong()
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
