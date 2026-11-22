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
})();
