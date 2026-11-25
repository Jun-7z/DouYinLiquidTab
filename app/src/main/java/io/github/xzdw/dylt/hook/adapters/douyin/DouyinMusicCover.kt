package io.github.xzdw.dylt.hook.adapters.douyin

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.xzdw.dylt.hookx.MethodHook
import io.github.xzdw.dylt.hookx.Xposed
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The bottom circle's music cover, read from the video's own data instead of the feed's
 * `MusicCoverView`. Other modules (dyyds 隐藏转盘) remove that view outright, and then
 * there is nothing on the page to copy.
 *
 * The feed holders (`VideoViewHolder`, `FeedImageViewHolder`) expose `getAweme()`, and the
 * model keeps its Gson field names: `Aweme.music`, `Aweme.aid`, `Music.coverThumb` /
 * `coverMedium` / `coverLarge`, `Music.mid` / `id`, `UrlModel.getUrlList()`. Checked
 * against 41.3.0 (versionCode 410301). The holders are remembered from their `bind`, and
 * the one whose page covers the middle of the screen is the video on screen.
 *
 * The cover is fetched once per URL on a background thread and kept in a small cache.
 */
internal object DouyinMusicCover {
    private val HOLDER_CLASSES = listOf(
        "com.ss.android.ugc.aweme.feed.adapter.VideoViewHolder",
        "com.ss.android.ugc.aweme.feed.adapter.FeedImageViewHolder",
    )
    private val COVER_FIELDS = listOf("coverThumb", "coverMedium", "coverLarge")
    private const val MUSIC_DETAIL = "com.ss.android.ugc.aweme.music.ui.MusicDetailActivity"
    private const val MAX_HOLDERS = 8
    private const val CACHE_SIZE = 6
    private const val MAX_BYTES = 4 shl 20

    private var installed = false
    private val holders = ArrayList<WeakReference<Any>>(MAX_HOLDERS)
    private val viewFields = HashMap<Class<*>, Array<Field>>()
    private val awemeGetters = HashMap<Class<*>, Method?>()
    private val fields = HashMap<String, Field?>()
    private var urlList: Method? = null
    private val screenAt = IntArray(2)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dylt-disc-cover").apply { isDaemon = true }
    }
    /** Main thread only. */
    private val cache = object : LinkedHashMap<String, Bitmap>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > CACHE_SIZE
    }
    private val loading = HashSet<String>()
    private var loggedLoad = false
    private var loggedFail = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true
        for (name in HOLDER_CLASSES) {
            val type = runCatching { Xposed.findClass(name, classLoader) }.getOrNull()
            if (type == null) {
                Xposed.log("[OfflineGlass][MusicCover] holder missing $name")
                continue
            }
            Xposed.hookAllMethods(type, "bind", object : MethodHook() {
                override fun afterHookedMethod(param: MethodHook.MethodHookParam) {
                    remember(param.thisObject ?: return)
                }
            })
        }
    }

    private fun remember(holder: Any) {
        synchronized(holders) {
            holders.removeAll { it.get() == null }
            if (holders.any { it.get() === holder }) return
            if (holders.size >= MAX_HOLDERS) holders.removeAt(0)
            holders.add(WeakReference(holder))
        }
    }

    /** The `Aweme` of the page that covers the middle of the screen, or null. */
    fun currentAweme(): Any? {
        val snapshot = synchronized(holders) { holders.mapNotNull { it.get() } }
        for (holder in snapshot) {
            val page = pageOf(holder) ?: continue
            if (!coversCenter(page)) continue
            return awemeOf(holder)
        }
        return null
    }

    /** Cover URLs of [aweme]'s music, smallest first. Empty when the video has no music. */
    fun coverUrls(aweme: Any): List<String> {
        val music = field(aweme.javaClass, "music")?.get(aweme) ?: return emptyList()
        for (name in COVER_FIELDS) {
            val model = field(music.javaClass, name)?.get(music) ?: continue
            val urls = urlsOf(model)
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    /** Main thread. The cached cover for [key], if it has been fetched. */
    fun cached(key: String): Bitmap? = cache[key]?.takeIf { !it.isRecycled }

    /**
     * Fetches the first of [urls] that decodes, scaled so its short side is about [size],
     * and center-cropped square. [onLoaded] runs on the main thread with the cache key.
     */
    fun load(urls: List<String>, size: Int, onLoaded: (String, Bitmap) -> Unit) {
        val key = urls.firstOrNull() ?: return
        if (cache.containsKey(key) || !loading.add(key)) return
        worker.execute {
            var bitmap: Bitmap? = null
            var failure: Throwable? = null
            for (url in urls) {
                val result = runCatching { fetch(url, size) }
                bitmap = result.getOrNull()
                if (bitmap != null) break
                failure = result.exceptionOrNull() ?: failure
            }
            main.post {
                loading.remove(key)
                if (bitmap != null) {
                    cache[key] = bitmap
                    if (!loggedLoad) {
                        loggedLoad = true
                        Xposed.log("[OfflineGlass][MusicCover] cover loaded ${bitmap.width}x${bitmap.height}")
                    }
                    onLoaded(key, bitmap)
                } else if (!loggedFail) {
                    loggedFail = true
                    Xposed.log("[OfflineGlass][MusicCover] cover load failed: ${failure?.javaClass?.simpleName} ${failure?.message}")
                }
            }
        }
    }

    /**
     * Opens Douyin's music page for the video on screen. `MusicDetailActivityDataProxy`
     * reads `id` (the music) and `aweme_id` from the extras.
     */
    fun openMusic(context: Context): Boolean {
        val aweme = currentAweme() ?: return false
        val music = field(aweme.javaClass, "music")?.get(aweme) ?: return false
        val mid = (field(music.javaClass, "mid")?.get(music) as? String)?.takeIf { it.isNotEmpty() }
            ?: (field(music.javaClass, "id")?.get(music) as? Long)?.takeIf { it != 0L }?.toString()
            ?: return false
        val aid = field(aweme.javaClass, "aid")?.get(aweme) as? String
        val intent = Intent()
            .setClassName(context.packageName, MUSIC_DETAIL)
            .putExtra("id", mid)
            .putExtra("enter_method", "click_cover")
            .putExtra("previous_page", "homepage_hot")
        if (!aid.isNullOrEmpty()) intent.putExtra("aweme_id", aid)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure { Xposed.log("[OfflineGlass][MusicCover] open music failed: $it") }
            .isSuccess
    }

    private fun fetch(url: String, size: Int): Bitmap? {
        if (!url.startsWith("http")) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val shortSide = min(w, h)
            if (shortSide > size) {
                val scale = size.toFloat() / shortSide
                decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            }
        }
        val side = min(decoded.width, decoded.height)
        if (decoded.width == decoded.height) return decoded
        return Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    }

    /** The holder's page: its largest attached, shown View field that is about full screen. */
    private fun pageOf(holder: Any): View? {
        val candidates = viewFields.getOrPut(holder.javaClass) { collectViewFields(holder.javaClass) }
        var best: View? = null
        for (field in candidates) {
            val view = runCatching { field.get(holder) as? View }.getOrNull() ?: continue
            if (!view.isAttachedToWindow || !view.isShown) continue
            val metrics = view.resources.displayMetrics
            if (view.width < metrics.widthPixels * 0.75f || view.height < metrics.heightPixels * 0.55f) continue
            if (best == null || view.width * view.height > best.width * best.height) best = view
        }
        return best
    }

    private fun coversCenter(page: View): Boolean {
        val metrics = page.resources.displayMetrics
        page.getLocationOnScreen(screenAt)
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        return cx >= screenAt[0] && cx < screenAt[0] + page.width &&
            cy >= screenAt[1] && cy < screenAt[1] + page.height
    }

    private fun collectViewFields(type: Class<*>): Array<Field> {
        val out = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!View::class.java.isAssignableFrom(field.type)) continue
                if (runCatching { field.isAccessible = true }.isSuccess) out.add(field)
            }
            current = current.superclass
        }
        return out.toTypedArray()
    }

    private fun awemeOf(holder: Any): Any? {
        val getter = awemeGetters.getOrPut(holder.javaClass) {
            runCatching { holder.javaClass.getMethod("getAweme").apply { isAccessible = true } }.getOrNull()
        } ?: return null
        return runCatching { getter.invoke(holder) }.getOrNull()
    }

    private fun urlsOf(model: Any): List<String> {
        val method = urlList ?: runCatching { model.javaClass.getMethod("getUrlList") }.getOrNull()?.also { urlList = it }
            ?: return emptyList()
        val list = runCatching { method.invoke(model) as? List<*> }.getOrNull() ?: return emptyList()
        return list.mapNotNull { (it as? String)?.takeIf { url -> url.startsWith("http") } }
    }

    private fun field(type: Class<*>, name: String): Field? {
        val key = type.name + '#' + name
        if (fields.containsKey(key)) return fields[key]
        var current: Class<*>? = type
        var found: Field? = null
        while (current != null && found == null) {
            found = runCatching { current.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            current = current.superclass
        }
        fields[key] = found
        return found
    }
}
