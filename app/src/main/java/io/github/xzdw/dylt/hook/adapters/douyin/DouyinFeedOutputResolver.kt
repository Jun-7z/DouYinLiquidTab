package io.github.xzdw.dylt.hook.adapters.douyin

import java.io.File
import java.util.zip.ZipFile

/**
 * Locates the feed-output factory without a per-version class table.
 *
 * Douyin re-mints the obfuscated factory name on every build (X.0wMt on 40.6,
 * X.1KBI on 40.7, X.0GO1 later, X.1OKa on 41.0.0, X.1HiJ on 41.1.0) and recycles
 * the old names onto unrelated classes. The static field names below are part of
 * Douyin's own source, so they survive that remap. The factory method is whatever
 * `(ViewGroup, boolean)` method on that class returns the class itself, and the
 * feed scope method is the `FeedVideoPlayerView` method that calls it.
 *
 * Checked against the 41.0.0 arm64 package (versionCode 410000, update 41000100)
 * and the 41.1.0 official arm64 package (versionCode 410101, update 41109900).
 */
internal object DouyinFeedOutputResolver {
    data class Target(
        val factoryClass: String,
        val factoryMethod: String,
        val feedScopeMethod: String?,
        val booleanSwitches: List<String>,
    )

    private val anchorField = "sIsEnableSurfaceviewForGeneralDevice".toByteArray()
    private const val FEED_CLASS = "Lcom/ss/android/ugc/aweme/feed/ui/FeedVideoPlayerView;"
    private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
    private val historicalSwitches = setOf("LJIJJ", "LJJI", "LJJIIJZLJL")

    fun resolve(classLoader: ClassLoader): Target? {
        for (path in apkPaths(classLoader)) {
            resolveApk(File(path))?.let { return it }
        }
        return null
    }

    /** Visible to the offline check; the hook calls [resolve]. */
    fun resolveApk(apk: File): Target? {
        if (!apk.isFile) return null
        return runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("classes\\d*\\.dex")) }
                    .toList()
                for (entry in entries) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    if (indexOf(bytes, anchorField) < 0) continue
                    parse(bytes)?.let { return it }
                }
                null
            }
        }.getOrNull()
    }

    private fun apkPaths(classLoader: ClassLoader): List<String> {
        val paths = LinkedHashSet<String>()
        runCatching {
            val base = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathList = base.getDeclaredField("pathList").apply { isAccessible = true }.get(classLoader)
                ?: return@runCatching
            val elements = pathList.javaClass.getDeclaredField("dexElements")
                .apply { isAccessible = true }
                .get(pathList) as? Array<*>
                ?: return@runCatching
            for (element in elements) {
                if (element == null) continue
                element.javaClass.declaredFields
                    .filter { it.type == File::class.java }
                    .forEach { field ->
                        field.isAccessible = true
                        val file = field.get(element) as? File ?: return@forEach
                        if (file.isFile && file.name.endsWith(".apk")) paths += file.path
                    }
            }
        }
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            val app = thread.getDeclaredMethod("currentApplication").invoke(null) ?: return@runCatching
            val info = app.javaClass.getMethod("getApplicationInfo").invoke(app) ?: return@runCatching
            val source = info.javaClass.getField("sourceDir").get(info) as? String
            if (!source.isNullOrEmpty()) paths += source
            @Suppress("UNCHECKED_CAST")
            val splits = info.javaClass.getField("splitSourceDirs").get(info) as? Array<String>
            splits?.forEach { paths += it }
        }
        return paths.toList()
    }

    private fun parse(data: ByteArray): Target? {
        if (data.size < 0x70 || data[0] != 'd'.code.toByte()) return null
        val stringIdsSize = u32(data, 0x38)
        val stringIdsOff = u32(data, 0x3C)
        val typeIdsOff = u32(data, 0x44)
        val protoIdsOff = u32(data, 0x4C)
        val fieldIdsSize = u32(data, 0x50)
        val fieldIdsOff = u32(data, 0x54)
        val methodIdsSize = u32(data, 0x58)
        val methodIdsOff = u32(data, 0x5C)
        val classDefsSize = u32(data, 0x60)
        val classDefsOff = u32(data, 0x64)

        val anchorIdx = (0 until stringIdsSize).firstOrNull { stringAt(data, stringIdsOff, it) == "sIsEnableSurfaceviewForGeneralDevice" }
            ?: return null
        val anchorFields = HashSet<Int>()
        for (i in 0 until fieldIdsSize) {
            val nameIdx = u32(data, fieldIdsOff + i * 8 + 4)
            if (nameIdx == anchorIdx) anchorFields += i
        }
        if (anchorFields.isEmpty()) return null

        var found: Target? = null
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val desc = typeAt(data, typeIdsOff, stringIdsOff, u32(data, base))
            val classDataOff = u32(data, base + 24)
            if (classDataOff == 0 || '$' in desc) continue
            val hit = runCatching {
                readClass(
                    data, desc, classDataOff, stringIdsOff, typeIdsOff, protoIdsOff,
                    fieldIdsOff, methodIdsOff, methodIdsSize, anchorFields,
                )
            }.getOrNull() ?: continue
            if (hit.feedScopeMethod != null) return hit
            if (found == null) found = hit
        }
        return found
    }

    private fun readClass(
        data: ByteArray,
        desc: String,
        classDataOff: Int,
        stringIdsOff: Int,
        typeIdsOff: Int,
        protoIdsOff: Int,
        fieldIdsOff: Int,
        methodIdsOff: Int,
        methodIdsSize: Int,
        anchorFields: Set<Int>,
    ): Target? {
        var cursor = classDataOff
        val (staticCount, c1) = uleb(data, cursor)
        val (instanceCount, c2) = uleb(data, c1)
        val (directCount, c3) = uleb(data, c2)
        val (virtualCount, c4) = uleb(data, c3)
        cursor = c4
        var ownsAnchor = false
        // Static and instance field lists are indexed separately; the first diff of each
        // list is an absolute field_ids index.
        var fieldIndex = 0
        repeat(staticCount + instanceCount) { index ->
            if (index == staticCount) fieldIndex = 0
            val (diff, next) = uleb(data, cursor)
            val (_, afterAcc) = uleb(data, next)
            cursor = afterAcc
            fieldIndex += diff
            if (index < staticCount && fieldIndex in anchorFields) ownsAnchor = true
        }
        if (!ownsAnchor) return null

        data class MethodInfo(val name: String, val ret: String, val params: List<String>, val idx: Int, val code: Int)
        val methods = ArrayList<MethodInfo>(directCount + virtualCount)
        var methodIndex = 0
        repeat(directCount + virtualCount) { index ->
            if (index == directCount) methodIndex = 0
            val (diff, next) = uleb(data, cursor)
            val (_, afterAcc) = uleb(data, next)
            val (code, afterCode) = uleb(data, afterAcc)
            cursor = afterCode
            methodIndex += diff
            if (methodIndex !in 0 until methodIdsSize) return null
            val methodBase = methodIdsOff + methodIndex * 8
            val name = stringAt(data, stringIdsOff, u32(data, methodBase + 4))
            val proto = u16(data, methodBase + 2)
            val protoBase = protoIdsOff + proto * 12
            val ret = typeAt(data, typeIdsOff, stringIdsOff, u32(data, protoBase + 4))
            val paramsOff = u32(data, protoBase + 8)
            val params = if (paramsOff == 0) emptyList() else {
                val size = u32(data, paramsOff)
                (0 until size).map { typeAt(data, typeIdsOff, stringIdsOff, u16(data, paramsOff + 4 + it * 2)) }
            }
            methods += MethodInfo(name, ret, params, methodIndex, code)
        }
        val factory = methods.firstOrNull {
            it.params == listOf(VIEW_GROUP, "Z") && it.ret == desc
        } ?: return null
        val feedScope = feedScopeMethod(
            data, stringIdsOff, protoIdsOff, methodIdsOff, methodIdsSize,
            classDefsSize(data), classDefsOff(data), typeIdsOff, factory.idx,
        )
        val switches = methods.mapNotNull { method ->
            if (method.ret != "Z" || method.params.isNotEmpty()) return@mapNotNull null
            if (method.name in historicalSwitches || readsAnchor(data, method.code, anchorFields)) method.name else null
        }.distinct()
        return Target(
            factoryClass = desc.removePrefix("L").removeSuffix(";").replace('/', '.'),
            factoryMethod = factory.name,
            feedScopeMethod = feedScope,
            booleanSwitches = switches,
        )
    }

    private fun classDefsSize(data: ByteArray) = u32(data, 0x60)
    private fun classDefsOff(data: ByteArray) = u32(data, 0x64)

    private fun feedScopeMethod(
        data: ByteArray,
        stringIdsOff: Int,
        protoIdsOff: Int,
        methodIdsOff: Int,
        methodIdsSize: Int,
        classDefsSize: Int,
        classDefsOff: Int,
        typeIdsOff: Int,
        factoryMethodIdx: Int,
    ): String? {
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * 32
            val desc = typeAt(data, typeIdsOff, stringIdsOff, u32(data, base))
            if (desc != FEED_CLASS) continue
            val classDataOff = u32(data, base + 24)
            if (classDataOff == 0) return null
            return runCatching {
                callerName(
                    data, classDataOff, stringIdsOff, protoIdsOff,
                    methodIdsOff, methodIdsSize, factoryMethodIdx,
                )
            }.getOrNull()
        }
        return null
    }

    private fun callerName(
        data: ByteArray,
        classDataOff: Int,
        stringIdsOff: Int,
        protoIdsOff: Int,
        methodIdsOff: Int,
        methodIdsSize: Int,
        factoryMethodIdx: Int,
    ): String? {
        var cursor = classDataOff
        val (staticCount, c1) = uleb(data, cursor)
        val (instanceCount, c2) = uleb(data, c1)
        val (directCount, c3) = uleb(data, c2)
        val (virtualCount, c4) = uleb(data, c3)
        cursor = c4
        repeat(staticCount + instanceCount) {
            val (_, next) = uleb(data, cursor)
            val (_, afterAcc) = uleb(data, next)
            cursor = afterAcc
        }
        var best: String? = null
        var methodIndex = 0
        repeat(directCount + virtualCount) { index ->
            if (index == directCount) methodIndex = 0
            val (diff, next) = uleb(data, cursor)
            val (_, afterAcc) = uleb(data, next)
            val (code, afterCode) = uleb(data, afterAcc)
            cursor = afterCode
            methodIndex += diff
            if (methodIndex !in 0 until methodIdsSize) return best
            if (!invokes(data, code, factoryMethodIdx)) return@repeat
            val name = stringAt(data, stringIdsOff, u32(data, methodIdsOff + methodIndex * 8 + 4))
            if (name == "<init>" || name == "<clinit>") return@repeat
            val proto = u16(data, methodIdsOff + methodIndex * 8 + 2)
            val paramsOff = u32(data, protoIdsOff + proto * 12 + 8)
            val noArgs = paramsOff == 0 || u32(data, paramsOff) == 0
            if (noArgs) return name
            if (best == null) best = name
        }
        return best
    }

    private fun readsAnchor(data: ByteArray, codeOff: Int, anchorFields: Set<Int>): Boolean {
        if (codeOff <= 0) return false
        val insnsSize = u32(data, codeOff + 12)
        val start = codeOff + 16
        val end = start + insnsSize * 2
        if (end > data.size || insnsSize < 0) return false
        var i = start
        while (i + 4 <= end) {
            val op = data[i].toInt() and 0xff
            if (op in 0x60..0x67 && u16(data, i + 2) in anchorFields) return true
            i += 2
        }
        return false
    }

    private fun invokes(data: ByteArray, codeOff: Int, methodIdx: Int): Boolean {
        if (codeOff <= 0) return false
        val insnsSize = u32(data, codeOff + 12)
        val start = codeOff + 16
        val end = start + insnsSize * 2
        if (end > data.size || insnsSize < 0) return false
        var i = start
        while (i + 4 <= end) {
            val op = data[i].toInt() and 0xff
            if (op == 0x6e || op == 0x6f || op == 0x70 || op == 0x71 || op == 0x72 ||
                op == 0x74 || op == 0x75 || op == 0x76 || op == 0x77 || op == 0x78
            ) {
                if (u16(data, i + 2) == methodIdx) return true
            }
            i += 2
        }
        return false
    }

    private fun typeAt(data: ByteArray, typeIdsOff: Int, stringIdsOff: Int, typeIdx: Int): String =
        stringAt(data, stringIdsOff, u32(data, typeIdsOff + typeIdx * 4))

    private fun stringAt(data: ByteArray, stringIdsOff: Int, idx: Int): String {
        val strOff = u32(data, stringIdsOff + idx * 4)
        val (_, cursor) = uleb(data, strOff)
        var end = cursor
        while (end < data.size && data[end] != 0.toByte()) end++
        if (end >= data.size) return ""
        if (end < 0) return ""
        return String(data, cursor, end - cursor, Charsets.UTF_8)
    }

    private fun u16(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xff) or
            ((data[off + 1].toInt() and 0xff) shl 8) or
            ((data[off + 2].toInt() and 0xff) shl 16) or
            ((data[off + 3].toInt() and 0xff) shl 24)

    private fun uleb(data: ByteArray, off: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var cursor = off
        while (cursor < data.size && shift <= 28) {
            val b = data[cursor].toInt() and 0xff
            cursor++
            result = result or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return result to cursor
            shift += 7
        }
        error("truncated uleb128")
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || data.size < needle.size) return -1
        val last = data.size - needle.size
        var i = 0
        while (i <= last) {
            var matched = true
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
            i++
        }
        return -1
    }
}
