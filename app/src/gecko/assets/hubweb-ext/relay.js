/*
 * Ponte entre a página e o app (HubWeb, Gecko). Roda isolado da página: pede ao app a configuração de detecção de usuário
 * do sistema aberto, entrega ao main.js (que roda na página, com os mesmos hooks do WebView) e repassa ao app o que ele
 * reportar. Só o login chega ao app; nunca a senha.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  var APP = 'hubweb';

  function enviar(msg) {
    try { return browser.runtime.sendNativeMessage(APP, msg); } catch (e) { return Promise.reject(e); }
  }

  enviar({ t: 'cfg', href: location.href }).then(function (r) {
    if (r && r.cfgs && r.cfgs.length) window.postMessage({ __hw: 1, t: 'cfg', cfgs: r.cfgs }, '*');
  }, function () {});

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (e.source !== window || !d || d.__hw !== 1 || d.t !== 'report') return;
    enviar({
      t: 'report',
      user: d.user == null ? '' : String(d.user),
      fonte: d.fonte == null ? '' : String(d.fonte),
      href: location.href,
      idx: Number(d.idx) || 0,
      novo: !!d.novo
    }).catch(function () {});
  });

  // Canal com o app: ele pede para gravar um texto no campo focado (leitor de código com acentos e símbolos).
  try {
    var porta = browser.runtime.connectNative(APP);
    porta.onMessage.addListener(function (m) {
      if (!m || m.t !== 'digitar' || typeof m.texto !== 'string') return;
      var e = document.activeElement;
      while (e && e.contentDocument && e.contentDocument.activeElement) e = e.contentDocument.activeElement;
      if (!e) return;
      var ok = false;
      try { ok = document.execCommand('insertText', false, m.texto); } catch (x) {}
      if (ok) return;
      try {
        if (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA') {
          var proto = e.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, (e.value || '') + m.texto);
          e.dispatchEvent(new Event('input', { bubbles: true }));
          e.dispatchEvent(new Event('change', { bubbles: true }));
        }
      } catch (x) {}
    });
  } catch (e) {}
})();
