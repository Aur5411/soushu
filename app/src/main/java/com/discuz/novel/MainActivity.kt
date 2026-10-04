package com.discuz.novel

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_STORAGE = 100
        /** 从「历史记录」页打开指定网址时，Intent 携带的 URL 键 */
        const val EXTRA_OPEN_URL = "open_url"
    }

    /** 适配高刷新率屏幕：在同分辨率模式中选刷新率最高的（API 23+，90Hz/120Hz 自动切换） */
    @SuppressLint("InlinedApi")
    private fun applyHighRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val display = windowManager.defaultDisplay
            val modes = display.supportedModes
            if (modes.isNullOrEmpty()) return
            val currentMode = display.mode
            // 在与当前分辨率相同的模式中，找刷新率最高的
            val bestMode = modes
                .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            if (bestMode.refreshRate > currentMode.refreshRate) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        } catch (e: Exception) { /* 忽略，不影响功能 */ }
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: Toolbar
    private var lastLoadedUrl: String? = null
    private var lastDesktopMode: Boolean? = null
    // 从「历史记录」页打开链接后，本次 onResume 不得用配置的首页覆盖掉它
    private var openedFromHistory = false

    // WebView 只能在主线程访问，而 shouldInterceptRequest 在后台线程运行（v1.8.4 修复）：
    // 预先在主线程缓存 UA 与最近的内容页地址（作下载 Referer），供后台拦截线程读取
    @Volatile private var cachedUserAgent: String = ""
    @Volatile private var lastContentPageUrl: String? = null
    private val popupWindows = mutableListOf<WebView>()

    // —— 启动入口智能重试 ——
    // 仅在「冷启动通往论坛」这条链路上生效：入口 soushu2030 → 发布导航页(.link-box) → 点最新地址进论坛。
    // 链路中任一环网络失败则自动重试(上限 3 次)，全失败弹窗提示 + 手动重试；进入真正的论坛/内容页后自动退出该模式，
    // 之后的普通浏览/断网不触发重试，避免打扰。
    private var entryMode = false          // 是否处于“正在尝试进入论坛”的启动窗口
    private var entryFailCount = 0         // 已连续失败次数
    private var entryWatchdog: Runnable? = null   // 进入超时看门狗(覆盖“停在 about:blank 无错误回调”的静默失败)
    private val ENTRY_MAX_RETRY = 3
    private val ENTRY_RETRY_DELAY_MS = 1500L
    private val ENTRY_WATCHDOG_MS = 6000L   // 进入窗口内未到达内容页视为一次失败
    private val entryHostMarkers = listOf("soushu2030", "soushufabu", "allshu", ".soushu", "book/", "/o/")

    /**
     * 发布页「最新地址」自动跳转命中的论坛主机名。
     * 论坛是动态域名（如 dq3s.b4e5w4dqwde.com，随发布页轮换），冷启动时并不知道它是谁，
     * 若不记下来放行，跳过去那一下会被「站外链接转系统浏览器」拦截踢出 App，导致永远进不了论坛。
     */
    @Volatile private var entryTrustedHost: String? = null

    /** 最近一次已做 DNS 预取的域名，避免每次加载都重复起线程（v2.7.0） */
    @Volatile private var lastPrefetchedHost: String? = null

    /**
     * 是否已经发起过「直达最新地址」的跳转。
     * 一旦发出，说明入口链路已经走通、目标就是论坛，此时应给页面更宽的加载时间；
     * 否则论坛本身响应慢（>6s）时看门狗会误判失败并重启整条链路，反复来回永远进不去。
     */
    @Volatile private var autoJumpIssued = false
    private val ENTRY_WATCHDOG_AFTER_JUMP_MS = 15000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHighRefreshRate()   // 适配高刷新率屏幕（90Hz/120Hz 自动切最高刷新率）
        Prefs.setDesktopMode(this, true)
        Prefs.setAdBlock(this, true)
        // 诊断功能：按设置里的「显示诊断菜单」开关决定是否启用（仅启动时读一次，重启才生效）
        DebugLog.setEnabled(Prefs.isDebugMenuVisible(this))
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()

        // 预热 WebView 内核：异步初始化一个影子 WebView，让浏览器内核/渲染引擎提前加载，
        // 后续真正浏览时首屏与页面切换更快（WebView 首次初始化是最慢的一环）。
        warmUpWebView()

        // 从「历史记录」页带 URL 启动：直接打开该网址，不走自动进论坛链路
        val openUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) {
            handleOpenUrl(openUrl)
        } else if (savedInstanceState == null) {
            beginForumEntry()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val openUrl = intent?.getStringExtra(EXTRA_OPEN_URL)
        if (!openUrl.isNullOrBlank()) handleOpenUrl(openUrl)
    }

    /** 从历史记录打开指定网址：退出自动进论坛窗口，直接加载目标页 */
    private fun handleOpenUrl(url: String) {
        entryMode = false
        disarmEntryWatchdog()
        openedFromHistory = true   // 本次打开来自历史记录，禁止 onResume 用配置首页覆盖
        loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后：网址 / 电脑版开关可能已改变，按需重新加载。
        // 注意：若用户走「启动自动进入论坛」链路(未在设置里配置网址，Prefs.getUrl() 为空)，此时绝不能
        // 因 lastLoadedUrl=soushu2030 而用空串 reload —— 那会让 WebView 跳去 about:blank，
        // 打断正在进行的入口加载并导致“卡在空白页无法自动进论坛”。
        val url = Prefs.getUrl(this)
        val desktop = Prefs.isDesktopMode(this)
        val uaChanged = lastDesktopMode != null && lastDesktopMode != desktop
        lastDesktopMode = desktop
        webView.settings.userAgentString = buildUserAgent()
        cachedUserAgent = webView.settings.userAgentString
        // 从历史记录打开链接后：本次 onResume 用历史链接，不得被配置首页覆盖
        if (openedFromHistory) {
            openedFromHistory = false
            return
        }
        // 空网址不跳转；冷启动正处于 entryMode 自动进论坛期间也不打扰(避免用配置空串盖掉入口页)。
        val duringEntry = entryMode && url.isBlank()
        if (url.isNotBlank() && !duringEntry && (url != lastLoadedUrl || uaChanged)) {
            loadUrl(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理启动重试看门狗，避免回调持有已销毁的 Activity 引用
        disarmEntryWatchdog()
    }

    /**
     * DNS 预取（v2.7.0 补齐）——之前只在注释里写了「DNS 预取 + 预连接」，并没有实现。
     *
     * 搜书吧的论坛域名是**动态轮换**的（今天还是 `xxx.b4e5w4dqwde.com`，明天就换一个），
     * 而且不少地区运营商的 DNS 解析本身就慢 100~800ms。用户在首页点分区时，
     * 系统要先做一次 DNS 查询才能建连，这段时间界面完全没有反馈 —— 正是「点了没反应、
     * 像要点两下」的观感来源之一。
     *
     * 做法：页面开始加载时，在后台线程把当前域名的 A 记录先解析出来（系统 DNS 缓存命中后续请求），
     * 等用户真的点进去时建连几乎立刻开始。纯后台预热，不改变任何请求顺序与结果。
     */
    private fun prefetchDns() {
        val host = try {
            val u = webView.url ?: Prefs.getUrl(this)
            Uri.parse(u).host
        } catch (e: Exception) { null }
        if (host.isNullOrBlank() || host == lastPrefetchedHost) return
        lastPrefetchedHost = host
        Thread {
            try {
                // 只做解析预热，不建立连接、不发请求；失败静默（离线时正常现象）
                java.net.InetAddress.getByName(host)
                DebugLog.log("PAGE", "DNS 预取完成: $host")
            } catch (e: Exception) {
                // 解析失败不影响后续正常加载，无需处理
            }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        // 多窗口支持 + onCreateWindow 接管（v1.8.1，Via 浏览器同方案）：
        // 跳转页的下载链接普遍用 window.open / target=_blank 触发，单窗口模式下这些请求
        // 会被系统静默丢弃（点击毫无反应）；接管后转发回当前 WebView 加载，
        // 服务器返回文件时即可触发 DownloadListener 自动下载。
        settings.setSupportMultipleWindows(true)
        // 允许 JS 自动弹窗（部分跳转页倒计时后自动触发下载）
        @Suppress("DEPRECATION")
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = buildUserAgent()
        cachedUserAgent = settings.userAgentString

        // ===== 浏览提速优化（v2.7.0 补齐）=====
        // 1) HTTP 缓存：WebView 默认即启用 HTTP 磁盘缓存（存于 cacheDir），
        //    LOAD_DEFAULT 遵循响应头缓存策略；Discuz 论坛的 CSS/JS/图片变化很小，
        //    命中缓存即可让帖子间切换、重复进分区明显变快。
        //    注：WebView 没有「设置缓存大小」的公开 API（只有 clearCache），
        //    缓存上限由系统按设备存储分配，不可调 —— 曾经误用 setCacheSize 编译不过。
        //    能真正提升命中率的开关是 cacheMode：列表翻页时优先用缓存、网络后台补齐。
        try {
            settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
        } catch (e: Exception) { }
        // 2) 渲染优先级调高：让 WebView 与主线程争 CPU 时优先给渲染管线，滑动更跟手
        try {
            settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        } catch (e: Exception) { }
        // 3) 关掉用不到的开销
        try {
            // 地理位置：用不到，省去权限与额外开销
            settings.setGeolocationEnabled(false)
        } catch (e: Exception) { }

        // 安全加固：关闭本地文件访问，防止网页读取本地文件
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false

        // JS 桥接：浏览器通道下载（页面内 fetch）分块回传使用
        webView.addJavascriptInterface(DownloadBridge(), "DiscuzApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                // 记录最近的内容页（非下载候选页）作为下载请求的 Referer
                if (url != null && !isDownloadCandidate(url)) lastContentPageUrl = url
                // 后台预解析域名：用户点分区/帖子时能省掉一段 DNS 等待（v2.7.0）
                prefetchDns()
                DebugLog.log("PAGE", "加载开始: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                if (DebugLog.isEnabled()) injectClickLogger()
                ScriptManager.inject(this@MainActivity, webView, url)
                autoJumpToForum()
                // 已到达真正的论坛/内容页才算进入成功：
                // 必须是 http(s) 的真实页面(排除 about:blank / data: 等内部空页)，且不再属于“发布链路”域名。
                // 注意不能凭 about:blank 就退出——meta-refresh/发布页跳转瞬间会经过空白页，误判会关掉重试窗口。
                if (entryMode && isRealHttpPage(url) && !isEntryHostUrl(url)) {
                    endForumEntry(success = true)
                    rememberForumUrl(url)
                }
                updateTitle()
                recordHistory(view, url)
                DebugLog.log("PAGE", "加载完成: $url | title=${view?.title}")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                DebugLog.log("NAV", url)
                // 免银币伪造签名链接：静默后台下载，不导航跳转
                if (isFakeFreeDownloadUrl(url)) {
                    handleFreeSilverDownload(url)
                    return true
                }
                // 附件：每次点击直接接管下载，不再「第一次放行、第二次才下载」
                if (isDirectAttachmentUrl(url)) {
                    startDirectAttachment(url)
                    return true
                }
                // 外链推广分区：先于站外判断拦下（点它时先停在站内 fid，由服务端再 301）
                if (interceptExternalForum(url)) return true
                if (interceptExternalNav(url)) return true
                return handleUrl(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null) {
                    DebugLog.log("NAV", url)
                    if (isFakeFreeDownloadUrl(url)) {
                        handleFreeSilverDownload(url)
                        return true
                    }
                    // 附件：每次点击直接接管下载，不再「第一次放行、第二次才下载」
                    if (isDirectAttachmentUrl(url)) {
                        startDirectAttachment(url)
                        return true
                    }
                    // 外链推广分区：先于站外判断拦下
                    if (interceptExternalForum(url)) return true
                    if (interceptExternalNav(url)) return true
                }
                return url?.let { handleUrl(it) } ?: false
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
            ) {
                val req = request
                val code = error?.errorCode ?: -1
                val desc = error?.description?.toString() ?: ""
                DebugLog.log("ERROR", "$code $desc @ ${req?.url} main=${req?.isForMainFrame}")
                // 仅主 frame 的网络级失败才考虑“打不开”重试（子资源/图片失败不触发）
                if (req != null && req.isForMainFrame && isEntryNetworkError(code)) {
                    recordEntryFailure()
                }
            }

            /**
             * 关键：发布入口 soushu2030 等站点证书已过期/域名不匹配（实测 CN=down-6699.juxianyaxu.com
             * 且 notAfter=2022-11-28 已过期）。默认 WebViewClient 会因 SSL 错误直接失败并停在 about:blank，
             * 且不触发 onReceivedError 的重试逻辑——这正是“加载完成后再变空白、卡死进不去”的根因。
             * 这里放行证书错误(等价 headless Chrome 的 --ignore-certificate-errors)，让发布页能正常渲染，
             * 才能拿到「最新地址」链接并直达论坛(论坛 dq3s... 证书本身有效)。
             */
            override fun onReceivedSslError(
                view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?
            ) {
                // 安全加固：仅对「发布入口」域名(soushu2030 等，证书确实过期)放行证书错误，
                // 保持自动获取最新地址功能；论坛主站等其余域名证书应有效，出现错误必是攻击 → 拒绝。
                val errUrl = error?.url ?: ""
                val isEntry = isEntryHostUrl(errUrl)
                if (isEntry) {
                    DebugLog.log("SSL", "放行发布入口证书错误: $errUrl")
                    handler?.proceed()
                } else {
                    DebugLog.log("SSL", "拒绝证书错误: $errUrl")
                    handler?.cancel()
                }
            }

            /**
             * v1.8.4 拦截网：不再依赖 setDownloadListener 是否回调。
             * 附件/文件地址的 GET 请求（含子框架/iframe/XHR）自己发一次请求：
             * - 服务器返回文件 → 直接保存（仅 Toast 提示，不弹窗），返回一个结果页给 WebView
             * - 服务器返回网页（跳转页/提示页）→ 原样交回 WebView 渲染，页面 JS 照常执行
             * 注意：本方法运行在后台线程，严禁访问 webView —— UA 与 Referer 用主线程缓存的字段
             */
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val url = req.url?.toString() ?: return null
                if (req.method != "GET") return null
                if (!isDownloadCandidate(url)) return null
                // 关键修复：主框架附件页必须完全交给 WebView 原生导航。
                // 主框架先 probe 再放行会产生重复请求，破坏 GBK 跳转页的 JS/Cookie 上下文，
                // 并导致页面出现 null.length；真正返回文件时由 DownloadListener 接管。
                if (req.isForMainFrame) {
                    DebugLog.log("INTERCEPT", "主框架附件放行原生导航: $url")
                    return null
                }
                // 非主框架资源才允许探测，避免重复保存
                if (allowNativeOnce(url)) {
                    DebugLog.log("INTERCEPT", "放行原生请求一次: $url")
                    return null
                }
                val ua = cachedUserAgent
                // 线程安全：这里不能读取 webView.settings 或 webView.url
                if (ua.isBlank()) return null
                val referer = lastContentPageUrl
                return try {
                    val p = DownloadHelper.probe(this@MainActivity, ua, url, referer)
                    if (!p.isFile) {
                        // 关键修复：探测请求拿到 HTML 后必须让 WebView 重新原生加载，
                        // 不能把自己读取的 HTML 重放给它，否则会丢失原始导航上下文、Cookie
                        // 和跳转页的 JS 执行环境；同时撤销去重标记，允许这次原生重试。
                        p.file.delete()
                        handledUrls.remove(url)
                        bypassProbeUrls[url] = System.currentTimeMillis()
                        DebugLog.log("INTERCEPT", "网页，放行 WebView 原生加载: $url")
                        null
                    } else if (!markHandled(url)) {
                        p.file.delete()
                        DebugLog.log("INTERCEPT", "重复请求，跳过: $url")
                        null
                    } else {
                        val hint = nameHintForUrl(p.finalUrl)
                        val name = DownloadHelper.resolveFileName(p.finalUrl, p.disposition, hint)
                        val savedName = DownloadHelper.saveFromFile(this@MainActivity, p.file, name, !hint.isNullOrBlank())
                        p.file.delete()
                        DebugLog.log("INTERCEPT", "已保存: $savedName (${p.size}B)")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "下载完成：$savedName\n保存于 Download/${Prefs.getDownloadDir(this@MainActivity)}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // 返回结果页，避免 WebView 再次把二进制文件当页面渲染；不弹窗
                        htmlResponse(downloadDonePage(savedName, p.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    }
                } catch (e: Exception) {
                    DebugLog.log("INTERCEPT", "失败: ${e.message}")
                    handledUrls.remove(url)
                    null
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            // 诊断：页面 JS 报错/警告也记入日志（按钮 onclick 执行失败会在这里现形）
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (!DebugLog.isEnabled()) return false   // 关闭诊断时不处理，走默认行为
                consoleMessage?.let {
                    DebugLog.log("JS", "${it.messageLevel()}: ${it.message()} @${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }

            // 接管新窗口请求（window.open / target=_blank）：用临时 WebView 捕获目标地址，
            // 转发回主 WebView 加载 —— 文件响应将由主 WebView 的 DownloadListener 捕获并自动下载
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                DebugLog.log("POPUP", "新窗口请求 (gesture=$isUserGesture)")
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.settings.databaseEnabled = true
                popup.settings.javaScriptCanOpenWindowsAutomatically = true
                popup.settings.setSupportMultipleWindows(true)
                popup.settings.userAgentString = cachedUserAgent
                popup.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        DebugLog.log("POPUP", "加载开始: $url")
                        // Popup 只是中转，不在隐藏窗口展示普通网页
                        if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                            runOnUiThread {
                                if (isDirectAttachmentUrl(url)) {
                                    // 附件：直接接管下载，不再等第二次点击
                                    v?.stopLoading()
                                    startDirectAttachment(url)
                                } else {
                                    forwardPopupUrl(url)
                                }
                            }
                        }
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        DebugLog.log("POPUP", "加载完成: $url")
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val u = req?.url?.toString() ?: return false
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            runOnUiThread { forwardPopupUrl(u) }
                            return true
                        }
                        return true
                    }
                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean {
                        val u = url ?: return false
                        if (u.startsWith("http://") || u.startsWith("https://")) {
                            runOnUiThread { forwardPopupUrl(u) }
                            return true
                        }
                        return true
                    }
                }
                popup.setDownloadListener { url, _, cd, mime, _ ->
                    DebugLog.log("DOWNLOAD", "Popup 下载监听: $url | mime=$mime | cd=$cd")
                    onDownloadStart(url, cd, mime)
                }
                popup.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        child: WebView?, dialog: Boolean, gesture: Boolean, msg: Message?
                    ): Boolean {
                        DebugLog.log("POPUP", "弹出页再次请求新窗口 (gesture=$gesture)")
                        val nested = WebView(this@MainActivity)
                        nested.settings.javaScriptEnabled = true
                        nested.settings.domStorageEnabled = true
                        nested.settings.javaScriptCanOpenWindowsAutomatically = true
                        nested.settings.userAgentString = cachedUserAgent
                        nested.webViewClient = object : WebViewClient() {}
                        nested.setDownloadListener { u, _, cd, mime, _ ->
                            DebugLog.log("DOWNLOAD", "嵌套弹出页下载监听: $u | mime=$mime | cd=$cd")
                            onDownloadStart(u, cd, mime)
                        }
                        popupWindows.add(nested)
                        val transport = msg?.obj as? WebView.WebViewTransport ?: return false
                        transport.webView = nested
                        msg.sendToTarget()
                        return true
                    }
                }
                popupWindows.add(popup)
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        // 主下载通道（v1.8.x）：附件点击走浏览器导航，服务器返回文件时系统回调此处 → 自动下载
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            DebugLog.log("DOWNLOAD", "触发下载监听: $url | mime=$mimeType | cd=$contentDisposition")
            onDownloadStart(url, contentDisposition, mimeType)
        }
    }

    /** 可能是下载目标的地址：Discuz 附件端点，或常见的文件扩展名 */
    private fun isDownloadCandidate(url: String): Boolean {
        val l = url.lowercase()
        // 只认真正的附件下载端点。注意：`attachpay` 是 Discuz「付费购买确认」浮层(AJAX 返回
        // 购买表单 text/xml)，不是附件文件，绝不能拦截，否则会把购买表单误存成假文件。
        if (l.contains("attachpay")) return false
        if (l.contains("mod=attachment") || l.contains("attachment.php")) return true
        val path = l.substringBefore('?')
        return path.endsWith(".txt") || path.endsWith(".zip") || path.endsWith(".rar") ||
            path.endsWith(".7z") || path.endsWith(".pdf") || path.endsWith(".epub")
    }

    /** 同一地址 5 秒内只处理一次，避免拦截通道与 DownloadListener 重复下载 */
    private val handledUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** HTML 探测后放行一次原生导航，防止 shouldInterceptRequest 与 WebView 互相循环 */
    private val bypassProbeUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun markHandled(url: String): Boolean {
        val now = System.currentTimeMillis()
        val it = handledUrls.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > 5000) it.remove()
        }
        val prev = handledUrls[url]
        if (prev != null && now - prev < 5000) return false
        handledUrls[url] = now
        return true
    }

    private fun allowNativeOnce(url: String): Boolean {
        val now = System.currentTimeMillis()
        bypassProbeUrls.entries.removeIf { now - it.value > 10000 }
        return bypassProbeUrls.remove(url) != null
    }

    private fun htmlResponse(html: String): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))

    /** 拦截下载成功后的结果页（代替系统下载弹窗） */
    private fun downloadDonePage(name: String, size: Int): String {
        val kb = maxOf(1, size / 1024)
        val safeName = android.text.TextUtils.htmlEncode(name)
        return "<html><head><meta name='viewport' content='width=device-width'>" +
            "<style>" +
            "html,body{background:#121826;color:#F4F7FB;margin:0}" +
            "body{font-family:sans-serif;padding:28px;line-height:1.8}" +
            ".card{max-width:680px;margin:8vh auto;padding:26px;border:1px solid #334155;" +
            "border-radius:16px;background:#1E293B;box-shadow:0 8px 30px #0008}" +
            ".ok{color:#86EFAC;font-size:14px;font-weight:bold}" +
            ".name{margin:14px 0;word-break:break-all;font-size:20px;color:#FFFFFF}" +
            ".meta{color:#CBD5E1;font-size:14px}" +
            ".tip{color:#94A3B8;font-size:13px;margin-top:20px}" +
            "</style></head><body><div class='card'>" +
            "<div class='ok'>下载完成</div>" +
            "<div class='name'>$safeName</div>" +
            "<div class='meta'>$kb KB</div>" +
            "<div class='tip'>可在右上角菜单「下载文件」中查看，返回上一页继续浏览。</div>" +
            "</div></body></html>"
    }

    /** 诊断用点击记录脚本（只读不改页面）：记录被点击元素（含带 onclick 的任意元素）的标签/href/onclick/文本 */
    private fun injectClickLogger() {
        val js = """
(function(){
  if(window.__dzClickLog) return; window.__dzClickLog=1;
  document.addEventListener('click',function(e){
    try{
      var n=e.target, found=null, hops=0;
      while(n&&n.nodeType===1&&hops++<6){
        if(/^(A|BUTTON|INPUT)$/.test(n.tagName)||n.getAttribute('onclick')){ found=n; break; }
        n=n.parentNode;
      }
      if(!found) found=e.target;
      if(!found||!window.DiscuzApp) return;
      var oc=(found.getAttribute&&found.getAttribute('onclick')||'').slice(0,150);
      var info=found.tagName+' | href='+(found.getAttribute&&found.getAttribute('href')||'')+' | onclick='+oc+' | text='+(found.textContent||'').trim().slice(0,40);
      window.DiscuzApp.logClick(info);
    }catch(err){}
  },true);
})();
""".trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 发布页自动跳转论坛。
     *
     * 场景：App 打开固定入口 www.soushu2030.com → meta refresh 落到"发布导航页"
     * (域名每次可能变，如 xxx.soushufabu.top:2228/o/...)，页面上用 .link-box 列出
     * 「最新地址/搜书吧」等真实论坛入口，需点击才能进论坛。此处按 DOM 结构自动点击，
     * 不依赖易变的发布页 URL。
     *
     * 判定：页面存在 .link-box，且内含文字匹配 最新地址/搜书吧/进入论坛 等、且指向 http 站点的链接 → 自动点击。
     * 点击后浏览器导航离开发布页；若目标实为论坛页(无 .link-box)，后续 onPageFinished 不会再触发点击。
     * 时间防抖：2 秒内不重复点击，规避同页多次 onPageFinished 造成的连点/抖动。
     */
    private var lastAutoJumpTs = 0L
    private var autoJumpAttempt = 0
    private val autoJumpPollRunnable = Runnable { runAutoJumpPoll(webView.url) }

    /**
     * 预热 WebView 内核（浏览提速）：WebView 首次初始化要加载浏览器内核/渲染引擎，
     * 是冷启动最慢的一环。这里延迟一小段时间后在主线程异步创建一个"影子 WebView"
     * 并立即销毁，让内核提前加载完成，后续真正浏览时首屏与页面切换更快。
     * 影子 WebView 不加载任何 URL、不绑定 Client，开销极小且用完即弃。
     */
    private fun warmUpWebView() {
        webView.postDelayed({
            try {
                val warm = WebView(applicationContext)
                warm.settings.javaScriptEnabled = true
                warm.loadDataWithBaseURL(null, "", "text/html", "UTF-8", null)
                warm.destroy()
            } catch (e: Exception) { /* 预热失败不影响主流程 */ }
        }, 300L)
    }

    /**
     * 在发布页上自动找「最新地址/搜书」链接并直达论坛。
     *
     * 兼容两种入口形态：
     *  - 发布页直接含 .link-box（当前 soushu2030 返回的形态，链接为静态 HTML）；
     *  - 发布页先经 meta/JS 跳到另一中间页才出现链接（结构随入口域名轮换变化）。
     * 做法：onPageFinished 触发一次 + 在稳定后延迟轮询最多 4 次，防「onPageFinished 触发瞬间
     * 页面正跳 about:blank / 链接晚注入」导致查不到。命中即 window.location.href 主框架直达论坛，
     * 绕开 target=_blank 的 onCreateWindow→popup→转发链路（实测在发布页上不稳定）。
     */
    private fun autoJumpToForum() {
        // 仅在“冷启动进入论坛”这段入口窗口(entryMode)内才自动跳转；
        // 一旦成功进论坛(endForumEntry 已把 entryMode 置 false)，就彻底停手，
        // 否则帖子详情页里的「论坛/首页」链接会被误命中，把用户拽回主页。
        if (!entryMode) return
        val now = System.currentTimeMillis()
        if (now - lastAutoJumpTs < 1500) return   // 同一入口页面防抖
        lastAutoJumpTs = now
        autoJumpAttempt = 0
        webView.removeCallbacks(autoJumpPollRunnable)
        webView.postDelayed(autoJumpPollRunnable, 350)
    }

    private fun runAutoJumpPoll(pageUrl: String?) {
        if (!entryMode) return   // 已离开入口窗口则不再轮询
        // 若已到达真正论坛/内容页(非发布链路域名)，停止本页轮询
        if (isRealHttpPage(pageUrl) && !isEntryHostUrl(pageUrl)) return
        autoJumpAttempt++
        val attempt = autoJumpAttempt
        // 第一阶段：只「找」不「跳」。跳转改由 Kotlin 在登记完目标域名之后再做 ——
        // 否则 JS 里 location.href 立刻发起的导航会先于本回调到达(实测早约 140ms)，
        // 目标域名还没登记就被「站外链接转系统浏览器」拦截踢出 App，冷启动永远进不了论坛。
        val js = """
(function(){
  try{
    var info={};
    info.url=location.href;
    info.title=(document.title||'').slice(0,40);
    info.bodyLen=(document.body?document.body.innerHTML.length:0);
    info.linkBox=document.querySelectorAll('.link-box').length;
    info.jumped=!!window.__dzAutoJumped;
    function pick(cands){
      var best=null, score=-1;
      for(var i=0;i<cands.length;i++){
        var a=cands[i];
        var href=(a.getAttribute('href')||'').trim();
        var txt=((a.textContent||'')+(a.getAttribute('title')||'')).replace(/\s+/g,'');
        if(!/^https?:\/\//i.test(href)) continue;
        if(!/最新地址|最新网址|最新|地址|搜书|进入论坛|论坛|bbs|discuz|soushu/i.test(txt+href)) continue;
        var s=0;
        if(/最新地址|最新网址/i.test(txt)) s+=5;
        if(/最新/i.test(txt)) s+=3;
        if(/搜书|soushu/i.test(txt+href)) s+=2;
        if(/论坛|bbs|discuz/i.test(txt+href)) s+=1;
        if(a.className.indexOf('link')>=0) s+=1;
        if(s>score){ score=s; best=href; }
      }
      return best;
    }
    var target=pick(document.querySelectorAll('a.link,a[href]'));
    info.target=target||'';
    info.cand=target?1:0;
    return JSON.stringify(info);
  }catch(e){ return JSON.stringify({err:String(e)}); }
})();
""".trimIndent()
        webView.evaluateJavascript(js) { res ->
            // ⚠️ evaluateJavascript 回传的是「被 JSON 编码过一层的字符串」：内层引号是 \"、斜杠是 \/。
            // 不能直接当 JSON 解析（拿 `"target"` 去匹配 `\"target\"` 永远匹配不到，会导致「找到了目标却不跳」）。
            // 必须先用 JSONTokener 解一层，拿回原始 JSON 文本再解析。
            val clean = decodeJsResult(res)
            DebugLog.log("AUTOJUMP", "第${attempt}次 检查$clean")
            val info = try {
                if (clean.isBlank() || clean == "null") null else org.json.JSONObject(clean)
            } catch (_: Exception) { null }
            val target = info?.optString("target", "")?.takeIf { it.isNotBlank() }
                ?: extractEscapedField(res, "target")
            if (target != null) {
                // 先把目标域名登记下来（内存 + 持久化），再发起跳转，站外拦截才认得它
                try {
                    Uri.parse(target).host?.lowercase()?.trim()?.takeIf { it.isNotBlank() }?.let { host ->
                        if (entryTrustedHost != host) {
                            entryTrustedHost = host
                            DebugLog.log("AUTOJUMP", "登记论坛域名: $host")
                        }
                        Prefs.addTrustedHost(this, host)
                    }
                } catch (_: Exception) {}
                DebugLog.log("AUTOJUMP", "直达最新地址: $target")
                // 已确认目标就是论坛：改用更宽的看门狗，等目标页慢慢加载完，避免误判失败重启链路
                autoJumpIssued = true
                armEntryWatchdog()
                // 第二阶段：用 JS 跳转(而非 webView.loadUrl)，保留 Referer，行为与原实现一致
                val jumpJs = "try{window.__dzAutoJumped=1;window.location.href=" +
                    org.json.JSONObject.quote(target) + ";}catch(e){}"
                webView.evaluateJavascript(jumpJs, null)
                return@evaluateJavascript
            }
            // 未命中且仍在入口链路，短暂后再查(链接可能晚注入)；最多约 4 次覆盖 ~2.5s
            if (attempt < 4 && entryMode && info?.optBoolean("jumped", false) != true) {
                webView.postDelayed(autoJumpPollRunnable, 550)
            }
        }
    }

    /**
     * 解开 evaluateJavascript 回传值的外层 JSON 编码，拿回 JS 实际返回的字符串。
     * 例：回调收到 `"{\"url\":\"http:\/\/a\",\"target\":\"https:\/\/b\"}"` → 还原为
     * `{"url":"http://a","target":"https://b"}`。解析失败时退回朴素去引号（兼容老行为）。
     */
    private fun decodeJsResult(res: String?): String {
        if (res.isNullOrBlank()) return ""
        return try {
            when (val v = org.json.JSONTokener(res).nextValue()) {
                is String -> v
                else -> res
            }
        } catch (_: Exception) {
            res.trim().removePrefix("\"").removeSuffix("\"")
        }
    }

    /**
     * 兜底：万一解不了一层编码（个别 OEM WebView 回传格式与 AOSP 不同），
     * 就直接在被转义过的原文里按 `\"field\":\"值\"` 抠出来，并把 `\/` 还原成 `/`。
     * 值的字符类必须允许 `\/` 这类转义（只用 `[^"\\]` 会在第一个反斜杠处断掉）。
     */
    private fun extractEscapedField(raw: String?, field: String): String? {
        if (raw.isNullOrBlank()) return null
        val pat = "\\\\?\"" + java.util.regex.Pattern.quote(field) +
            "\\\\?\"\\s*:\\s*\\\\?\"((?:[^\"\\\\]|\\\\.)*?)\\\\?\""
        return try {
            Regex(pat).find(raw)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ============ 启动入口智能重试 ============
    // 仅“冷启动通往论坛”链路生效。失败自动重试(上限 ENTRY_MAX_RETRY 次)，仍失败弹窗 + 手动重试。
    // 判定成功：onPageFinished 到达非发布链路域名(已进论坛/内容页) → endForumEntry(success=true)。
    // 进入论坛后普通浏览/断网不触发重试。

    /** 冷启动进入论坛：优先打开设置里填写的网址；为空才走「发布页 → 最新地址」自动跳转链路 */
    private fun beginForumEntry() {
        // 用户在设置里配置了网址：直接进，不进 entryMode 重试窗口(普通浏览不打扰)。
        val configured = Prefs.getUrl(this)
        if (configured.isNotBlank()) {
            entryMode = false
            entryFailCount = 0
            disarmEntryWatchdog()
            DebugLog.log("ENTRY", "使用设置网址进入: $configured")
            loadUrl(configured)
            return
        }
        // 未配置网址：走固定入口发布页 + 自动跳转 + 智能重试
        entryMode = true
        entryFailCount = 0
        autoJumpIssued = false
        DebugLog.log("ENTRY", "开始进入论坛（启动）")
        loadUrl("https://www.soushu2030.com")
        armEntryWatchdog()
    }

    /** 判定是否为可视为“内容页”的真实 http(s) 页面（排除 about:blank / data: / 空 url） */
    private fun isRealHttpPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        return low.startsWith("http://") || low.startsWith("https://")
    }

    /** 退出启动重试窗口 */
    private fun endForumEntry(success: Boolean) {
        disarmEntryWatchdog()
        if (!entryMode && success) return   // 已退出则忽略重复成功信号
        entryMode = false
        entryFailCount = 0
        DebugLog.log("ENTRY", if (success) "已进入论坛，退出启动重试" else "放弃启动重试")
    }

    /**
     * 成功进入论坛后，把当前论坛的站点根地址自动写入「设置 → 论坛网址」，
     * 下次启动即可直接进该站(不再走发布页跳转)。
     * 提取协议 + 主机(含端口)，去掉 path/query —— 如
     * https://dq3s.b4e5w4dqwde.com/forum.php?mod=viewthread&tid=... → https://dq3s.b4e5w4dqwde.com/
     */
    private fun rememberForumUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val u = java.net.URL(url)
            val root = "${u.protocol}://${u.host}" +
                (if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else "") + "/"
            // 已成功进入的论坛域名一并记为可信域名：论坛域名会轮换，下次重定向到它时不再被当站外链接
            u.host?.lowercase()?.trim()?.takeIf { it.isNotBlank() }?.let { h ->
                entryTrustedHost = h
                Prefs.addTrustedHost(this, h)
            }
            val existing = Prefs.getUrl(this)
            if (existing != root) {
                Prefs.setUrl(this, root)
                DebugLog.log("ENTRY", "已自动记录论坛网址: $root")
            }
        } catch (e: Exception) {
            DebugLog.log("ENTRY", "记录论坛网址失败: ${e.message}")
        }
    }

    /** URL 是否仍属于“发布链路”域名（soushu2030 入口 / 发布导航页） */
    private fun isEntryHostUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        return entryHostMarkers.any { low.contains(it) }
    }

    /**
     * 拦截指向「外链推广分区」的导航。
     *
     * 页面里的版块入口已由注入脚本摘掉，但面包屑、最新回复、搜索结果里仍可能留下这些 fid 的链接；
     * 这类分区是 Discuz 的「外部链接（redirect）」型，进去就是站外推广站，一律就地拦下并给一句提示。
     *
     * 必须排在 [interceptExternalNav] **之前**判定：点外链分区时浏览器先停在站内
     * `forum.php?mod=forumdisplay&fid=N`，由服务端回301/302 才跳站外。
     * 若先跑站外判断，这次站内请求不会被拦，页面会闪一下才跳走。
     */
    private fun interceptExternalForum(url: String): Boolean {
        if (!AdBlocker.isHiddenForumUrl(url)) return false
        val name = AdBlocker.forumNameOf(AdBlocker.forumIdOf(url))
        DebugLog.log("NAV", "拦截外链推广分区: $url")
        Toast.makeText(
            this,
            if (name != null) "「$name」是外链推广站，已屏蔽" else "该版块为外链推广站，已屏蔽",
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /**
     * 安全加固：拦截站外 http(s) 链接，交给系统浏览器打开（不在 App 内 WebView 加载），
     * 避免 JS 桥暴露给任意第三方页面。论坛主站域名(设置/最近页面)与发布入口域名放行。
     */
    private fun interceptExternalNav(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isEntryHostUrl(url)) return false
        val h = try { Uri.parse(url).host?.lowercase()?.trim() } catch (e: Exception) { null }
            ?: return false
        val allowedHosts = mutableListOf<String>()
        Prefs.getUrl(this).let { u ->
            try { Uri.parse(u).host?.lowercase()?.trim()?.let { allowedHosts.add(it) } } catch (_: Exception) {}
        }
        lastContentPageUrl?.let { u ->
            try { Uri.parse(u).host?.lowercase()?.trim()?.let { allowedHosts.add(it) } } catch (_: Exception) {}
        }
        // 发布页自动跳转命中的论坛域名（运行时才得知的动态域名）与历史记录过的论坛域名
        entryTrustedHost?.let { allowedHosts.add(it) }
        try { allowedHosts.addAll(Prefs.getTrustedHosts(this)) } catch (_: Exception) {}
        if (allowedHosts.any { h == it || h.endsWith(".$it") }) return false
        DebugLog.log("NAV", "站外链接转系统浏览器: $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /** 记录浏览历史（仅真实 http(s) 页面，排除 about:blank/data:） */
    private fun recordHistory(view: WebView?, url: String?) {
        if (url.isNullOrBlank() || !isRealHttpPage(url)) return
        val title = (view?.title ?: webView.title)?.takeIf { it.isNotBlank() } ?: url
        HistoryStore.add(this, url, title)
    }

    /** 判定“打不开”类网络错误码（DNS/连接/超时/IO 等），忽略 HTTP 状态与文件类错误 */
    private fun isEntryNetworkError(code: Int): Boolean {
        return code == android.webkit.WebViewClient.ERROR_UNKNOWN ||        // -1
            code == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||       // -2 DNS
            code == android.webkit.WebViewClient.ERROR_CONNECT ||           // -6 无法连接
            code == android.webkit.WebViewClient.ERROR_TIMEOUT ||           // -8 超时
            code == android.webkit.WebViewClient.ERROR_IO ||                // -7 网络 IO
            code == android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE // -11
    }

    /** 启动链路失败统一入口（网络错误 / 看门狗超时）：自动重试，达上限弹窗 + 手动重试 */
    private fun recordEntryFailure() {
        if (!entryMode) return
        entryFailCount++
        DebugLog.log("ENTRY", "启动进入失败，第 $entryFailCount/$ENTRY_MAX_RETRY 次")
        if (entryFailCount < ENTRY_MAX_RETRY) {
            autoJumpIssued = false   // 即将从入口页重走整条链路，跳转标记复位
            armEntryWatchdog()
            webView.postDelayed({
                // 延迟期间若已成功进入(用户已开始浏览)则不再重试
                if (entryMode) {
                    DebugLog.log("ENTRY", "自动重试进入…")
                    loadUrl("https://www.soushu2030.com")
                }
            }, ENTRY_RETRY_DELAY_MS)
        } else {
            endForumEntry(success = false)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("暂时无法访问")
                .setMessage("网站暂时打不开，请检查网络后重试，或稍后再试。")
                .setCancelable(false)
                .setPositiveButton("重试") { _, _ -> beginForumEntry() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 进入看门狗：进入窗口内若一直没到达真实内容页（可能静默停在 about:blank），超时视为一次失败并重试 */
    private fun armEntryWatchdog() {
        disarmEntryWatchdog()
        val run = Runnable {
            if (!entryMode) return@Runnable
            DebugLog.log("ENTRY", "进入看门狗超时：仍未进入论坛")
            recordEntryFailure()
        }
        entryWatchdog = run
        webView.postDelayed(run, if (autoJumpIssued) ENTRY_WATCHDOG_AFTER_JUMP_MS else ENTRY_WATCHDOG_MS)
    }

    private fun disarmEntryWatchdog() {
        entryWatchdog?.let { webView.removeCallbacks(it) }
        entryWatchdog = null
    }

    /** JS 桥接对象：浏览器通道（页面内 fetch）分块回传。运行在独立 JS 线程，UI 操作需切回主线程 */
    private inner class DownloadBridge {

        @JavascriptInterface
        fun logClick(info: String?) {
            if (!info.isNullOrBlank()) DebugLog.log("CLICK", info)
        }

        /**
         * 页面清理脚本发现的「外链推广分区」fid（逗号分隔）转交 [AdBlocker] 记账。
         *
         * 这样站点新增外链分区时，**导航层拦截（面包屑/最新回复/搜索结果里的残留链接）
         * 也会自动跟上**，不必改代码重装。参数来自页面自身文本，无需校验。
         */
        @JavascriptInterface
        fun reportExternalForums(csv: String?) {
            if (csv.isNullOrBlank()) return
            AdBlocker.rememberExternalFids(csv)
        }

        /**
         * 页面清理脚本上报的外链分区名，让拦截提示能说出具体是哪个分区（如「赚币攻略是外链推广站」）。
         */
        @JavascriptInterface
        fun reportExternalForumName(fid: Int, name: String?) {
            AdBlocker.rememberForumName(fid, name)
        }

        @JavascriptInterface
        fun fetchBegin(cd: String?, mime: String?) {
            val u = pendingFetchUrl ?: return
            fetchNameHint = nameHintForUrl(u)
            fetchFileName = try {
                DownloadHelper.resolveFileName(u, if (cd.isNullOrBlank()) null else cd, fetchNameHint)
            } catch (e: Exception) { "download_" + System.currentTimeMillis() }
            fetchB64.setLength(0)
            DebugLog.log("FETCH", "浏览器通道命名: $fetchFileName | cd=$cd | mime=$mime | hint=$fetchNameHint")
        }

        /**
         * 附件真实文件名上报（注入脚本从帖子 DOM 的 `<span class="attachname">` 读出）。
         * 点下载时 contentDisposition 为 null、URL 只是脚本页，原生侧只能靠这里拿到帖子里的文件名。
         */
        @JavascriptInterface
        fun attachName(auditId: String?, name: String?) {
            if (auditId.isNullOrBlank() || name.isNullOrBlank()) return
            attachNames[auditId.trim()] = name.trim()
            DebugLog.log("ATT", "附件名上报: $auditId -> $name")
        }

        @JavascriptInterface
        fun fetchChunk(part: String?) {
            if (part != null) fetchB64.append(part)
        }

        @JavascriptInterface
        fun fetchEnd() {
            val data = try {
                android.util.Base64.decode(fetchB64.toString(), android.util.Base64.DEFAULT)
            } catch (e: Exception) { null }
            fetchB64.setLength(0)
            runOnUiThread {
                if (data == null) {
                    DebugLog.log("FETCH", "base64 解码失败")
                    Toast.makeText(this@MainActivity, "下载失败：数据解码错误", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val savedName = DownloadHelper.save(this@MainActivity, data, fetchFileName, !fetchNameHint.isNullOrBlank())
                    fetchFileName = savedName
                    DebugLog.log("FETCH", "保存成功: $savedName (${data.size}B)")
                    Toast.makeText(
                        this@MainActivity,
                        "下载完成：$savedName\n保存于 Download/${Prefs.getDownloadDir(this@MainActivity)}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    DebugLog.log("FETCH", "保存失败: ${e.message}")
                    Toast.makeText(this@MainActivity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun fetchFail(info: String?) {
            DebugLog.log("FETCH", "失败: $info")
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "下载失败：服务器返回网页（${info ?: "未知原因"}）\n请确认已在论坛登录，且自定义脚本已生效",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 浏览器通道状态
    private var pendingFetchUrl: String? = null
    private var fetchFileName: String = ""
    private var fetchNameHint: String? = null
    private val fetchB64 = StringBuilder()

    /**
     * 附件真实文件名映射：附件 id → 帖子内显示的文件名（由注入脚本从 DOM 上报）。
     * Discuz 帖子页的文件名在 `<span class="attachname">` 里，而下载链接文字固定是「下载」，
     * 下载时响应头又没有 filename，所以必须靠这张表才能命名为帖子里的文件名。
     * 只在内存保留、天然随页面跳转覆盖，无需清理策略。
     */
    private val attachNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 从附件下载 URL 求「附件 id」（与注入脚本 auditOf 同一套规则）：
     * `?aid=<urlencode(base64)>` 解码后形如 `4563314|61f7b6c2|...`，取 `|` 前第一段；
     * aid 本身是纯数字时直接用。免银币伪造签名 `aid|1|1|1|tid` 同样取第一段。
     */
    private fun auditIdOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val m = Regex("[?&]aid=([^&#]+)").find(url) ?: return null
        val raw = try {
            java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
        } catch (e: Exception) { m.groupValues[1] }
        if (raw.all { it.isDigit() } && raw.isNotEmpty()) return raw
        return try {
            // base64 里可能含 '+'，URLDecoder 会把它解成空格，需还原
            val b64 = raw.replace(' ', '+')
            val decoded = String(
                android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
            decoded.substringBefore('|').filter { it.isLetterOrDigit() }.ifBlank { null }
        } catch (e: Exception) { null }
    }

    /** 查附件 URL 对应的「帖子内文件名」，查不到返回 null */
    private fun nameHintForUrl(url: String?): String? {
        val id = auditIdOf(url) ?: return null
        return attachNames[id]
    }

    /**
     * 浏览器通道下载：原生请求被服务器返回网页拦截时，改用页面内 fetch 获取文件
     * （网络栈/Cookie/会话与真实浏览器完全一致，可过 WAF 挑战与防盗链），
     * 文件内容转 base64 分块经 JS 桥回传（每块 512KB，避免 Binder 事务上限），原生侧保存。
     */
    private fun runBrowserFetch(url: String) {
        DebugLog.log("FETCH", "启动浏览器通道: $url")
        Toast.makeText(this, "正在通过浏览器通道下载…", Toast.LENGTH_SHORT).show()
        val safe = url.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
(async function(){
  try{
    var seen={};
    async function get(u, depth){
      if(depth>5) throw new Error('下载跳转超过5层');
      if(seen[u]) throw new Error('下载地址循环跳转');
      seen[u]=1;
      var r=await fetch(u,{credentials:'include',redirect:'follow'});
      var ct=r.headers.get('content-type')||'';
      if(/text\/html|xhtml/i.test(ct)){
        var h=await r.text(), title='', target=null;
        var doc=new DOMParser().parseFromString(h,'text/html');
        var titleNode=doc.querySelector('title');
        if(titleNode) title=(titleNode.textContent||'').trim();
        var meta=doc.querySelector('meta[http-equiv="refresh"],meta[http-equiv="Refresh"]');
        if(meta){
          var mc=meta.getAttribute('content')||'';
          var mi=mc.toLowerCase().indexOf('url=');
          if(mi>=0) target=mc.substring(mi+4).trim().replace(/^['\"]|['\"]$/g,'');
        }
        if(!target){
          var scripts=doc.querySelectorAll('script');
          for(var si=0;si<scripts.length&&!target;si++){
            var st=scripts[si].textContent||'';
            var keys=['location.href','location.replace','window.location','window.open','open('];
            for(var ki=0;ki<keys.length&&!target;ki++){
              var pos=st.toLowerCase().indexOf(keys[ki].toLowerCase());
              if(pos<0) continue;
              var q1=st.indexOf("'",pos), q2=st.indexOf('"',pos);
              var q=q1>=0 && (q2<0 || q1<q2) ? q1 : q2;
              if(q>=0){
                var end=st.indexOf(st.charAt(q),q+1);
                if(end>q) target=st.substring(q+1,end);
              }
            }
          }
        }
        if(!target){
          var els=doc.querySelectorAll('a[href],form[action],iframe[src],button[data-url],*[data-href]');
          for(var i=0;i<els.length;i++){
            var el=els[i], x=el.getAttribute('href')||el.getAttribute('action')||el.getAttribute('src')||el.getAttribute('data-url')||el.getAttribute('data-href')||'';
            var tx=((el.textContent||'')+' '+(el.getAttribute('onclick')||'')+' '+x);
            var xl=x.toLowerCase();
            var isSearch=xl.indexOf('search.php')>=0 || xl.indexOf('searchsubmit')>=0;
            var isDownload=/(下载|重新下载|附件|download|attachment|\\.txt(?:[?#]|$)|\\.zip(?:[?#]|$)|\\.rar(?:[?#]|$))/i.test(tx);
            if(x && !/^javascript:/i.test(x) && !isSearch && isDownload){ target=x; break; }
          }
        }
        if(target){
          target=target.replace(/&amp;/g,'&');
          return await get(new URL(target,r.url).href,depth+1);
        }
        throw new Error((title||'服务器返回网页')+'：未找到下一层下载地址');
      }
      var cd=r.headers.get('content-disposition')||'';
      var buf=await r.arrayBuffer(), b=new Uint8Array(buf), bin='';
      for(var k=0;k<b.length;k+=32768){ bin+=String.fromCharCode.apply(null,b.subarray(k,k+32768)); }
      var b64=btoa(bin), P=524288;
      window.DiscuzApp.fetchBegin(cd,ct);
      for(var j=0;j<b64.length;j+=P) window.DiscuzApp.fetchChunk(b64.substr(j,P));
      window.DiscuzApp.fetchEnd();
    }
    await get('$safe',0);
  }catch(e){ window.DiscuzApp.fetchFail(String(e)); }
})();
""".trimIndent()
        pendingFetchUrl = url
        webView.evaluateJavascript(js, null)
    }

    /**
     * 判断是否为「免银币下载」伪造签名链接：URL 含 mod=attachment，aid 为 base64，
     * 解码后形如 `aid|sign|timestamp|uid|tid`，其中 sign 与 timestamp 都被脚本写成 `1`。
     */
    private fun isFakeFreeDownloadUrl(url: String): Boolean {
        if (!isDirectAttachmentUrl(url)) return false
        val m = Regex("[?&]aid=([^&]+)").find(url) ?: return false
        val aid = m.groupValues[1]
        return try {
            val decoded = String(
                android.util.Base64.decode(
                    android.net.Uri.decode(aid).replace(' ', '+'),
                    android.util.Base64.DEFAULT
                ),
                Charsets.UTF_8
            )
            val parts = decoded.split('|')
            parts.size >= 4 && parts[1] == "1" && parts[2] == "1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 「免银币下载」静默处理：伪造签名链接不导航 WebView，后台请求解析出
     * 「重新下载」真实链接后直接下载，页面保持当前帖子页不动。
     */
    private fun handleFreeSilverDownload(url: String) {
        val cleanUrl = url.replace("&amp;", "&")
        val referer = lastContentPageUrl ?: webView.url
        DebugLog.log("FREE-DL", "静默免银币下载: $cleanUrl | referer=$referer")
        Toast.makeText(this, "开始下载…", Toast.LENGTH_SHORT).show()
        Thread {
            val real = DownloadHelper.resolveFreeSilverDownload(cachedUserAgent, cleanUrl, referer)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (real != null) {
                    DebugLog.log("FREE-DL", "解析到真实链接，静默下载: $real")
                    onDownloadStart(real, null, null)
                } else {
                    Toast.makeText(this, "下载失败：未能解析到附件地址", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 论坛附件端点：不要把附件跳转页交给 WebView 渲染，直接走自研下载器。 */
    private fun isDirectAttachmentUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("mod=attachment") || l.contains("attachment.php")
    }

    /** 直接处理附件地址（不显示确认弹窗、不要求二次点击）。 */
    private fun startDirectAttachment(url: String) {
        DebugLog.log("DOWNLOAD", "直接接管附件: $url")
        onDownloadStart(url, null, null)
    }

    /** 弹窗地址转发：空白页直接吞掉；附件直接下载；普通页面回主 WebView */
    private fun forwardPopupUrl(url: String?) {
        if (url.isNullOrBlank()) return
        val lower = url.lowercase()
        if (lower.startsWith("about:") || lower.startsWith("javascript:")) {
            DebugLog.log("POPUP", "吞掉: $url")
            return
        }
        // 附件：直接接管下载，不再等第二次点击
        if (isDirectAttachmentUrl(url)) {
            startDirectAttachment(url)
            return
        }
        DebugLog.log("POPUP", "转发普通页面到主 WebView: $url")
        webView.loadUrl(url)
    }

    private fun onDownloadStart(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrBlank()) return
        val httpUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            // 相对路径：以当前页面地址为基准补全
            val base = webView.url ?: return run {
                Toast.makeText(this, "暂不支持此类下载链接", Toast.LENGTH_SHORT).show()
            }
            try {
                java.net.URL(java.net.URL(base), url).toString()
            } catch (e: Exception) {
                Toast.makeText(this, "暂不支持此类下载链接", Toast.LENGTH_SHORT).show()
                return
            }
        }
        // Android 9 及以下写公共目录需要存储权限
        if (Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            Toast.makeText(this, "请授予存储权限后重新点击下载", Toast.LENGTH_LONG).show()
            return
        }
        // v1.8.x：无确认弹窗，检测到文件立即自动下载（原生通道，失败自动切浏览器通道）
        // 文件名优先级：帖子内文件名（DOM 上报）→ 响应头 Content-Disposition → URL 兜底
        val hint = nameHintForUrl(httpUrl)
        val fileName = try {
            DownloadHelper.resolveFileName(httpUrl, contentDisposition, hint)
        } catch (e: Exception) { "download_" + System.currentTimeMillis() }
        DebugLog.log("DL", "开始原生下载: $httpUrl | mime=$mimeType | cd=$contentDisposition | hint=$hint | 命名=$fileName")
        val nameDisplay = if (fileName.startsWith("download_")) "自动识别文件名" else fileName
        Toast.makeText(this, "开始下载：$nameDisplay", Toast.LENGTH_SHORT).show()
        DownloadHelper.start(
            this, webView.settings.userAgentString, httpUrl,
            contentDisposition, webView.url,
            { origUrl -> runBrowserFetch(origUrl) },
            hint
        )
    }

    /** UA 由设置页开关决定，默认电脑版 */
    private fun buildUserAgent(): String {
        return if (Prefs.isDesktopMode(this)) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
    }

    private fun handleUrl(url: String): Boolean {
        val lower = url.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> false // 站内跳转，同 WebView 加载
            lower.startsWith("javascript:") -> true // 忽略 javascript 伪协议
            lower.startsWith("about:") -> true
            else -> {
                // 外部协议（mailto / intent / market 等）交给系统
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun loadUrl(url: String) {
        val normalized = Prefs.normalizeUrl(url)
        lastLoadedUrl = url
        webView.loadUrl(normalized)
    }

    private fun goHome() {
        val url = Prefs.getUrl(this)
        if (url.isNotBlank()) loadUrl(url)
        else Toast.makeText(this, "未配置网址", Toast.LENGTH_SHORT).show()
    }

    private fun updateTitle() {
        val t = webView.title
        supportActionBar?.title = if (t.isNullOrBlank()) "小说论坛" else t
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // 根据诊断功能开关（启动时按设置初始化，重启才生效），控制「诊断日志」菜单项可见性
        menu?.findItem(R.id.action_debug)?.isVisible = DebugLog.isEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stop -> { webView.stopLoading(); true }
            R.id.action_refresh -> {
                // 强制刷新：清缓存后重新加载，忽略 HTTP 缓存
                webView.clearCache(true)
                webView.reload()
                true
            }
            R.id.action_home -> { goHome(); true }
            R.id.action_downloads -> {
                startActivity(Intent(this, DownloadsActivity::class.java))
                true
            }
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_debug -> {
                showDebugLog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** 诊断日志弹窗：查看 / 复制 / 清空，用于远程定位下载/重命名问题 */
    private fun showDebugLog() {
        val tv = android.widget.TextView(this).apply {
            text = DebugLog.dump().ifBlank { "（暂无日志，请先去页面点击一次下载按钮再回来看）" }
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        val sv = android.widget.ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("诊断日志")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .setNegativeButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("debug_log", DebugLog.dump()))
                Toast.makeText(this, "已复制，可粘贴发送", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空") { _, _ -> DebugLog.clear() }
            .show()
    }
}
