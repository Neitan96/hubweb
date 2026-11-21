/*
 * Detecção do usuário logado (HubWeb). Injetado só em páginas permitidas do item; __CFG__ é o bloco "usuario" do JSON.
 * Nunca lê campos de senha: só observa que algo foi digitado neles (evento "input"), sem tocar no valor.
 * Reporta ao app por HubWebUser.report(login, fonte, href); login vazio = "ninguém logado".
 */
(function (cfg) {
  'use strict';
  // Cada detecção do item tem o seu índice (__idx) e a sua trava; várias convivem na mesma página.
  var GUARD = '__hwUser' + cfg.__idx, PEND = '__hwPending' + cfg.__idx;
  if (window[GUARD] || window.top !== window) return;
  var B = window.HubWebUser;
  if (!B) return;
  window[GUARD] = true;

  var last = null, empties = 0, pwTyped = false;
  var TIPO = String(cfg.tipo || '');

  // ---------------------------------------------------------------- utilitários

  function str(v) { return v == null ? '' : String(v).trim(); }
  function parse(t) { try { return JSON.parse(t); } catch (e) { return null; } }

  /** Acessa "a.b[0].c" em um objeto. */
  function dig(obj, path) {
    if (obj == null || !path) return obj;
    var parts = String(path).replace(/\[(\d+)\]/g, '.$1').split('.');
    for (var i = 0; i < parts.length; i++) {
      if (obj == null) return undefined;
      obj = obj[parts[i]];
    }
    return obj;
  }

  /** Lê uma informação (claim) do corpo de um JWT; a assinatura e o token nunca saem da página. */
  function jwt(tok, claim) {
    try {
      var p = String(tok).split('.')[1];
      if (!p) return null;
      p = p.replace(/-/g, '+').replace(/_/g, '/');
      while (p.length % 4) p += '=';
      return dig(JSON.parse(decodeURIComponent(escape(atob(p)))), claim);
    } catch (e) { return null; }
  }

  /** Valor bruto -> login: "campo" (caminho JSON) e/ou "claim" (JWT), nessa ordem. */
  function extract(raw) {
    var v = raw;
    if (v == null) return '';
    if (cfg.campo) {
      var o = (typeof v === 'string') ? parse(v) : v;
      v = o ? dig(o, cfg.campo) : null;
    }
    if (cfg.claim) {
      v = (v == null) ? null : jwt(String(v).replace(/^Bearer\s+/i, ''), cfg.claim);
    }
    if (v == null || typeof v === 'object') return '';
    return str(v);
  }

  function applyRegex(v) {
    if (!cfg.regex || v === '') return v;
    try {
      var m = new RegExp(cfg.regex).exec(v);
      return m ? str(m[1] !== undefined ? m[1] : m[0]) : '';
    } catch (e) { return ''; }
  }

  /** `novo` = uma ação de login confirmada (formulário, requisição): vale mesmo que seja o mesmo usuário de antes. */
  function report(user, fonte, novo) {
    user = str(user).slice(0, 200);
    if (user === '') {
      empties++;
      if (empties < 2) return;          // "ninguém logado" só vale depois de 2 leituras seguidas
      if (last === null) { last = ''; return; }
    } else {
      empties = 0;
    }
    if (user === last && !novo) return;
    last = user;
    try { B.report(user, fonte || TIPO, location.href, Number(cfg.__idx) || 0, !!novo); } catch (e) {}
  }

  function isPassword(el) { return !!el && el.tagName === 'INPUT' && String(el.type).toLowerCase() === 'password'; }
  function pwInputs() { return document.querySelectorAll('input[type=password]'); }

  function glob2re(g) {
    g = String(g).replace(/^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\//, '');
    return new RegExp('^' + g.replace(/[.+^$\{\}()|\[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.') + '$', 'i');
  }
  function hostPath(u) {
    try { var a = new URL(u, location.href); return a.host + a.pathname; } catch (e) { return String(u); }
  }

  // ---------------------------------------------------------------- modos de leitura (consulta periódica)

  /** Uma leitura; pode devolver valor ou Promise. */
  function once() {
    switch (TIPO) {
      case 'elemento': {
        var el = cfg.seletor ? document.querySelector(cfg.seletor) : null;
        if (!el || isPassword(el)) return '';
        if (cfg.atributo) return el.getAttribute(cfg.atributo);
        return (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') ? el.value : el.textContent;
      }
      case 'cookie': {
        var c = B.cookie(location.href, cfg.nome);
        return c;
      }
      case 'storage': {
        var s = (cfg.armazenamento === 'sessao') ? window.sessionStorage : window.localStorage;
        return s.getItem(cfg.nome);
      }
      case 'js':
        return (0, eval)(cfg.expressao);
      case 'url':
        return location.href;
      case 'titulo':
        return document.title;
      case 'endpoint':
        return fetch(cfg.url, {
          method: (cfg.metodo || 'GET').toUpperCase(),
          credentials: 'include',
          headers: { 'Accept': 'application/json' }
        }).then(function (r) { return r.ok ? r.text() : ''; }).catch(function () { return ''; });
    }
    return '';
  }

  function runCheck() {
    var v;
    try { v = once(); } catch (e) { v = ''; }
    Promise.resolve(v).then(function (raw) {
      report(applyRegex(extract(raw)), TIPO);
    }, function () {});
  }

  function startPolling() {
    [300, 1500, 4000].forEach(function (ms) { setTimeout(runCheck, ms); });
    var iv = Number(cfg.intervaloSegundos);
    if (isNaN(iv)) iv = 15;
    if (iv > 0) setInterval(runCheck, Math.max(iv, 5) * 1000);
    window.addEventListener('popstate', runCheck);
    window.addEventListener('hashchange', runCheck);
    document.addEventListener('visibilitychange', runCheck);
    // Logo depois de uma interação (provável login/logout) confere de novo sem esperar o intervalo.
    var burst = false;
    function schedule() {
      if (burst) return;
      burst = true;
      setTimeout(runCheck, 1200);
      setTimeout(function () { runCheck(); burst = false; }, 3500);
    }
    document.addEventListener('click', schedule, true);
    document.addEventListener('keydown', function (e) { if (e.key === 'Enter') schedule(); }, true);
  }

  // ---------------------------------------------------------------- chamadas HTTP (resposta / requisicao)

  var reUrl = null;
  function matches(url, method) {
    if (!cfg.url) return false;
    if (!reUrl) reUrl = glob2re(cfg.url);
    var want = cfg.metodo ? String(cfg.metodo).toUpperCase() : (TIPO === 'requisicao' ? 'POST' : '');
    if (want && String(method).toUpperCase() !== want) return false;
    return reUrl.test(hostPath(url));
  }

  function httpOk(status, text) {
    var rule = cfg.sucesso || {};
    var ok = rule.status ? [].concat(rule.status).indexOf(status) >= 0 : (status >= 200 && status < 300);
    if (!ok) return false;
    if (rule.campo) {
      var o = parse(text);
      var v = o ? dig(o, rule.campo) : null;
      return v != null && v !== '' && v !== false;
    }
    return true;
  }

  /** Só o campo configurado é lido do corpo enviado (usuário); o resto, inclusive a senha, é descartado. */
  function userFromBody(body) {
    if (body == null) return '';
    var obj = null;
    try {
      if (typeof body === 'string') {
        obj = parse(body);
        if (!obj) obj = Object.fromEntries(new URLSearchParams(body));
      } else if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
        obj = Object.fromEntries(body);
      } else if (typeof FormData !== 'undefined' && body instanceof FormData) {
        obj = Object.fromEntries(body.entries());
      }
    } catch (e) { obj = null; }
    var v = obj ? dig(obj, cfg.campo) : null;
    obj = null;
    return (v == null || typeof v === 'object') ? '' : str(v);
  }

  function handleHttp(status, text, header, candidate) {
    if (TIPO === 'requisicao') {
      if (candidate && httpOk(status, text)) report(candidate, 'requisicao', true);
    } else { // resposta: o usuário vem da própria resposta (corpo ou cabeçalho)
      if (status < 200 || status >= 300) return;
      var raw = cfg.cabecalho ? header : text;
      // "repetir": true = essa chamada é a de login: cada resposta é um novo login, mesmo do mesmo usuário.
      report(applyRegex(extract(raw)), 'resposta', cfg.repetir === true);
    }
  }

  function hook() {
    var of = window.fetch;
    if (of) {
      window.fetch = function (input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url);
        var method = (init && init.method) || (input && input.method) || 'GET';
        var watch = false, cand = '';
        try {
          watch = matches(url, method);
          if (watch && TIPO === 'requisicao') cand = userFromBody(init && init.body);
        } catch (e) {}
        var p = of.apply(this, arguments);
        if (watch) {
          p.then(function (res) {
            var c = res.clone();
            c.text().then(function (t) {
              handleHttp(res.status, t, cfg.cabecalho ? res.headers.get(cfg.cabecalho) : null, cand);
            });
          }).catch(function () {});
        }
        return p;
      };
    }
    var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__hw = { m: m, u: u };
      return XO.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      var h = this.__hw, self = this;
      try {
        if (h && matches(h.u, h.m)) {
          var cand = (TIPO === 'requisicao') ? userFromBody(body) : '';
          this.addEventListener('load', function () {
            var t = '';
            try { t = (self.responseType === '' || self.responseType === 'text') ? self.responseText : ''; } catch (e) {}
            handleHttp(self.status, t, cfg.cabecalho ? self.getResponseHeader(cfg.cabecalho) : null, cand);
          });
        }
      } catch (e) {}
      return XS.apply(this, arguments);
    };
  }

  // ---------------------------------------------------------------- formulário de login

  function userField() {
    if (cfg.seletor) {
      var s = document.querySelector(cfg.seletor);
      return (s && !isPassword(s)) ? s : null;
    }
    var pw = pwInputs()[0];
    if (!pw) return null;
    var scope = pw.form || pw.closest('form') || document;
    var inputs = Array.prototype.slice.call(scope.querySelectorAll('input')).filter(function (i) {
      var t = String(i.type || 'text').toLowerCase();
      return ['text', 'email', 'tel', 'number', 'search'].indexOf(t) >= 0 && !i.disabled && i.offsetParent !== null;
    });
    var before = inputs.filter(function (i) { return i.compareDocumentPosition(pw) & Node.DOCUMENT_POSITION_FOLLOWING; });
    var pool = before.length ? before : inputs;
    var byAuto = pool.filter(function (i) { return /username/i.test(i.getAttribute('autocomplete') || ''); });
    if (byAuto.length) return byAuto[0];
    var byName = pool.filter(function (i) {
      return /user|usu|login|mail|cpf|matr|cod|ident/i.test((i.name || '') + ' ' + (i.id || '') + ' ' + (i.placeholder || ''));
    });
    if (byName.length) return byName[byName.length - 1];
    return pool.length ? pool[pool.length - 1] : null;
  }

  function checkRule(rule) {
    var tipo = rule.tipo || 'senhaSumiu';
    switch (tipo) {
      case 'senhaSumiu':
        return Promise.resolve(pwInputs().length === 0 && document.readyState !== 'loading');
      case 'url':
        try { return Promise.resolve(new RegExp(rule.regex).test(location.href)); } catch (e) { return Promise.resolve(false); }
      case 'elemento':
        return Promise.resolve(!!(rule.seletor && document.querySelector(rule.seletor)));
      case 'endpoint':
        return fetch(rule.url, { credentials: 'include' })
          .then(function (r) { return r.ok ? r.text() : ''; })
          .then(function (t) {
            if (!t) return false;
            if (!rule.campo) return true;
            var o = parse(t), v = o ? dig(o, rule.campo) : null;
            return v != null && v !== '';
          }).catch(function () { return false; });
    }
    return Promise.resolve(false);
  }

  /** Espera a regra de sucesso (padrão: o campo de senha sumiu) por até "espera" segundos antes de descartar a tentativa. */
  function waitSuccess(user, startedAt) {
    var rule = cfg.sucesso || {};
    var timeout = (Number(rule.espera) || 10) * 1000;
    (function poll() {
      checkRule(rule).then(function (ok) {
        if (ok) {
          try { sessionStorage.removeItem(PEND); } catch (e) {}
          report(user, 'formulario', true);
        } else if (Date.now() - startedAt < timeout) {
          setTimeout(poll, 700);
        } else {
          try { sessionStorage.removeItem(PEND); } catch (e) {}
        }
      });
    })();
  }

  function attempt() {
    if (!pwTyped) return;
    var f = userField();
    if (!f) return;
    var u = str(f.value);     // só o campo de usuário é lido
    if (!u) return;
    pwTyped = false;
    var now = Date.now();
    // Login por navegação (a página recarrega): guarda a tentativa para conferir na página seguinte.
    try { sessionStorage.setItem(PEND, JSON.stringify({ u: u, t: now })); } catch (e) {}
    waitSuccess(u, now);
  }

  function startForm() {
    // Marca que algo foi digitado em um campo de senha, sem ler o valor.
    document.addEventListener('input', function (e) { if (isPassword(e.target)) pwTyped = true; }, true);
    // Na fase de captura roda antes dos manipuladores da página (que podem limpar os campos).
    document.addEventListener('submit', attempt, true);
    document.addEventListener('click', function (e) {
      var t = e.target;
      if (t && t.closest && t.closest('button,input[type=submit],input[type=button],[role=button],a')) attempt();
    }, true);
    document.addEventListener('keydown', function (e) { if (e.key === 'Enter') attempt(); }, true);
    try {
      var p = parse(sessionStorage.getItem(PEND));
      var rule = cfg.sucesso || {};
      var limit = ((Number(rule.espera) || 10) * 2) * 1000;
      if (p && p.u && Date.now() - p.t < limit) waitSuccess(p.u, p.t);
    } catch (e) {}
  }

  // ---------------------------------------------------------------- regra de logout (opcional)

  /**
   * "logout": {"tipo": "url", "regex": "..."} ou {"tipo": "elemento", "seletor": "..."}.
   * Quando a página (ou um elemento dela) casa, a tela é a de login/saída: ninguém está logado, e o app registra o logout.
   */
  function checkLogout() {
    var r = cfg.logout;
    if (!r) return;
    var on = false;
    try {
      on = (r.tipo === 'elemento') ? !!document.querySelector(r.seletor) : new RegExp(r.regex).test(location.href);
    } catch (e) {}
    if (on && last !== '') {
      last = '';
      try { B.report('', 'logout', location.href, Number(cfg.__idx) || 0, false); } catch (e) {}
    }
  }

  if (cfg.logout) {
    if (document.readyState === 'complete') checkLogout(); else window.addEventListener('load', checkLogout);
    window.addEventListener('popstate', checkLogout);
    window.addEventListener('hashchange', checkLogout);
  }

  // ---------------------------------------------------------------- início

  if (TIPO === 'resposta' || TIPO === 'requisicao') {
    hook();
  } else if (TIPO === 'formulario') {
    startForm();
  } else {
    if (document.readyState === 'complete') startPolling(); else window.addEventListener('load', startPolling);
  }
})(__CFG__);
