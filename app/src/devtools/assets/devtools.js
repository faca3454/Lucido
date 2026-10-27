// Herramientas de los APK de prueba. No va en la versión publicada (ver app/build.gradle.kts).
// La app lo pone antes de block.user.js en cada página:
// - Mediciones: tareas largas, cuánto tarda cada cambio de página en dibujarse y cada video en arrancar, y
//   cuánto video bajó.
//   La medición automática de Ajustes (DevTools.kt) agrega este resumen (summary) a su informe, y mide
//   cada paso con start() y result().
// - Si deslizar para responder no encuentra "Responder", copia la estructura del mensaje (sin el texto).
(() => {
  if (window.__lucidoDev || !window.LucidoDev) return;
  const id = Math.random().toString(36).slice(2); // esta página: la medición sabe cuándo ya cargó otra

  // Solo el tipo de pantalla: nada de usuarios ni chats
  const screen = () => {
    const p = location.pathname;
    const m = p.match(/^\/(direct\/\w+|reels?|p|stories|explore|accounts|watch|shorts|results|feed)\b/);
    return m ? '/' + m[1] : p === '/' ? '/' : '/@';
  };

  // Lo que hizo block.user.js por su cuenta (frenar un toque, cerrar un cartel), para el diagnóstico de toques
  const trace = [];

  const visits = [[0, screen()]]; // [desde cuándo, pantalla]: las tareas largas se avisan tarde
  const tasks = []; // [inicio, duración] de cada tarea larga
  let longObs = null;
  try {
    longObs = new PerformanceObserver((list) => { for (const e of list.getEntries()) tasks.push([e.startTime, e.duration]); });
    longObs.observe({ type: 'longtask', buffered: true });
  } catch (e) {}
  let fcp = null; // primer contenido dibujado
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (e.name === 'first-contentful-paint') fcp = e.startTime;
    }).observe({ type: 'paint', buffered: true });
  } catch (e) {}
  let ready = null; // block.user.js avisó que ya se ve la página (al recargar, saca la foto de antes)

  // Cambio de página: hasta el último cuadro con cambios, cuando pasa medio segundo sin ninguno (10 s como
  // mucho). Si cambiás de página antes, queda lo que se llegó a medir, con *. La carga y los pasos de la
  // medición automática no se cortan por un cambio de página del sitio (fixed).
  const navs = [];
  let stop = null, fixed = false;
  const measure = (t0, label, mine, done) => {
    if (stop) stop('*');
    fixed = mine;
    if (t0) visits.push([t0, screen()]);
    let end = t0, frame = false, quiet = 0;
    const obs = new MutationObserver(() => {
      if (!frame) { frame = true; requestAnimationFrame(() => { frame = false; end = performance.now(); }); }
      clearTimeout(quiet);
      quiet = setTimeout(() => stop(''), 500);
    });
    const max = setTimeout(() => stop('+'), 10000);
    stop = (more) => {
      obs.disconnect(); clearTimeout(quiet); clearTimeout(max); stop = null; fixed = false;
      navs.push(`${label} ${Math.round(end - t0)}${more}`);
      if (done) done(end, more);
    };
    obs.observe(document, { childList: true, subtree: true });
    quiet = setTimeout(() => stop(''), 500);
  };
  let last = null; // lo último que midió la medición automática (o la carga): { t0, end, more }
  const keep = (t0) => (end, more) => { last = { t0, end, more }; };
  measure(0, screen() + ' (carga)', true, keep(0));
  const auto = () => { if (!fixed) measure(performance.now(), screen(), false); };
  const push = history.pushState;
  history.pushState = function (...a) { const r = push.apply(this, a); auto(); return r; };
  addEventListener('popstate', auto);

  // Video que bajó la página: pedidos que salieron de verdad (block.user.js va después y retiene los suyos
  // antes de llegar acá) y cuánto pesaban. Más lo que "Videos solo al tocar" dejó esperando, mandó o descartó,
  // y lo que hizo el script por su cuenta (notes). Se guarda en la pestaña (sessionStorage): sigue contando
  // aunque la página se recargue, hasta "Poner en cero" (Ajustes → Pruebas → Datos bajados)
  const KEY = 'lucido-dev';
  let saved = {};
  try { saved = JSON.parse(sessionStorage.getItem(KEY)) || {}; } catch (e) {}
  const data = Object.assign({ n: 0, bytes: 0, held: 0, sent: 0, dropped: 0 }, saved.data);
  const notes = Object.assign({}, saved.notes);
  const bytes = Object.assign({ img: 0, video: 0, js: 0, otros: 0 }, saved.bytes); // todo lo que bajó, por tipo
  let saving = 0;
  const store = () => { try { sessionStorage.setItem(KEY, JSON.stringify({ data, notes, bytes })); } catch (e) {} };
  const save = () => { clearTimeout(saving); saving = setTimeout(store, 300); };
  addEventListener('pagehide', store);
  const isVideo = (url) => {
    try {
      const u = new URL(url, location.href);
      return (/(^|\.)(cdninstagram\.com|fbcdn\.net)$/.test(u.hostname) && /\.(mp4|m4v|m4a|webm)$/i.test(u.pathname)) ||
        /(^|\.)googlevideo\.com$/.test(u.hostname);
    } catch (e) {
      return false;
    }
  };
  const weigh = (h) => { const n = parseInt(h, 10); if (n > 0) { data.bytes += n; save(); } };
  // Todo lo que bajó la página, por tipo: Instagram y YouTube dejan ver el tamaño (Timing-Allow-Origin). No
  // cuenta lo que pide un <video> por su cuenta (sin fetch), ni lo que vino de la caché
  const kind = (e) => isVideo(e.name) ? 'video'
    : e.initiatorType === 'img' || /\.(jpe?g|png|webp|gif|heic|avif)(\?|$)/i.test(e.name) ? 'img'
      : e.initiatorType === 'script' || /\.js(\?|$)/i.test(e.name) ? 'js' : 'otros';
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (e.transferSize > 0) bytes[kind(e)] += e.transferSize;
      save();
    }).observe({ type: 'resource', buffered: true });
  } catch (e) {}
  const fetch0 = window.fetch;
  window.fetch = function (input) {
    const p = fetch0.apply(window, arguments);
    if (isVideo(input instanceof Request ? input.url : String(input))) {
      data.n++;
      save();
      p.then((r) => weigh(r.headers.get('content-length')), () => {});
    }
    return p;
  };
  const xvideo = new WeakSet(), xopen = XMLHttpRequest.prototype.open, xsend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (isVideo(String(url))) xvideo.add(this); else xvideo.delete(this);
    return xopen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    if (xvideo.has(this)) {
      data.n++;
      save();
      this.addEventListener('loadend', () => weigh(this.getResponseHeader('content-length')), { once: true });
    }
    return xsend.apply(this, arguments);
  };

  let benching = false; // midiendo en esta página: los videos, en silencio
  let wantVideo = null; // paso "abrir un video": desde cuándo y desde qué página (no cuentan las vistas previas de ahí)
  const asked = new WeakMap(), videos = [];
  addEventListener('play', (e) => {
    if (!(e.target instanceof HTMLVideoElement)) return;
    asked.set(e.target, performance.now());
    if (benching) e.target.muted = true;
  }, true);
  addEventListener('playing', (e) => {
    if (wantVideo && location.pathname !== wantVideo.path) {
      const t0 = wantVideo.t0, end = performance.now();
      wantVideo = null;
      if (stop) stop('');
      last = { t0, end, more: '' };
    }
    const t = asked.get(e.target);
    if (t === undefined) return;
    asked.delete(e.target);
    videos.push(Math.round(performance.now() - t));
  }, true);

  // Toques sobre fotos o videos en los chats: qué se tocó (la estructura, sin el texto), si el toque
  // llegó a la página, qué pasó en el segundo y medio siguiente y si hay otra igual (el original) con botón
  const taps = [];
  const shape = (u) => {
    try {
      const x = new URL(u, location.href);
      return x.hostname === location.hostname ? x.pathname.replace(/\/(reels?|p|t|stories)\/[^/]+/g, '/$1/#') : x.hostname;
    } catch (e) { return '?'; }
  };
  const desc = (el) => el.tagName.toLowerCase() + ['role', 'aria-label', 'href', 'tabindex'].map((a) => {
    const v = el.getAttribute(a);
    return v === null ? '' : `[${a}=${a === 'href' ? shape(v) : v.slice(0, 30)}]`;
  }).join('');
  const mediaIn = (el) => (el.matches('img,video') ? el : el.querySelector('img,video'));
  const key = (m) => ((m.currentSrc || m.src || m.poster || '').split('?')[0].match(/[^/]+$/) || [''])[0];
  const sanitize = (el) => {
    const c = el.cloneNode(true);
    const walk = (n) => {
      if (n.nodeType === 3) { if (n.textContent.trim()) n.textContent = '…'; return; }
      if (n.nodeType !== 1) return;
      for (const a of [...n.attributes]) {
        if (a.name === 'href') n.setAttribute('href', shape(a.value));
        else if (a.name === 'aria-label') n.setAttribute('aria-label', a.value.slice(0, 30));
        else if (!/^(role|aria-|tabindex|data-)/.test(a.name)) n.removeAttribute(a.name);
      }
      [...n.childNodes].forEach(walk);
    };
    walk(c);
    return c.outerHTML.replace(/<path[^>]*>(<\/path>)?/g, '').slice(0, 1500);
  };
  let lastTap = null;
  addEventListener('click', (e) => {
    if (!/^\/direct\/t\//.test(location.pathname) || !e.isTrusted) return;
    let card = e.target.nodeType === 1 ? e.target : e.target.parentElement;
    for (let i = 0; card && i < 8 && !mediaIn(card); i++) card = card.parentElement; // hasta la foto o el video
    const m = card && mediaIn(card);
    if (!m) return;
    const chain = [];
    for (let el = e.target; el && el !== document.body && chain.length < 12; el = el.parentElement) chain.push(desc(el));
    const k = key(m);
    const twins = k ? [...document.querySelectorAll('img,video')].filter((o) => o !== m && key(o) === k) : [];
    const tap = {
      t: e.timeStamp, event: e, reached: false, prevented: false, after: '', // hora del toque, antes que cualquier listener
      media: `${m.tagName.toLowerCase()} de ${Math.round(m.getBoundingClientRect().width)}px`,
      twins: twins.map((o) => {
        const b = o.closest('a[href],button,[role="button"],[role="link"]');
        return `${b ? desc(b) : 'sin botón'} de ${Math.round(o.getBoundingClientRect().width)}px`;
      }).join(' | ') || 'ninguna',
      chain: chain.join(' < ').slice(0, 700), card: sanitize(card),
    };
    taps.push(tap);
    if (taps.length > 2) taps.shift();
    lastTap = tap;
    const p0 = location.pathname;
    setTimeout(() => {
      const ours = trace.filter(([t]) => t >= tap.t).map(([, w]) => w).join('; ');
      tap.after = `url ${location.pathname === p0 ? 'igual' : 'cambió a ' + shape(location.pathname)}, ` +
        `ventanas abiertas ${document.querySelectorAll('[role="dialog"]').length}, el script: ${ours || 'nada'}`;
    }, 1500);
  }, true);
  addEventListener('click', (e) => { // al final del recorrido: nadie lo frenó en el camino
    if (lastTap && lastTap.event === e) { lastTap.reached = true; lastTap.prevented = e.defaultPrevented; }
  });

  // Lo que hizo el script por su cuenta, con cuántas veces (solo lo que no tiene datos de nadie)
  const script = () => {
    const all = Object.entries(notes);
    return all.length ? ['Lo que hizo el script (veces):', ...all.slice(-25).map(([w, n]) => `  ${w}: ${n}`)] : [];
  };
  const mb = (n) => (n / 1048576).toFixed(1);
  const videoLine = () => `Video bajado: ${data.n} pedidos, ${mb(data.bytes)} MB` +
    (data.held ? ` · solo al tocar: ${data.held} en espera, ${data.sent} mandados después, ${data.dropped} descartados` : '');
  const bytesLine = () => `Bajó ${mb(bytes.img + bytes.video + bytes.js + bytes.otros)} MB: imágenes ${mb(bytes.img)}, ` +
    `video ${mb(bytes.video)}, código ${mb(bytes.js)}, otros ${mb(bytes.otros)}`;

  // Resumen de lo medido en esta página (para el informe de la medición automática); null si no hay nada
  const summary = () => {
    if (!navs.length && !videos.length && !tasks.length && !data.n && !data.held && !Object.keys(notes).length) return null;
    const long = {}; // pantalla → [cantidad, ms en total, ms la más larga]
    for (const [start, ms] of tasks) {
      const k = visits.filter(([t]) => t <= start).pop()[1], t = long[k] || (long[k] = [0, 0, 0]);
      t[0]++; t[1] += ms; t[2] = Math.max(t[2], ms);
    }
    const sorted = [...videos].sort((a, b) => a - b);
    const chrome = (navigator.userAgent.match(/Chrome\/[\d.]+/) || ['?'])[0];
    return [
      `Lúcido rendimiento · ${location.hostname} · ${chrome} · ${Math.round(performance.now() / 60000)} min`,
      'Tareas largas (cantidad, ms en total, la más larga):',
      ...Object.entries(long).map(([k, [n, sum, max]]) => `  ${k}: ${n}, ${Math.round(sum)}, ${Math.round(max)}`),
      'Cambios de página (ms hasta dibujarse; + = seguía cambiando a los 10 s, * = cambiaste antes):',
      '  ' + navs.slice(-30).join(' · '),
      `Videos, de play() a playing (ms): ${sorted.length ? `mediana ${sorted[sorted.length >> 1]}, máx ${sorted[sorted.length - 1]} (n=${sorted.length})` : 'ninguno'}`,
      '  ' + videos.slice(-30).join(' '),
      videoLine(),
      ...script(),
      ...taps.map((t) => `Toque en foto/video de un chat: ${t.media} · llegó a la página: ${t.reached ? (t.prevented ? 'sí (y alguien lo usó)' : 'sí') : 'no'}` +
        `\n  después: ${t.after || 'sin medir'}\n  otra igual: ${t.twins}\n  cadena: ${t.chain}\n  ${t.card}`),
    ].join('\n');
  };

  // Si no apareció "Responder", mandar a la app la estructura del mensaje y las etiquetas que hay a su
  // altura (sin el texto de los mensajes), para arreglarlo
  const swipeFailed = (bubble) => {
    const box = bubble.getBoundingClientRect();
    let row = bubble;
    for (let i = 0; i < 3 && row.parentElement && row.parentElement !== document.body; i++) row = row.parentElement;
    const clone = row.cloneNode(true);
    const KEEP = /^(role|aria-|tabindex|title|dir|data-)/;
    const walk = (n) => {
      if (n.nodeType === 3) {
        const inButton = n.parentElement && n.parentElement.closest('[role="button"], [role="menuitem"], button');
        if (n.textContent.trim() && !(inButton && n.textContent.trim().length <= 25)) n.textContent = '…';
        return;
      }
      if (n.nodeType !== 1) return;
      for (const a of [...n.attributes]) if (!KEEP.test(a.name)) n.removeAttribute(a.name);
      [...n.childNodes].forEach(walk);
    };
    walk(clone);
    const html = clone.outerHTML.replace(/<path[^>]*>(<\/path>)?/g, '').slice(0, 6000);
    const near = [...document.querySelectorAll('[aria-label], [title]')].filter((e) => {
      const r = e.getBoundingClientRect();
      return r.width > 0 && r.bottom > box.top - 150 && r.top < box.bottom + 150;
    }).map((e) => `${e.tagName.toLowerCase()}[role=${e.getAttribute('role')}] ${e.getAttribute('aria-label') || e.getAttribute('title')}`);
    const menus = [...document.querySelectorAll('[role="dialog"], [role="menu"], [role="listbox"]')]
      .map((m) => (m.innerText || '').slice(0, 300));
    LucidoDev.report(`Lúcido swipe-reply\n${location.pathname.replace(/\d+/g, '#')}\n` +
      `cerca: ${near.join(' | ').slice(0, 2000)}\nmenús: ${menus.join(' || ')}\n${html}`);
  };

  window.__lucidoDev = {
    // Avisos de block.user.js
    on(what, x) {
      if (what === 'note') {
        trace.push([performance.now(), x]);
        notes[x] = (notes[x] || 0) + 1;
        save();
      }
      else if (what === 'ready') { if (ready === null) ready = performance.now(); }
      else if (what === 'swipe') swipeFailed(x);
      else if (what === 'video') { data[x]++; save(); }
    },
    // Medición automática: empieza a medir lo que hace el paso que sigue (con video, hasta que arranca).
    // Devuelve el id de la página, para saber después si se recargó.
    start(video) {
      benching = true;
      const t0 = performance.now();
      measure(t0, screen() + (video ? ' (video)' : ''), true, video ? null : keep(t0));
      last = null; // no lo que cortó recién
      wantVideo = video ? { t0, path: location.pathname } : null;
      return id;
    },
    // Lo que midió, en ms, o null si sigue midiendo. Para una carga, old es el id de la página de antes (si
    // todavía es esa, null) y fcp/ready son desde que empezó a cargar (después de la carga, hasta 6 s, se
    // espera ready). long: [cantidad, ms en total] de tareas largas en el paso.
    result(old) {
      benching = true;
      if (id === old || !last) return null;
      if (last.t0 === 0 && ready === null && performance.now() < last.end + 6000) return null;
      if (longObs) for (const e of longObs.takeRecords()) tasks.push([e.startTime, e.duration]);
      const ms = (t) => (t === null ? null : Math.round(t));
      const mine = tasks.filter(([t]) => t >= last.t0 && t <= last.end);
      return {
        id, ms: ms(last.end - last.t0), more: last.more, fcp: ms(fcp), ready: ms(ready),
        long: [mine.length, Math.round(mine.reduce((sum, [, d]) => sum + d, 0))],
      };
    },
    id,
    summary,
    // Para "Datos bajados": video y lo que hizo el script desde que se puso en cero (null si nada)
    report() {
      if (!data.n && !data.held && !Object.keys(notes).length && !(bytes.img + bytes.video + bytes.js + bytes.otros)) return null;
      const name = /instagram/.test(location.hostname) ? 'Instagram' : /youtube/.test(location.hostname) ? 'YouTube' : location.hostname;
      return [name, bytesLine(), videoLine(), ...script()].join('\n');
    },
    reset() {
      for (const k of Object.keys(data)) data[k] = 0;
      for (const k of Object.keys(bytes)) bytes[k] = 0;
      for (const k of Object.keys(notes)) delete notes[k];
      try { sessionStorage.removeItem(KEY); } catch (e) {}
    },
  };
})();
