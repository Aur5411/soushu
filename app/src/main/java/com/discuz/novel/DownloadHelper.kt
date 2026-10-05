package com.discuz.novel

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * 自研下载器（v1.8.0）：
 * - 带登录 Cookie + 桌面 UA + 帖子页 Referer + 浏览器导航头（Accept/Sec-Fetch 等，
 *   降低被站点 WAF 识别为非浏览器请求的概率）
 * - 手动跟随 HTTP 重定向（每跳都重新带上 Cookie）
 * - HTML 跳转页自动解析真实文件地址继续下载（meta refresh / location.href 等，最多 3 层）
 * - 仍拿到 HTML 时不再直接报错：通过 onHtmlFallback 回调切换「浏览器通道」
 *   （MainActivity 用 WebView 页面内 fetch() 下载，网络栈与真实浏览器完全一致）
 * - 文件名处理：响应头 Content-Disposition 始终优先；URL 段排除 .php 等脚本页；
 *   乱码修复（GBK/UTF-8）；去网站标记；兜底名按 MIME 补扩展名
 */
object DownloadHelper {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val MAX_DOWNLOAD_BYTES = 128L * 1024L * 1024L
    private const val MAX_PROBE_HTML_BYTES = 1024L * 1024L
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

    /**
     * @param onHtmlFallback 原生请求最终仍返回网页时回调（主线程），参数为原始下载地址；
     *        传 null 则直接抛错提示
     */
    fun start(
        ctx: Context, userAgent: String, url: String,
        contentDisposition: String?, referer: String?,
        onHtmlFallback: ((String) -> Unit)? = null,
        nameHint: String? = null
    ) {
        val appCtx = ctx.applicationContext
        thread {
            try {
                // 文件名优先级：帖子内文件名(nameHint) → 响应头 Content-Disposition → URL 末段兜底
                val presetName = resolveFileName(url, contentDisposition, nameHint)
                val finalName = download(appCtx, userAgent, url, presetName, referer, onHtmlFallback, nameHint)
                if (finalName != null) {
                    mainHandler.post {
                        Toast.makeText(appCtx, "下载完成：$finalName\n保存于 Download/${Prefs.getDownloadDir(appCtx)}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 原生通道把活交给了浏览器通道（返回 null 且已回调 onHtmlFallback）。
                    // 关键修复（v2.5.0）：这里以前完全静默——用户只看到「开始下载」就再无动静，
                    // 既没有成功提示也没有失败提示，无法判断到底发生了什么。现在明确告知。
                    mainHandler.post {
                        Toast.makeText(
                            appCtx,
                            "正在改用浏览器通道下载…\n若随后仍无「下载完成」提示，说明服务器返回的是网页（通常是未登录或权限不足）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(appCtx, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- 文件名处理 ----------------

    /**
     * 从 Content-Disposition / URL 中解析文件名，并做乱码修复 + 去网站标记。
     *
     * @param nameHint 帖子内附件显示的真实文件名（由注入脚本从 DOM 的
     *        `<span class="attachname">` 读出、按附件 id 上报）。**这是最可靠的来源**：
     *        点下载时 DownloadListener 传回的 contentDisposition 通常为 null，
     *        URL 又只是 `forum.php?mod=attachment&aid=...`（解析出来是脚本页名），
     *        只有帖子里的名字才是用户真正想要的文件名。
     */
    fun resolveFileName(url: String, contentDisposition: String?, nameHint: String? = null): String {
        // 0. 最高优先级：帖子内显示的文件名（已经是可读中文，**原样保留**）
        //
        // 关键修复：原先这里会再走一遍 stripWebsite()，那是 v2.5.0 引入的回归。
        // stripWebsite 的 trim 列表含 '【' '】' '（' '）'，本是为剥掉「【某某网www.x.com】」
        // 这类水印的装饰括号；但书名本身大量使用【】——《阳光正好儿媳苏钥》（加料版）.txt
        // 被剥成「阳光正好儿媳苏钥】（加料版）.txt，首字直接丢失、留下孤儿右括号。
        //
        // 早期可用版本（51476c5~82d15ae）压根没有 nameHint，名字来自响应头，从不进
        // stripWebsite，所以不会丢字。现在恢复该语义：**只清非法字符与空白，不动标点**。
        if (!nameHint.isNullOrBlank()) {
            val cleaned = fixEncoding(nameHint)
                .replace(Regex("[\\\\/:*?\"<>|\\uFFFD]"), "_")
                .trim()
            if (cleaned.isNotBlank() && !looksLikeScriptOrGenericPage(cleaned)) {
                return cleaned
            }
        }

        var name: String? = null

        // 1. Content-Disposition 中的 filename*=（RFC 5987 编码，如 UTF-8''%E4%B9%A6.txt）
        if (!contentDisposition.isNullOrBlank()) {
            Regex("filename\\*\\s*=\\s*([^']*)''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.let {
                    val raw = it.groupValues[2].trim().trim('"')
                    name = decodeFilenameCandidates(raw).firstOrNull()
                }
            // 2. Content-Disposition 中的普通 filename=
            //    Discuz(PHP) 中文附件名常用 MIME encoded-word 包裹：filename="=?UTF-8?B?5Lmm5ZCN.txt?="
            //    必须先还原 encoded-word，否则会被当成一串无意义字符（旧版就漏了这一步，是书名异常的主因）
            if (name.isNullOrBlank()) {
                Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                    .find(contentDisposition)?.let {
                        val raw = decodeMimeWord(it.groupValues[1].trim())
                        name = decodeFilenameCandidates(raw).firstOrNull()
                    }
            }
        }

        // 3. URL 最后一段兜底：但 Discuz 附件链接形如 forum.php?mod=attachment&aid=xxx，
        //    其最后一段是 forum.php —— 脚本页绝不是文件名，必须排除（旧版会把它当书名保存）
        if (name.isNullOrBlank() || !name!!.contains('.') || looksLikeScriptOrGenericPage(name)) {
            val seg = Uri_parseLastSegment(url)
            if (seg != null && seg.contains('.') && !looksLikeScriptOrGenericPage(seg)) {
                val segName = try {
                    fixEncoding(URLDecoder.decode(seg, "UTF-8"))
                } catch (e: Exception) { fixEncoding(seg) }
                if (!looksLikeScriptOrGenericPage(segName)) name = segName
            }
        }

        // 4. 最终兜底名（响应头/URL 都拿不到可用名，或只是脚本页时）
        if (name.isNullOrBlank() || looksLikeScriptOrGenericPage(name)) {
            name = "download_" + System.currentTimeMillis()
        }

        var result = fixEncoding(name!!)
        result = stripWebsite(result)

        // 清理文件名非法字符与无效乱码
        result = result.replace(Regex("[\\\\/:*?\"<>|\\uFFFD]"), "_")

        return result
    }

    private fun Uri_parseLastSegment(url: String): String? = try {
        android.net.Uri.parse(url).lastPathSegment
    } catch (e: Exception) { null }

    /** 脚本/网页类扩展名：Discuz 附件链接的最后一段常是这些，绝不能当文件名 */
    private val SCRIPT_EXTENSIONS = setOf(
        ".php", ".php3", ".php4", ".php5", ".phtml", ".phps", ".asp", ".aspx", ".ashx",
        ".jsp", ".jspx", ".do", ".action", ".cgi", ".pl", ".shtml", ".html", ".htm", ".xhtml"
    )

    /** Discuz 附件下载端点的通用无意义主体名 */
    private val GENERIC_FILE_BASES = setOf(
        "attachment", "forum", "file", "index", "member", "home", "misc",
        "viewthread", "plugin", "download", "ajax", "api", "search"
    )

    /**
     * 判断解析出来的名字是不是「脚本页/通用端点名」而非真实文件名。
     * - 无扩展名 → 视为正常名（有些附件确实没有扩展名），不判为脚本页；
     * - 扩展名属脚本/网页类 → 是脚本页；
     * - 主体名是 Discuz 通用端点名（forum/attachment/...）→ 是脚本页。
     */
    private fun looksLikeScriptOrGenericPage(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val i = name.lastIndexOf('.')
        if (i <= 0) return false
        val ext = name.substring(i).lowercase()
        if (ext in SCRIPT_EXTENSIONS) return true
        val base = name.substring(0, i).lowercase()
        return base in GENERIC_FILE_BASES
    }

    /**
     * 还原 MIME encoded-word：`=?UTF-8?B?<base64>?=` 或 `=?GBK?Q?<quoted>?=`。
     * Discuz(PHP) 在 Content-Disposition 里给中文文件名时会用这种格式。
     * 解析失败原样返回，不影响其它分支。
     */
    private fun decodeMimeWord(value: String): String {
        val t = value.trim().trim('"')
        val m = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").find(t) ?: return t
        return try {
            val cs = charsetOf(m.groupValues[1])
            val mode = m.groupValues[2].uppercase()
            val payload = m.groupValues[3]
            val bytes = if (mode == "B") {
                android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            } else {
                decodeQuotedPrintable(payload)
            }
            String(bytes, cs)
        } catch (e: Exception) { t }
    }

    private fun charsetOf(name: String): java.nio.charset.Charset = try {
        java.nio.charset.Charset.forName(name.trim())
    } catch (e: Exception) { Charsets.UTF_8 }

    /** Q 编码：`_` 表示空格，`=XX` 是十六进制字节，其余按 latin-1 单字节 */
    private fun decodeQuotedPrintable(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '_' -> { out.write(' '.code); i++ }
                c == '=' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3)
                    out.write(hex.toInt(16)); i += 3
                }
                else -> { out.write(c.toString().toByteArray(Charsets.ISO_8859_1)); i++ }
            }
        }
        return out.toByteArray()
    }

    /**
     * 乱码修复：文件名可能经历 URL 编码、ISO-8859-1 误解码或 GBK/UTF-8 混用，
     * 同时尝试多种还原路径，优先选择中文字符更多且替换符更少的结果。
     */
    fun fixEncoding(s: String): String = decodeFilenameCandidates(s).firstOrNull() ?: s

    private fun decodeFilenameCandidates(value: String): List<String> {
        val seeds = linkedSetOf(value.trim().trim('"'))
        try {
            if (value.contains('%')) seeds.add(URLDecoder.decode(value, "UTF-8"))
        } catch (_: Exception) { }
        val result = linkedSetOf<String>()
        for (seed in seeds) {
            result.add(seed)
            // 尝试更全面的编码组合，GB18030 覆盖了 GBK 并能处理更多生僻字
            val encodings = listOf("ISO-8859-1", "GB18030", "UTF-8", "Big5")
            for (e1 in encodings) {
                try {
                    val bytes = seed.toByteArray(charset(e1))
                    for (e2 in encodings) {
                        try { result.add(String(bytes, charset(e2))) } catch (_: Exception) { }
                    }
                } catch (_: Exception) { }
            }
        }
        return result.sortedWith(compareByDescending<String> { scoreName(it) }.thenBy { it.length })
    }

    /** 文件名评分：中文字符加分，替换符与常见 UTF-8/GBK 乱码特征扣分。 */
    private fun scoreName(s: String): Int {
        var score = 0
        for (c in s) {
            when {
                c == '锟' -> score -= 30   // "锟斤拷"乱码核心字(U+FFFD被GBK误解码)
                c == '\uFFFD' -> score -= 30
                c in '\u4e00'..'\u9fff' -> {
                    // GB2312 一级字库常用字加分，区外生僻/乱码字减分，让正确解码胜出
                    if (isGb2312Level1Hanzi(c)) score += 6 else score -= 4
                }
                c in '\u3040'..'\u30FF' -> score -= 10   // 日文假名混入是乱码特征
                c in '\u0080'..'\u00BF' -> score -= 2
                c in '\u00C0'..'\u00FF' -> score -= 3
                c == 'Ã' || c == 'Â' || c == 'Ð' || c == 'Ñ' || c == '�' -> score -= 12
            }
        }
        if (looksMojibake(s)) score -= 20
        if (looksChineseMojibake(s)) score -= 30
        return score
    }

    private fun looksMojibake(s: String): Boolean =
        s.contains('Ã') || s.contains('Â') || s.contains('Ð') || s.contains('Ñ') ||
            s.contains('�') || Regex("[\u00C0-\u00FF][\u0080-\u00BF]").containsMatchIn(s)

    /**
     * 统计式中文乱码检测（根治方案，替代枚举生僻字字表）。
     *
     * 原理：GBK↔UTF-8 双向误解码会产生海量、无法穷举的生僻字（"鍤欒笣钑"、"闁鐑閺瀚"、
     * "閿熸枻鎷"……），而正常中文书名几乎全由 GB2312 常用字构成。关键观察是——
     * 把每个汉字再按 GBK 编码，看它的高位字节是否落在「GB2312 一级字库区（0xB0–0xD7）」：
     *   · 正常常用字（"我""那""书"……）GBK 高位字节几乎都在 0xB0–0xD7 内；
     *   · 乱码生僻字（"鍤"=0xE5、"欒"=0x99、"钑"=0xE8……）GBK 高位字节几乎都落在该区之外。
     * 因此统计「GB2312 一级字库区外」汉字的占比，超过阈值即判乱码——不需要认识每个乱码字。
     * （实测：乱码样本区外占比 83%，正常书名仅 3%，阈值 30% 可稳健区分。）
     */
    private fun looksChineseMojibake(s: String): Boolean {
        if (s.contains('锟')) return true   // "锟斤拷"乱码核心字
        var hanCount = 0   // CJK 基本区汉字总数
        var outCount = 0   // GBK 高位字节不在 GB2312 一级字库区(0xB0..0xD7)的数量
        for (c in s) {
            when {
                c in '\u4e00'..'\u9fff' -> {
                    hanCount++
                    if (!isGb2312Level1Hanzi(c)) outCount++
                }
                c in '\u3040'..'\u30FF' -> outCount++   // 平假名/片假名混入
                c in '\u3400'..'\u4DBF' -> outCount++   // 扩展A区（正常文件名几乎不用）
            }
        }
        if (hanCount == 0) return false
        // 区外汉字占比超过 30% 即判乱码（正常书名几乎全在区内，乱码生僻字几乎全在区外）
        return outCount * 10 > hanCount * 3
    }

    /**
     * 判断单个汉字是否为 GB2312 一级字库常用字：
     * 将该字按 GBK 编码，看其首字节是否落在 0xB0..0xD7（GB2312 一级字库的高位区间，
     * 覆盖 3755 个最常用汉字）。GBK 编码时生僻字/乱码字的首字节往往 <0xB0 或 >0xD7。
     */
    private fun isGb2312Level1Hanzi(c: Char): Boolean {
        return try {
            val bytes = c.toString().toByteArray(charset("GBK"))
            bytes.size == 2 && (bytes[0].toInt() and 0xFF) in 0xB0..0xD7
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 删除文件名中的网站标记：
     * 匹配主体部分（不含扩展名）里的 www.xxx.com / xxx.com / @xxx.com 等，
     * 只认常见域名后缀，不会误伤 .txt / .zip 等文件扩展名。
     */
    fun stripWebsite(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var b = base.replace(
            Regex("(?i)(?:www\\.)?[a-z0-9][a-z0-9-]{0,62}(?:\\.(?:com|net|org|cn|cc|vip|xyz|top|site|me|info|io|co|tv|la|in|pw|fun|icu))+"),
            " "
        )
        // 清理残留的分隔符与括号
        b = b.replace(Regex("[@_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[- ]{2,}"), " ")
            .replace(Regex("[\\[\\(【（]\\s*[\\]\\)】）]"), " ")
            .trim(' ', '-', '_', '[', ']', '(', ')', '【', '】', '（', '）', '@', ',', '，')
        if (b.isBlank()) b = base
        return b + ext
    }

    /** 按 MIME 猜扩展名（公开：浏览器通道保存时也用） */
    fun guessExt(mimeType: String?): String {
        return when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "text/plain" -> ".txt"
            "application/zip", "application/x-zip-compressed" -> ".zip"
            "application/epub+zip" -> ".epub"
            "application/pdf" -> ".pdf"
            "application/x-rar-compressed", "application/vnd.rar" -> ".rar"
            "application/x-7z-compressed", "application/x-7z" -> ".7z"
            "application/x-mobipocket-ebook", "application/x-mobi8-ebook" -> ".mobi"
            "application/x-fictionbook+xml" -> ".fb2"
            else -> ""
        }
    }

    // ---------------- 请求拦截探测（v1.8.3） ----------------

    /** 探测结果：一次请求的响应体与类型信息 */
    data class Probe(
        /** 探测结果的临时文件；调用方消费后必须删除。 */
        val file: File,
        val size: Long,
        val contentType: String,
        val disposition: String?,
        val finalUrl: String,
        val isFile: Boolean
    )

    /**
     * 拦截探测：自己发一次 GET（带 Cookie/浏览器头/手动跟随重定向），判断响应是否为文件。
     * 响应体先流式写入缓存文件，不再把整本 TXT 读进 ByteArray；调用方保存或放行后负责删除
     * Probe.file。网页只保留最多 1 MB 到磁盘，随后交回 WebView 原生加载。
     */
    fun probe(ctx: Context, userAgent: String, url: String, referer: String?): Probe {
        var currentUrl = url
        val currentReferer = referer ?: url
        var conn = openConn(userAgent, currentUrl, currentReferer)
        var hops = 0
        while (hops++ < 5) {
            conn.connect()
            val code = conn.responseCode
            applySetCookies(conn, currentUrl)
            DebugLog.log("PROBE", "HTTP $code | type=${conn.contentType} | $currentUrl")
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("重定向地址缺失（HTTP $code）")
                currentUrl = resolveUrl(currentUrl, loc)
                conn = openConn(userAgent, currentUrl, currentReferer)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("服务器返回 HTTP $code")
            }
            break
        }

        val ct = conn.contentType ?: ""
        val cd = conn.getHeaderField("Content-Disposition")
        val ctLow = ct.lowercase()
        val isFile = cd?.contains("attachment", ignoreCase = true) == true ||
            (isBinaryContentType(ctLow) && !ctLow.startsWith("text/") &&
                !ctLow.contains("xml") && !ctLow.contains("json"))
        val limit = if (isFile) MAX_DOWNLOAD_BYTES else MAX_PROBE_HTML_BYTES
        val temp = File.createTempFile("probe_", ".part", ctx.cacheDir)
        var keep = false
        return try {
            val size = conn.inputStream.use { input ->
                copyLimitedToFile(input, temp, limit, failOnLimit = isFile)
            }
            DebugLog.log("PROBE", "判定=${if (isFile) "文件" else "网页"} | ${size}B | ct=$ct cd=$cd")
            keep = true
            Probe(temp, size, ct, cd, conn.url?.toString() ?: currentUrl, isFile)
        } finally {
            conn.disconnect()
            if (!keep) temp.delete()
        }
    }

    /**
     * 「免银币下载」静默解析：给定伪造签名的下载链接（aid=base64(aid|1|1|1|tid)），
     * 请求后 Discuz 返回「原附件链接已失效」提示页，页内「点击这里重新下载」链接才是
     * 带真实签名、uid=1（免币）的有效下载地址。这里请求并解析出该真实地址，返回绝对 URL，
     * 失败返回 null。整个过程不导航 WebView，供后台静默下载。
     */
    fun resolveFreeSilverDownload(userAgent: String, fakeUrl: String, referer: String?): String? {
        var currentUrl = fakeUrl
        val currentReferer = referer ?: fakeUrl
        var conn = openConn(userAgent, currentUrl, currentReferer)
        var hops = 0
        try {
            while (hops++ < 5) {
                conn.connect()
                val code = conn.responseCode
                applySetCookies(conn, currentUrl)
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return null
                    currentUrl = resolveUrl(currentUrl, loc)
                    conn = openConn(userAgent, currentUrl, currentReferer)
                    continue
                }
                if (code !in 200..299) {
                    conn.disconnect()
                    return null
                }
                break
            }
            val body = conn.inputStream.use { input ->
                val buf = ByteArray(1024 * 1024)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
            conn.disconnect()
            val target = findRedirectInHtml(body, currentUrl)
            DebugLog.log("FREE-DL", "免银币解析: ${target ?: "未找到"}")
            return target
        } catch (e: Exception) {
            try { conn.disconnect() } catch (_: Exception) {}
            return null
        }
    }

    /** 明确的二进制/文档文件 MIME（不含任何 text/xml/json 表单类） */
    private fun isBinaryContentType(ct: String): Boolean {
        if (ct.isBlank()) return true   // 无 Content-Type 时无法判断，保守按文件处理(后续靠扩展名/下载器兜底)
        return ct.contains("octet-stream") ||
            ct.contains("zip") || ct.contains("rar") || ct.contains("7z") ||
            ct.contains("pdf") || ct.contains("epub") ||
            ct.contains("msword") || ct.contains("word") ||
            ct.contains("excel") || ct.contains("spreadsheet") ||
            ct.contains("image/") || ct.contains("audio/") || ct.contains("video/")
    }

    /** 把响应中的 Set-Cookie 写回 WebView CookieManager（对齐浏览器行为） */
    private fun applySetCookies(conn: HttpURLConnection, url: String) {
        try {
            val cm = CookieManager.getInstance()
            val fields = conn.headerFields ?: return
            for ((k, v) in fields) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    v?.forEach { cm.setCookie(url, it) }
                }
            }
        } catch (e: Exception) { /* 忽略 */ }
    }

    /** 建立带浏览器头的连接（调用方负责 connect/disconnect） */
    private fun openConn(userAgent: String, url: String, referer: String?): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            setRequestProperty("Referer", referer ?: url)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
            setRequestProperty("Sec-Fetch-Dest", "document")
            setRequestProperty("Sec-Fetch-Mode", "navigate")
            setRequestProperty("Sec-Fetch-Site", "same-origin")
            setRequestProperty("Sec-Fetch-User", "?1")
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = false // 手动跟随，保证每跳都带 Cookie 与 Referer
        }
    }

    // ---------------- 下载与保存 ----------------

    /**
     * 兼容旧调用方：小字节数组先落到缓存文件，再复用统一的流式保存路径。
     * 大文件下载路径不应调用此方法，应直接调用 saveFromFile。
     */
    fun save(ctx: Context, data: ByteArray, fileName: String, preserveName: Boolean = false): String {
        if (data.size.toLong() > MAX_DOWNLOAD_BYTES) {
            throw IOException("下载文件超过 ${MAX_DOWNLOAD_BYTES / (1024L * 1024L)} MB 限制")
        }
        val temp = File.createTempFile("download_", ".part", ctx.cacheDir)
        return try {
            FileOutputStream(temp).use { it.write(data) }
            saveFromFile(ctx, temp, fileName, preserveName)
        } finally {
            temp.delete()
        }
    }

    /** 创建浏览器通道使用的临时文件。 */
    fun createBrowserTempFile(ctx: Context): File =
        File.createTempFile("browser_", ".part", ctx.cacheDir)

    /**
     * 接收浏览器通道的一小段 Base64 数据并追加到临时文件。
     * 每次只解码当前分块，避免把整本 TXT 保存在 StringBuilder 或 ByteArray 中。
     */
    fun appendBase64Chunk(file: File, encoded: String, currentSize: Long): Long {
        if (encoded.length > 2 * 1024 * 1024) {
            throw IOException("下载数据分块过大")
        }
        val bytes = try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IOException("下载数据解码失败", e)
        }
        val nextSize = currentSize + bytes.size.toLong()
        if (nextSize > MAX_DOWNLOAD_BYTES) {
            throw IOException("下载文件超过 ${MAX_DOWNLOAD_BYTES / (1024L * 1024L)} MB 限制")
        }
        FileOutputStream(file, true).use { it.write(bytes) }
        return nextSize
    }

    /**
     * 从缓存文件流式保存到 Download 目录，整个过程只保留固定大小的复制缓冲区。
     *
     * @param preserveName true = 该名字来自帖子内 `attachname`，**原样保留**，
     *        不做 cleanName 剥后缀、不做多编码猜测（用户要的就是帖子里的文件名）。
     */
    fun saveFromFile(ctx: Context, source: File, fileName: String, preserveName: Boolean = false): String {
        if (!source.isFile || !source.canRead()) throw IOException("下载临时文件不可读")
        val size = source.length()
        if (size <= 0L) throw IOException("下载文件为空")
        if (size > MAX_DOWNLOAD_BYTES) {
            throw IOException("下载文件超过 ${MAX_DOWNLOAD_BYTES / (1024L * 1024L)} MB 限制")
        }
        // 用文件真实内容（魔数）识别类型，据此补/校正扩展名与 MIME，而非只靠文件名猜
        val fileType = detectFileType(source)
        var finalName = normalizeSavedFileName(fileName, fileType, preserveName)
        if (Build.VERSION.SDK_INT >= 29) {
            finalName = ensureUniqueNameMediaStore(ctx, finalName)
        }
        val finalMime = fileType.mime
        if (Build.VERSION.SDK_INT >= 29) {
            saveViaMediaStore(ctx, source, finalName, finalMime)
        } else {
            saveLegacy(ctx, source, finalName)
        }
        return finalName
    }

    /** 返回实际使用的文件名；走浏览器通道回退时返回 null */
    private fun download(
        ctx: Context, userAgent: String, url: String,
        presetName: String, referer: String?,
        onHtmlFallback: ((String) -> Unit)?,
        nameHint: String? = null
    ): String? {
        var currentUrl = url
        var currentReferer = referer ?: url
        var name = presetName
        var htmlHops = 0
        var totalHops = 0

        while (totalHops++ < 10) {
            val conn = openConn(userAgent, currentUrl, currentReferer)
            conn.connect()
            val code = conn.responseCode
            applySetCookies(conn, currentUrl)

            // HTTP 重定向：手动跟随
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IOException("重定向地址缺失（HTTP $code）")
                currentUrl = resolveUrl(currentUrl, loc)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("服务器返回 HTTP $code")
            }

            val contentType = conn.contentType ?: ""
            val respCd = conn.getHeaderField("Content-Disposition")
            DebugLog.log("DL", "HTTP $code | type=$contentType | cd=$respCd | $currentUrl")

            // HTML 跳转页（Discuz 下载确认页/系统提示页）：解析真实文件地址后继续
            if (contentType.lowercase().contains("text/html")) {
                val bodyBytes = conn.inputStream.use { input ->
                    val buf = ByteArray(512 * 1024)
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    buf.copyOf(n)
                }
                conn.disconnect()
                // 诊断：把服务器返回的网页「标题 + 正文摘录」记入日志。
                // 区分「未登录/权限不足」与「下载中转页」的关键证据就在这里（原本完全看不到）。
                val title = findTitleInHtml(bodyBytes)
                val excerpt = htmlExcerpt(bodyBytes)
                DebugLog.log("DL", "返回网页而非文件 | HTTP=$code | len=${bodyBytes.size} | title=$title | body=${excerpt}")
                val next = findRedirectInHtml(bodyBytes, currentUrl)
                if (next != null && htmlHops < 3) {
                    htmlHops++
                    currentReferer = currentUrl
                    currentUrl = next
                    continue
                }
                // 穿透失败：交给浏览器通道（WebView 页面内 fetch，网络栈与真实浏览器一致）。
                // 把服务器页面的原话告诉用户 —— 这是判断「未登录 / 权限不足 / 附件失效」的唯一线索，
                // 以前这条分支完全静默，用户只看到「开始下载」后再无动静，无从判断原因。
                if (onHtmlFallback != null) {
                    val cb = onHtmlFallback
                    val note = title ?: excerpt.ifBlank { "未知提示" }
                    mainHandler.post {
                        Toast.makeText(
                            ctx,
                            "下载受阻：服务器返回的是网页\n「${note.take(60)}」\n正在改用浏览器通道…若再无「下载完成」提示，就是未登录或权限不足",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    mainHandler.post { cb(url) }
                    return null
                }
                throw IOException("返回的是网页而非文件（$currentUrl）\n请确认已在论坛登录，且自定义脚本已生效")
            }

            // 文件名优先用「帖子内显示的文件名」(nameHint)；没有才用响应头 filename 刷新，
            // URL 兜底已在 presetName 里。（响应头乱码由 resolveFileName 内的 fixEncoding 处理）
            if (respCd != null) {
                try {
                    val re = resolveFileName(currentUrl, respCd, nameHint)
                    if (isGarbledName(re)) {
                        name = if (!isGarbledName(name)) name else "download_" + System.currentTimeMillis()
                        DebugLog.log("DL", "文件名无效，用时间戳兜底: $name (原解析='$re')")
                    } else {
                        name = re
                        DebugLog.log("DL", "文件名解析正常: $re")
                    }
                } catch (e: Exception) { DebugLog.log("DL", "文件名解析异常: ${e.message}") }
            }

            // 下载内容先限额读入临时文件，避免大 TXT 形成整文件内存副本。
            val temp = File.createTempFile("download_", ".part", ctx.cacheDir)
            // 名字来自帖子内 attachname 时原样保留（不剥后缀、不做编码猜测）。
            // 只以 nameHint 是否存在为准：响应头若把 name 改成了别的（含 (1) 后缀等），
            // 也不能因此丢掉「原样保留」语义 —— 用户要的就是帖子里的文件名。
            val keepAsIs = !nameHint.isNullOrBlank()
            val finalName = try {
                conn.inputStream.use { input -> copyLimitedToFile(input, temp, MAX_DOWNLOAD_BYTES) }
                saveFromFile(ctx, temp, name, keepAsIs)
            } finally {
                temp.delete()
            }
            DebugLog.log("DL", "最终保存文件名: $finalName")
            conn.disconnect()
            return finalName
        }
        throw IOException("重定向次数过多")
    }

    /**
     * 从 HTML 跳转页中解析真实下载地址（UTF-8 / GBK 双解码尝试）：
     * - meta refresh：<meta http-equiv="refresh" content="x;url=XXX">
     * - JS 跳转：location.href='XXX' / location.replace("XXX") / window.location='XXX'
     * - 直接文件链接：<a href="...txt/zip/rar/epub..."> 或 data/attachment 路径
     */
    private fun findRedirectInHtml(body: ByteArray, baseUrl: String): String? {
        val texts = mutableListOf(String(body, Charsets.UTF_8))
        try { texts.add(String(body, charset("GBK"))) } catch (e: Exception) { /* 忽略 */ }

        val patterns = listOf(
            Regex("(?i)<meta[^>]+http-equiv\\s*=\\s*['\"]?refresh[^>]+content\\s*=\\s*['\"][^'\"]*url=([^'\"<>]+)", RegexOption.IGNORE_CASE),
            Regex("location\\.(?:href|replace)\\s*[=(]\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            Regex("window\\.location\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            // 跳转页常用 window.open / open 新窗口，不能只依赖 location.href
            Regex("(?:window\\.)?open\\s*\\(\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            // Discuz/下载中转页常把真正地址放在 form、data-url、onclick 或 iframe 中
            Regex("<(?:form|iframe)[^>]+(?:action|src)\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE),
            Regex("(?:data-(?:url|href)|onclick)\\s*=\\s*['\"][^'\"]*(https?://[^'\"]+|(?:forum\\.php|attachment\\.php|data/attachment)[^'\"]*)['\"]", RegexOption.IGNORE_CASE),
            Regex("href\\s*=\\s*[\"']([^\"']*(?:data/attachment|mod=attachment|attachment\\.php|\\.txt|\\.zip|\\.rar|\\.7z|\\.epub)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
        )

        for (text in texts) {
            for (re in patterns) {
                val m = re.find(text) ?: continue
                var target = m.groupValues[1].trim()
                if (target.isEmpty()) continue
                // URL 编码实体还原
                target = target.replace("&amp;", "&")
                if (target.startsWith("javascript:") || target.startsWith("#")) continue
                if (target.equals(baseUrl, ignoreCase = true)) continue
                // search.php 是论坛搜索/提示页，不是真实附件地址，严禁继续跟随
                val lowerTarget = target.lowercase()
                if (lowerTarget.contains("search.php") || lowerTarget.contains("searchsubmit")) continue
                // HTML 实体、转义斜杠和 JS 字符串转义还原
                target = target.replace("\\\\/", "/").replace("\\u0026", "&")
                try {
                    val resolved = resolveUrl(baseUrl, target)
                    if (!resolved.equals(baseUrl, ignoreCase = true)) return resolved
                } catch (e: Exception) { /* 尝试下一个 */ }
            }
        }
        return null
    }

    private fun resolveUrl(base: String, target: String): String {
        return URL(URL(base), target).toString()
    }

    private fun ensureExtension(name: String, mimeType: String?): String {
        val ext = guessExt(mimeType).ifEmpty { ".txt" }
        val clean = name.trim().ifBlank { "download_" + System.currentTimeMillis() }
        val dotIndex = clean.lastIndexOf('.')
        val base = if (dotIndex > 0) clean.substring(0, dotIndex) else clean
        return base + ext
    }

    /** 判断文件名是否疑似乱码/无效（用于下载拦截/浏览器通道的书名兜底） */
    fun isGarbledName(name: String): Boolean {
        if (name.isBlank() || name.startsWith("download_")) return true
        if (looksMojibake(name) || looksChineseMojibake(name) || looksGarbledByUnderscore(name)) return true
        if (name.contains("提示信息") || name.contains("Powered by Discuz")) return true
        // 通用/无意义文件名（Discuz 附件常返回 attachment.php / forum.php 等），按去掉扩展名后的主体判断
        val base = name.substringBeforeLast('.').trim().lowercase()
        if (base in listOf("attachment", "forum", "file", "index", "member", "home", "misc", "viewthread", "plugin", "download")) return true
        return false
    }

    /**
     * 下划线泛滥式乱码检测：GBK 字节无法解码时会产生 U+FFFD(�)，被替换成下划线后，
     * 文件名会变成「_________禣_________[_____]」这种大量下划线 + 极少有效内容的形态。
     * 正常文件名（书名）几乎不含下划线，或仅用 1~2 个下划线作分隔；而下划线 >= 3 且
     * 有效内容（汉字/字母数字）极少，是解码彻底失败的强信号。
     */
    private fun looksGarbledByUnderscore(s: String): Boolean {
        val base = s.substringBeforeLast('.')
        if (base.length < 3) return false
        var underscore = 0
        var meaningful = 0   // 汉字 + 字母数字
        for (c in base) {
            when {
                c == '_' -> underscore++
                c in '\u4e00'..'\u9fff' || c.isLetterOrDigit() -> meaningful++
            }
        }
        return underscore >= 3 && meaningful <= 2
    }

    /** 解析文件名：书名（帖子标题）非空时直接用它，否则解析响应头/URL */
    private fun isZipData(data: ByteArray): Boolean {
        return data.size >= 4 &&
            data[0] == 0x50.toByte() &&
            data[1] == 0x4B.toByte() &&
            data[2] == 0x03.toByte() &&
            data[3] == 0x04.toByte()
    }

    private fun mimeTypeForData(data: ByteArray): String =
        if (isZipData(data)) "application/zip" else "text/plain"

    /** 文件类型识别结果：真实扩展名 + 对应的 MIME。 */
    data class FileType(val ext: String, val mime: String)

    private val UNKNOWN_FILE_TYPE = FileType("", "application/octet-stream")

    /**
     * 通过文件头魔数识别真实类型，用于在书名（帖子标题）不带扩展名时补上正确后缀，
     * 确保 .epub / .pdf / .zip / .rar / .7z 等文件保存后仍保留后缀。
     * 未识别到则返回空扩展名，由调用方按文本兜底。
     */
    private fun detectFileType(file: File): FileType {
        if (!file.isFile || file.length() < 4L) return UNKNOWN_FILE_TYPE
        val h = ByteArray(16)
        var n = 0
        try {
            FileInputStream(file).use { input ->
                while (n < h.size) {
                    val c = input.read(h, n, h.size - n)
                    if (c < 0) break
                    if (c == 0) continue
                    n += c
                }
            }
        } catch (e: Exception) {
            return UNKNOWN_FILE_TYPE
        }
        if (n < 4) return UNKNOWN_FILE_TYPE

        fun b(i: Int): Int = h[i].toInt() and 0xFF

        // PDF：%PDF
        if (b(0) == 0x25 && b(1) == 0x50 && b(2) == 0x44 && b(3) == 0x46)
            return FileType(".pdf", "application/pdf")

        // RAR：Rar!\x1A\x07（RAR4）或 Rar!\x1A\x07\x01\x00（RAR5）
        if (b(0) == 0x52 && b(1) == 0x61 && b(2) == 0x72 && b(3) == 0x21)
            return FileType(".rar", "application/x-rar-compressed")

        // 7z：7z\xBC\xAF\x27\x1C
        if (b(0) == 0x37 && b(1) == 0x7A && b(2) == 0xBC && b(3) == 0xAF)
            return FileType(".7z", "application/x-7z-compressed")

        // ZIP 类：PK\x03\x04 / PK\x05\x06 / PK\x07\x08
        val isPk = b(0) == 0x50 && b(1) == 0x4B &&
            (b(2) == 0x03 || b(2) == 0x05 || b(2) == 0x07)
        if (isPk) {
            // epub 也是 zip 容器：首条 local file header 后紧跟 "mimetype" 段
            if (n >= 38) {
                val sig = String(h, 30, minOf(8, n - 30), Charsets.US_ASCII)
                if (sig.startsWith("mimetype")) {
                    return FileType(".epub", "application/epub+zip")
                }
            }
            return FileType(".zip", "application/zip")
        }

        // GZIP：\x1F\x8B
        if (b(0) == 0x1F && b(1) == 0x8B)
            return FileType(".gz", "application/gzip")

        // MOBI/PalmDB：以 "BOOKMOBI" 或 "TEXtREAd" 开头（PalmDOC）
        if (n >= 8) {
            val head = String(h, 0, 8, Charsets.US_ASCII)
            if (head.startsWith("BOOKMOBI"))
                return FileType(".mobi", "application/x-mobipocket-ebook")
            if (head.startsWith("TEXtREAd"))
                return FileType(".mobi", "application/x-mobipocket-ebook")
        }

        return UNKNOWN_FILE_TYPE
    }

    private fun normalizeSavedFileName(fileName: String, fileType: FileType, preserveName: Boolean = false): String {
        // preserveName：名字来自帖子内 attachname，原样保留 —— 不做多编码猜测（避免把正常中文
        // 「解码」坏）、不做 cleanName 剥后缀（用户明确要帖子里的文件名，别擅自改）。
        if (preserveName) {
            val safe = fileName
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\uFFFD]"), "_")
                .trim()
            if (safe.isNotBlank()) {
                // 仅按文件真实类型补扩展名（帖子名有时无后缀），不改动主体
                val dotIdx = safe.lastIndexOf('.')
                val hasExt = dotIdx > 0 && safe.substring(dotIdx).lowercase() in setOf(
                    ".txt", ".zip", ".epub", ".pdf", ".rar", ".7z", ".gz",
                    ".mobi", ".azw3", ".azw", ".chm", ".umd", ".html", ".htm",
                    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"
                )
                if (hasExt || fileType.ext.isEmpty()) return safe
                return safe + fileType.ext
            }
        }

        // 书名兜底/正常中文文件名：已是正确中文，跳过 fixEncoding 的多编码猜测（避免把正常书名再"解码"坏）。
        // 仅当文件名疑似乱码（无中文或含乱码特征）时才走 fixEncoding 尝试还原。
        val hasHanzi = fileName.any { it in '\u4e00'..'\u9fff' }
        val alreadyClean = hasHanzi && !looksMojibake(fileName) && !looksChineseMojibake(fileName)
        val safe = (if (alreadyClean) fileName else fixEncoding(fileName))
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\uFFFD]"), "_")
            .trim()

        // 保留原始扩展名（.epub/.pdf/.txt/.zip/.rar 等），仅当无合理扩展名时才用魔数识别的真实扩展名兜底
        val knownExts = setOf(
            ".txt", ".zip", ".epub", ".pdf", ".rar", ".7z", ".gz",
            ".mobi", ".azw3", ".azw", ".chm", ".umd", ".html", ".htm"
        )
        val dotIdx = safe.lastIndexOf('.')
        val rawExt = if (dotIdx >= 0) safe.substring(dotIdx).lowercase() else ""
        val baseName: String
        val ext: String
        if (dotIdx >= 0 && rawExt in knownExts) {
            // 已有合理扩展名，优先保留原始扩展名
            baseName = safe.substring(0, dotIdx).trim()
            ext = safe.substring(dotIdx)
        } else if (fileType.ext.isNotEmpty()) {
            // 无扩展名时用魔数识别的真实类型补后缀
            baseName = safe.trim()
            ext = fileType.ext
        } else {
            // 兜底：按文本处理
            baseName = safe.substringBeforeLast('.', safe).trim()
            ext = ".txt"
        }
        if (baseName.isBlank()) {
            return "download_" + System.currentTimeMillis() + ext
        }
        val readable = if (looksMojibake(baseName) || looksChineseMojibake(baseName)) {
            "download_" + System.currentTimeMillis()
        } else {
            cleanName(baseName)
        }
        return readable + ext
    }

    /** 清理书名的后缀修饰语和符号（如"斗破苍穹最终修改版》=====..." → "斗破苍穹"）。 */
    private fun cleanName(name: String): String {
        var r = name.trim()
        // 1. 去掉末尾连续符号（非中文、非字母数字）
        r = r.trimEnd { ch -> !ch.isLetterOrDigit() && ch !in '\u4e00'..'\u9fff' }
        // 2. 去掉末尾的修饰后缀（常见小说文件名后缀，仅当去掉后仍剩有效书名）
        val suffixes = listOf(
            "最终修改版", "修改版", "精校版", "校对版", "精修版", "完整版",
            "无删减", "未删减", "全本", "完结", "完本", "全文", "校对全本", "全本无删减"
        )
        for (s in suffixes) {
            if (r.endsWith(s) && r.length > s.length) {
                r = r.dropLast(s.length)
                r = r.trimEnd { ch -> !ch.isLetterOrDigit() && ch !in '\u4e00'..'\u9fff' }
                break
            }
        }
        return r.trim().ifBlank { name }
    }

    private fun findTitleInHtml(body: ByteArray): String? {
        val text = String(body, Charsets.UTF_8)
        val m = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.trim()?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    /**
     * 从网页响应里抽出「人能看懂」的正文摘录，用于诊断日志。
     *
     * Discuz 的提示页（未登录/权限不足/附件失效）关键信息都在 `#messagetext` 或
     * `.alert_error` 里，页面其它部分全是模板噪声。这里优先取该区域文本，
     * 取不到再退回全文前段。按 GBK / UTF-8 双解码并挑中文更多的那个结果
     * （搜书吧是 GBK 站点，直接用 UTF-8 解会全是乱码）。
     */
    private fun htmlExcerpt(body: ByteArray): String {
        val candidates = mutableListOf<String>()
        candidates.add(String(body, Charsets.UTF_8))
        try { candidates.add(String(body, charset("GBK"))) } catch (_: Exception) {}
        // 选「可读字符（汉字/字母/数字）最多」的解码结果
        val text = candidates.maxByOrNull { s -> s.count { it.isLetterOrDigit() } } ?: return ""
        val area = Regex(
            "id\\s*=\\s*[\"']messagetext[\"'][^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(text)?.groupValues?.get(1)
            ?: Regex(
                "class\\s*=\\s*[\"'][^\"']*alert_error[^\"']*[\"'][^>]*>(.*?)</div>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).find(text)?.groupValues?.get(1)
            ?: text
        val plain = area
            .replace(Regex("(?is)<script.*?</script>"), " ")
            .replace(Regex("(?is)<style.*?</style>"), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
        return plain.take(200)
    }

    /** 固定缓冲区复制并限制文件大小；超限时直接停止并删除临时文件。 */
    private fun copyLimitedToFile(
        input: InputStream,
        target: File,
        limit: Long,
        failOnLimit: Boolean = true
    ): Long {
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var total = 0L
        FileOutputStream(target).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count.toLong()
                if (total > limit) {
                    if (failOnLimit) throw IOException("响应内容超过 ${limit / (1024L * 1024L)} MB 限制")
                    break
                }
                output.write(buffer, 0, count)
            }
            output.flush()
        }
        return total
    }

    private fun readLimitedBytesFromFile(file: File, limit: Long): ByteArray {
        val size = minOf(file.length(), limit).toInt()
        val result = ByteArray(size)
        var offset = 0
        FileInputStream(file).use { input ->
            while (offset < result.size) {
                val count = input.read(result, offset, result.size - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
        }
        return if (offset == result.size) result else result.copyOf(offset)
    }

    private fun isZipFile(file: File): Boolean {
        if (file.length() < 4L) return false
        val header = ByteArray(4)
        return try {
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) return false
                    if (count == 0) continue
                    offset += count
                }
            }
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * MediaStore 相对目录（**末尾必须带 '/'**）。
     *
     * 关键修复（v2.5.0）：原实现在写盘时用 `Download/<dir>`（无尾斜杠），而
     * `ensureUniqueNameMediaStore` 与 `DownloadsActivity.loadFiles()` 都用
     * `Download/<dir>/`（有尾斜杠）。RELATIVE_PATH 不一致会让「已写入的文件」在 App 的
     * 下载列表里查不出来，表现为「Toast 说下载完成，但列表里没有文件」。
     * 统一从这里取值，杜绝再次分叉。
     */
    private fun relPathFor(ctx: Context): String =
        Environment.DIRECTORY_DOWNLOADS + "/" + Prefs.getDownloadDir(ctx) + "/"

    /** Android 10+：写入 MediaStore Downloads，无需权限，可在系统下载目录中查看。 */
    private fun saveViaMediaStore(ctx: Context, source: File, fileName: String, mimeType: String?) {
        val resolver = ctx.contentResolver
        val relPath = relPathFor(ctx)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
        }
        // 不再删除旧文件：文件名已由 ensureUniqueNameMediaStore 保证唯一，同名自动加 (1)(2) 后缀
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        val copied: Long
        try {
            copied = resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> copyStream(input, output) }
            } ?: throw IOException("无法写入文件")
        } catch (e: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
        // 写后核验：确认 MediaStore 里确实有这条记录且字节数一致。
        // 不核验的话，某些 ROM 上 insert 成功但 openOutputStream 静默截断，
        // 会重演「提示下载完成、实际没有文件」的假成功。
        //
        // 关键修复：MediaStore 的索引是异步的，insert+close 之后**立刻** query 常常查不到
        // 刚写入的那一行（实测记录=null 而复制=6257098 B，文件其实已落盘）。
        // 原实现把 null 直接当失败抛 IOException → 已经下载成功的文件被报成「下载失败」。
        // 现在改成轮询重试；仍查不到才降级为「不可核验」而非「失败」——
        // 因为字节已经 copy 完并 close 成功，落盘是既成事实，核验只是为了兜住静默截断。
        var actual: Long? = null
        for (attempt in 0 until 5) {
            actual = mediaStoreSizeOf(ctx, fileName, relPath)
            if (actual != null) break
            if (attempt < 4) Thread.sleep(150)
        }
        DebugLog.log("SAVE", "MediaStore 写入: $relPath$fileName | 复制=$copied B | 记录=$actual B")
        if (actual == null) {
            // 重试 750ms 仍查不到：多数是 ROM 索引延迟，文件已写入，不应判为失败。
            DebugLog.log("SAVE", "提示：MediaStore 索引未同步到该文件（已写入 $copied B），按成功处理")
            return
        }
        if (actual != copied) {
            DebugLog.log("SAVE", "警告：记录大小($actual)与写入大小($copied)不一致")
        }
    }

    /** 查询 MediaStore 中某文件的 SIZE；不存在返回 null（用于写后核验） */
    private fun mediaStoreSizeOf(ctx: Context, name: String, relativePath: String): Long? {
        val projection = arrayOf(MediaStore.Downloads.SIZE)
        val selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?"
        return try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(name, relativePath), null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        } catch (e: Exception) { null }
    }

    /** 固定缓冲区流式复制，避免整本 TXT 读入内存。返回实际写入字节数。 */
    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count.toLong()
        }
        output.flush()
        return total
    }

    /**
     * API 29+（MediaStore）：若目标目录已存在同名文件，则自动追加 (1)(2)… 后缀，
     * 保留旧文件、新文件用新名，避免覆盖丢失。
     */
    private fun ensureUniqueNameMediaStore(ctx: Context, fileName: String): String {
        val relPath = relPathFor(ctx)   // 与写入、列表查询共用同一路径（尾斜杠一致）
        if (!mediaStoreNameExists(ctx, fileName, relPath)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""   // ext 含点，如 ".txt"
        var i = 1
        var candidate: String
        do {
            candidate = "$base($i)$ext"
            i++
        } while (mediaStoreNameExists(ctx, candidate, relPath))
        return candidate
    }

    /** 查询 MediaStore 目标目录下是否存在指定文件名 */
    private fun mediaStoreNameExists(ctx: Context, name: String, relativePath: String): Boolean {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?"
        return try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(name, relativePath), null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Android 9 及以下：直接写公共 Download 目录（调用前已确保存储权限） */
    private fun saveLegacy(ctx: Context, source: File, fileName: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Prefs.getDownloadDir(ctx)
        )
        if (!dir.exists()) dir.mkdirs()
        var file = File(dir, fileName)
        var i = 1
        val dot = fileName.lastIndexOf('.')
        while (file.exists()) {
            val n = if (dot > 0) {
                fileName.substring(0, dot) + "($i)" + fileName.substring(dot)
            } else "$fileName($i)"
            file = File(dir, n)
            i++
        }
        FileOutputStream(file).use { output ->
            FileInputStream(source).use { input -> copyStream(input, output) }
        }
    }
}
