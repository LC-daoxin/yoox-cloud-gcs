// 上云 API 文档门户 —— 多级折叠导航（数据驱动）
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

  // ============================================================
  // 文档导航树
  // 节点: { label, page? } 叶子 → 跳转页面
  //       { label, children: [...] } 分组 → 折叠
  // ============================================================
  var NAV_TREE = [
    {
      label: '基础介绍', children: [
        { label: '产品介绍', page: 'overview' },
        { label: '产品架构', page: 'product-architecture' },
        { label: '产品支持', page: 'product-support' }
      ]
    },
    {
      label: '功能集合', children: [
        {
          label: 'APP功能集合', children: [
            { label: 'APP上云', page: 'enterprise' },
            { label: '态势感知', page: 'situation' },
            { label: '直播功能', page: 'livestream' },
            { label: '地图元素', page: 'map-element' },
            { label: '媒体管理', page: 'media' },
            { label: '航线管理', page: 'wayline' },
            { label: 'HMS 管理', page: 'hms' },
            { label: '指令飞行', page: 'command-flight' }
          ]
        }
      ]
    },
    {
      label: 'API 介绍', children: [
        {
          label: '航线文件格式标准', children: [
            { label: '总体介绍', page: 'wayline-format' },
            { label: 'template.kml 说明', page: 'template-kml' },
            { label: 'waylines.wpml 说明', page: 'waylines-wpml' },
            { label: '共用元素信息', page: 'wpml-elements' }
          ]
        },
        {
          label: 'APP上云', children: [
            {
              label: 'MQTT', children: [
                { label: 'Topic定义', page: 'mqtt-topic' },
                { label: '飞行器', children: [
                  { label: '设备属性', page: 'mqtt-aircraft-properties' }
                ] },
                { label: '遥控器', children: [
                  { label: '设备属性', page: 'mqtt-controller-properties' },
                  { label: '设备管理', page: 'mqtt-controller-management' },
                  { label: '直播功能', page: 'mqtt-controller-livestream' },
                  { label: '媒体管理', page: 'mqtt-controller-media' },
                  { label: '航线管理', page: 'mqtt-controller-wayline' },
                  { label: 'HMS 管理', page: 'mqtt-controller-hms' },
                  { label: '指令飞行与云台控制', page: 'mqtt-controller-command' },
                  { label: '目标识别', page: 'mqtt-controller-target' }
                ] }
              ]
            },
            {
              label: 'HTTPS', children: [
                { label: '地图元素', children: [
                  { label: '创建地图元素', page: 'https-map-create' },
                  { label: '更新地图元素', page: 'https-map-update' },
                  { label: '获取地图元素', page: 'https-map-get' },
                  { label: '删除地图元素', page: 'https-map-delete' }
                ] },
                { label: '态势感知', children: [
                  { label: '获取设备拓扑', page: 'https-topology-get' }
                ] },
                { label: '登录', children: [
                  { label: '登录', page: 'https-login' }
                ] }
              ]
            },
            {
              label: 'WebSocket', children: [
                { label: '地图元素', children: [
                  { label: '消息发布', page: 'ws-map-publish' }
                ] },
                { label: '态势感知', children: [
                  { label: '消息发布', page: 'ws-situation-publish' }
                ] }
              ]
            }
          ]
        }
      ]
    },
    { label: '错误码', page: 'errors' }
  ];

  // ============================================================
  // 递归渲染导航树
  // ============================================================
  var CHEVRON_SVG = '<svg class="nav-arrow" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>';

  function renderNode(node, depth) {
    var el = document.createElement('div');
    el.className = 'nav-group';
    el.dataset.depth = depth;

    if (node.children && node.children.length) {
      // 分组节点
      var head = document.createElement('div');
      head.className = 'nav-group-head';
      head.innerHTML = '<span class="nav-label">' + escHtml(node.label) + '</span>' + CHEVRON_SVG;
      head.addEventListener('click', function () {
        el.classList.toggle('open');
      });
      el.appendChild(head);

      var childrenWrap = document.createElement('div');
      childrenWrap.className = 'nav-children';
      node.children.forEach(function (child) {
        childrenWrap.appendChild(renderNode(child, depth + 1));
      });
      el.appendChild(childrenWrap);
    } else {
      // 叶子节点
      var leaf = document.createElement('a');
      leaf.className = 'nav-leaf';
      leaf.textContent = node.label;
      leaf.href = '#' + (node.page || '') + (node.anchor ? '/' + node.anchor : '');
      leaf.dataset.page = node.page || '';
      leaf.dataset.anchor = node.anchor || '';
      leaf.addEventListener('click', function (e) {
        e.preventDefault();
        if (node.page) goTo(node.page, node.anchor);
      });
      el.appendChild(leaf);
      el.className = ''; // 叶子不需要 nav-group
    }

    return el;
  }

  function escHtml(s) {
    var d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  function buildSidebar() {
    sideNav.innerHTML = '';
    NAV_TREE.forEach(function (node) {
      sideNav.appendChild(renderNode(node, 0));
    });
  }

  // ============================================================
  // 页面切换
  // ============================================================
  function goTo(pageId, anchorId) {
    var page = document.getElementById(pageId);
    if (!page || !page.classList.contains('doc-page')) return;

    pages.forEach(function (p) { p.classList.toggle('active', p === page); });

    // 高亮当前叶子；没有专属锚点项时，回退到该页面的主目录项。
    var navLeaves = Array.prototype.slice.call(sideNav.querySelectorAll('.nav-leaf'));
    var hasExactAnchor = !!anchorId && navLeaves.some(function (leaf) {
      return leaf.dataset.page === pageId && leaf.dataset.anchor === anchorId;
    });
    navLeaves.forEach(function (leaf) {
      var matchesPage = leaf.dataset.page === pageId;
      var matchesTarget = hasExactAnchor
        ? leaf.dataset.anchor === anchorId
        : leaf.dataset.anchor === '';
      leaf.classList.toggle('active', matchesPage && matchesTarget);
    });

    // 自动展开到当前叶子的所有祖先分组
    var activeLeaf = sideNav.querySelector('.nav-leaf.active');
    if (activeLeaf) {
      var parent = activeLeaf.parentElement;
      while (parent && parent !== sideNav) {
        if (parent.classList.contains('nav-group')) {
          parent.classList.add('open');
        }
        parent = parent.parentElement;
      }
    }

    buildToc(page);
    closeSidebar();

    history.replaceState(null, '', '#' + pageId + (anchorId ? '/' + anchorId : ''));
    if (anchorId) {
      setTimeout(function () { scrollToAnchor(anchorId); }, 60);
    } else {
      content.scrollIntoView({ block: 'start' });
      window.scrollTo(0, 0);
    }
  }

  // ============================================================
  // 右侧 TOC
  // ============================================================
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

  // ============================================================
  // 滚动高亮 TOC
  // ============================================================
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
  }

  // ============================================================
  // 代码块复制
  // ============================================================
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

  // ============================================================
  // Enterprise 上云交互时序图
  // ============================================================
  function initEnterpriseSequence() {
    var root = document.getElementById('enterpriseSequence');
    var stage = document.getElementById('enterpriseSequenceStage');
    var detail = document.getElementById('enterpriseSequenceDetail');
    if (!root || !stage || !detail) return;

    var actors = [
      { name: 'APP1', role: '云服务接入端' },
      { name: 'Cloud Server', role: '第三方云平台' },
      { name: 'APP2', role: '设备与地图消息端' }
    ];
    var phases = {
      config: { label: '填写 MQTT/HTTP 地址、账号密码，WebSocket 地址', from: 0, to: 0 },
      mqtt: { label: 'MQTT 建立连接', from: 0, to: 1 },
      auth: { label: 'Login 授权', from: 0, to: 1 },
      topology: { label: '设备上线', from: 0, to: 1 },
      websocket: { label: 'WebSocket 建立连接', from: 0, to: 1 },
      realtime: { label: '设备上线、下线、OSD 上报', from: 1, to: 2 },
      map: { label: '地图元素创建、删除、更新', from: 1, to: 2 }
    };
    var steps = [
      { phase: 'config', from: 0, to: 0, title: '填写上云配置', label: '填写 MQTT/HTTP 地址、账号密码，WebSocket 地址', protocol: '配置', detail: '在 APP1 的云服务配置页填写 MQTT/HTTP 地址、账号密码与 WebSocket 地址。' },
      { phase: 'mqtt', from: 0, to: 1, title: '发起 MQTT 连接', label: '发起 MQTT 连接', protocol: 'MQTT', detail: 'APP1 使用已配置的地址和凭据向 Cloud Server 发起 MQTT 连接。' },
      { phase: 'mqtt', from: 1, to: 0, title: 'MQTT 连接成功', label: '返回 MQTT 连接成功应答', protocol: 'MQTT · Reply', reply: true, detail: 'Cloud Server 完成鉴权并向 APP1 返回连接成功应答。' },
      { phase: 'mqtt', from: 0, to: 1, title: '订阅相关 Topic', label: '订阅相关 Topic', protocol: 'MQTT SUBSCRIBE', detail: '连接建立后，APP1 订阅设备状态、请求应答、服务与事件等相关 Topic。' },
      { phase: 'auth', from: 0, to: 1, title: 'Login 请求获取 token', label: 'Login 请求获取 token<br><code>POST /manage/api/v1/login</code>', protocol: 'HTTPS POST', detail: 'APP1 调用 POST /manage/api/v1/login 发起登录授权并获取访问令牌。' },
      { phase: 'auth', from: 1, to: 0, title: '返回登录参数', label: '返回参数 <code>token</code>、<code>workspaceId</code>', protocol: 'HTTPS · Reply', reply: true, detail: 'Cloud Server 返回 token 与 workspaceId，APP1 完成登录。' },
      { phase: 'topology', from: 0, to: 1, title: '上报设备拓扑', label: '发送 update_topo 给云平台<br><code>Topic: sys/product/{gateway_sn}/status</code>', protocol: 'MQTT · update_topo', detail: 'APP1 在 sys/product/{gateway_sn}/status 上发送 update_topo，向云平台报告网关与子设备拓扑。' },
      { phase: 'topology', from: 1, to: 0, title: '返回拓扑更新结果', label: '返回查询结果<br><code>Topic: sys/product/{gateway_sn}/status_reply</code>', protocol: 'MQTT · update_topo · Reply', reply: true, detail: 'Cloud Server 更新设备拓扑，并在 status_reply Topic 返回 update_topo 的处理结果。' },
      { phase: 'websocket', from: 0, to: 1, title: '发起 WebSocket 连接', label: '发起 WebSocket 连接', protocol: 'WebSocket', detail: 'APP1 使用登录结果向 Cloud Server 发起 WebSocket 长连接。' },
      { phase: 'websocket', from: 1, to: 0, title: 'WebSocket 连接成功', label: '返回 WebSocket 连接成功应答', protocol: 'WebSocket · Reply', reply: true, detail: 'Cloud Server 接受连接，此后通过该通道推送设备拓扑、OSD 与地图元素消息。' },
      { phase: 'realtime', from: 2, to: 1, title: '上报设备实时消息', label: '设备上线、下线、OSD 上报', protocol: '设备消息', detail: 'APP2 向 Cloud Server 上报设备上线、下线与 OSD 遥测。' },
      { phase: 'realtime', from: 1, to: 0, title: '推送设备实时消息', label: '云服务推送设备拓扑 Online、Offline、OSD 消息', protocol: 'WebSocket · Push', reply: true, detail: 'Cloud Server 通过 WebSocket 向 APP1 推送设备拓扑 Online、Offline 与 OSD 消息，由客户端处理。' },
      { phase: 'map', from: 2, to: 1, title: '上报地图元素消息', label: '地图元素创建、删除、更新消息', protocol: '地图元素消息', detail: 'APP2 将地图元素的创建、删除与更新消息发送给 Cloud Server。' },
      { phase: 'map', from: 1, to: 0, title: '推送地图元素消息', label: '云服务推送地图元素创建、删除、更新消息', protocol: 'WebSocket · Push', reply: true, detail: 'Cloud Server 通过 WebSocket 将地图元素变更推送给 APP1，由客户端处理并更新界面。' }
    ];

    function renderActors(container) {
      actors.forEach(function (actor) {
        var el = document.createElement('div');
        el.className = 'sequence-actor';
        el.innerHTML = actor.name + '<small>' + actor.role + '</small>';
        container.appendChild(el);
      });
    }
    root.querySelectorAll('.sequence-actors').forEach(renderActors);

    actors.forEach(function () {
      var line = document.createElement('span');
      line.className = 'sequence-lifeline';
      line.setAttribute('aria-hidden', 'true');
      stage.appendChild(line);
    });

    var currentPhase = '';
    steps.forEach(function (step, index) {
      if (step.phase !== currentPhase) {
        currentPhase = step.phase;
        var phase = phases[step.phase];
        var phaseEl = document.createElement('div');
        var phaseLeft = (Math.min(phase.from, phase.to) * 33.333 + 7) + '%';
        var phaseWidth = ((Math.abs(phase.to - phase.from) + 1) * 33.333 - 14) + '%';
        phaseEl.className = 'sequence-phase';
        phaseEl.dataset.phase = step.phase;
        phaseEl.style.setProperty('--phase-left', phaseLeft);
        phaseEl.style.setProperty('--phase-width', phaseWidth);
        phaseEl.textContent = phase.label;
        stage.appendChild(phaseEl);
      }

      var fromX = 16.666 + step.from * 33.333;
      var toX = 16.666 + step.to * 33.333;
      var left = Math.min(fromX, toX);
      var width = Math.abs(toX - fromX);
      var stepEl = document.createElement('button');
      stepEl.type = 'button';
      stepEl.className = 'sequence-step' + (step.reply ? ' reply' : '') + (step.from > step.to ? ' reverse' : '');
      stepEl.dataset.phase = step.phase;
      stepEl.dataset.index = String(index);
      stepEl.style.setProperty('--arrow-left', left + '%');
      stepEl.style.setProperty('--arrow-width', width + '%');
      stepEl.style.setProperty('--label-left', (left + 1.5) + '%');
      stepEl.style.setProperty('--label-width', Math.max(width - 3, 18) + '%');
      stepEl.setAttribute('aria-label', (index + 1) + '. ' + step.title + '，' + actors[step.from].name + ' 到 ' + actors[step.to].name);
      stepEl.innerHTML = '<span class="sequence-step-label">' + step.label + '</span><span class="sequence-arrow" aria-hidden="true"></span>';
      stepEl.addEventListener('click', function () {
        stage.querySelectorAll('.sequence-step').forEach(function (item) { item.classList.remove('active'); });
        stepEl.classList.add('active');
        detail.innerHTML = '<span class="sequence-detail-index">' + String(index + 1).padStart(2, '0') + '</span><div><strong>' + step.title + ' · ' + step.protocol + '</strong><p>' + actors[step.from].name + ' → ' + actors[step.to].name + '。' + step.detail + '</p></div>';
      });
      stage.appendChild(stepEl);
    });

    root.querySelectorAll('[data-sequence-filter]').forEach(function (filter) {
      filter.addEventListener('click', function () {
        var value = filter.dataset.sequenceFilter;
        root.querySelectorAll('[data-sequence-filter]').forEach(function (item) { item.classList.toggle('active', item === filter); });
        stage.querySelectorAll('[data-phase]').forEach(function (item) {
          item.classList.toggle('is-muted', value !== 'all' && item.dataset.phase !== value);
        });
      });
    });
  }

  // ============================================================
  // 态势感知交互时序图
  // ============================================================
  function initSituationSequence() {
    var root = document.getElementById('situationSequence');
    var stage = document.getElementById('situationSequenceStage');
    var detail = document.getElementById('situationSequenceDetail');
    if (!root || !stage || !detail) return;

    var actors = [
      { name: 'APP1', role: '地图与态势展示端' },
      { name: 'Cloud Server', role: '第三方云平台' },
      { name: 'APP2', role: '其他设备端' }
    ];
    var phases = {
      auth: { label: '登录第三方云平台', from: 0, to: 1 },
      websocket: { label: 'WebSocket 连接', from: 0, to: 1 },
      baseline: { label: 'Enterprise 首次上线 · 获取设备列表拓扑', from: 0, to: 1 },
      telemetry: { label: '状态推送 · 循环', from: 0, to: 2 },
      topology: { label: '设备上线 / 下线 · 可选', from: 0, to: 2 }
    };
    var steps = [
      { phase: 'auth', from: 0, to: 1, title: '登录第三方云平台', label: '登录第三方云平台', protocol: 'HTTPS', detail: 'APP1 向 Cloud Server 发起登录请求。' },
      { phase: 'auth', from: 1, to: 0, title: '登录成功', label: '登录成功', protocol: 'HTTPS · Reply', reply: true, detail: 'Cloud Server 完成认证并向 APP1 返回登录成功应答。' },
      { phase: 'websocket', from: 0, to: 1, title: '建立 WebSocket', label: 'WebSocket 连接', protocol: 'WebSocket', detail: 'APP1 向 Cloud Server 发起 WebSocket 长连接。' },
      { phase: 'websocket', from: 1, to: 0, title: 'WebSocket 连接成功', label: '连接', protocol: 'WebSocket · Reply', reply: true, detail: 'Cloud Server 接受连接，后续通过该通道推送态势与拓扑消息。' },
      { phase: 'baseline', from: 0, to: 1, title: '首次请求设备拓扑', label: 'APP1 首次上线，请求设备列表拓扑', protocol: 'HTTPS', detail: 'APP1 首次上线后，请求同一工作空间下的完整设备列表及拓扑。' },
      { phase: 'baseline', from: 1, to: 0, title: '返回设备拓扑', label: '应答', protocol: 'HTTPS · Reply', reply: true, detail: 'Cloud Server 向 APP1 返回工作空间内的设备列表与拓扑基线。' },
      { phase: 'telemetry', from: 2, to: 1, title: '上报设备遥感信息', label: '推送设备遥感信息', protocol: '设备遥测', detail: 'APP2 持续向 Cloud Server 上报坐标与设备遥感信息。' },
      { phase: 'telemetry', from: 1, to: 0, title: '定频推送设备遥测', label: '定频推送设备遥测信息', protocol: 'WebSocket · Push', detail: 'Cloud Server 定频向 APP1 推送同一工作空间下的设备遥测，APP1 据此更新地图位置与状态。' },
      { phase: 'topology', from: 2, to: 1, title: '其他设备拓扑更新', label: '其他设备拓扑更新', protocol: '设备拓扑', detail: 'APP2 上线、下线或拓扑变化时，将更新发送给 Cloud Server。' },
      { phase: 'topology', from: 1, to: 0, title: '推送设备上线信息', label: '通过 WebSocket 推送 <code>device_online</code> 信息', protocol: 'WebSocket · Push', detail: 'Cloud Server 通过 WebSocket 向 APP1 广播设备上线、下线或更新通知。' },
      { phase: 'topology', from: 0, to: 0, title: '处理 WebSocket 信息', label: '处理 WebSocket 信息', protocol: '客户端处理', selfLoop: true, detail: 'APP1 处理拓扑通知，并触发设备列表拓扑刷新。' },
      { phase: 'topology', from: 0, to: 1, title: '重新请求设备拓扑', label: '请求设备列表拓扑', protocol: 'HTTPS', detail: 'APP1 收到设备变化通知后，重新请求设备列表拓扑以更新本地基线。' }
    ];

    function renderActors(container) {
      actors.forEach(function (actor) {
        var el = document.createElement('div');
        el.className = 'sequence-actor';
        el.innerHTML = actor.name + '<small>' + actor.role + '</small>';
        container.appendChild(el);
      });
    }
    root.querySelectorAll('.sequence-actors').forEach(renderActors);

    actors.forEach(function () {
      var line = document.createElement('span');
      line.className = 'sequence-lifeline';
      line.setAttribute('aria-hidden', 'true');
      stage.appendChild(line);
    });

    var currentPhase = '';
    steps.forEach(function (step, index) {
      if (step.phase !== currentPhase) {
        currentPhase = step.phase;
        var phase = phases[step.phase];
        var phaseEl = document.createElement('div');
        phaseEl.className = 'sequence-phase';
        phaseEl.dataset.phase = step.phase;
        phaseEl.style.setProperty('--phase-left', (Math.min(phase.from, phase.to) * 33.333 + 7) + '%');
        phaseEl.style.setProperty('--phase-width', ((Math.abs(phase.to - phase.from) + 1) * 33.333 - 14) + '%');
        phaseEl.textContent = phase.label;
        stage.appendChild(phaseEl);
      }

      var fromX = 16.666 + step.from * 33.333;
      var toX = 16.666 + step.to * 33.333;
      var left = Math.min(fromX, toX);
      var width = Math.abs(toX - fromX);
      var stepEl = document.createElement('button');
      stepEl.type = 'button';
      stepEl.className = 'sequence-step' + (step.reply ? ' reply' : '') + (step.from > step.to ? ' reverse' : '') + (step.selfLoop ? ' self-loop' : '');
      stepEl.dataset.phase = step.phase;
      stepEl.dataset.index = String(index);
      stepEl.style.setProperty('--arrow-left', left + '%');
      stepEl.style.setProperty('--arrow-width', width + '%');
      stepEl.style.setProperty('--label-left', (left + 1.5) + '%');
      stepEl.style.setProperty('--label-width', Math.max(width - 3, 18) + '%');
      stepEl.setAttribute('aria-label', (index + 1) + '. ' + step.title + '，' + actors[step.from].name + ' 到 ' + actors[step.to].name);
      stepEl.innerHTML = '<span class="sequence-step-label">' + step.label + '</span><span class="sequence-arrow" aria-hidden="true"></span>';
      stepEl.addEventListener('click', function () {
        stage.querySelectorAll('.sequence-step').forEach(function (item) { item.classList.remove('active'); });
        stepEl.classList.add('active');
        detail.innerHTML = '<span class="sequence-detail-index">' + String(index + 1).padStart(2, '0') + '</span><div><strong>' + step.title + ' · ' + step.protocol + '</strong><p>' + actors[step.from].name + ' → ' + actors[step.to].name + '。' + step.detail + '</p></div>';
      });
      stage.appendChild(stepEl);
    });

    root.querySelectorAll('[data-situation-filter]').forEach(function (filter) {
      filter.addEventListener('click', function () {
        var value = filter.dataset.situationFilter;
        root.querySelectorAll('[data-situation-filter]').forEach(function (item) { item.classList.toggle('active', item === filter); });
        stage.querySelectorAll('[data-phase]').forEach(function (item) {
          item.classList.toggle('is-muted', value !== 'all' && item.dataset.phase !== value);
        });
      });
    });
  }

  // ============================================================
  // 顶部导航 data-goto
  // ============================================================
  function bindGoto() {
    document.querySelectorAll('[data-goto]').forEach(function (el) {
      el.addEventListener('click', function (e) {
        e.preventDefault();
        goTo(el.dataset.goto, el.dataset.anchor);
      });
    });
  }

  // ============================================================
  // 侧栏（移动端）
  // ============================================================
  function openSidebar() { sidebar.classList.add('open'); sidebarMask.classList.add('open'); }
  function closeSidebar() { sidebar.classList.remove('open'); sidebarMask.classList.remove('open'); }
  menuToggle.addEventListener('click', function () {
    sidebar.classList.contains('open') ? closeSidebar() : openSidebar();
  });
  sidebarMask.addEventListener('click', closeSidebar);

  // ============================================================
  // 搜索：过滤导航树
  // ============================================================
  searchInput.addEventListener('input', function () {
    var q = searchInput.value.trim().toLowerCase();
    if (!q) {
      // 清空搜索：恢复全部可见
      sideNav.querySelectorAll('.nav-group, .nav-leaf').forEach(function (el) {
        el.style.display = '';
      });
      return;
    }
    // 先全部隐藏
    sideNav.querySelectorAll('.nav-group, [class=""]').forEach(function (el) {
      el.style.display = 'none';
    });
    // 匹配叶子，向上展开
    sideNav.querySelectorAll('.nav-leaf').forEach(function (leaf) {
      var hit = leaf.textContent.toLowerCase().indexOf(q) > -1;
      if (hit) {
        leaf.style.display = '';
        var parent = leaf.parentElement;
        while (parent && parent !== sideNav) {
          parent.style.display = '';
          if (parent.classList.contains('nav-group')) parent.classList.add('open');
          parent = parent.parentElement;
        }
      }
    });
    // 匹配分组标题
    sideNav.querySelectorAll('.nav-group-head .nav-label').forEach(function (label) {
      if (label.textContent.toLowerCase().indexOf(q) > -1) {
        var group = label.closest('.nav-group');
        if (group) {
          group.style.display = '';
          group.classList.add('open');
          group.querySelectorAll('.nav-group, .nav-leaf, [class=""]').forEach(function (el) {
            el.style.display = '';
          });
          // 向上展开
          var parent = group.parentElement;
          while (parent && parent !== sideNav) {
            parent.style.display = '';
            if (parent.classList.contains('nav-group')) parent.classList.add('open');
            parent = parent.parentElement;
          }
        }
      }
    });
  });

  // ============================================================
  // 路由
  // ============================================================
  function initLivestreamApiPage() {
    var template = document.getElementById('mqttControllerLivestreamTemplate');
    var target = document.getElementById('mqttControllerLivestreamContent');
    if (template && target && !target.hasChildNodes()) {
      target.appendChild(template.content.cloneNode(true));
    }
  }

  function routeFromHash() {
    var raw = location.hash.slice(1);
    if (!raw) { goTo(pages[0].id); return; }
    var parts = raw.split('/');
    var pageId = parts[0];
    if (pageId === 'livestream' && parts[1] === 'lv-api') {
      goTo('mqtt-controller-livestream');
      return;
    }
    var defaultAnchors = {
      'api-https': 'ah-auth',
      'api-websocket': 'ws-conn'
    };
    if (document.getElementById(pageId)) goTo(pageId, parts[1] || defaultAnchors[pageId]);
    else goTo(pages[0].id);
  }

  // ============================================================
  // 初始化
  // ============================================================
  initLivestreamApiPage();
  buildSidebar();
  bindGoto();
  addCopyButtons();
  initEnterpriseSequence();
  initSituationSequence();
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('hashchange', function () {
    routeFromHash();
  });
  routeFromHash();
})();
