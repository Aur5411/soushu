package com.discuz.novel


/**
 * 外链推广分区（点了就302 到站外推广站的分区）屏蔽。
 *
 * 依据是搜书吧首页真实快照与 Discuz! X3.4 官方模板源码，不是猜的：
 *
 *  -搜书吧论坛（`sp6m.fwsefwef66s.com` 快照，GBK 编码）首页共 13 个版块格子，
 *    其中 6 个的外链分区渲染成`<dd><a href="...">链接到外部地址</a></dd>`：
 *    fid=53 自助改名、78 赚币攻略、97 帮助中心、85 岛国APP、86 韩漫在线、73 搜书网址发布器下载。
 *  - 判据来自 Discuz 官方模板 `template/default/forum/discuz.php` 与
 *    `forumdisplay_subforum.php`：`{lang url_link}`（语言包 `lang_template.php`
 *    → 中文「链接到外部地址」）**只在 `$forum['redirect']` 分支内输出**。
 *    所以「版块列表格子文本含该文案」严格等价于「该分区是 redirect 型外链推广分区」，
 *    **与 fid 无关** —— 站点日后新增多少外链分区都会被自动命中，不必改代码重装。
 *  - 同时兼容繁体「鏈接到外部地址」与英文 `url_link`，防站点改语言包后漏判。
 *  - 实测分布：仅版块列表页的 `td.fl_g` 命中，帖子页/主题页不出现，绝不误伤正文。
 *
 * 做法与 SXSY（尚香书院，X3.5）一致，并同样让**页面层与导航层联动**：
 * 页面每发现一个此前未知的外链分区，就通过 JS 桥把 fid 与分区名回传原生，
 * 于是面包屑 / 最新回复 / 搜索结果里残留的外链分区链接也会被导航层拦下。
 */
object AdBlocker {

    /** Discuz 语言包 `url_link` 的中文原文，外链 redirect 型分区在版块列表里必渲染这句 */
    private val EXT_LINK_MARKS = listOf("链接到外部地址", "鏈接到外部地址", "url_link")

    /**
     * 运行期动态发现的外链分区 fid。
     *
     * 只增不减：站点若把某个分区改成内网，最多是那一个分区多点打不开，
     * 不会把正常分区误隐藏 —— 广告宁可漏放，也不能误伤内容。
     */
    private val learnedExternalFids: MutableSet<Int> = LinkedHashSet()

    /** 外链分区名，仅用于拦截提示文案 */
    private val forumNames: MutableMap<Int, String> = HashMap()

    /** 快照里已知的外链分区名（仅作日志对照，不参与判定） */
    init {
        forumNames[53] = "自助改名"
        forumNames[73] = "搜书网址发布器下载"
        forumNames[78] = "赚币攻略"
        forumNames[85] = "岛国APP"
        forumNames[86] = "韩漫在线"
        forumNames[97] = "帮助中心"
    }

    /**
     * 该地址是否**主题列表页**（某个分区的帖子列表）。
     *
     * 用于「隐藏站内内容广告帖」——这类广告帖只出现在主题列表里：
     *  - `forum.php?mod=forumdisplay&fid=N`（分区主题列表）
     *  - 带标签/分类筛选的同类页：`...&filter=typeid&typeid=N`
     *  - 伪静态形态：`/forum-39-1.html`
     *
     * 帖子页（`viewthread&tid=`）与首页一律排除：帖子页不该动用户的正在读的内容，
     * 首页只排版块不排主题（首页由 [isForumHomePage] 负责）。
     */
    /**
     * 取 URL 的查询参数值（android.net.Uri 没有 queryParameter 这个 API，手写一份）。
     * 参数名大小写不敏感；%XX 解码，+ 还原成空格。
     */
    private fun queryParam(url: String, key: String): String? {
        val qi = url.indexOf('?')
        if (qi < 0) return null
        val hash = url.indexOf('#', qi)
        val query = if (hash > 0) url.substring(qi + 1, hash) else url.substring(qi + 1)
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (!k.equals(key, ignoreCase = true)) continue
            val raw = if (eq >= 0) pair.substring(eq + 1) else ""
            return try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
        }
        return null
    }

    /** 只取路径部分并小写（不含 query / fragment） */
    private fun pathOf(url: String): String {
        var v = url
        val qi = v.indexOf('?')
        if (qi >= 0) v = v.substring(0, qi)
        val hi = v.indexOf('#')
        if (hi >= 0) v = v.substring(0, hi)
        return v.lowercase()
    }

    fun isThreadListPage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val path = pathOf(url)
        val mod = (queryParam(url, "mod") ?: "").lowercase()

        // 帖子详情页 / 单主题页：绝不碰
        if (mod == "viewthread") return false
        if (queryParam(url, "tid") != null) return false
        // 分区主题列表
        if (mod == "forumdisplay") return true
        // 伪静态分区页 /forum-39-1.html（首页 /forum.php 已被 path 形态排除）
        if (Regex("/forum-\\d+-\\d+\\.html$").containsMatchIn(path)) return true
        return false
    }

    /** 该地址是否**论坛首页**（版块总览页）。
     *
     * 清理脚本**只允许在首页生效**——这是「只改首页，其他页面一律不动」的代码保证，
     * 而不是碰巧首页才有那个文案：
     *  - 首页 Discuz 输出的是版块总览（`forum.php` / `index.php` / `portal.php` / `forum.php?mod=index`），
     *    外链分区就排在这页的 `table.fl_tb` 里；
     *  - 分区页 `forumdisplay&fid=N` 也会渲染 `table.fl_tb`（含子版块），那里删格子会打乱帖子列表页版式，
     *    所以明确排除；
     *  - 帖子页 `viewthread&tid=N`、主题页等本来就没有外链分区，排除后连 DOM 都不用碰，开销更小。
     */
    fun isForumHomePage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val path = pathOf(url)
        val mod = (queryParam(url, "mod") ?: "").lowercase()

        // 带 mod 参数的页面：只放行 mod=index（首页的等价入口）
        if (mod.isNotEmpty()) return mod == "index"
        // 出现分区/帖子/主题等参数，一律不是首页
        if (queryParam(url, "fid") != null) return false
        if (queryParam(url, "tid") != null) return false
        if (queryParam(url, "goto") != null) return false
        // 路径型首页：/ 或 /forum.php / /index.php / /portal.php
        // 注意排除 /forum-39-1.html 这类伪静态分区页（它属于分区页，不是首页）
        val p = path.substringAfterLast('/')
        return p.isEmpty() || p == "forum.php" || p == "index.php" || p == "portal.php"
    }

    /**
 * 主题列表页的「站内内容广告帖」隐藏（与外链分区是两类不同的东西）。
 *
 * 依据是搜书吧分区页真实快照（`fid=39`，164KB）：
 *  - 这类帖在标题链接上带 Discuz「content广告位」插件的官方标记：
 *    `<a id="content_adver&aid=5" ... onclick="CONTENT_TID='adver&aid=5'">`，
 *    标题链接则是 `forum.php?mod=viewthread&tid=adver&aid=5&...`。
 *  - **判据：标题链接的 `tid` 不是纯数字**（形如 `adver&aid=N`）。
 *    实测该页60 条主题里，非数字 tid 只有 `adver` 这一个取值，
 *    其余全部是正常数字 tid —— 所以这个判据既不会漏，也不会误伤正常主题。
 *  - 典型标题：「H韩漫在线无限制观看,会员招募中,详情见本贴」「国产资源app,适合午夜观看」。
 *
 * 注意：**不能按中文标题关键词匹配**。标题随时会被换文案，按结构标记判定才一劳永逸。
 * 这些帖的 tbody id 是正常的 `normalthread_<数字>` / `stickthread_<数字>`，
 * 只能靠标题链接的 tid 形态认出，所以整块 tbody 摘掉、连分隔线一起清。
 */
fun contentAdJs(): String {
        return """
(function(){
  if(window.__dzContentAd) return;
  window.__dzContentAd=1;
  try{
  function tidOfTbody(tb){
    // 只看主题标题链接(a.s.xst)：它是这一行 tid 的权威来源
    var a=tb.querySelector('a.s.xst');
    if(!a) return '';
    var h=a.getAttribute('href')||'';
    var m=h.match(/[?&]tid=([^&#]*)/);
    if(!m) return '';
    var t=m[1];
    // Discuz 会把 & 写成 &amp;，这里只取第一段（adver），足够判定
    t=t.replace(/&amp;/g,'&');
    var amp=t.indexOf('&');
    if(amp>0) t=t.slice(0,amp);
    try{ return decodeURIComponent(t); }catch(e){ return t; }
  }
  function hasAdMark(tb){
    // Discuz 内容广告插件的标记。用 querySelectorAll 全量查 —— 同一页可能有多条广告
    // 共用同一个 tbody id（实测 fid=39页里aid=4 与 aid=5 就是），单数 querySelector
    // 只返回第一个，会漏掉其余的。
    var marks=tb.querySelectorAll('a[id^="content_adver"]');
    if(marks && marks.length>0) return true;
    var html=tb.innerHTML||'';
    return html.indexOf('CONTENT_TID=')>=0 || html.indexOf('content_adver')>=0;
  }
  var tbs=document.querySelectorAll('tbody'), n=0, i, tb;
  // 先收集再删除：边遍历边删会因 DOM 变动漏掉后面的节点
  var kill=[];
  for(i=0;i<tbs.length;i++){
    tb=tbs[i];
    if(!tb.parentNode) continue;
    var tid=tidOfTbody(tb);
    if(!tid) continue;
    // 纯数字 tid = 正常主题；非数字 = Discuz 广告位（站点实测唯一取值 adver）
    if(/^\d+$/.test(tid)) continue;
    if(!hasAdMark(tb)) continue;
    kill.push(tb);
  }
  for(i=0;i<kill.length;i++){ if(kill[i].parentNode) kill[i].parentNode.removeChild(kill[i]); n++; }
  // 广告帖被摘后，Discuz 留下的分隔线(<tbody id="separatorline">)会孤零零留在列表里，一并清掉。
  // 判据很保守：整块里没有任何主题链接、文本也几乎为空，才认定为「被摘广告留下的残骸」。
  var seps=document.querySelectorAll('tbody[id="separatorline"]');
  for(i=seps.length-1;i>=0;i--){
    var e=seps[i];
    if(!e.parentNode) continue;
    if(e.querySelector('a.s.xst')) continue;      // 里面还有主题 —— 是真的分隔线，留着
    if((e.textContent||'').replace(/\s|\u00a0/g,'').length>2) continue; // 有实质内容，留着
    e.parentNode.removeChild(e);
  }
  try{ if(window.DiscuzApp && window.DiscuzApp.logClick){ window.DiscuzApp.logClick('AD: 隐藏内容广告帖 '+n+' 条'); } }catch(e){}
  }catch(e){}
  // 无论成败都释放锁定：上一次若抛异常，这里保证下次注入还能再跑一遍
  window.__dzContentAd=0;
})();
""".trimIndent()
    }

    /** 该地址是否指向已知外链分区（forumdisplay&fid=N / forum-N-1.html） */
    fun isHiddenForumUrl(url: String?): Boolean {
        val fid = forumIdOf(url) ?: return false
        synchronized(learnedExternalFids) { return fid in learnedExternalFids }
    }

    /** 从 URL 里取版块 id */
    fun forumIdOf(url: String?): Int? {
        if (url.isNullOrBlank()) return null
        Regex("[?&]fid=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("/forum-(\\d+)-").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** 外链分区名（仅用于提示文案，可能为 null） */
    fun forumNameOf(fid: Int?): String? = fid?.let { forumNames[it] }

    /** 记录外链分区名，供拦截提示展示 */
    fun rememberForumName(fid: Int?, name: String?) {
        if (fid == null || fid <= 0 || name.isNullOrBlank()) return
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 24) return
        forumNames.putIfAbsent(fid, clean)
    }

    /**
     * JS 桥回调：页面清理脚本每发现一个此前未知的外链分区 fid 就上报一次。
     * 于是**导航层拦截**（面包屑 / 最新回复 / 搜索结果里的残留链接）也自动跟上，
     * 不再需要为了屏蔽一个新分区去改代码重装。
     */
    fun rememberExternalFids(csv: String?) {
        if (csv.isNullOrBlank()) return
        val added = ArrayList<String>()
        for (part in csv.split(',')) {
            val fid = part.trim().toIntOrNull() ?: continue
            if (fid <= 0) continue
            if (synchronized(learnedExternalFids) { learnedExternalFids.add(fid) }) {
                added.add(fid.toString())
            }
        }
        if (added.isNotEmpty()) {
            DebugLog.log("AD", "新发现外链分区 fid=${added.joinToString("/")}，已纳入屏蔽")
        }
    }

    /** 已学习到的外链分区 fid，供日志展示 */
    fun knownExternalFids(): List<Int> = synchronized(learnedExternalFids) {
        learnedExternalFids.sorted()
    }

    /**
     * 页面内清理脚本（onPageFinished 注入，此时文档已完整解析）。
     *
     * **判据**：只处理 `table.fl_tb`（版块列表）里的 `td.fl_g` 格子与 `li`（子版块列表），
     * 且只摘「文本含 Discuz 外链分区固定文案『链接到外部地址』」的那些格子。
     * 命中即等价于「该分区是外链推广分区」，站点新增 fid 也会自动命中。
     *
     * 安全阀（都来自 SXSY 那次修复的教训）：
     *  - 绝不按「这一行没文字就删掉」清理 —— 帖子页布局表格里到处是只放图片/占位符的行，
     *    删掉会破坏列宽导致整页排版错位；
     *  - 只在文档完整解析后执行，那时行才有内容；
     *  - 优先 CSS 隐藏以外的物理摘除仅限 `td.fl_g` 格子本身，不碰页面其它节点。
     */
    fun cleanupJs(): String {
        val marks = EXT_LINK_MARKS.joinToString(",") { "'" + it.replace("'", "") + "'" }
        return """
(function(){
  var MARKS=[$marks];
  var found={}, names={};
  function isExtText(s){
    if(!s) return false;
    for(var i=0;i<MARKS.length;i++){ if(s.indexOf(MARKS[i])>=0) return true; }
    return false;
  }
  function fidOfCell(cell){
    var as=cell.querySelectorAll('a[href]'), m;
    for(var i=0;i<as.length;i++){
      var h=as[i].getAttribute('href')||'';
      m=h.match(/[?&]fid=(\d+)/)||h.match(/\/forum-(\d+)-/);
      if(m) return parseInt(m[1],10);
    }
    return 0;
  }
  function nameOfCell(cell){
    var dt=cell.querySelector('dt'), a, t;
    if(!dt) return '';
    a=dt.querySelector('a');
    if(!a) return '';
    t=(a.textContent||'').replace(/\s|\u00a0/g,'');
    return t.length>24?t.slice(0,24):t;
  }
  var cells=[], sc=document.querySelectorAll('td.fl_g'), i;
  for(i=0;i<sc.length;i++) cells.push(sc[i]);
  sc=document.querySelectorAll('li.normal');
  for(i=0;i<sc.length;i++) cells.push(sc[i]);

  var rows=[], t, c, fid, ch, k, hasInner;
  for(t=0;t<cells.length;t++){
    c=cells[t];
    if(!isExtText(c.textContent)) continue;
    if(!c.parentNode) continue;
    // 只摘最内层的 fl_g：若是外层容器里的嵌套 fl_g，跳过避免连带
    hasInner=false; ch=c.children;
    for(k=0;k<ch.length;k++){
      if(ch[k].querySelector && ch[k].querySelector('td.fl_g')){ hasInner=true; break; }
    }
    if(hasInner) continue;
    fid=fidOfCell(c);
    if(fid>0){ found[fid]=1; names[fid]=nameOfCell(c); }
    var row=c.parentNode;
    if(row && row.nodeType===1 && (row.tagName==='TR'||row.tagName==='LI'||row.tagName==='UL')){
      rows.push(row);
    }
    if(row) row.removeChild(c);
  }

  // 摘完格子后，把因此变空且仍在版块表内的行也清掉（版式收紧，不留空洞）
  var tables=document.querySelectorAll('table.fl_tb'), j, row, inTable;
  for(j=0;j<rows.length;j++){
    row=rows[j];
    if(!row || !row.parentNode) continue;
    if(row.tagName==='TR'){
      inTable=false;
      for(t=0;t<tables.length;t++){ if(tables[t]===row.parentNode || tables[t].contains(row)){ inTable=true; break; } }
      if(!inTable) continue;
      if(row.querySelector('.fl_g')) continue;
      if((row.textContent||'').replace(/\s|\u00a0/g,'').length>0) continue;
      if(row.parentNode) row.parentNode.removeChild(row);
    }else if(row.tagName==='UL'){
      if((row.textContent||'').replace(/\s|\u00a0/g,'').length>0) continue;
      if(row.parentNode) row.parentNode.removeChild(row);
    }
  }

  // 双列布局补位：Discuz 版块表每行放 2 个格子，每个 `td.fl_g` 写死 width=49.9%。
  // 摘掉其中一个后，剩下那个仍占半行宽 —— 右边会空出一大块，整块区域看起来就"塌"了。
  // 所以这里给所有「因清理而只剩一个格子」的行补colspan=2 并把宽度改成 100%，
  // 让唯一剩下的分区正常撑满整行。这是纯版式修正，不改任何内容与顺序。
  var allRows=document.querySelectorAll('table.fl_tb tr'), z;
  for(z=0; z<allRows.length; z++){
    var r=allRows[z];
    var tds=[], kids=r.children, q;
    for(q=0;q<kids.length;q++){ if(kids[q].tagName==='TD') tds.push(kids[q]); }
    if(tds.length===1){
      var only=tds[0];
      var isForumCell = (only.className||'').indexOf('fl_g')>=0;
      if(isForumCell){
        only.setAttribute('colspan','2');
        only.setAttribute('width','100%');
        only.style.width='100%';
      }
    }
  }

  // 上报新发现的外链分区 fid 与名称，供原生导航层拦截自适应、提示更具体
  var ids=Object.keys(found);
  if(ids.length){
    try{
      if(window.DiscuzApp && window.DiscuzApp.reportExternalForums){
        window.DiscuzApp.reportExternalForums(ids.join(','));
        for(var q=0;q<ids.length;q++){
          if(names[ids[q]]) window.DiscuzApp.reportExternalForumName(parseInt(ids[q],10), names[ids[q]]);
        }
      }
    }catch(e){}
  }
})();
""".trimIndent()
    }
}