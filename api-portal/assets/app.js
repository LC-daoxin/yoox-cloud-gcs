// YOOX Cloud GCS API 文档门户 —— 交互脚本（零依赖）
(function () {
  'use strict';

  var content = document.getElementById('content');
  var sideNav = document.getElementById('sideNav');
  var tocList = document.getElementById('tocList');
  var sidebar = document.getElementById('sidebar');
  var sidebarMask = document.getElementById('sidebarMask');
  var menuToggle = document.getElementById('menuToggle');
  var searchInput = document.getElementById('searchInput');

  var pages = Array.prototype.slice.call(content.querySelectorAll('.doc-page'));

  // 依据页面结构构建左侧导航树
  function buildSidebar() {
    pages.forEach(function (page, i) {
      var group = document.createElement('div');
      group.className = 'side-group';
      group.dataset.page = page.id;

      var title = document.createElement('div');
      title.className = 'side-title';
      title.innerHTML = '<span class="idx">' + (i + 1) + '</span><span>' + page.dataset.title + '</span>';
      title.addEventListener('click', function () { goTo(page.id); });
      group.appendChild(title);

      var links = document.createElement('div');
      links.className = 'side-links';
      page.querySelectorAll('h2[id]').forEach(function (h2) {
        var a = document.createElement('a');
        a.href = '#' + page.id + '/' + h2.id;
        a.textContent = h2.textContent;
        a.dataset.anchor = h2.id;
        a.addEventListener('click', function (e) {
          e.preventDefault();
          goTo(page.id, h2.id);
        });
        links.appendChild(a);
      });
      group.appendChild(links);
      sideNav.appendChild(group);
    });
  }

  // 构建右侧“本页目录”
  function buildToc(page) {
    tocList.innerHTML = '';
    page.querySelectorAll('h2[id], h3[id]').forEach(function (h) {
      var a = document.createElement('a');
      a.href = '#' + page.id + '/' + h.id;
      a.textContent = h.textContent;
      if (h.tagName === 'H3') a.className = 'sub';
      a.dataset.anchor = h.id;
      a.addEventListener('click', function (e) {
        e.preventDefault();
        scrollToAnchor(h.id);
      });
      tocList.appendChild(a);
    });
  }

  function scrollToAnchor(anchorId) {
    var el = document.getElementById(anchorId);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function goTo(pageId, anchorId) {
    var page = document.getElementById(pageId);
    if (!page || !page.classList.contains('doc-page')) return;

    pages.forEach(function (p) { p.classList.toggle('active', p === page); });

    // 同步导航高亮
    sideNav.querySelectorAll('.side-group').forEach(function (g) {
      g.classList.toggle('active', g.dataset.page === pageId);
    });

    buildToc(page);
    closeSidebar();

    var hash = anchorId ? pageId + '/' + anchorId : pageId;
    if (location.hash.slice(1) !== hash) history.replaceState(null, '', '#' + hash);

    if (anchorId) {
      setTimeout(function () { scrollToAnchor(anchorId); }, 60);
    } else {
      content.scrollIntoView({ block: 'start' });
      window.scrollTo(0, 0);
    }
    updateSideActive(anchorId);
  }

  function updateSideActive(anchorId) {
    sideNav.querySelectorAll('.side-links a').forEach(function (a) {
      a.classList.toggle('active', a.dataset.anchor === anchorId);
    });
  }

  // 滚动时高亮当前锚点（TOC + 侧栏）
  function onScroll() {
    var active = pages.filter(function (p) { return p.classList.contains('active'); })[0];
    if (!active) return;
    var heads = Array.prototype.slice.call(active.querySelectorAll('h2[id], h3[id]'));
    var current = null;
    for (var i = 0; i < heads.length; i++) {
      if (heads[i].getBoundingClientRect().top <= 90) current = heads[i];
    }
    if (!current && heads.length) current = heads[0];
    if (!current) return;
    tocList.querySelectorAll('a').forEach(function (a) {
      a.classList.toggle('active', a.dataset.anchor === current.id);
    });
    var h2 = current.tagName === 'H2' ? current.id : null;
    if (h2) updateSideActive(h2);
  }

  // 代码块复制按钮
  function addCopyButtons() {
    content.querySelectorAll('pre').forEach(function (pre) {
      var btn = document.createElement('button');
      btn.className = 'copy-btn';
      btn.textContent = '复制';
      btn.addEventListener('click', function () {
        var code = pre.querySelector('code');
        var text = code ? code.innerText : pre.innerText;
        navigator.clipboard.writeText(text).then(function () {
          btn.textContent = '已复制';
          setTimeout(function () { btn.textContent = '复制'; }, 1500);
        }).catch(function () { btn.textContent = '复制失败'; });
      });
      pre.appendChild(btn);
    });
  }

  // 顶部导航 data-goto
  function bindGoto() {
    document.querySelectorAll('[data-goto]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.preventDefault();
        goTo(el.dataset.goto);
      });
    });
  }

  // 侧栏（移动端）
  function openSidebar() { sidebar.classList.add('open'); sidebarMask.classList.add('open'); }
  function closeSidebar() { sidebar.classList.remove('open'); sidebarMask.classList.remove('open'); }
  menuToggle.addEventListener('click', function () {
    sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
  });
  sidebarMask.addEventListener('click', closeSidebar);

  // 搜索：过滤侧栏项，匹配标题/锚点文本
  searchInput.addEventListener('input', function () {
    var q = searchInput.value.trim().toLowerCase();
    sideNav.querySelectorAll('.side-group').forEach(function (g) {
      var groupHit = g.querySelector('.side-title').textContent.toLowerCase().indexOf(q) > -1;
      var anyLink = false;
      g.querySelectorAll('.side-links a').forEach(function (a) {
        var hit = a.textContent.toLowerCase().indexOf(q) > -1;
        a.style.display = (!q || hit || groupHit) ? '' : 'none';
        if (hit) anyLink = true;
      });
      g.style.display = (!q || groupHit || anyLink) ? '' : 'none';
    });
  });

  // 解析 hash: #page 或 #page/anchor
  function routeFromHash() {
    var raw = location.hash.slice(1);
    if (!raw) { goTo(pages[0].id); return; }
    var parts = raw.split('/');
    var pageId = parts[0];
    var anchorId = parts[1];
    if (document.getElementById(pageId)) goTo(pageId, anchorId);
    else goTo(pages[0].id);
  }

  // 初始化
  buildSidebar();
  bindGoto();
  addCopyButtons();
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('hashchange', function () {
    var raw = location.hash.slice(1);
    var parts = raw.split('/');
    var active = pages.filter(function (p) { return p.classList.contains('active'); })[0];
    if (active && parts[0] === active.id && parts[1]) scrollToAnchor(parts[1]);
    else routeFromHash();
  });
  routeFromHash();
})();
