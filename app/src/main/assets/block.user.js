// ==UserScript==
// @name         Lúcido
// @description  Bloquea el feed de Reels/Shorts pero deja ver los que te mandan
// @version      22
// @match        *://*.instagram.com/*
// @match        *://instagram.com/*
// @match        *://*.youtube.com/*
// @run-at       document-start
// ==/UserScript==
//
// Este archivo es la fuente única de las reglas: la app lo trae incluido y además
// descarga la versión de la rama main. Para publicar un arreglo sin sacar un APK nuevo,
// editalo y subí el número de @version.
(() => {
  if (window.__noscroll) return;
  window.__noscroll = true;

  // --- Config (la app la inyecta desde Ajustes; en Safari/Userscripts usa estos valores) ---
  const DEFAULTS = {
    igFeed: 'normal', igBlockExplore: true, igTapToPlay: false, igReels: false, ytShorts: false,
    restricted: false, restrictedMsg: '', smooth: false, swipeReply: false, igBlockAds: true, ytBlockAds: true,
  };
  const config = (given) => {
    const c = Object.assign({}, DEFAULTS, given || {});
    if (c.igBlockFeed) c.igFeed = 'blocked'; // versiones viejas de la app mandan igBlockFeed
    delete c.igBlockFeed;
    return c;
  };
  // Solo mensajes, Reels y Shorts pueden cambiar con la página abierta (sección 14); el resto, al cargar
  const CFG = config(window.__noscrollCfg);

  const IG = /(^|\.)instagram\.com$/.test(location.hostname);
  const YT = /(^|\.)youtube\.com$/.test(location.hostname);
  if (!IG && !YT) return;

  // Aviso que dejó la página anterior al redirigir. Se lee antes de la guardia de URL,
  // que puede redirigir otra vez y seguir corriendo un instante en esta página.
  let pendingToast = null;
  try { pendingToast = sessionStorage.getItem('brl_toast'); sessionStorage.removeItem('brl_toast'); } catch (e) {}

  // --- 1. Ocultar accesos al feed (cosmético; los selectores pueden romperse) ---
  // igReels / ytShorts: la app los deja ver mientras no se llegue al límite diario (ni sea de noche).
  // Se rearma si cambia la configuración con la página abierta.
  const css = () => {
    const hidden = [!CFG.igReels && 'a[href="/reels/"]', CFG.igBlockExplore && 'a[href="/explore/"]'].filter(Boolean);
    const SMOOTH = CFG.smooth
      ? '@view-transition { navigation: auto; }' + // fundido también en las páginas que se recargan
        '::view-transition-old(root), ::view-transition-new(root) { animation-duration: .18s; }'
      : '';
    return SMOOTH + (IG
      ? (hidden.length ? `${hidden.join(', ')} { display:none !important; }` : '') +
        (CFG.restricted ? 'a[href="/"], video { display:none !important; }' : '')
      : (CFG.ytShorts ? '' : `
        ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model,
        ytm-rich-item-renderer:has(a[href^="/shorts/"]),
        ytm-video-with-context-renderer:has(a[href^="/shorts/"]),
        ytm-pivot-bar-item-renderer:has(.pivot-shorts),
        ytd-reel-shelf-renderer, ytd-rich-shelf-renderer[is-shorts],
        ytd-rich-item-renderer:has(a[href^="/shorts/"]),
        ytd-guide-entry-renderer:has(a[title="Shorts"]),
        ytd-mini-guide-entry-renderer[aria-label="Shorts"]
        { display:none !important; }`) +
        // Anuncios entre los videos, en la búsqueda, debajo del reproductor y encima del video (sección 15)
        (CFG.ytBlockAds ? `
        ad-slot-renderer, ytm-rich-item-renderer:has(ad-slot-renderer),
        ytm-promoted-sparkles-web-renderer, ytm-promoted-sparkles-text-search-renderer,
        ytm-promoted-video-renderer, ytm-companion-ad-renderer, ytm-companion-slot,
        ytm-brand-video-singleton-renderer, ytm-brand-video-shelf-renderer,
        ytd-ad-slot-renderer, ytd-rich-item-renderer:has(ytd-ad-slot-renderer),
        ytd-in-feed-ad-layout-renderer, ytd-promoted-sparkles-web-renderer, ytd-banner-promo-renderer,
        ytd-statement-banner-renderer, ytd-companion-slot-renderer, ytd-action-companion-ad-renderer,
        ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"],
        #masthead-ad, #player-ads, .ytp-ad-overlay-container, .ytp-ad-image-overlay
        { display:none !important; }` : ''));
  };
  let style = null;
  const addStyle = () => {
    style = document.createElement('style');
    style.textContent = css();
    (document.head || document.documentElement).appendChild(style);
  };
  const restyle = () => { if (style) style.textContent = css(); };
  document.documentElement ? addStyle() : addEventListener('DOMContentLoaded', addStyle);

  // Los APK de prueba ponen antes sus herramientas (devtools.js, que no va en la versión publicada): les
  // cuenta lo que hace el script por su cuenta, cuándo ya se ve la página y si no encontró "Responder"
  const dev = (what, x) => { if (window.__lucidoDev) window.__lucidoDev.on(what, x); };

  // Publicaciones del feed de Instagram (secciones 15 y 16): cada una es un <article>; si en esa página no los
  // usa, el contenedor más grande con un solo botón de "Me gusta" (y no más alto que dos pantallas y media)
  const LIKE = 'svg[aria-label="Me gusta"], svg[aria-label="Like"], svg[aria-label="Ya no me gusta"], svg[aria-label="Unlike"]';
  const feedPosts = () => {
    const articles = document.getElementsByTagName('article');
    if (articles.length) return [...articles];
    const out = new Set();
    for (const like of document.querySelectorAll(LIKE)) {
      let p = like;
      for (let up = p.parentElement; up && up !== document.body && up.querySelectorAll(LIKE).length === 1 &&
        up.getBoundingClientRect().height < innerHeight * 2.5; up = up.parentElement) p = up;
      if (p !== like) out.add(p);
    }
    return [...out];
  };
  const hasPosts = (el) => !!el.querySelector('article, ' + LIKE);
  // Las cuentas que seguís (sección 16), en minúsculas, para filtrar el feed (secciones 16 y 18). null si no se sabe
  let igFollowed = null;

  // Lo que una sección rehace al cambiar la URL: corre ahora y después en cada cambio (desde check)
  const onRoute = [];
  const route = (f) => { onRoute.push(f); f(); };
  // Lo que una sección rehace si cambia la configuración con la página abierta (sección 14)
  const onConfig = [];

  // --- 2. Reels: que la pantalla no se mueva al deslizar ---
  // Bloquea el gesto sobre el contenedor que pasa de un video al siguiente.
  // Los comentarios y todo lo que no tenga videos adentro sigue scrolleando.
  // Un touchmove que puede frenar el gesto hace que el navegador espere a la página en cada
  // movimiento: por eso va en ese contenedor y en la página entera solo en /reel/. Con Reels permitidos
  // (dentro del límite) no hace nada, y se activa si se termina el tiempo con la página abierta.
  if (IG) {
    const onReel = () => /^\/reels?\/[\w-]+/.test(location.pathname);
    // Con snap vertical cuenta aunque todavía no desborde: el visor puede sumar el siguiente reel después
    const scrollerOf = (el) => {
      for (; el && el.nodeType === 1; el = el.parentElement) {
        const s = getComputedStyle(el);
        if ((s.overflowY === 'auto' || s.overflowY === 'scroll') &&
            (el.scrollHeight > el.clientHeight || /^(y|block|both)/.test(s.scrollSnapType))) return el;
      }
      return null; // scrollea la página entera
    };
    const blocks = (target) => {
      const s = scrollerOf(target);
      if (!s) return onReel();
      if (!s.querySelector('video')) return false;
      // Fuera de /reel/ también, por si el visor se abre encima del chat sin cambiar la URL
      return onReel() || getComputedStyle(s).scrollSnapType !== 'none';
    };

    let blocking = false;
    const START = { capture: true, passive: true }, MOVE = { capture: true, passive: false };
    const start = (e) => { if (e.touches.length === 1) blocking = !CFG.igReels && blocks(e.target); };
    const move = (e) => { if (blocking && e.cancelable) e.preventDefault(); };
    const listen = (el, on) => {
      const f = on ? 'addEventListener' : 'removeEventListener';
      el[f]('touchstart', start, START);
      el[f]('touchmove', move, MOVE);
    };
    route(() => listen(window, onReel() && !CFG.igReels));
    // El visor puede abrirse encima del chat sin cambiar la URL: se engancha su contenedor
    // (el que tiene scroll-snap) apenas un video carga o arranca, antes de que lo deslices
    const hooked = new WeakSet();
    const hook = (e) => {
      if (CFG.igReels) return;
      const s = e.target instanceof HTMLVideoElement && scrollerOf(e.target);
      if (!s || hooked.has(s) || getComputedStyle(s).scrollSnapType === 'none') return;
      hooked.add(s);
      listen(s, true);
    };
    addEventListener('loadstart', hook, true);
    addEventListener('playing', hook, true);
    // Se terminó el tiempo con un reel abierto encima del chat: engancharlo ya, sin esperar al próximo
    onConfig.push(() => { for (const v of document.getElementsByTagName('video')) hook({ target: v }); });
    addEventListener('wheel', (e) => {
      if (!CFG.igReels && blocks(e.target)) e.preventDefault();
    }, { capture: true, passive: false });
    addEventListener('keydown', (e) => {
      const t = e.target;
      if (!CFG.igReels && /^(ArrowUp|ArrowDown|PageUp|PageDown)$/.test(e.key) &&
          !(t.isContentEditable || /^(INPUT|TEXTAREA)$/.test(t.tagName)) && blocks(t)) e.preventDefault();
    }, true);
  }

  // Navegar dentro del sitio (como el botón atrás), sin recargar ni pasar por la pantalla de carga de
  // Instagram. El sitio se movió si cambió el título, si sacó varios elementos o si ya no está lo que había
  // en el centro; si en 1 s no hay ninguna de esas señales, recién ahí se carga la URL (como antes).
  const navigate = (to, replace, beforeReload) => {
    const title = document.title;
    const before = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    let removed = 0;
    const obs = new MutationObserver((records) => {
      for (const r of records) for (const n of r.removedNodes) if (n.nodeType === 1) removed += 1 + n.getElementsByTagName('*').length;
    });
    obs.observe(document.documentElement, { childList: true, subtree: true });
    history[replace ? 'replaceState' : 'pushState'](null, '', to);
    dispatchEvent(new PopStateEvent('popstate', { state: null }));
    setTimeout(() => {
      obs.disconnect();
      const moved = document.title !== title || removed > 10 || !before || !before.isConnected || !before.getClientRects().length;
      if (location.pathname !== to || moved) return;
      if (beforeReload) beforeReload();
      location.replace(to);
    }, 1000);
  };

  // Al inbox: con la página ya andando, dentro del sitio (después de que termine su propia navegación);
  // si recién empieza a cargar, cargándolo directo
  const toInbox = () => {
    if (document.readyState === 'loading') location.replace('/direct/inbox/');
    else setTimeout(() => { if (location.pathname !== '/direct/inbox/') navigate('/direct/inbox/', true); }, 0);
  };

  // --- 3. Guardia de URL (respaldo; no depende del DOM) ---
  let allowedReel = null;
  let lastPath = null;
  const check = () => {
    const p = location.pathname;
    if (p !== lastPath) { lastPath = p; for (const f of onRoute) f(); }

    if (YT) {
      // Short -> reproductor clásico: se ve igual, pero sin swipe al siguiente
      const m = p.match(/^\/shorts\/([\w-]{11})/);
      if (m && !CFG.ytShorts) location.replace('/watch?v=' + m[1] + location.search.replace(/^\?/, '&'));
      return;
    }

    // Instagram
    // Modo solo mensajes (hora de dormir): fuera de los chats, todo vuelve al inbox
    if (CFG.restricted && !/^\/(direct|accounts|challenge)\//.test(p)) {
      try { sessionStorage.setItem('brl_toast', CFG.restrictedMsg); } catch (e) {}
      toInbox();
      return;
    }
    const blocked =
      (!CFG.igReels && /^\/reels\/?$/.test(p)) ||
      (CFG.igBlockExplore && /^\/explore\/?$/.test(p)) ||
      (CFG.igFeed === 'blocked' && p === '/');
    if (blocked) {
      toInbox();
      return;
    }

    const m = p.match(/^\/reels?\/([\w-]+)/);
    if (!m || CFG.igReels) { allowedReel = null; return; }
    const id = m[1];
    if (allowedReel === null) {
      allowedReel = id;                               // reel abierto a propósito (DM, link, perfil)
    } else if (id !== allowedReel) {
      location.replace('/reel/' + allowedReel + '/'); // pasó al siguiente: vuelve
    }
  };

  for (const k of ['pushState', 'replaceState']) {
    const orig = history[k];
    history[k] = function (...a) { const r = orig.apply(this, a); check(); return r; };
  }
  addEventListener('popstate', () => check());
  // Respaldo por si la SPA cambia la URL por otra vía. Lo normal lo atrapan los hooks y popstate,
  // así que alcanza con mirar cada tanto (antes era cada 400 ms, también mientras scrolleás)
  setInterval(check, 3000);
  check();

  // --- 4. Modo solo mensajes: sin videos (ni reels que te mandan); los audios sí ---
  // Siempre instalado en Instagram y activo según CFG.restricted: puede empezar con la página abierta
  if (IG) {
    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (!CFG.restricted || !(this instanceof HTMLVideoElement)) return play.call(this);
      return Promise.reject(new DOMException('Hora de dormir', 'NotAllowedError'));
    };
    addEventListener('play', (e) => { if (CFG.restricted && e.target instanceof HTMLVideoElement) e.target.pause(); }, true);
    // Empezó con la página abierta: frenar el video que se estaba viendo
    onConfig.push(() => { if (CFG.restricted) for (const v of document.getElementsByTagName('video')) v.pause(); });

    // Aviso de por qué te devolvió al inbox
    const toast = () => {
      const t = document.createElement('div');
      t.textContent = pendingToast;
      t.style.cssText =
        'position:fixed;left:50%;top:16px;transform:translateX(-50%);z-index:2147483647;max-width:90vw;' +
        'background:rgba(0,0,0,.85);color:#fff;padding:10px 16px;border-radius:20px;font:14px sans-serif;' +
        'text-align:center;pointer-events:none';
      document.body.appendChild(t);
      setTimeout(() => t.remove(), 4000);
    };
    if (CFG.restricted && pendingToast) document.body ? toast() : addEventListener('DOMContentLoaded', toast);
  }

  // --- 5. Videos solo al tocar (Instagram, opcional) ---
  // Los videos no arrancan ni se descargan hasta que los tocás. Los reels e historias
  // que abrís arrancan solos: ya tocaste para abrirlos.
  if (IG && CFG.igTapToPlay) {
    const tapped = new WeakSet();
    const badges = new WeakMap();
    const viewerPage = () => /^\/(reels?|stories)\//.test(location.pathname);
    // Un reel que te mandan se abre encima del chat, a veces sin cambiar la URL: se lo reconoce porque pasa
    // de un video a otro deslizando para arriba. Los carruseles del feed se deslizan de costado: no cuentan
    const inViewer = (v) => {
      if (viewerPage()) return true;
      if (!v.isConnected || !/^\/direct\//.test(location.pathname)) return false;
      for (let el = v.parentElement; el && el.nodeType === 1; el = el.parentElement) {
        if (/^(y|block|both)/.test(getComputedStyle(el).scrollSnapType)) return true;
      }
      return false;
    };
    const allowed = (v) => !(v instanceof HTMLVideoElement) || tapped.has(v) || inViewer(v);

    // Frenar la reproducción no alcanza: el reproductor de Instagram baja los videos por su cuenta (pide
    // los pedazos al servidor, no el <video>). Esos pedidos pasan mientras se ve un video permitido, en un
    // reel o una historia, o recién tocaste algo en un chat (los reels que te mandan se abren encima, sin
    // cambiar la URL). Si no, esperan. Al abrirse pasan los últimos (los de los videos que tenés cerca) y
    // los de antes se descartan, como si se hubiera cortado la conexión.
    // Mientras ves el que tocaste, el comienzo de los otros (bytestart=0: Instagram los adelanta por si bajás)
    // sigue esperando; los pedazos que siguen del tuyo pasan
    const playing = new Set(); // audios y videos permitidos que arrancaron
    let chatTap = -Infinity, tapAt = -Infinity;
    const isOpen = (url) => {
      if (viewerPage() || (/^\/direct\//.test(location.pathname) && performance.now() - chatTap < 3000)) return true;
      for (const m of playing) if (m.paused || m.ended) playing.delete(m);
      if (!playing.size) return false;
      return !(url && /[?&]bytestart=0(&|$)/.test(url) && performance.now() - tapAt > 2000);
    };
    const isVideo = (url) => {
      try {
        const u = new URL(url, location.href);
        return /(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname) && /\.(mp4|m4v|m4a|webm)$/i.test(u.pathname);
      } catch (e) {
        return false;
      }
    };
    const KEEP = 2;
    let held = []; // { send, drop? }, en el orden en que llegaron
    const hold = (h) => { held.push(h); dev('video', 'held'); };
    const release = (why) => {
      if (!held.length || !isOpen()) return;
      const all = held;
      held = [];
      dev('note', `videos solo al tocar: salieron los pedidos (${why})`);
      all.forEach((h, i) => (i >= all.length - KEEP || !h.drop ? h.send() : h.drop()));
    };
    const play0 = (m) => {
      if (playing.has(m)) return;
      playing.add(m);
      release(!(m instanceof HTMLVideoElement) ? 'un audio' : tapped.has(m) ? 'tocaste un video' : 'un reel o una historia');
    };

    const fetch0 = window.fetch;
    window.fetch = function (input, init) {
      const url = input instanceof Request ? input.url : String(input);
      const signal = (init && init.signal) || (input instanceof Request ? input.signal : null);
      if (isOpen(url) || !isVideo(url) || (signal && signal.aborted)) return fetch0.apply(window, arguments);
      const args = arguments;
      return new Promise((resolve, reject) => {
        const off = () => { if (signal) signal.removeEventListener('abort', abort); };
        const h = {
          send: () => { off(); dev('video', 'sent'); fetch0.apply(window, args).then(resolve, reject); },
          drop: () => { off(); dev('video', 'dropped'); reject(new TypeError('Failed to fetch')); },
        };
        // Instagram ya no lo quiere (pasaste de largo): se olvida
        const abort = () => { held = held.filter((x) => x !== h); reject(signal.reason || new DOMException('Aborted', 'AbortError')); };
        if (signal) signal.addEventListener('abort', abort);
        hold(h);
      });
    };
    const xhr = XMLHttpRequest.prototype, xopen = xhr.open, xsend = xhr.send, xabort = xhr.abort;
    const xurl = new WeakMap(), xheld = new WeakMap();
    const forget = (x) => { const h = xheld.get(x); if (h) { xheld.delete(x); held = held.filter((y) => y !== h); } };
    xhr.open = function (method, url) { forget(this); xurl.set(this, String(url)); return xopen.apply(this, arguments); };
    xhr.abort = function () { forget(this); return xabort.apply(this, arguments); };
    xhr.send = function () {
      const url = xurl.get(this) || '';
      if (isOpen(url) || !isVideo(url)) return xsend.apply(this, arguments);
      const x = this, args = arguments;
      const h = { send: () => { xheld.delete(x); dev('video', 'sent'); xsend.apply(x, args); } }; // sin drop: no se puede imitar el corte
      xheld.set(x, h);
      hold(h);
    };
    route(() => release('abriste un reel o una historia'));
    addEventListener('click', (e) => {
      if (!e.isTrusted || !/^\/direct\//.test(location.pathname)) return;
      chatTap = performance.now();
      release('tocaste algo en un chat');
    }, true);

    // Desde que se crea el video, sin precarga ni arranque solo (React los pone con setAttribute)
    const prop = (k, off) => {
      const d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, k);
      Object.defineProperty(HTMLMediaElement.prototype, k, { ...d, set(x) { d.set.call(this, allowed(this) ? x : off); } });
    };
    prop('preload', 'none');
    prop('autoplay', false);
    const setAttr = Element.prototype.setAttribute;
    Element.prototype.setAttribute = function (name, value) {
      if (this instanceof HTMLVideoElement && /^(preload|autoplay)$/i.test(name) && !allowed(this)) {
        if (/^autoplay$/i.test(name)) return;
        value = 'none';
      }
      return setAttr.call(this, name, value);
    };

    const showBadge = (v) => {
      const parent = v.parentElement;
      if (badges.has(v) || !parent) return;
      const b = document.createElement('div');
      b.textContent = '▶';
      b.style.cssText =
        'position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:64px;height:64px;' +
        'border-radius:50%;background:rgba(0,0,0,.55);color:#fff;font:28px/64px sans-serif;' +
        'text-align:center;pointer-events:none;z-index:2147483647';
      if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
      parent.appendChild(b);
      badges.set(v, b);
    };
    const hideBadge = (v) => { badges.get(v)?.remove(); badges.delete(v); };

    const play = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () {
      if (allowed(this)) {
        const p = play.call(this);
        play0(this); // después: recién ahí deja de estar en pausa
        return p;
      }
      showBadge(this);
      return Promise.reject(new DOMException('Tocá el video para reproducirlo', 'NotAllowedError'));
    };
    // Por si arranca por el atributo autoplay, sin pasar por play()
    addEventListener('play', (e) => {
      if (allowed(e.target)) { hideBadge(e.target); play0(e.target); return; }
      e.target.pause();
      showBadge(e.target);
    }, true);

    // El ▶ en los videos frenados (y, por las dudas, sin precarga si llegó por otro lado)
    let pending = false;
    new MutationObserver(() => {
      if (pending) return;
      pending = true;
      setTimeout(() => {
        pending = false;
        for (const v of document.getElementsByTagName('video')) {
          if (allowed(v)) continue;
          if (v.preload !== 'none') { v.autoplay = false; v.preload = 'none'; }
          showBadge(v);
        }
      }, 200);
    }).observe(document, { childList: true, subtree: true }); // document: el <html> puede no existir todavía

    // Primer toque sobre un video frenado: arranca (en vez de, por ejemplo, activar el sonido)
    addEventListener('click', (e) => {
      if (e.target.closest && e.target.closest('a[href],button')) return;
      for (const v of document.getElementsByTagName('video')) {
        if (allowed(v)) continue;
        const r = v.getBoundingClientRect();
        if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) continue;
        e.preventDefault();
        e.stopPropagation();
        dev('note', 'videos al tocar: frené el toque y reproduje el video');
        tapped.add(v);
        tapAt = performance.now();
        v.preload = 'auto';
        v.play().catch(() => {});
        return;
      }
    }, true);
  }

  // --- 6. Sacar los carteles de "Usá la app" (Instagram) ---
  // Por texto, porque las clases cambian seguido. En una ventana se toca "Ahora no";
  // en un banner fijo se oculta el botón (y el banner, si no tiene nada más).
  // Se mira solo lo que se agregó, cuando el navegador está libre (no en medio del scroll). Una
  // ventana se mira entera: el "Ahora no" puede aparecer después que el botón.
  if (IG) {
    const APP = /^(abrir|usar|usá|usa|descargar|obtener|ver|cambiar a|open|use|get|download|view|switch to)( en| in)?( la| the)? (app|aplicación|instagram)$/i;
    const LATER = /^(ahora no|no, gracias|cancelar|cerrar|(seguir|continuar) en (el navegador|la web)|not now|no thanks|cancel|close|continue (in|on) (the )?(browser|web))$/i;
    const text = (el) => (el.textContent || '').replace(/\s+/g, ' ').trim();
    const CLICKABLE = 'a,button,[role="button"]';

    const fix = (el) => {
      if (!APP.test(text(el))) return;
      const dialog = el.closest('[role="dialog"]');
      const later = dialog && [...dialog.querySelectorAll(CLICKABLE)].find((b) => LATER.test(text(b)));
      if (later) { dev('note', `carteles: toqué "${text(later)}" en una ventana con "${text(el)}"`); later.click(); return; }

      dev('note', `carteles: oculté "${text(el)}"`);
      el.style.setProperty('display', 'none', 'important');
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const pos = getComputedStyle(p).position;
        if (pos !== 'fixed' && pos !== 'sticky') continue;
        const others = [...p.querySelectorAll(CLICKABLE)].filter(
          (b) => b !== el && !APP.test(text(b)) && !LATER.test(text(b)) &&
            !/^(cerrar|close)$/i.test(b.getAttribute('aria-label') || '')
        );
        if (!others.length) p.style.setProperty('display', 'none', 'important');
        break;
      }
    };
    let added = [], scheduled = false;
    const idle = window.requestIdleCallback ? (f) => requestIdleCallback(f, { timeout: 1000 }) : (f) => setTimeout(f, 300);
    const sweep = () => {
      scheduled = false;
      const roots = new Set();
      for (const n of added) {
        const el = n.nodeType === 1 ? n : n.parentElement; // texto nuevo: su elemento
        if (el && el.isConnected) roots.add(el.closest('[role="dialog"]') || el.closest(CLICKABLE) || el);
      }
      added = [];
      for (const r of roots) {
        if (r.matches(CLICKABLE)) fix(r);
        for (const b of r.querySelectorAll(CLICKABLE)) fix(b);
      }
    };
    new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'characterData') added.push(r.target);
        else for (const n of r.addedNodes) added.push(n);
      }
      if (!scheduled && added.length) { scheduled = true; idle(sweep); }
    }).observe(document, { childList: true, subtree: true, characterData: true }); // document: el <html> puede no existir todavía
  }

  // --- 7. Transiciones suaves (opcional, apagadas por defecto) ---
  // Fundido corto al cambiar de página dentro del sitio, de cómo se veía a la página en vivo. No se
  // espera a que la nueva termine de dibujarse: congelaba la pantalla. Mientras dura, no toma toques.
  if (CFG.smooth && document.startViewTransition) {
    const VIDEO_PAGE = /^\/(reels?|stories|shorts|watch)(\/|$)/; // un video congelado se nota
    let running = false;
    const push = history.pushState;
    history.pushState = function (...a) {
      const from = location.pathname;
      const r = push.apply(this, a);
      if (!running && document.visibilityState === 'visible' &&
          !VIDEO_PAGE.test(from) && !VIDEO_PAGE.test(location.pathname)) {
        running = true;
        try {
          document.startViewTransition(() => {}).finished.finally(() => { running = false; });
        } catch (e) { running = false; }
      }
      return r;
    };
  }

  // --- 8. Deslizar un mensaje a la derecha para responder (Instagram, opcional) ---
  // Instagram web no lo trae. El mensaje se mueve con el dedo y, al soltar, se imita lo que hace
  // la persona a mano: mantenerlo presionado y tocar "Responder" en el menú. Depende del HTML de
  // Instagram: si deja de andar, se arregla acá.
  if (IG && CFG.swipeReply) {
    // "Responder" puede venir como etiqueta ("Responder", "Responder al mensaje"), como título del
    // dibujo (<title> del SVG) o como texto de un ítem de menú
    const LABEL_RE = /(^|\s)(responder|reply)(\s|$)/i;
    const TEXT_RE = /^(responder|reply)$/i;
    const isReply = (el) => {
      const title = el.querySelector ? el.querySelector(':scope > title') : null;
      const lbl = el.getAttribute('aria-label') || el.getAttribute('title') || (title && title.textContent) || '';
      if (LABEL_RE.test(lbl.trim())) return true;
      return el.matches('[role="button"], [role="menuitem"], button') &&
        TEXT_RE.test((el.textContent || '').replace(/\s+/g, ' ').trim());
    };
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

    const scrollerOf = (el) => {
      for (; el && el.nodeType === 1; el = el.parentElement) {
        const oy = getComputedStyle(el).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight) return el;
      }
      return null;
    };
    // La burbuja tocada: dentro de la lista que scrollea (no el encabezado ni la caja de texto), el
    // ancestro más grande que todavía no ocupa todo el ancho (las filas sí lo ocupan; las burbujas no).
    const bubbleOf = (target) => {
      let el = target.nodeType === 1 ? target : target.parentElement;
      if (!el || el.closest('textarea, input, [contenteditable="true"], [role="textbox"], form')) return null;
      const list = scrollerOf(el);
      if (!list) return null;
      const full = list.clientWidth * 0.85;
      while (el.parentElement && el.parentElement !== list && el.parentElement.getBoundingClientRect().width < full)
        el = el.parentElement;
      const r = el.getBoundingClientRect();
      return r.width < full && r.height < innerHeight * 0.6 ? el : null;
    };

    // Eventos de mentira para "mantener presionado": táctiles, de puntero y el menú contextual,
    // así anda con cualquiera de las formas en que el sitio lo detecte
    let simulating = false;
    const fire = (el, type, x, y) => {
      const base = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y };
      if (type.startsWith('touch')) {
        if (typeof Touch !== 'function') return;
        const t = new Touch({ identifier: 9999, target: el, clientX: x, clientY: y, pageX: x + scrollX, pageY: y + scrollY });
        const now = type === 'touchstart' ? [t] : [];
        el.dispatchEvent(new TouchEvent(type, { ...base, touches: now, targetTouches: now, changedTouches: [t] }));
      } else if (type.startsWith('pointer')) {
        el.dispatchEvent(new PointerEvent(type, { ...base, pointerId: 9999, pointerType: 'touch', isPrimary: true, buttons: type === 'pointerdown' ? 1 : 0 }));
      } else {
        el.dispatchEvent(new MouseEvent(type, { ...base, view: window, button: type === 'contextmenu' ? 2 : 0 }));
      }
    };
    const longPress = async (el, x, y) => {
      simulating = true;
      try {
        fire(el, 'pointerdown', x, y);
        fire(el, 'touchstart', x, y);
        await sleep(650);
        fire(el, 'contextmenu', x, y);
        await sleep(60);
        // "Cancelar" en vez de "soltar": el menú queda abierto y no cuenta como un toque
        fire(el, 'pointercancel', x, y);
        fire(el, 'touchcancel', x, y);
      } finally {
        simulating = false;
      }
    };

    const reply = async (bubble, target, x, y) => {
      const list = scrollerOf(bubble);
      const box = bubble.getBoundingClientRect();
      // La fila del mensaje (el primer ancestro de ancho completo). Instagram pone su barra (⋮ ↩ ☺) al
      // costado del texto, que puede no ser lo que deslizaste: la cita de una respuesta, por ejemplo
      const row = bubble.parentElement !== list ? bubble.parentElement : null;
      // Un botón dentro de la lista solo vale si se ve y es de este mensaje: en su fila o a su altura
      // (puede haber quedado el de otro mensaje); uno en un menú aparte, sí
      const mine = (b) => {
        if (!list || !list.contains(b)) return true;
        const r = b.getBoundingClientRect();
        const cy = (r.top + r.bottom) / 2;
        return r.width > 0 && r.height > 0 && ((row && row.contains(b)) || (cy >= box.top - 4 && cy <= box.bottom + 4));
      };
      // El más cercano al mensaje, por si quedó a la vista la barra de otro
      const findReply = (root) => {
        let best = null, dist = Infinity;
        for (const el of root.querySelectorAll('[aria-label], [title], svg, [role="button"], [role="menuitem"], button')) {
          if (!isReply(el) || !mine(el)) continue;
          const r = el.getBoundingClientRect();
          const d = Math.abs(r.top + r.bottom - box.top - box.bottom);
          if (d < dist) { best = el; dist = d; }
        }
        return best;
      };
      // 1. Barra que aparece al pasar el mouse (Instagram en pantallas grandes)
      for (const type of ['mouseover', 'mouseenter', 'mousemove']) fire(target, type, x, y);
      await sleep(80);
      let btn = findReply(list || bubble.parentElement || document.body);
      // 2. Mantener presionado y elegir "Responder" en el menú (Instagram en el celular)
      if (!btn) {
        await longPress(target, x, y);
        for (let i = 0; i < 15 && !btn; i++) {
          const menus = [...document.querySelectorAll('[role="dialog"], [role="menu"], [role="listbox"]')];
          for (const m of [...menus, document.body]) if (!btn) btn = findReply(m);
          if (!btn) await sleep(100);
        }
      }
      if (!btn) {
        // Que no quede colgada la barra de Instagram que abrió el "pasar el mouse"
        for (const type of ['mouseout', 'mouseleave']) fire(target, type, x, y);
        dev('swipe', bubble);
        return;
      }
      // Se toca el elemento mismo, aunque sea un dibujo SVG (que no tiene click()): el evento sube
      // hasta el botón que lo maneja, igual que con un dedo
      const r = btn.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) fire(btn, type, cx, cy);
    };

    // La lista de mensajes con touch-action: pan-y: deslizar de costado no la mueve y el navegador
    // scrollea sin esperar a la página. Tiene que estar antes del gesto: se marca al entrar al chat
    // (lo que hay en el centro de la pantalla) y, por las dudas, al deslizar una burbuja.
    const panY = (el) => { const s = el && scrollerOf(el); if (s) s.style.touchAction = 'pan-y'; };
    const inChat = () => /^\/direct\/t\//.test(location.pathname);
    route(() => {
      if (inChat()) for (const ms of [500, 1500, 4000]) setTimeout(() => {
        if (inChat()) panY(document.elementFromPoint(innerWidth / 2, innerHeight / 2));
      }, ms);
    });

    // Pasado el punto de responder la burbuja sigue al dedo cada vez menos, en vez de frenar en seco
    const rubber = (o) => (o * 120 * 0.55) / (120 + 0.55 * o);
    const EASE = 'cubic-bezier(.23,1,.32,1)'; // la curva de la app (Brand.EASE_OUT)
    const still = () => matchMedia('(prefers-reduced-motion: reduce)').matches; // "quitar animaciones"
    const haptic = () => { if (window.NoScrollBridge && typeof NoScrollBridge.haptic === 'function') NoScrollBridge.haptic(); };
    // Flecha de responder dibujada (no un carácter), del color de los textos secundarios de Instagram
    const replyIcon = () => {
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('width', '24');
      svg.setAttribute('height', '24');
      const path = document.createElementNS(svg.namespaceURI, 'path');
      path.setAttribute('d', 'M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z');
      path.setAttribute('fill', 'currentColor');
      svg.appendChild(path);
      return svg;
    };

    // Al tocar solo se anota dónde: la burbuja se busca (y se mide) si el dedo va hacia la derecha
    let bubble = null, target = null, arrow = null, sx = 0, sy = 0, dx = 0, decided = false, armed = false;
    addEventListener('touchstart', (e) => {
      bubble = target = null;
      if (simulating || !inChat() || e.touches.length !== 1) return;
      target = e.target;
      sx = e.touches[0].clientX;
      sy = e.touches[0].clientY;
      dx = 0;
      decided = false;
      armed = false;
    }, { capture: true, passive: true });
    addEventListener('touchmove', (e) => {
      if (simulating || !(decided ? bubble : target)) return;
      const mx = e.touches[0].clientX - sx, my = e.touches[0].clientY - sy;
      if (!decided) {
        if (Math.abs(mx) < 10 && Math.abs(my) < 10) return;
        decided = true;
        if (!(mx > 0 && Math.abs(mx) > Math.abs(my) * 1.5)) return; // vertical: scroll normal
        if (!(bubble = bubbleOf(target))) return;
        panY(bubble);
        const r = bubble.getBoundingClientRect();
        arrow = document.createElement('div');
        arrow.appendChild(replyIcon());
        arrow.style.cssText = `position:fixed;left:${Math.max(8, r.left - 32)}px;top:${r.top + r.height / 2 - 12}px;` +
          'width:24px;height:24px;opacity:0;pointer-events:none;z-index:2147483647;color:#8e8e8e;' +
          (still() ? '' : `transition:transform .15s ${EASE}`);
        document.body.appendChild(arrow);
      }
      dx = Math.max(0, mx);
      bubble.style.transition = 'none';
      bubble.style.transform = `translateX(${dx <= 60 ? dx : 60 + rubber(dx - 60)}px)`;
      arrow.style.opacity = String(Math.min(dx / 60, 1));
      // Pasó el punto: soltar responde. Se marca con la flecha y una vibración corta (una vez por pasada)
      if ((dx >= 60) !== armed) {
        armed = dx >= 60;
        arrow.style.transform = armed ? 'scale(1.25)' : '';
        if (armed) haptic();
      }
    }, { capture: true, passive: true });
    const end = (cancel) => () => {
      if (simulating || !bubble || !decided) { bubble = null; return; }
      const b = bubble, t = target;
      bubble = null;
      b.style.transition = still() ? 'none' : `transform .2s ${EASE}`;
      b.style.transform = '';
      if (arrow) { arrow.remove(); arrow = null; }
      if (!cancel && dx >= 60) setTimeout(() => reply(b, t, sx, sy), 220); // cuando volvió a su lugar
    };
    addEventListener('touchend', end(false), { capture: true, passive: true });
    addEventListener('touchcancel', end(true), { capture: true, passive: true });
  }

  // --- 9. Pull-to-refresh: avisarle a la app si el gesto empieza arriba de todo ---
  // Instagram y YouTube scrollean dentro de contenedores, no la ventana,
  // así que la app no puede saberlo sola.
  if (window.NoScrollBridge) {
    addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return; // segundo dedo: no es un tirón hacia abajo nuevo
      const p = location.pathname;
      // En chats (cargan mensajes viejos tirando hacia abajo) y reels, nunca recargar
      if (IG && /^\/(direct\/t|reels?)\//.test(p)) { NoScrollBridge.setAtTop(false); return; }
      let top = (document.scrollingElement || document.documentElement).scrollTop <= 0;
      for (let el = e.target; top && el && el.nodeType === 1 && el !== document.body; el = el.parentElement) {
        if (el.scrollTop > 0) top = false;
      }
      NoScrollBridge.setAtTop(top);
    }, { capture: true, passive: true });
  }

  // --- 10. Citas en los chats: tocar la foto o el reel de una respuesta abre el original ---
  // Instagram web no hace nada al tocar la cita. Si el original está cargado en el chat (la misma
  // imagen, más grande y con su botón), se toca ese. Si no está, queda como antes.
  if (IG) {
    const CLICKABLE = 'a[href],button,[role="button"],[role="link"]';
    const fileOf = (m) => ((m.currentSrc || m.src || m.poster || '').split('?')[0].match(/[^/]+$/) || [''])[0];
    addEventListener('click', (e) => {
      if (!e.isTrusted || !/^\/direct\/t\//.test(location.pathname)) return;
      const t = e.target.nodeType === 1 ? e.target : e.target.parentElement;
      const btn = t && t.closest(CLICKABLE);
      if (!btn || btn.hasAttribute('href')) return; // un link (perfil, post) ya lleva a algún lado
      const media = t.matches('img,video') ? [t] : btn.querySelectorAll('img,video');
      if (media.length !== 1) return;
      const m = media[0], file = fileOf(m), w = m.getBoundingClientRect().width;
      const original = file && [...document.querySelectorAll('img,video')].find((o) =>
        o !== m && fileOf(o) === file && o.getBoundingClientRect().width > w * 1.1 && o.closest(CLICKABLE));
      if (!original) return;
      dev('note', 'citas: toqué el original');
      const r = original.getBoundingClientRect();
      const at = { bubbles: true, cancelable: true, composed: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      setTimeout(() => { // como un dedo: el sitio puede escuchar cualquiera de estos
        for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
          original.dispatchEvent(type.startsWith('pointer')
            ? new PointerEvent(type, { ...at, pointerId: 9998, pointerType: 'touch', isPrimary: true })
            : new MouseEvent(type, at));
        }
      }, 0);
    }, true);
  }

  // --- 11. Abrir desde una notificación, sin recargar (Instagram) ---
  // La app llama a __lucidoGo(ruta, chat) si Instagram ya está cargado: se navega dentro del sitio
  // (como el botón atrás) y, si viene el nombre del chat, se toca su fila en la lista. Devuelve true
  // si se encarga; si no, la app carga la URL como antes. Solo toca una fila si hay una sola con ese
  // nombre exacto (si no, queda en la lista: nunca abre otro chat), y si tocás algo, deja de buscar.
  if (IG) {
    const CLICKABLE = 'a[href],button,[role="button"],[role="link"]';
    const norm = (t) => (t || '').normalize('NFC').replace(/\s+/g, ' ').trim().toLowerCase();
    const named = (name) => {
      const out = [];
      if (!document.body) return out;
      const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n; (n = walk.nextNode());) if (n.parentElement && norm(n.data) === name) out.push(n.parentElement);
      return out;
    };
    // Las filas de la lista ocupan casi todo el ancho (las notas de arriba, no)
    const rows = (name) => {
      const found = new Set();
      for (const el of named(name)) {
        const row = el.closest(CLICKABLE);
        const r = row && row.getBoundingClientRect();
        if (r && r.width >= innerWidth * 0.6 && r.height > 0 && r.height < innerHeight / 2) found.add(row);
      }
      return [...found];
    };
    // Ya en ese chat: su nombre está en el encabezado
    const inChat = (name) => /^\/direct\/t\//.test(location.pathname) &&
      named(name).some((el) => { const r = el.getBoundingClientRect(); return r.height > 0 && r.bottom < 140; });
    const tap = (el) => {
      const r = el.getBoundingClientRect();
      const at = { bubbles: true, cancelable: true, composed: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']) {
        el.dispatchEvent(type.startsWith('pointer')
          ? new PointerEvent(type, { ...at, pointerId: 9997, pointerType: 'touch', isPrimary: true })
          : new MouseEvent(type, at));
      }
    };

    let stopLooking = () => {};
    const openChat = (name) => {
      stopLooking();
      let done = false;
      const stop = () => { done = true; removeEventListener('touchstart', stop, true); };
      stopLooking = stop;
      addEventListener('touchstart', stop, { capture: true, passive: true });
      const until = performance.now() + 8000; // la lista tarda en llegar
      const tick = () => {
        if (done) return;
        if (performance.now() > until || !/^\/direct\//.test(location.pathname) || inChat(name)) return stop();
        const found = rows(name);
        if (found.length === 1) { stop(); dev('note', 'notificación: abrí el chat'); tap(found[0]); return; }
        setTimeout(tick, 250);
      };
      tick();
    };

    window.__lucidoGo = (to, chat) => {
      try {
        const name = chat ? norm(chat) : null;
        if (to) {
          if (!/^\/direct\//.test(to)) return false;
          if (name && inChat(name)) return true;
          // Si el sitio no se mueve y hay que cargarla, el chat se abre en la página nueva
          if (location.pathname !== to) navigate(to, false, () => {
            stopLooking();
            try { if (name) sessionStorage.setItem('brl_chat', name); } catch (e) {}
          });
        }
        if (name) openChat(name);
        return true;
      } catch (e) {
        return false;
      }
    };

    let left = null; // chat que quedó por abrir de la página anterior
    try { left = sessionStorage.getItem('brl_chat'); sessionStorage.removeItem('brl_chat'); } catch (e) {}
    if (left) document.body ? openChat(left) : addEventListener('DOMContentLoaded', () => openChat(left));
  }

  // --- 12. Pantalla prendida mientras se ve un video (Instagram y YouTube) ---
  // Le avisa a la app si hay un video reproduciéndose: así el teléfono no apaga la pantalla en medio de
  // un video (en pantalla completa se apagaba). En pausa o al terminar, vuelve a apagarse sola.
  if (window.NoScrollBridge && typeof NoScrollBridge.setPlaying === 'function') {
    let was = false;
    const tell = () => {
      const now = [...document.getElementsByTagName('video')].some((v) => !v.paused && !v.ended && v.readyState > 2);
      if (now !== was) { was = now; NoScrollBridge.setPlaying(now); }
    };
    for (const type of ['playing', 'pause', 'ended', 'emptied']) addEventListener(type, tell, true);
    addEventListener('pagehide', () => { if (was) { was = false; NoScrollBridge.setPlaying(false); } });
  }

  // --- 13. La página nueva ya se ve (Instagram y YouTube) ---
  // Al recargar, la app deja a la vista una foto de la página de antes hasta este aviso, en vez de la
  // pantalla de carga. Cuenta cuando hay contenido de verdad (no solo el logo de carga: cientos de
  // elementos) y pasaron 300 ms sin cambios; a los 5 s, igual.
  if (window.NoScrollBridge && typeof NoScrollBridge.ready === 'function') {
    let quiet = 0, done = false;
    const filled = () => document.body && document.body.getElementsByTagName('*').length > 300;
    const fin = () => {
      if (done) return;
      done = true;
      obs.disconnect();
      clearTimeout(quiet);
      NoScrollBridge.ready(location.hostname);
      dev('ready');
    };
    const obs = new MutationObserver(() => {
      clearTimeout(quiet);
      quiet = setTimeout(() => { if (filled()) fin(); }, 300);
    });
    obs.observe(document, { childList: true, subtree: true });
    setTimeout(fin, 5000);
  }

  // --- 14. Reglas nuevas sin recargar ---
  // Cuando empieza o termina la noche, el foco, el límite o una pausa, la app llama a __lucidoConfig con
  // la configuración nueva y se aplica en la página abierta: estilos, guardia de URL (vuelve al inbox si
  // hace falta), videos y el bloqueo del deslizamiento de Reels. Si cambió otra cosa (un ajuste del
  // panel), devuelve false y la app recarga la página, como antes.
  const LIVE = ['restricted', 'restrictedMsg', 'igReels', 'ytShorts'];
  window.__lucidoConfig = (given) => {
    try {
      const next = config(given);
      for (const k of new Set([...Object.keys(next), ...Object.keys(CFG)])) {
        if (!LIVE.includes(k) && next[k] !== CFG[k]) return false;
      }
      for (const k of LIVE) CFG[k] = next[k];
      restyle();
      for (const f of onRoute) f();
      for (const f of onConfig) f();
      check();
      return true;
    } catch (e) {
      return false;
    }
  };

  // --- 15. Sin anuncios (YouTube e Instagram; se pueden apagar) ---
  // YouTube, con la receta de uBlock Origin (sin su código): los anuncios de un video vienen en los datos
  // del video (adPlacements, playerAds, adSlots) y se borran antes de que el reproductor los lea, lleguen
  // con la página (ytInitialPlayerResponse) o en un pedido (JSON.parse, Response.json). Si igual aparece
  // uno, se silencia, se acelera y se toca "Omitir"; al terminar, el video vuelve a como estaba. Los de
  // entre videos, con CSS (sección 1). Si YouTube cambia la forma: mirar qué cambió uBlock Origin en sus
  // filtros de youtube.com.
  if (YT && CFG.ytBlockAds) {
    const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots'];
    const strip = (o) => {
      if (!o || typeof o !== 'object') return;
      for (const t of [o, o.playerResponse]) {
        if (t && typeof t === 'object') for (const k of AD_KEYS) if (k in t) delete t[k];
      }
    };
    const prune = (o) => { if (Array.isArray(o)) o.forEach(strip); else strip(o); return o; };
    const parse = JSON.parse;
    JSON.parse = function () { return prune(parse.apply(this, arguments)); };
    const json = Response.prototype.json;
    Response.prototype.json = function () { return json.apply(this, arguments).then(prune); };
    let initial;
    try {
      Object.defineProperty(window, 'ytInitialPlayerResponse', {
        configurable: true, get: () => initial, set: (v) => { initial = prune(v); },
      });
    } catch (e) {}

    const SKIP = '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [class*="skip-ad"], [class*="ad-skip"]';
    const before = new WeakMap(); // sonido y velocidad del video antes del anuncio
    const skipAd = (e) => {
      const v = e.target;
      if (!(v instanceof HTMLVideoElement)) return;
      const ad = v.closest('.ad-showing, .ad-interrupting');
      if (!ad) {
        const b = before.get(v);
        if (b) { before.delete(v); v.muted = b.muted; v.playbackRate = b.rate; }
        return;
      }
      if (!before.has(v)) { before.set(v, { muted: v.muted, rate: v.playbackRate }); dev('note', 'anuncios: salteé uno de YouTube'); }
      v.muted = true;
      v.playbackRate = 16; // sin saltar al final: si el anuncio viene pegado al video, se saltearía el video también
      const btn = ad.querySelector(SKIP);
      if (btn) btn.click();
    };
    for (const type of ['loadedmetadata', 'playing', 'timeupdate']) addEventListener(type, skipAd, true);
  }

  // Instagram: las publicaciones con "Publicidad" se ocultan y las historias patrocinadas se saltean. Por el
  // texto de la etiqueta, que va arriba junto al nombre (como los carteles de "Usá la app", sección 6).
  if (IG && CFG.igBlockAds) {
    const AD = /^(publicidad|patrocinado|sponsored|ad|anuncio|publicidade|sponsoris[ée]|sponsorizzato|gesponsert)$/i;
    // La etiqueta entre los primeros textos de root; puede venir partida en varios elementos chicos
    const label = (root, max) => {
      const walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let n, i = 0; (n = walk.nextNode()) && i < max; i++) {
        for (let el = n.parentElement, up = 0; el && up < 3 && el !== root; el = el.parentElement, up++) {
          const t = el.textContent;
          if (t.length > 40) break;
          if (AD.test(t.replace(/\s+/g, ' ').trim())) return el;
        }
      }
      return null;
    };
    // Una publicación lleva la etiqueta arriba, junto al nombre. Un <article> con otros adentro, o más alto que
    // dos pantallas, es una lista: ocultarlo se llevaría el feed entero
    const isAd = (post) => {
      if (post.querySelector('article') || post.querySelectorAll(LIKE).length > 1) return false;
      const box = post.getBoundingClientRect();
      if (box.height > innerHeight * 2.5) return false;
      const el = label(post, 40);
      return !!el && el.getBoundingClientRect().top - box.top < 120;
    };
    // Se achica a nada sin sacarla de la página: si una publicación desaparece (display:none), Instagram deja
    // de cargar las que siguen
    let styled = false;
    const hidePost = (post) => {
      if (post.hasAttribute('data-lucido-ad') || !isAd(post)) return;
      if (!styled) {
        styled = true;
        const s = document.createElement('style');
        s.textContent = '[data-lucido-ad] { height:1px !important; min-height:0 !important; margin:0 !important; ' +
          'padding:0 !important; border:0 !important; overflow:hidden !important; visibility:hidden !important; }';
        (document.head || document.documentElement).appendChild(s);
      }
      post.setAttribute('data-lucido-ad', '');
      dev('note', 'anuncios: oculté una publicación');
    };

    // Historia patrocinada: la etiqueta arriba y a la vista, en cualquier parte de la página (debajo de las
    // historias puede seguir el feed, con cientos de textos antes)
    const storyLabel = () => {
      const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n; (n = walk.nextNode());) {
        if (n.data.length > 40) continue;
        for (let el = n.parentElement, up = 0; el && up < 3; el = el.parentElement, up++) {
          const t = el.textContent;
          if (t.length > 40) break;
          if (!AD.test(t.replace(/\s+/g, ' ').trim())) continue;
          const r = el.getBoundingClientRect();
          const seen = !el.checkVisibility || el.checkVisibility({ visibilityProperty: true, opacityProperty: true });
          if (r.height > 4 && r.top >= 0 && r.top < innerHeight * 0.3 && seen) return el;
          break;
        }
      }
      return null;
    };
    // Se avanza a la que sigue: "Siguiente" si está (si no, un toque en el costado derecho, como un dedo:
    // táctil, puntero y click); al segundo intento, la flecha del teclado; al tercero, el toque
    const tapRight = () => {
      const x = innerWidth * 0.9, y = innerHeight / 2, target = document.elementFromPoint(x, y);
      if (!target) return;
      const at = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y };
      const touch = typeof Touch === 'function' && new Touch({ identifier: 9995, target, clientX: x, clientY: y, pageX: x, pageY: y });
      for (const type of ['pointerdown', 'touchstart', 'pointerup', 'touchend', 'mousedown', 'mouseup', 'click']) {
        if (type.startsWith('touch')) {
          if (!touch) continue;
          const now = type === 'touchstart' ? [touch] : [];
          target.dispatchEvent(new TouchEvent(type, { ...at, touches: now, targetTouches: now, changedTouches: [touch] }));
        } else {
          target.dispatchEvent(type.startsWith('pointer')
            ? new PointerEvent(type, { ...at, pointerId: 9996, pointerType: 'touch', isPrimary: true })
            : new MouseEvent(type, at));
        }
      }
    };
    const tries = new WeakMap(); // etiqueta → [intentos, último]
    const skipStory = () => {
      const el = document.body && storyLabel();
      if (!el) return;
      const [n, last] = tries.get(el) || [0, -Infinity];
      if (n >= 3 || performance.now() - last < 1500) return;
      tries.set(el, [n + 1, performance.now()]);
      dev('note', `anuncios: salteé una historia patrocinada (intento ${n + 1})`);
      const next = [...document.querySelectorAll('[aria-label]')].find((b) =>
        /^(siguiente|next)$/i.test(b.getAttribute('aria-label').trim()) && b.getBoundingClientRect().height > 0);
      if (n === 0 && next) next.click();
      else if (n === 1) {
        (document.activeElement || document.body).dispatchEvent(new KeyboardEvent('keydown',
          { key: 'ArrowRight', code: 'ArrowRight', keyCode: 39, which: 39, bubbles: true, cancelable: true }));
      } else tapRight();
      // Si no cambió nada en la página, igual se vuelve a mirar
      setTimeout(() => { if (/^\/stories\//.test(location.pathname)) skipStory(); }, 1600);
    };

    // Se mira lo que se agregó, cuando el navegador está libre; en las historias, la parte de arriba
    let added = [], scheduled = false;
    const idle = window.requestIdleCallback ? (f) => requestIdleCallback(f, { timeout: 500 }) : (f) => setTimeout(f, 200);
    const sweep = () => {
      scheduled = false;
      const nodes = added;
      added = [];
      if (/^\/stories\//.test(location.pathname)) return skipStory();
      if (!nodes.length) return;
      for (const p of feedPosts()) hidePost(p); // pocas (Instagram tiene en la página solo las de cerca)
    };
    new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'characterData') { added.push(r.target); continue; }
        for (const n of r.addedNodes) {
          added.push(n);
          // Una publicación nueva, apenas aparece (antes de que se dibuje: si se achica después, la pantalla salta)
          if (n.nodeType === 1) for (const post of n.tagName === 'ARTICLE' ? [n] : n.getElementsByTagName('article')) hidePost(post);
        }
      }
      if (!scheduled && added.length) { scheduled = true; idle(sweep); }
    }).observe(document, { childList: true, subtree: true, characterData: true }); // document: el <html> puede no existir todavía
  }

  // --- 16. Solo cuentas que seguís (Instagram) ---
  // El inicio normal de Instagram, con su fila de historias (que pasa de una persona a otra, sin recargar),
  // sin lo que no es de cuentas que seguís. Instagram mezcla recomendaciones sin marcarlas, así que se compara
  // el autor de cada publicación con la lista de cuentas que seguís (la de tu perfil, que se le pide a Instagram
  // y se guarda en el teléfono un día). Además, las que dicen "Seguir" o "Sugerencia para ti" arriba, los
  // carruseles de sugerencias y, desde "Estás al día", todo lo que sigue (son sugerencias): ahí termina. Las de
  // antes del final se achican a 1 px sin salir de la página (si una desaparece, Instagram deja de cargar las
  // que siguen); las de después desaparecen, justamente para que deje de cargar sugerencias
  if (IG && CFG.igFeed === 'following') {
    const FOLLOW = /^(seguir|follow)$/i;
    const SUGGESTED = /^(sugerencias? para ti|suggested for you|sugerido para ti|publicaci[oó]n sugerida|suggested post|publicaciones sugeridas|suggested posts|reels sugeridos|suggested reels)$/i;
    const END = /(est[aá]s al d[ií]a|ya viste todo|all caught up|completely caught up|viste todas las publicaciones|seen all new posts)/i;
    const onHome = () => location.pathname === '/' && !CFG.restricted;
    const API = { credentials: 'include', headers: { 'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest' } };

    // Las cuentas que seguís, y la tuya (tus publicaciones también van). null mientras no se sabe
    const KEY = 'brl_following';
    let asking = false, askedAt = -Infinity;
    try {
      const c = JSON.parse(localStorage.getItem(KEY));
      if (c && Date.now() - c.at < 864e5 && c.users.length) igFollowed = new Set(c.users);
    } catch (e) {}
    const ask = async () => {
      if (igFollowed || asking || Date.now() - askedAt < 5 * 60e3) return; // si falló, de nuevo en 5 minutos
      asking = true;
      askedAt = Date.now();
      try {
        // Tu cuenta: el número, de la cookie de la sesión o preguntándole a Instagram (que da también tu nombre)
        let id = (document.cookie.match(/(?:^|;\s*)ds_user_id=(\d+)/) || [])[1], me = '';
        const r0 = await fetch('/api/v1/accounts/current_user/?edit=true', API);
        const j0 = r0.ok ? await r0.json().catch(() => null) : null;
        if (j0 && j0.user) { id = id || String(j0.user.pk || j0.user.pk_id || ''); me = j0.user.username || ''; }
        if (!id) return dev('note', `seguidos: no pude saber cuál es tu cuenta (Instagram respondió ${r0.status})`);
        const users = me ? [me.toLowerCase()] : [];
        let max = '';
        for (let page = 0; page < 50; page++) { // hasta 10.000 cuentas
          const r = await fetch(`/api/v1/friendships/${id}/following/?count=200${max ? '&max_id=' + encodeURIComponent(max) : ''}`, API);
          if (!r.ok) return dev('note', `seguidos: Instagram no dio la lista (respondió ${r.status})`);
          const j = await r.json();
          for (const u of j.users || []) if (u && u.username) users.push(u.username.toLowerCase());
          if (!j.next_max_id) break;
          max = j.next_max_id;
        }
        if (users.length <= (me ? 1 : 0)) return dev('note', 'seguidos: la lista vino vacía');
        igFollowed = new Set(users);
        try { localStorage.setItem(KEY, JSON.stringify({ at: Date.now(), users })); } catch (e) {}
        dev('note', `seguidos: la lista tiene ${users.length - (me ? 1 : 0)} cuentas`);
        later();
      } catch (e) {
        dev('note', 'seguidos: no pude traer la lista de cuentas que seguís (' + String(e && e.message).slice(0, 60) + ')');
      } finally {
        asking = false;
      }
    };

    // Un texto corto que cumple test, entre los primeros max de root (y que no esté en skip)
    const find = (root, test, max, skip) => {
      const walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let n, i = 0; (n = walk.nextNode()) && i < max; i++) {
        const t = n.data.length <= 80 && n.data.trim();
        if (t && test.test(t) && !(skip && skip(n.parentElement))) return n.parentElement;
      }
      return null;
    };
    // Los autores: los links a perfiles de arriba, junto a la foto (una publicación en colaboración tiene varios)
    const NOT_USER = /^(explore|reels?|p|stories|direct|accounts|about|legal|tv|web|challenge)$/;
    const authors = (post, top) => {
      const out = [];
      for (const a of post.querySelectorAll('a[href]')) {
        const m = a.getAttribute('href').match(/^\/([A-Za-z0-9._]{1,30})\/?$/);
        if (m && !NOT_USER.test(m[1]) && a.getBoundingClientRect().top - top < 80) out.push(m[1].toLowerCase());
      }
      return out;
    };
    // De una cuenta que no seguís: su autor no está en la lista, o "Seguir" o "Sugerencia para ti" arriba
    const notFollowed = (post) => {
      const top = post.getBoundingClientRect().top;
      const near = (el) => !!el && el.getBoundingClientRect().top - top < 120;
      if (near(find(post, FOLLOW, 40)) || near(find(post, SUGGESTED, 40))) return true;
      if (!igFollowed) return false;
      const who = authors(post, top);
      return who.length > 0 && !who.some((u) => igFollowed.has(u));
    };
    // Lo que va en la lista del feed al lado de las publicaciones (un carrusel, el aviso del final): el ancestro
    // más grande que no tiene publicaciones adentro. null si no está en la lista
    const blockOf = (el) => {
      let b = el;
      while (b.parentElement && b.parentElement !== document.body && !hasPosts(b.parentElement)) b = b.parentElement;
      return b.parentElement && hasPosts(b.parentElement) ? b : null;
    };

    let styled = false;
    const hide = (el, how, why) => {
      if (el.hasAttribute('data-lucido-hide')) return;
      if (!styled) {
        styled = true;
        const s = document.createElement('style');
        s.textContent = '[data-lucido-hide="1px"] { height:1px !important; min-height:0 !important; margin:0 !important; ' +
          'padding:0 !important; border:0 !important; overflow:hidden !important; visibility:hidden !important; } ' +
          '[data-lucido-hide="fin"] { display:none !important; }';
        (document.head || document.documentElement).appendChild(s);
      }
      el.setAttribute('data-lucido-hide', how);
      dev('note', why);
    };

    let end = null; // "Estás al día": lo que viene después son sugerencias
    const after = (el) => !!end && !!(end.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
    const sweep = () => {
      if (!onHome() || !document.body) return;
      const posts = feedPosts();
      if (!posts.length) return;
      ask();
      const inPost = (el) => posts.some((p) => p.contains(el));
      if (end && !end.isConnected) end = null;
      if (!end) {
        const e = find(document.body, END, Infinity, inPost);
        if (e) { end = blockOf(e) || e; dev('note', 'seguidos: llegué a "Estás al día"'); }
      }
      for (const post of posts) {
        if (post.closest('[data-lucido-hide], [data-lucido-ad]')) continue;
        if (after(post)) hide(post, 'fin', 'seguidos: oculté lo que sigue a "Estás al día"');
        else if (notFollowed(post)) hide(post, '1px', 'seguidos: oculté una publicación de una cuenta que no seguís');
      }
      // Carruseles y títulos de sugerencias, fuera de las publicaciones
      const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      for (let n; (n = walk.nextNode());) {
        const t = n.data.length <= 40 && n.data.trim();
        if (!t || !SUGGESTED.test(t) || inPost(n.parentElement) || n.parentElement.closest('[data-lucido-hide]')) continue;
        const b = blockOf(n.parentElement);
        if (b && b !== end && !b.contains(end)) hide(b, after(b) ? 'fin' : '1px', 'seguidos: oculté sugerencias');
      }
      // Después del final, todo lo de la lista (también lo que no es una publicación)
      if (end && end.parentElement && hasPosts(end.parentElement)) {
        for (let x = end.nextElementSibling; x; x = x.nextElementSibling) {
          if (!x.hasAttribute('data-lucido-hide')) hide(x, 'fin', 'seguidos: oculté lo que sigue a "Estás al día"');
        }
      }
    };
    // Una publicación nueva se mira apenas aparece, antes de que se dibuje: si se achica después, la pantalla
    // salta. Lo demás (sugerencias, el final, lo que tarda en llegar), como mucho una vez cada 600 ms
    let timer = 0;
    const later = () => { if (!timer && onHome()) timer = setTimeout(() => { timer = 0; sweep(); }, 600); };
    new MutationObserver((records) => {
      if (!onHome()) return;
      for (const r of records) {
        for (const n of r.addedNodes) {
          if (n.nodeType !== 1) continue;
          for (const post of n.tagName === 'ARTICLE' ? [n] : n.getElementsByTagName('article')) {
            if (post.closest('[data-lucido-hide], [data-lucido-ad]')) continue;
            if (after(post)) hide(post, 'fin', 'seguidos: oculté lo que sigue a "Estás al día"');
            else if (notFollowed(post)) hide(post, '1px', 'seguidos: oculté una publicación de una cuenta que no seguís');
          }
        }
      }
      later();
    }).observe(document, { childList: true, subtree: true, characterData: true });
    route(later);
  }

  // --- 17. Acostar el teléfono mirando un video: pantalla completa (YouTube) ---
  // Con la rotación del teléfono prendida, la app llama a __lucidoTheater(true) al girar a horizontal en un
  // video, y (false) al volver a vertical. El reproductor ocupa toda la pantalla con sus controles; no es la
  // pantalla completa del navegador porque esa solo se puede pedir con un toque. Devuelve false si no hay video.
  if (YT) {
    const FULL = `
      html.lucido-full, html.lucido-full body { overflow:hidden !important; }
      html.lucido-full #player-container-id { position:fixed !important; inset:0 !important; width:100vw !important;
        height:100vh !important; max-height:none !important; margin:0 !important; z-index:2147483647 !important;
        background:#000 !important; transform:none !important; }
      html.lucido-full #player-container-id #player, html.lucido-full #movie_player, html.lucido-full .html5-video-container {
        width:100% !important; height:100% !important; max-height:none !important; padding:0 !important; }
      html.lucido-full #movie_player video { width:100% !important; height:100% !important; left:0 !important;
        top:0 !important; object-fit:contain !important; }`;
    let fullStyle = null;
    window.__lucidoTheater = (on) => {
      const html = document.documentElement;
      if (on && !(/^\/watch/.test(location.pathname) && document.querySelector('#player-container-id video'))) return false;
      if (on && !fullStyle) {
        fullStyle = document.createElement('style');
        fullStyle.textContent = FULL;
        (document.head || html).appendChild(fullStyle);
      }
      html.classList.toggle('lucido-full', on);
      dispatchEvent(new Event('resize')); // que el reproductor se acomode al tamaño nuevo
      return true;
    };
    // Otro video sí, otra página no: si se va del video, sale (la app se entera por la URL)
    route(() => { if (!/^\/watch/.test(location.pathname)) document.documentElement && document.documentElement.classList.remove('lucido-full'); });
  }

  // --- 18. El feed de Instagram, filtrado desde los datos ---
  // Lo más prolijo es que lo que no va ni llegue a dibujarse: se saca de los datos del feed
  // (xdt_api__v1__feed__timeline__connection) cuando Instagram los lee (JSON.parse, Response.json), como los
  // anuncios de YouTube (sección 15). Sale lo que es un anuncio y, en "Solo cuentas que seguís", lo de cuentas
  // que no seguís (según Instagram mismo, friendship_status, o la lista de la sección 16) y las sugerencias. Lo
  // que no se reconozca queda, y lo atajan las secciones 15 y 16. Si dos páginas seguidas no traen nada de
  // cuentas que seguís, el feed termina: ya viste todo y lo que sigue son sugerencias
  if (IG && (CFG.igBlockAds || CFG.igFeed === 'following')) {
    const FEED = 'xdt_api__v1__feed__timeline__connection';
    const only = CFG.igFeed === 'following';
    const myId = (document.cookie.match(/(?:^|;\s*)ds_user_id=(\d+)/) || [])[1];
    let empty = 0, seen = false;
    // 'ad', 'other' (de una cuenta que no seguís, o una sugerencia), 'followed' o '?' (no se sabe: queda)
    const kind = (n) => {
      if (!n || typeof n !== 'object') return '?';
      const m = n.media;
      if (n.ad || (m && (m.ad_id || m.is_ad || m.ad_action))) return 'ad';
      if (n.explore_story || n.suggested_users) return 'other';
      const u = m && (m.user || m.owner);
      if (!u || !u.username) return '?';
      if (myId && String(u.pk || u.id) === myId) return 'followed';
      const fs = u.friendship_status;
      if (fs && typeof fs.following === 'boolean') return fs.following ? 'followed' : 'other';
      if (igFollowed) return igFollowed.has(String(u.username).toLowerCase()) ? 'followed' : 'other';
      return '?';
    };
    const prune = (c) => {
      if (!c || !Array.isArray(c.edges)) return;
      if (!seen) { seen = true; dev('note', 'feed: leo los datos del feed'); }
      let ads = 0, others = 0, mine = 0;
      c.edges = c.edges.filter((e) => {
        const k = kind(e && e.node);
        if (k === 'ad' && CFG.igBlockAds) { ads++; return false; }
        if (k === 'other' && only) { others++; return false; }
        if (k === 'followed') mine++;
        return true;
      });
      for (let i = 0; i < ads; i++) dev('note', 'feed: saqué un anuncio de los datos');
      for (let i = 0; i < others; i++) dev('note', 'feed: saqué una publicación de una cuenta que no seguís de los datos');
      if (!only || !c.page_info) return;
      if (mine) empty = 0;
      else if (others && ++empty >= 2 && c.page_info.has_next_page) {
        c.page_info.has_next_page = false;
        dev('note', 'feed: terminé el feed (dos páginas sin cuentas que seguís)');
      }
    };
    const find = (o, depth) => {
      if (!o || typeof o !== 'object' || depth > 40) return;
      if (o[FEED]) prune(o[FEED]);
      for (const k in o) if (o[k] && typeof o[k] === 'object') find(o[k], depth + 1);
    };
    const parse = JSON.parse;
    JSON.parse = function (text) {
      const o = parse.apply(this, arguments);
      if (typeof text === 'string' && text.indexOf(FEED) !== -1) { try { find(o, 0); } catch (e) {} }
      return o;
    };
    Response.prototype.json = function () { return this.text().then((t) => JSON.parse(t)); };
  }
})();
