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
import kotlin.math.min

/**
 * The pill is always 首页 / 朋友 / 消息 / 我. 「显示加号」 shows or hides the original
 * publish circle. 「右侧按钮」 adds a second circle of that same size to its right;
 * today that circle is the music-disc proxy.
 *
 * The real MusicCoverView stays in the feed item. Reparenting it into the glass host
 * pulls it out of the recycled page that loads the cover, and a translation does not
 * move it either: on 41.x the disc sits in a strip at the bottom of each page, the bar
 * lies below the page's own bounds (the page clips it), the glass is painted over page
 * content, and the page scrolls while the bar stays put.
 *
 * The proxy rebinds to whichever cover belongs to the page currently on screen and
 * re-reads that cover every [ART_REFRESH_MS], so an image that finishes loading after
 * the swipe still reaches the bar. When the page has no `MusicCoverView` at all (dyyds
 * 隐藏转盘 removes it), the cover comes from the video's own data instead, see
 * [DouyinMusicCover], and a tap opens the music page directly. A video without music
 * shows the empty circle.
 * Every attached page's disc is held transparent, not only the selected one, and
 * Douyin's own views on the disc spot (the 听抖音 / 听合集 / 玩同款 badge, the second
 * cover row) go to alpha 0 with it. Hiding is alpha only; visibility belongs to Douyin
 * and to any module (dyyds) that hides the disc itself.
 *
 * Where the disc is in the narrow action column, the nearest small ancestor in that
 * column stays attached so the cover drawable keeps loading, but that slot
 * measures to height 0. A view wider than about 40% of the screen or taller than about
 * half of it is never collapsed or moved. If the column then still leaves a hole, only
 * the narrow buttons above the disc shift down. The circle spins on its own.
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
    /** Re-read the bound cover this often; Fresco swaps the image inside one drawable. */
    private const val ART_REFRESH_MS = 500L
    /** Walks in a row that found no cover before the circle lets go of the last one. */
    private const val MISS_LIMIT = 3
    /** Disc row we are willing to collapse. Larger than this is not the disc slot. */
    private const val SLOT_MAX_W = 0.35f
    private const val SLOT_MAX_H = 0.30f
    /** Hard stop. A view this big is the page, the pager, or the video. */
    private const val TOUCH_MAX_W = 0.40f
    private const val TOUCH_MAX_H = 0.50f
    /** The action column itself. Tall is normal; wide means we climbed too far. */
    private const val COLUMN_MAX_W = 0.35f

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
    /** What the bar draws. Replaced only by a render that differs, so it never blanks. */
    private var art: Bitmap? = null
    /** Render target, swapped with [art] when the new picture differs. */
    private var spare: Bitmap? = null
    private var artKey = 0
    private var artPrint = 0L
    private var artAt = 0L
    private var artTriedKey = 0
    private var artTriedAt = 0L
    private var loggedMode = ""
    private var loggedDisc = ""
    private var loggedPlaying: Boolean? = null
    private var lastLiveRotation = Float.NaN
    private var spinView: View? = null
    private var watchPosted = false
    private var collapseHooksInstalled = false
    private var videoStamp = Long.MIN_VALUE
    private var videoStampAt = 0L
    private var hostRef: WeakReference<View>? = null
    private val hiddenBadges = WeakHashMap<View, Int>()
    private val badgeLogged = HashSet<String>()
    /** Discs we keep transparent. Their pre-draw listener puts alpha 0 back before each draw. */
    private val forced = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
    )
    private val held = ArrayList<HeldDisc>(4)
    /**
     * Douyin views drawn on the disc spot, held at alpha 0. The value is the alpha they
     * had when muted, put back when the right circle goes away.
     */
    private val muted = WeakHashMap<View, Float>()
    private val mutedLogged = HashSet<String>()
    /** Every cover the last walk found, one per attached page. Reused between walks. */
    private val pageDiscs = ArrayList<View>(4)
    /** Consecutive walks that found no cover on the page on screen. */
    private var misses = 0
    /** Cover read from the video data while the page has no disc view. Never drawn into. */
    private var dataArt: Bitmap? = null
    private var dataKey: String? = null
    private var dataAt = 0L
    private var loggedData: Boolean? = null

    /**
     * Column slots currently forced to a 0 measured height. Replaced as a whole so the
     * measure hook can scan it without taking a lock.
     */
    @Volatile private var collapsedSlots: Array<View> = emptyArray()
    private var collapseLayoutAt = 0L
    private var collapseSeekAt = 0L
    private var collapseNudges = 0
    private var loggedCollapseMiss = false
    private var unsafeLog = ""
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

    fun discArt(): Bitmap? =
        (if (discRef != null) art else dataArt)?.takeIf { !it.isRecycled && it.width > 0 }

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

    /**
     * Collapse support for a disc that sits in the narrow action column. These are
     * View-wide hooks, and every hooked method is deoptimized, so each `measure` and
     * `setLayoutParams` call in the whole app would run interpreted plus a dispatch.
     * Installed on the first real collapse only; a disc in the bottom strip (41.x)
     * never installs them. Alpha needs no hook: the pre-draw listeners put alpha 0 back
     * after Douyin's own fades and before the frame draws. Visibility is never touched:
     * Douyin fades the disc in through INVISIBLE, and other modules (dyyds 隐藏转盘) hide it
     * the same way.
     */
    private fun installCollapseHooks() {
        if (collapseHooksInstalled) return
        collapseHooksInstalled = true
        runCatching {
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
                    val measuredW = view.measuredWidth
                    if (overSlotBudget(view, measuredW, natural)) {
                        logUnsafe(
                            "measure ${view.javaClass.simpleName} ${measuredW}x$natural",
                        )
                        return
                    }
                    if (natural > 0) rememberFreed(view, natural)
                    if (natural == 0) return
                    runCatching { setMeasuredDimension(view, measuredW, 0) }
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
            dataKey = null
            dataArt = null
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
        releaseOversized()
        val cached = discRef?.get()?.takeIf { coverBoundsOk(it) }
        if (cached == null) discRef = null
        // The collapsed cover has no height and the previous page stays attached,
        // so "still attached" is not "still the selected video". Re-resolve when
        // that page no longer owns the screen, and on a short timer otherwise.
        val pageChanged = cached == null || !cached.isAttachedToWindow || !ownsScreenCenter(cached)
        // A page with no cover (图文, live, ads) must not keep the 50 ms walk running.
        val fast = pageChanged && misses < MISS_LIMIT
        val due = now - lookedAt >= if (fast) RESCAN_MS else LOOK_MS
        if (due) {
            lookedAt = now
            val found = findDisc(root)?.takeIf { coverBoundsOk(it) }
            misses = if (found == null) (misses + 1).coerceAtMost(MISS_LIMIT) else 0
            if (found != null && found !== cached) {
                rebindDisc(host, cached, found)
            } else if (found == null && pageChanged && misses >= MISS_LIMIT) {
                unbindDisc(host)
            }
            if (showRight) holdPages()
            pageDiscs.clear()
        }
        val view = discRef?.get()
        if (view == null) {
            if (showRight) ensureDataArt(host, now)
            return
        }
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
            muteOverlays(anchorOf(view))
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
            val context = hostRef?.get()?.context
            if (context != null && DouyinMusicCover.openMusic(context)) {
                Xposed.log("[OfflineGlass][BottomSlot] disc click=music page")
                return true
            }
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

    /**
     * No disc view on the page: take the cover from the video data. Resolved every
     * [LOOK_MS]; a new video clears the circle until its own cover arrives, so the bar
     * never shows the previous video's art.
     */
    private fun ensureDataArt(host: View, now: Long) {
        if (now - dataAt < LOOK_MS) return
        dataAt = now
        val aweme = DouyinMusicCover.currentAweme()
        val urls = aweme?.let { runCatching { DouyinMusicCover.coverUrls(it) }.getOrNull() }.orEmpty()
        val key = urls.firstOrNull()
        val found = aweme != null
        if (loggedData != found) {
            loggedData = found
            Xposed.log("[OfflineGlass][BottomSlot] disc source=data video=$found cover=${key != null}")
        }
        if (key == dataKey) return
        dataKey = key
        dataArt = key?.let(DouyinMusicCover::cached)
        host.invalidate()
        if (key == null || dataArt != null) return
        DouyinMusicCover.load(urls, ART_PX) { loaded, bitmap ->
            if (loaded == dataKey && discRef == null) {
                dataArt = bitmap
                hostRef?.get()?.invalidate()
            }
        }
    }

    /**
     * Re-read the cover every [ART_REFRESH_MS]. Fresco keeps one root drawable per view and
     * swaps the image inside it, so a recycled page, or a cover that finishes loading after
     * the swipe, does not change the identity key. A render equal to what the bar shows is
     * dropped without an invalidate, and a blank one never replaces it.
     */
    private fun ensureArt(view: View, now: Long) {
        val drawable = coverDrawable(view)
        val key = System.identityHashCode(view) * 31 + System.identityHashCode(drawable)
        val same = key == artKey && art != null
        if (same) {
            if (now - artAt < ART_REFRESH_MS) return
            artAt = now
        } else {
            if (key == artTriedKey && now - artTriedAt < LOOK_MS) return
            artTriedKey = key
            artTriedAt = now
        }
        val drawn = if (canDraw(view)) renderView(view) else null
        val bitmap = drawn ?: drawable?.let(::renderDrawable) ?: return
        artKey = key
        artAt = now
        val print = fingerprint(bitmap)
        if (art != null && print == artPrint) return
        spare = art
        art = bitmap
        artPrint = print
        hostRef?.get()?.invalidate()
        val source = if (drawn != null) "draw" else "drawable"
        Xposed.log(
            "[OfflineGlass][BottomSlot] disc art=$source${if (same) " refresh" else ""} " +
                "vis=${view.visibility} alpha=${view.alpha}",
        )
    }

    /** 64 sampled pixels: enough to tell two covers, or a placeholder and its image, apart. */
    private fun fingerprint(bitmap: Bitmap): Long {
        var hash = 17L
        var y = 10
        while (y < bitmap.height) {
            var x = 10
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += 20
            }
            y += 20
        }
        return hash
    }

    /**
     * Alpha is applied by the parent, not by [View.draw], so a disc we forced to
     * alpha 0 still paints its cover into the proxy bitmap.
     */
    private fun canDraw(view: View): Boolean =
        view.visibility != View.GONE && view.width > 0 && view.height > 0

    private fun renderView(view: View): Bitmap? {
        val bitmap = obtainSpare()
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
        val bitmap = obtainSpare()
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

    private fun obtainSpare(): Bitmap {
        val current = spare
        if (current != null && !current.isRecycled && current.width == ART_PX && current.height == ART_PX) {
            return current
        }
        return Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888).also { spare = it }
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
     * Nearest ancestor of [disc] that is still the disc row: at most about 35% of
     * the screen wide and 30% tall, whose parent is the narrow column on the
     * right. Stops at the first view that is larger than that. A full-screen
     * page is not a column, and this never returns one of its children.
     */
    private fun columnSlot(disc: View): View? {
        var node: View = disc
        var depth = 0
        while (depth++ < 8) {
            val parent = node.parent as? ViewGroup ?: return null
            if (parent is GlassHostLayout) return null
            if (node !== disc) {
                if (exceedsTouchLimit(node)) {
                    logUnsafe("ancestor ${node.javaClass.simpleName} ${node.width}x${node.height}")
                    return null
                }
                if (withinSlot(node) && narrowColumn(parent)) return node
                val w = max(node.width, node.measuredWidth)
                val h = max(node.height, node.measuredHeight)
                if (w > 0 && h > 0 && !withinSlot(node)) {
                    logUnsafe("slot ${node.javaClass.simpleName} ${w}x$h")
                    return null
                }
            }
            node = parent
        }
        return null
    }

    /** Wider than ~40% of the screen, or taller than ~half of it. Unknown size is not "too big". */
    private fun exceedsTouchLimit(view: View): Boolean {
        val sw = screenW(view)
        val sh = screenH(view)
        if (sw <= 0 || sh <= 0) return true
        val w = max(view.width, view.measuredWidth)
        val h = max(view.height, view.measuredHeight)
        if (w <= 0 && h <= 0) return false
        return w > sw * TOUCH_MAX_W || h > sh * TOUCH_MAX_H
    }

    /** Laid-out disc row. Height 0 is the collapsed slot; a missing width is not. */
    private fun withinSlot(view: View): Boolean {
        val sw = screenW(view)
        val sh = screenH(view)
        if (sw <= 0 || sh <= 0) return false
        val w = max(view.width, view.measuredWidth)
        val h = max(view.height, view.measuredHeight)
        if (w <= 0) return false
        if (w > sw * SLOT_MAX_W || h > sh * SLOT_MAX_H) return false
        return !exceedsTouchLimit(view)
    }

    /** Measured size we must not force to height 0. */
    private fun overSlotBudget(view: View, width: Int, height: Int): Boolean {
        val sw = screenW(view)
        val sh = screenH(view)
        if (sw <= 0 || sh <= 0) return true
        if (width > sw * TOUCH_MAX_W || height > sh * TOUCH_MAX_H) return true
        return width > sw * SLOT_MAX_W || height > sh * SLOT_MAX_H
    }

    /**
     * The right-hand action column. It may be tall. It may not be wide, and it
     * has to sit on the right half. This view itself is never collapsed.
     */
    private fun narrowColumn(group: ViewGroup): Boolean {
        val sw = screenW(group)
        if (sw <= 0 || group.width <= 0 || group.childCount < 2) return false
        if (group.width > sw * COLUMN_MAX_W) return false
        val loc = IntArray(2)
        group.getLocationOnScreen(loc)
        if (loc[0] < sw * 0.45f || loc[0] >= sw) return false
        val center = loc[0] + group.width / 2f
        return center >= sw * 0.50f
    }

    private fun stillSafeSlot(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        return withinSlot(view) && narrowColumn(parent) && !exceedsTouchLimit(view)
    }

    /** A cover we are willing to mirror. A full-screen view is not a disc. */
    private fun coverBoundsOk(view: View): Boolean {
        if (!view.isAttachedToWindow || exceedsTouchLimit(view)) return false
        val sw = screenW(view)
        val sh = screenH(view)
        if (sw <= 0 || sh <= 0) return false
        val w = max(view.width, view.measuredWidth)
        val h = max(view.height, view.measuredHeight)
        if (w <= 0 || w > sw * SLOT_MAX_W || h > sh * SLOT_MAX_H) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val cx = loc[0] + w / 2
        return cx >= sw * 0.55f && cx <= sw
    }

    private fun narrowSibling(child: View): Boolean {
        if (child.visibility == View.GONE || exceedsTouchLimit(child)) return false
        val sw = screenW(child)
        val sh = screenH(child)
        if (sw <= 0 || sh <= 0 || child.width <= 0 || child.height <= 0) return false
        if (child.width > sw * SLOT_MAX_W || child.height > sh * TOUCH_MAX_H) return false
        val loc = IntArray(2)
        child.getLocationOnScreen(loc)
        if (loc[0] < sw * 0.45f || loc[0] >= sw) return false
        val center = loc[0] + child.width / 2f
        return center >= sw * 0.50f
    }

    private fun screenW(view: View) = view.resources.displayMetrics.widthPixels

    private fun screenH(view: View) = view.resources.displayMetrics.heightPixels

    private fun logUnsafe(detail: String) {
        if (detail == unsafeLog) return
        unsafeLog = detail
        Xposed.log("[OfflineGlass][BottomSlot] disc collapse=unsafe $detail")
    }

    /** Drop a hold that turned out to be bigger than the disc row. */
    private fun releaseOne(view: View) {
        val item = held.firstOrNull { it.view === view } ?: return
        held.remove(item)
        publishCollapsed()
        restore(item)
    }

    /** Undo a collapse or a shift that is no longer inside the right column. */
    private fun releaseOversized() {
        val bad = ArrayList<HeldDisc>()
        for (item in held) {
            val oversized = exceedsTouchLimit(item.view)
            val badSlot = item.collapse && !stillSafeSlot(item.view)
            if (oversized || badSlot) bad.add(item)
        }
        if (bad.isNotEmpty()) {
            for (item in bad) held.remove(item)
            publishCollapsed()
            for (item in bad) {
                logUnsafe(
                    "release ${item.view.javaClass.simpleName} " +
                        "${item.view.width}x${item.view.height}",
                )
                restore(item)
            }
        }
        val stale = ArrayList<View>()
        for (view in shiftApplied.keys) {
            if (!narrowSibling(view)) stale.add(view)
        }
        for (view in stale) {
            val already = shiftApplied.remove(view) ?: continue
            view.translationY = view.translationY - already
        }
    }

    /**
     * Transparent and still attached. Visibility stays with Douyin and with any module
     * that hides the disc itself. The column slot measures to height 0 so the buttons
     * above it move down. GONE is not used: that makes isShown() false and the cover
     * loader stops.
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
            if (exceedsTouchLimit(target)) {
                logUnsafe("hold ${target.javaClass.simpleName} ${target.width}x${target.height}")
                continue
            }
            val collapse = slot != null && target === slot && stillSafeSlot(target)
            if (target === slot && !collapse) continue
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
            if (target.alpha != 0f) target.alpha = 0f
            if (collapse) installCollapseHooks()
            if (collapse) squashSlot(item, rememberIncoming = false)
            Xposed.log(
                "[OfflineGlass][BottomSlot] disc hide=alpha0 ${target.javaClass.simpleName} " +
                    "wasVis=${item.visibility} wasAlpha=${item.alpha}" +
                    if (collapse) " collapse=height0" else "",
            )
        }
        publishCollapsed()
        if (slot == null) {
            if (!loggedCollapseMiss && unsafeLog.isEmpty()) {
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
        if (!stillSafeSlot(view)) return
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
        if (exceedsTouchLimit(view)) {
            logUnsafe("keep ${view.javaClass.simpleName} ${view.width}x${view.height}")
            releaseOne(view)
            return@OnPreDrawListener true
        }
        if (view.alpha != 0f) view.alpha = 0f
        val item = held.firstOrNull { it.collapse && it.view === view }
        if (item != null) {
            if (!stillSafeSlot(view)) {
                logUnsafe("keep ${view.javaClass.simpleName} ${view.width}x${view.height}")
                releaseOne(view)
                return@OnPreDrawListener true
            }
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
        restoreMuted()
        if (held.isEmpty() && collapsedSlots.isEmpty() && shiftApplied.isEmpty()) return
        val copy = ArrayList(held)
        held.clear()
        collapsedSlots = emptyArray()
        spinView = null
        loggedCollapseMiss = false
        collapseNudges = 0
        unsafeLog = ""
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
        if (!coverBoundsOk(to)) {
            logUnsafe("rebind ${to.javaClass.simpleName} ${to.width}x${to.height}")
            return
        }
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

    /** The page on screen has no cover (图文, live, ads): show the empty circle, not the last one. */
    private fun unbindDisc(host: View) {
        if (discRef == null && art == null) return
        discRef = null
        spinView = null
        lastLiveRotation = Float.NaN
        if (art != null) spare = art
        art = null
        artKey = 0
        artPrint = 0L
        host.invalidate()
        Xposed.log("[OfflineGlass][BottomSlot] disc unbind: no cover on the page on screen")
    }

    /** Hold every attached page's cover, so the next one never shows during a swipe. */
    private fun holdPages() {
        for (index in pageDiscs.indices) {
            val disc = pageDiscs[index]
            if (held.none { it.view === disc }) holdCurrent(disc)
            muteOverlays(anchorOf(disc))
        }
    }

    /** The view on the disc spot: the cover's container when it has one. */
    private fun anchorOf(disc: View): View {
        val parent = disc.parent as? View ?: return disc
        return if (parent.javaClass.simpleName == "MusicCoverContainerLayout") parent else disc
    }

    /**
     * Douyin also draws on the disc spot: the 听抖音 / 听合集 / 玩同款 badge is a
     * `ConstraintLayout` beside `MusicCoverContainerLayout`, and a second row in the same
     * bottom strip holds another cover circle. A view at most 2.5× the disc wide and 2×
     * tall that covers at least 40% of it goes to alpha 0 while the disc lives in the bar.
     * Visibility stays Douyin's. This runs on every pre-draw for the page on screen, so a
     * badge Douyin shows or fades in later is caught before it draws. Layout coordinates
     * only; nothing is allocated.
     */
    private fun muteOverlays(anchor: View) {
        val row = anchor.parent as? ViewGroup ?: return
        val w = anchor.width
        val h = anchor.height
        if (w <= 0 || h <= 0) return
        val left = anchor.left
        val top = anchor.top
        muteChildren(row, anchor, left, top, w, h)
        // The bottom strip is only as tall as its rows. A taller parent is the action
        // column or the page, and its other children are not on the disc.
        val strip = row.parent as? ViewGroup ?: return
        if (strip is GlassHostLayout || strip.height <= 0 || strip.height > h * 2) return
        val stripLeft = left + row.left
        val stripTop = top + row.top
        for (index in 0 until strip.childCount) {
            val sibling = strip.getChildAt(index) ?: continue
            if (sibling === row || sibling.visibility == View.GONE) continue
            if (sibling is ViewGroup && sibling.width > w * 5 / 2) {
                muteChildren(sibling, null, stripLeft - sibling.left, stripTop - sibling.top, w, h)
            } else {
                muteIfOver(sibling, stripLeft, stripTop, w, h)
            }
        }
    }

    private fun muteChildren(group: ViewGroup, skip: View?, left: Int, top: Int, w: Int, h: Int) {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index) ?: continue
            if (child !== skip) muteIfOver(child, left, top, w, h)
        }
    }

    private fun muteIfOver(view: View, left: Int, top: Int, w: Int, h: Int) {
        if (view.visibility == View.GONE) return
        if (muted.containsKey(view)) {
            // Douyin fades these in; alpha 0 goes back before this frame draws.
            if (view.alpha != 0f) view.alpha = 0f
            return
        }
        val vw = view.width
        val vh = view.height
        if (vw <= 0 || vh <= 0 || vw > w * 5 / 2 || vh > h * 2) return
        val ix = min(left + w, view.right) - max(left, view.left)
        val iy = min(top + h, view.bottom) - max(top, view.top)
        if (ix <= 0 || iy <= 0 || ix.toLong() * iy * 5 < w.toLong() * h * 2) return
        if (forced.contains(view) || exceedsTouchLimit(view)) return
        muted[view] = view.alpha
        view.alpha = 0f
        val name = view.javaClass.simpleName
        if (mutedLogged.add(name)) {
            Xposed.log("[OfflineGlass][BottomSlot] disc overlay=alpha0 $name ${vw}x$vh")
        }
    }

    private fun restoreMuted() {
        if (muted.isEmpty()) return
        val copy = HashMap(muted)
        muted.clear()
        mutedLogged.clear()
        for ((view, alpha) in copy) {
            if (view.alpha == 0f) view.alpha = alpha
        }
        Xposed.log("[OfflineGlass][BottomSlot] disc overlay=restore ${copy.size}")
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
        if (!stillSafeSlot(slot) || !narrowColumn(parent)) {
            clearShift(parent, all = false)
            return
        }
        val item = held.firstOrNull { it.collapse && it.view === slot } ?: return
        val capPx = (slot.resources.displayMetrics.density * 160f).toInt().coerceAtLeast(1)
        val capFrac = (screenH(slot) * SLOT_MAX_H).toInt().coerceAtLeast(1)
        val freed = item.naturalHeight.coerceAtMost(minOf(capPx, capFrac))
        if (freed <= 0) return
        val above = ArrayList<View>(parent.childCount)
        var lowest = Int.MIN_VALUE
        val slotAnchor = if (slot.height > 0) slot.top + slot.height / 2 else slot.top
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) ?: continue
            if (child === slot || !narrowSibling(child)) continue
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
     * the middle of the screen. Adjacent pager pages lose. Every cover the walk
     * meets is also kept in [pageDiscs], so the caller can hold each page's disc.
     */
    private fun findDisc(root: View): View? {
        pageDiscs.clear()
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        var best: View? = null
        var bestScore = 0L
        while (stack.isNotEmpty() && visited++ < WALK_BUDGET) {
            val view = stack.removeLast()
            if (view is GlassHostLayout) continue
            if (view.javaClass.simpleName == "MusicCoverView" && coverBoundsOk(view)) {
                pageDiscs.add(view)
                val score = pageScore(view)
                if (score > bestScore) {
                    best = view
                    bestScore = score
                }
            }
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    view.getChildAt(index)?.let(stack::add)
                }
            }
        }
        return best
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
        if (!coverBoundsOk(view) || view.visibility == View.GONE) return 0L
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
