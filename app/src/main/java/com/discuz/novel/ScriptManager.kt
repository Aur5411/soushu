package com.discuz.novel

import android.content.Context
import android.webkit.WebView

/**
 * 页面脚本注入管理（每个页面加载完成后执行）：
 * 1. 去广告预设脚本（始终开启）
 * 2. 外链推广分区屏蔽（[AdBlocker]，始终开启）
 * 3. 用户自定义 JS 脚本（设置页可配置）
 *
 * 注：原内置的「搜书吧免银币下载 2.0」改链脚本与夜间模式 CSS 已按需求删除，
 * 应用不再内置其它任何脚本。
 */
object ScriptManager {

    /** 页面加载完成后统一入口：注入去广告脚本 + 自定义脚本。
     *
     *  [url] 为当前页面地址，用于「外链分区屏蔽」只作用于首页（见 [AdBlocker.isForumHomePage]）。 */
    fun inject(ctx: Context, webView: WebView, url: String? = null) {
        val js = buildJs(ctx, url)
        if (js.isNotBlank()) webView.evaluateJavascript(js, null)
    }

    private fun buildJs(ctx: Context, url: String? = null): String {
        val sb = StringBuilder()
        sb.append(adBlockJs())
        sb.append('\n')
        // 帖子/分区点击提速（必须最先注入，见 singleTapFixJs 的说明）
        sb.append(singleTapFixJs())
        sb.append('\n')
        // 外链推广分区（点了就302 到站外推广站的分区）整块摘除 —— **仅在论坛首页生效**。
        // 判据是 Discuz 官方文案「链接到外部地址」，与 fid 无关，站点新增外链分区自动命中；
        // 页面门禁保证分区页 / 帖子页 / 主题页一律不受影响。
        if (AdBlocker.isForumHomePage(url)) sb.append(AdBlocker.cleanupJs()).append('\n')
        // 隐藏主题列表里的站内内容广告帖（判据：标题链接 tid 非数字，即 Discuz content广告位插件）。
        // 这类帖只出现在分区主题列表页，所以门禁与上面的首页门禁分开。
        if (AdBlocker.isThreadListPage(url)) sb.append(AdBlocker.contentAdJs()).append('\n')
        sb.append(floatCloseFixJs())
        sb.append('\n')
        // 自动回复固定启用：与桌面自动回复脚本 v2.2.6 保持一致
        sb.append(autoReplyJs())
        sb.append('\n')
        // 附件「重新下载」自动重试：免银币脚本伪造签名下载时，Discuz 会返回
        // 「原附件链接已失效」提示页，页内有「点击这里重新下载」链接；自动点击之完成下载。
        sb.append(retryAttachmentJs())
        sb.append('\n')
        // 附件真实文件名上报：Discuz 帖子页的 <span class="attachname"> 里才有真实文件名，
        // 下载链接文字固定是「下载」。点下载时 contentDisposition 为 null，URL 又只是
        // forum.php?mod=attachment&aid=...，原生侧解析不出书名 —— 必须由本脚本先把
        // 文件名按 aid 上报给原生，下载时用它命名。
        sb.append(attachNameJs())
        val custom = Prefs.getCustomJs(ctx).trim()
        if (custom.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(custom)
        }
        return sb.toString()
    }

    // ---------------- Discuz 预设脚本 ----------------

    /**
     * 修「分区/帖子要点两下才进去」（v2.7.0）。
     *
     * 根因来自 Discuz 官方脚本 `static/js/forum.js:289`：
     * ```
     * function atarget(obj) {
     *   obj.target = getcookie('atarget') > 0 ? '_blank' : '';
     * }
     * ```
     * 主题列表里每个帖子链接都写了 `onclick="atarget(this)"`。它把 `target` 改成**空字符串**，
     * 而本App 为了接住跳转页的 `window.open` / `target=_blank` 开启了
     * `setSupportMultipleWindows(true)` —— 多窗口模式下 `target=''` 同样会触发 `onCreateWindow`。
     * 于是每次点击的实际链路是：
     * ```
     * 点一下 → onCreateWindow → 新建一个临时 popup WebView → popup 才开始请求
     *      → onPageStarted 里才 webView.loadUrl(url) → 页面终于出现
     * ```
     * 中间**多了一次 WebView 创建 + 一整轮网络请求**，主界面在 popup 请求完成前毫无反应，
     * 用户感知就是「点了没进去，再点一次」。第二次点击时页面已在前台，所以才「正常」。
     *
     * 修法：把 `atarget` 提前重定义成**同步把 target 定为 `_self`**。
     * 这样点击直接由主 WebView 处理，不进 onCreateWindow，少掉整整一跳。
     *
     * 注意：
     *  - 必须保留 `window.atarget`，Discuz 页面里还有 `setatarget()` 的开关 UI 依赖它；
     *  - 只改站内 http(s) 链接的 target，外链（站外推广站）仍交给原生拦截，不受影响；
     *  - 用捕获阶段监听 click 而不是改写每个元素的 onclick，改写会被 Discuz 重新渲染覆盖。
     */
    private fun singleTapFixJs(): String {
        return """
(function(){
  if(window.__dzSingleTap) return; window.__dzSingleTap=1;
  // Discuz 的 atarget：把「异步改写 target」换成「同步定为_self」，点击不再产生新窗口请求
  try{
    window.atarget=function(obj){
      try{
        if(!obj) return;
        obj.target='_self';
        // 站点若开启了「新窗口打开帖子」开关（atarget cookie），这里尊重用户选择不强制
        // —— 但 App 内本来就用同一个 WebView 承载，保持 _self 才能走单次点击直达。
      }catch(e){}
    };
  }catch(e){}
  // 捕获阶段兜底：万一 Discuz 用别的方式（内联 onclick 已被缓存、AJAX 重渲染）改了 target，
  // 点击瞬间同步纠正，保证链接在主 WebView 内直接打开。
  try{
    document.addEventListener('click', function(ev){
      var a=null;
      try{ a=ev.target && ev.target.closest ? ev.target.closest('a[href]') : null; }catch(e){ a=null; }
      if(!a) return;
      var href=a.getAttribute('href')||'';
      if(!href || href.charAt(0)==='#') return;
      // 只处理站内页面链接。站内链接既有绝对路径(https://host/...)也有相对路径(portal.php?mod=xx)，
      // 相对路径在多窗口模式下同样会走 onCreateWindow，所以不能只按 http(s) 前缀筛。
      // 排除协议类(非 http/https)即可 —— 原生层仍会照常拦截站外外链。
      if(/^[a-z][a-z0-9+.-]*:/i.test(href) && !/^https?:/i.test(href)) return;
      var t=a.getAttribute('target')||'';
      // 空 target 或 _self 都属主WebView 直开；仅当仍是 _blank 时才需要纠正
      if(t!=='_blank') return;
      try{ a.setAttribute('target','_self'); }catch(e){}
    }, true);
  }catch(e){}
})();
""".trimIndent()
    }

    /**
     * 隐藏 Discuz 常见广告容器与联盟广告。
     * v1.8.4：改用 CSS 隐藏而非 remove() —— 页面自身脚本可能引用这些节点，
     * 物理删除会导致脚本读 null 报错（如跳转页 null.length 崩溃），CSS 隐藏安全得多。
     */
    private fun adBlockJs(): String {
        return """
(function(){
  var css='#float_left,#float_right,.a_pt,.a_pb,.a_pr,.a_mu,.a_fl,.a_fr,#scbar_ad,#ad_content,.float_ad,#right_ads,.ads,.adbox,a[href*="cpro.baidu.com"],iframe[src*="pos.baidu.com"],div[id^="ad_"]{display:none !important;}';
  css+='.t_f img,.t_f a[href*="mod=attachment"] img,td.t_f img,img[id^="aimg_"],img.zoom{display:none!important;}';
  try{
    var s=document.createElement('style');
    s.type='text/css';
    s.appendChild(document.createTextNode(css));
    (document.head||document.documentElement).appendChild(s);
  }catch(e){}
})();
""".trimIndent()
    }

    /**
     * Discuz 浮层关闭兜底（v1.9.78 修复「购买附件后 register/login 浮层关不掉」）。
     *
     * 根因：站点装了 boan_h5upload 插件，页面里引入 jQuery 1.11 并执行 `$.noConflict()`，
     * 在 AJAX 动态加载的浮层内容(attachpay/register/login)里，Discuz 的 `$`(等价 document.getElementById)
     * 会被 jQuery 干扰——`$('fwin_register')` 不再按 id 查元素，导致 hideWindow 定位不到浮层、
     * 关不掉。而浮层内容是在 onPageFinished 之后才 AJAX 注入的，一次性脚本够不着。
     *
     * 做法：常驻注入，三重兜底：
     *  1) 用闭包保存「按 id 查元素」的可靠实现，重写 window.hideWindow：先走原逻辑，
     *     再无条件按 id 强制移除 fwin_* 元素 + 其 _cover 遮罩 + 清空 append_parent 里的孤儿浮层。
     *  2) 全局捕获阶段监听 click：命中 onclick 含 hideWindow(...) 的元素时，解析 key 强制关闭。
     *  3) MutationObserver 兜底：监听新插入的 fwin_* 浮层，保证修复在浮层出现后依然生效。
     * 全部 try/catch，绝不干扰页面自身正常脚本。
     */
    private fun floatCloseFixJs(): String {
        return """
(function(){
  if(window.__dzFloatFix) return; window.__dzFloatFix=1;
  function byId(id){ return document.getElementById(id); }
  // 强制关闭某个浮层 key：移除 fwin_<k> 与遮罩，清理 append_parent 下的孤儿浮层
  function forceClose(k){
    try{
      var el=byId('fwin_'+k);
      if(el && el.parentNode){ el.parentNode.removeChild(el); }
      var cover=byId('fwin_'+k+'_cover');
      if(cover && cover.parentNode){ cover.parentNode.removeChild(cover); }
    }catch(e){}
    // 兜底：移除所有 fwin_* 浮层（含遮罩），确保购买/登录/注册等任何浮层都能真正关掉
    try{
      var all=document.querySelectorAll('[id^="fwin_"]');
      for(var i=0;i<all.length;i++){
        var n=all[i];
        if(n && n.parentNode && /^fwin_/.test(n.id) && !/_content_/.test(n.id)){
          n.parentNode.removeChild(n);
        }
      }
    }catch(e){}
    try{
      var ap=byId('append_parent');
      if(ap){ ap.style.display='none'; }
    }catch(e){}
  }
  // 重写 hideWindow：先走原实现(若存在)，再强制 DOM 移除兜底
  try{
    var orig=window.hideWindow;
    window.hideWindow=function(k, all, clear){
      try{ if(orig) orig.apply(this, arguments); }catch(e){}
      forceClose(k);
    };
  }catch(e){}
  // 捕获阶段点击兜底：onclick 里写 hideWindow('xxx') 的关闭按钮，确保真正关掉
  try{
    document.addEventListener('click', function(e){
      var n=e.target, hops=0;
      while(n && n.nodeType===1 && hops++<6){
        var oc=(n.getAttribute&&n.getAttribute('onclick'))||'';
        var m=/hideWindow\s*\(\s*['"]([^'"]+)['"]/.exec(oc);
        if(m){ forceClose(m[1]); }
        n=n.parentNode;
      }
    }, true);
  }catch(e){}
  // MutationObserver：浮层是新插入的，监听以保证关闭能力始终存在
  try{
    var mo=new MutationObserver(function(muts){
      for(var i=0;i<muts.length;i++){
        var added=muts[i].addedNodes;
        for(var j=0;j<added.length;j++){
          var node=added[j];
          if(!node || node.nodeType!==1) continue;
          if(node.id && /^fwin_/.test(node.id)){
            // 浮层出现后，给内部所有 hideWindow 关闭按钮再兜底绑定一次
            try{
              var btns=node.querySelectorAll('[onclick*="hideWindow"]');
              for(var b=0;b<btns.length;b++){
                (function(btn){
                  btn.addEventListener('click', function(){
                    var m=/(['"])([^'"]+)\1/.exec(btn.getAttribute('onclick')||'');
                    if(m) forceClose(m[2]);
                  });
                })(btns[b]);
              }
            }catch(e){}
          }
        }
      }
    });
    mo.observe(document.documentElement||document.body, {childList:true, subtree:true});
  }catch(e){}
})();
""".trimIndent()
    }

    /**
     * 自动回复「回复可见」隐藏内容（v2.2.6，对齐桌面油猴脚本逻辑）。
     *
     * 触发前优先检测 61 秒冷却是否归零：未归零则弹出剩余倒计时并暂停回复，归零才继续。
     * 只在楼主 1 楼有 [hide] 回复可见提示、且无「可见且非回复门控」的付费/购买元素时静默回复。
     * 付费判断覆盖三种形态：attachpay 链接、购买/付费按钮、「售价 X 金币」标记（点击文件即付费的付费附件）；
     * 排除淘专辑(mod=collection)/标签(mod=tag)/合集等名字带「购买」的非付费导航链接。
     * 命中付费元素时输出诊断详情（desc()），便于定位具体按钮。
     * 提交成功后记录时间戳并刷新解锁；倒计时幂等、刷新后依然显示。
     */
    private fun autoReplyJs(): String {
        return """
(function(){
  if(window.__dzAutoReply) return; window.__dzAutoReply=1;
  var REPLY_TEXT='谢谢楼主辛苦分享，让我看看隐藏的内容';   // ≥15字，避免论坛字数限制
  var COOLDOWN_MS=61000;   // 回复成功后冷却 61 秒
  var pollCount=0;
  var payBtnCache=null;        // 付费按钮检测结果缓存（页面加载后不变，只扫一次）
  var lockedHintCache=null;    // 隐藏提示检测结果缓存

  function log(s){
    try{ if(window.console&&console.log) console.log('[自动回复] '+s); }catch(e){}
  }

  log('脚本已加载 URL='+location.href);

  function getLastSuccess(){
    try{ var v=localStorage.getItem('__dzLastReplySuccess'); return v?parseInt(v,10):0; }catch(e){ return 0; }
  }
  function setLastSuccess(){
    try{ localStorage.setItem('__dzLastReplySuccess', String(Date.now())); }catch(e){}
  }

  // 定位楼主 1 楼容器（付费按钮 / [hide] 提示都在楼主层）
  function getFirstFloor(){
    try{
      var f=document.querySelector('#postlist [id^="post_"]');
      if(f) return f;
      var m=document.querySelector('[id^="postmessage_"]');
      if(m) return m.closest('[id^="post_"]')||m;
      var p=document.querySelector('.plhin');
      if(p) return p;
      return null;
    }catch(e){ return null; }
  }

  // 元素是否「可见」：自身及祖先无 display:none / visibility:hidden / opacity:0，且非零尺寸
  function isVisible(el){
    if(!el) return false;
    try{
      var n=el;
      while(n && n.nodeType===1){
        var s=window.getComputedStyle(n);
        if(!s || s.display==='none' || s.visibility==='hidden') return false;
        if(parseFloat(s.opacity)===0) return false;
        n=n.parentElement;
      }
      var r=el.getBoundingClientRect();
      if(r.width===0 && r.height===0) return false;
      return true;
    }catch(e){ return true; }  // 计算失败时保守按可见处理，避免漏掉真付费
  }

  // 判断某个「购买/付费」按钮是否本质是「回复可见/0银」门控（回复即可解锁，不算真付费）
  function isReplyVisibleGate(el){
    try{
      var nodes=[], n=el;
      for(var k=0;k<3 && n && n.nodeType===1;k++){
        nodes.push(n);
        n=n.parentElement;
      }
      var t='';
      for(var k=0;k<nodes.length;k++) t += (nodes[k].textContent||'')+' ';
      return /回复可见|回复即可|回复后|0银|免费|回帖/.test(t);
    }catch(e){ return false; }
  }

  // 元素描述：用于诊断日志，输出标签/class/href/display/visibility + 一段 outerHTML
  function desc(el){
    try{
      var st=window.getComputedStyle(el);
      var cls=(typeof el.className==='string')?el.className:'';
      var href=(el.getAttribute&&el.getAttribute('href'))||'';
      var html=(el.outerHTML||'').replace(/\s+/g,' ').slice(0,180);
      return '<'+el.tagName.toLowerCase()+'> class='+cls+' href='+href+' disp='+st.display+' vis='+st.visibility+' | '+html;
    }catch(e){ return String(el); }
  }

  // 判断一个 <a> 链接是否指向「付费/购买」动作页；排除淘专辑(mod=collection)、标签(mod=tag)、
  // 合集等普通导航链接（这些只是名字里带"购买"字样，实际不是付费动作）
  function isPayHref(el){
    try{
      if(el.tagName && el.tagName.toLowerCase()!=='a') return false;
      var href=(el.getAttribute&&el.getAttribute('href'))||'';
      if(!href) return false;
      // 明确排除：淘专辑/合集、标签、空间、版块列表、帖子正文、门户等非付费链接
      // 注意 mod=misc&action=attachpay 是真正的付费附件链接，不能排除
      if(/mod=(collection|tag|space|forumdisplay|viewthread|guide)|space-uid|home\.php/.test(href)) return false;
      // 明确付费特征：attachpay / 购买动作 / 积分支付
      return /attachpay|action=(pay|buy)|buyattach|payto|credits/.test(href);
    }catch(e){ return false; }
  }

  // 检测「真正可见且非回复门控」的付费/购买按钮：藏在 [hide]（display:none）里的隐藏付费按钮不算（付费附件走 attachpay，回复不能解锁）。
  // 覆盖三种付费形态：attachpay 链接、购买/付费按钮、「售价」标记（点击文件即付费的付费附件）
  function hasPayButton(scope){
    try{
      var root=scope||document;
      if(!root) return false;
      // 1) attachpay 付费附件购买链接
      var links=root.querySelectorAll('a[href*="attachpay"]');
      for(var i=0;i<links.length;i++){
        if(isVisible(links[i]) && !isReplyVisibleGate(links[i])){
          log('检测到可见付费附件链接: '+desc(links[i]));
          return true;
        }
      }
      // 2) 「售价」标记：付费附件价格（点击文件即付费，链接可能是 mod=attachment 但带售价）
      var priceEls=root.querySelectorAll('span, em, strong, font, b, i, div, p');
      for(var k=0;k<priceEls.length;k++){
        var pt=(priceEls[k].textContent||'').replace(/\s+/g,'');
        if(/售价\s*[:：]?\s*\d+/.test(pt)){
          if(!isVisible(priceEls[k])){
            log('售价标记已隐藏(回复可见)，忽略: '+desc(priceEls[k]));
            continue;
          }
          if(isReplyVisibleGate(priceEls[k])){
            log('售价标记属「回复可见/0银」门控，不算真付费: '+desc(priceEls[k]));
            continue;
          }
          log('检测到付费附件售价标记: '+desc(priceEls[k]));
          return true;
        }
      }
      // 3) 购买/付费按钮（<a> 链接需过 isPayHref 排除淘专辑/标签等导航）
      var els=root.querySelectorAll('a, button, input[type="submit"], input[type="button"]');
      for(var j=0;j<els.length;j++){
        var t=((els[j].textContent||'')+(els[j].value||'')).replace(/\s+/g,'');
        if(t.indexOf('购买')>=0||t.indexOf('付费')>=0){
          // <a> 链接若 href 明确指向非付费页（淘专辑/标签/合集等），不算付费按钮
          var isA=(els[j].tagName&&els[j].tagName.toLowerCase()==='a');
          if(isA && !isPayHref(els[j])){
            log('「购买」字样链接指向非付费页，忽略: '+desc(els[j]));
            continue;
          }
          if(!isVisible(els[j])){
            log('付费按钮已隐藏(回复可见)，忽略: '+desc(els[j]));
            continue;
          }
          if(isReplyVisibleGate(els[j])){
            log('付费按钮属「回复可见/0银」门控，不算真付费: '+desc(els[j]));
            continue;
          }
          log('检测到可见真付费按钮: '+desc(els[j]));
          return true;
        }
      }
      // 4) 附件下载链接（<a> 指向 mod=attachment / attachment.php，非 attachpay 付费）：
      //    第一楼有附件文件时，回复无法解锁文件，不自动回复
      var fileLinks=root.querySelectorAll('a[href*="mod=attachment"], a[href*="attachment.php"]');
      for(var m=0;m<fileLinks.length;m++){
        var fhref=(fileLinks[m].getAttribute&&fileLinks[m].getAttribute('href'))||'';
        if(/attachpay/.test(fhref)) continue;   // 付费附件已在前几步处理
        if(!isVisible(fileLinks[m])){
          log('附件链接已隐藏(回复可见)，忽略: '+desc(fileLinks[m]));
          continue;
        }
        if(isReplyVisibleGate(fileLinks[m])){
          log('附件链接属「回复可见/0银」门控，不算: '+desc(fileLinks[m]));
          continue;
        }
        log('检测到可见附件下载链接，脚本不生效: '+desc(fileLinks[m]));
        return true;
      }
      return false;
    }catch(e){ return false; }
  }

  // 检测「回复可见」隐藏提示（Discuz [hide] 标签渲染），限定在给定容器内
  function hasLockedHint(scope){
    try{
      var root=scope||document;
      if(!root) return false;
      var t=(root.innerText||root.textContent||'');
      return /隐藏[^。]{0,40}回复|回复[^。]{0,30}(可见|浏览|查看|即可)|需要回复|本帖隐藏|回复后.{0,10}(显示|可见)|回复即可|隐藏的内容/.test(t);
    }catch(e){ return false; }
  }

  function isFirstPage(){
    try{
      var url=location.href;
      if(/[?&]goto=findpost/.test(url)) return false;
      var m=/[?&]page=(\d+)/.exec(url);
      if(m && parseInt(m[1],10)>1) return false;
      return true;
    }catch(e){ return true; }
  }

  function findMsg(){
    return document.getElementById('fastpostmessage')
      || document.querySelector('textarea[name="message"]')
      || document.getElementById('postmessage')
      || document.querySelector('#fastpostform textarea')
      || document.querySelector('textarea');
  }

  function findSubmit(){
    return document.getElementById('fastpostsubmit')
      || document.querySelector('button[name="replysubmit"]')
      || document.querySelector('#fastpostsubmit')
      || document.querySelector('#fastpostform button[type="submit"]')
      || document.querySelector('#fastpostform input[type="submit"]');
  }

  // 冷却倒计时浮窗：独立运行，幂等（校准剩余秒数，不重建定时器），不受回复功能暂停影响
  var __dzToastTimer=null;
  var __dzToastSec=0;
  function showCooldownToast(remainSec){
    try{
      var toast=document.getElementById('__dzCooldownToast');
      if(!toast){
        toast=document.createElement('div');
        toast.id='__dzCooldownToast';
        toast.style.cssText='position:fixed;bottom:80px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.82);color:#fff;padding:10px 22px;border-radius:22px;font-size:14px;z-index:99999;pointer-events:none;white-space:nowrap;box-shadow:0 2px 8px rgba(0,0,0,0.35);';
        (document.body||document.documentElement).appendChild(toast);
      }
      __dzToastSec=remainSec;
      toast.textContent='自动回复冷却中，还剩 '+__dzToastSec+' 秒';
      if(!__dzToastTimer){
        __dzToastTimer=setInterval(function(){
          __dzToastSec--;
          if(__dzToastSec<=0){
            if(toast.parentNode) toast.remove();
            clearInterval(__dzToastTimer);
            __dzToastTimer=null;
            return;
          }
          toast.textContent='自动回复冷却中，还剩 '+__dzToastSec+' 秒';
        },1000);
      }
    }catch(e){}
  }

  // 提交回复：填内容 + 点击提交（静默），先记录冷却时间戳再点击，随后刷新解锁
  function doReply(msg){
    try{
      var savedScroll=window.scrollY;
      msg.value=REPLY_TEXT;
      try{ msg.dispatchEvent(new Event('input',{bubbles:true})); }catch(e){}
      try{ msg.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){}
      log('已填入回复内容，准备静默提交');
      setTimeout(function(){
        try{
          var btn=findSubmit();
          if(!btn){ log('未找到提交按钮'); return; }
          setLastSuccess();   // 先记录冷却时间戳再点击，即使同步跳转也不丢
          btn.click();
          try{ window.scrollTo(0,savedScroll); }catch(e){}
          log('已提交回复，进入 61 秒冷却');
          setTimeout(function(){
            log('刷新页面以解锁隐藏内容');
            location.reload();
          },1500);
        }catch(e){ log('提交异常: '+e.message); }
      },1000);
    }catch(e){ log('doReply 异常: '+e.message); }
  }

  function tryAutoReply(){
    try{
      // 1) 冷却检查（最高优先级）：优先检测倒计时是否归零，未归零则弹剩余时间并暂停回复
      var last=getLastSuccess();
      if(last>0 && Date.now()-last<COOLDOWN_MS){
        var remain=Math.ceil((COOLDOWN_MS-(Date.now()-last))/1000);
        showCooldownToast(remain);
        return;
      }

      // 2) 冷却已过，检测回复条件
      if(!/mod=viewthread/.test(location.href)) return;   // 只在帖子详情页执行，主页/版块列表不做 DOM 扫描
      if(!isFirstPage()){ log('非帖子第一页，不自动回复'); return; }

      var scope=getFirstFloor()||document;

      if(payBtnCache===null) payBtnCache=hasPayButton(scope);   // 只扫一次
      if(payBtnCache){ log('楼主层有付费/附件门控，脚本不生效'); return; }
      if(lockedHintCache===null) lockedHintCache=hasLockedHint(scope);   // 只扫一次
      if(!lockedHintCache){ log('未检测到隐藏提示'); return; }
      log('检测到隐藏提示，需要回复');

      var msg=findMsg();
      if(!msg){ log('未找到回复框'); return; }
      log('找到回复框: id='+(msg.id||'无')+', name='+(msg.name||'无'));
      if(msg.value && msg.value.trim()){ log('回复框已有内容，跳过'); return; }

      doReply(msg);
    }catch(e){ log('tryAutoReply 异常: '+e.message); }
  }

  function schedule(){
    if(!/mod=viewthread/.test(location.href)) return;   // 只在帖子详情页轮询，避免主页/列表页卡顿
    if(pollCount>=45) return;   // 最多约 90 秒，覆盖 61 秒冷却 + 缓冲
    pollCount++;
    tryAutoReply();
    setTimeout(schedule,2000);
  }
  if(document.readyState==='complete'){ schedule(); }
  else { window.addEventListener('load', schedule); }
})();
""".trimIndent()
    }

    /**
     * 附件「重新下载」自动重试。
     *
     * 背景：免银币下载脚本会把附件链接改造成伪造签名 `?mod=attachment&aid=<base64(aid|1|1|1|tid)>`，
     * 点击后 Discuz 校验签名失败，返回「提示信息：抱歉，原附件链接已失效」页面；该页
     * `#messagetext` 内含「点击这里重新下载」链接（href 为带真实签名、uid=1 的下载地址）。
     * 这里检测到该提示页时自动点击「重新下载」链接，走现有下载链路直接下载，无需用户手动点。
     */
    private fun retryAttachmentJs(): String {
        return """
(function(){
  if(window.__dzRetryAttach) return; window.__dzRetryAttach=1;
  try{
    var mt = document.getElementById('messagetext');
    if(!mt) return;
    var txt = (mt.textContent || mt.innerText || '');
    if(txt.indexOf('原附件链接已失效') < 0 && txt.indexOf('重新下载') < 0) return;
    var link = mt.querySelector('a[href*="mod=attachment"]') || mt.querySelector('a[href*="aid="]');
    if(link){
      var href = link.getAttribute('href') || link.href || '';
      if(href && href.indexOf('mod=attachment') >= 0){
        // 延迟一点确保页面稳定，再触发导航（走 shouldOverrideUrlLoading 的附件拦截→下载）
        setTimeout(function(){
          try{ window.location.href = href; }catch(e){}
        }, 300);
      }
    }
  }catch(e){}
})();
""".trimIndent()
    }

    /**
     * 附件真实文件名上报（v2.4.0）。
     *
     * 背景：搜书吧帖子页的附件块结构为
     *   <dl class="tattl"><dd>
     *     <p class="mbn"><span class="attachname">1.jpg</span><span class="y">免费</span></p>
     *     <p class="buttons"><a href="forum.php?mod=attachment&aid=<base64>&nothumb=yes"
     *         id="aid4563314" class="xw1 btn_download">下载</a></p>
     *   </dd></dl>
     * —— 真实文件名在 `<span class="attachname">` 里，而下载链接的文字固定是「下载」。
     *
     * 点下载时 DownloadListener 给的 contentDisposition 为 null，URL 也只是
     * `forum.php?mod=attachment&aid=...`（解析出来是脚本页名）。所以「帖子内的文件名」
     * 原生侧根本拿不到，必须由本脚本先把 DOM 里的名字按附件 id 上报，下载时用它命名。
     *
     * 上报键用附件 id：`aid` 是 URL 编码的 base64，解码后形如
     * `4563314|61f7b6c2|1790359004|1119960|1503027`，第一段就是附件 id。
     * 该形态对「正常签名」与「免银币伪造签名(aid|1|1|1|tid)」都成立；aid 本身就是纯数字时直接用它。
     */
    private fun attachNameJs(): String {
        return """
(function(){
  if(window.__dzAttNameInit) return; window.__dzAttNameInit=1;
  var reported={};

  function norm(s){ return String(s==null?'':s).replace(/\u00a0/g,' ').replace(/\s+/g,' ').trim(); }

  // 通用占位文字（不是文件名）
  function isGeneric(t){
    return !t || /^(下载|附件|立即下载|点击下载|点击这里下载|重新下载|免费|download|attach|attachment)$/i.test(t);
  }

  // 从 href 求 Discuz 附件 id
  function auditOf(href){
    try{
      var m=/[?&]aid=([^&#]+)/.exec(String(href||''));
      if(!m) return '';
      // 先百分号解码，再把残留空格还原成 base64 的 '+'（Discuz 把 '+' 编码为 %2B，
      // 不受影响；只在服务器漏编码时兜底），避免 '+' 被当成空格导致解码失败。
      var raw=decodeURIComponent(m[1]).replace(/ /g,'+');
      if(/^\d+$/.test(raw)) return raw;              // 直接数字形态
      var txt='';
      try{ txt=atob(raw); }catch(e){ return ''; }
      var id=(txt.split('|')[0]||'').replace(/[^0-9A-Za-z]/g,'');
      return id;
    }catch(e){ return ''; }
  }

  // 从链接所在容器找真实文件名
  function nameOf(a){
    try{
      var box=null;
      try{
        box = (a.closest && (a.closest('dl.tattl') || a.closest('.pattl') || a.closest('ignore_js_op'))) || null;
      }catch(e){ box=null; }
      if(!box){
        // 模板差异兜底：逐级上溯找工作里含 .attachname 的容器
        var n=a, hops=0;
        while(n && n.nodeType===1 && hops++<6){
          if(n.querySelector && n.querySelector('.attachname')){ box=n; break; }
          n=n.parentElement;
        }
      }
      if(box){
        // 主路径：Discuz 标准模板的 <span class="attachname">真实文件名</span>
        var sp=box.querySelector('.attachname');
        var t=norm(sp? sp.textContent : '');
        if(!isGeneric(t)) return t;
        // 兜底：容器 dd / .mbn 内首个「像文件名」的文本（含扩展名）
        var cands=box.querySelectorAll('dd,p,.mbn');
        for(var i=0;i<cands.length;i++){
          var x=norm(cands[i].textContent);
          if(!isGeneric(x) && /\.[A-Za-z0-9]{1,6}(\s|,|$)/.test(x)) return x;
        }
      }
      // 最后兜底：链接自身文字 / title（部分模板直接把文件名写成链接文字）
      var at=norm(a.textContent);
      if(!isGeneric(at)) return at;
      var ti=norm(a.getAttribute && a.getAttribute('title'));
      if(!isGeneric(ti)) return ti;
      return '';
    }catch(e){ return ''; }
  }

  function scan(){
    try{
      if(!window.DiscuzApp || !window.DiscuzApp.attachName) return;
      var links=document.querySelectorAll('a[href*="mod=attachment"],a[href*="attachment.php"]');
      for(var i=0;i<links.length;i++){
        var a=links[i], href=a.getAttribute('href')||'';
        if(/attachpay/i.test(href)) continue;         // 付费购买浮层，不是文件
        var id=auditOf(href);
        if(!id || reported[id]) continue;
        var nm=nameOf(a);
        if(!nm) continue;
        reported[id]=nm;
        try{ window.DiscuzApp.attachName(id, nm); }catch(e){}
      }
    }catch(e){}
  }

  function start(){
    scan();
    // 附件块可能由 AJAX/异步模板插入：监听只置脏位并合并处理，并限时拆除，
    // 避免像旧代码那样「回调里扫全文档且永不停止」拖慢主线程。
    var dirty=false, mo=null;
    try{
      mo=new MutationObserver(function(){ dirty=true; });
      mo.observe(document.documentElement||document.body,{childList:true,subtree:true});
    }catch(e){}
    var iv=setInterval(function(){ if(dirty){ dirty=false; scan(); } },300);
    setTimeout(function(){
      try{ if(mo){ mo.disconnect(); mo=null; } }catch(e){}
      try{ clearInterval(iv); }catch(e){}
      scan();
    },20000);
  }

  if(document.readyState==='complete') start();
  else window.addEventListener('load', start);
})();
""".trimIndent()
    }
}
