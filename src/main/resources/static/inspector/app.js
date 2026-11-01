/*
 * generic-graphql · Inspector
 *
 * Zeichnet Call Tree, Timeline und Paket-Animation aus dem Mitschnitt eines Requests
 * (TraceView-JSON von /inspector/api/traces/{id} bzw. dem SSE-Ereignis "trace").
 * Seite, Request-Liste und Detail-Panel rendert Thymeleaf; dieses Skript holt die
 * Fragmente nach und haelt die Seite ueber Server-Sent Events live.
 *
 * Wiedergabe in "visueller Zeit": an jeder Uebergabe (Start oder Ende eines Knotens)
 * steht die Trace-Zeit kurz still, damit die Pakete sichtbar von Knoten zu Knoten laufen.
 */
(() => {
  'use strict';

  const BODY = document.body;
  const BASE = BODY.dataset.base || '/inspector';
  const GRAPHQL = BODY.dataset.graphql || '/graphql';
  const ERR = '#FF7B72';
  const SVGNS = 'http://www.w3.org/2000/svg';
  const REDUCED = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const $ = (id) => document.getElementById(id);

  // ------------------------------------------------------------------ Farben je Spur
  const SRC = {
    root: { color: '#C3CAD6', tint: 'rgba(195, 202, 214, 0.12)', glow: 'rgba(195, 202, 214, 0.22)', run: 'other' },
    db: { color: '#6E92F7', tint: 'rgba(110, 146, 247, 0.14)', glow: 'rgba(110, 146, 247, 0.28)', run: 'db' },
    trust: { color: '#EDA845', tint: 'rgba(237, 168, 69, 0.14)', glow: 'rgba(237, 168, 69, 0.28)', run: 'trust' },
    presence: { color: '#86E8DB', tint: 'rgba(134, 232, 219, 0.13)', glow: 'rgba(134, 232, 219, 0.26)', run: 'presence' }
  };
  const EXTRA = ['#C7B3FF', '#F2C38B', '#9DB2D6', '#B5D98C'];
  let extraCount = 0;

  function rgba(hex, a) {
    const n = parseInt(hex.slice(1), 16);
    return 'rgba(' + (n >> 16) + ', ' + ((n >> 8) & 255) + ', ' + (n & 255) + ', ' + a + ')';
  }

  function src(lane) {
    if (!SRC[lane]) {
      const c = EXTRA[extraCount++ % EXTRA.length];
      SRC[lane] = { color: c, tint: rgba(c, 0.14), glow: rgba(c, 0.28), run: 'other' };
    }
    return SRC[lane];
  }

  const ICONS = {
    root: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3.5" y="4.5" width="17" height="15" rx="2"></rect><path d="M7.5 9.5l3 2.5-3 2.5M12.5 15h4"></path></svg>',
    db: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5.5" rx="7.5" ry="2.8"></ellipse><path d="M4.5 5.5v13c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8v-13"></path><path d="M4.5 12c0 1.55 3.36 2.8 7.5 2.8s7.5-1.25 7.5-2.8"></path></svg>',
    remote: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 18.5h10.2a4.3 4.3 0 0 0 .7-8.55A6.2 6.2 0 0 0 6.1 8.6 4.95 4.95 0 0 0 7 18.5z"></path></svg>',
    filter: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 5h16l-6.2 7.4V19l-3.6-1.8v-4.8L4 5z"></path></svg>',
    step: '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 7h14M5 12h9M5 17h6"></path><path d="M16 15l2.5 2.5L22 14"></path></svg>'
  };

  // ------------------------------------------------------------------ Zustand
  const S = {
    traces: new Map(),
    trace: null,
    m: null,
    nodeId: null,
    tab: 'node',
    follow: true,
    tempo: 1,
    zoom: 1,
    v: null,
    playing: false,
    raf: 0,
    services: new Map(),
    pending: new Map(),
    live: 'connecting',
    detailsKey: null
  };

  // ------------------------------------------------------------------ kleine Helfer
  const fmt = (v) => (Math.round(v * 10) / 10).toFixed(1).replace('.', ',');
  const pct = (v) => Math.round(v * 1000) / 1000;
  const r1 = (v) => Math.round(v * 10) / 10;
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const ease = (u) => (u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2);
  const now = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());

  function esc(text) {
    return String(text == null ? '' : text)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function plural(n, one, many) {
    return n + ' ' + (n === 1 ? one : many);
  }

  async function fetchJson(url, options) {
    const res = await fetch(url, Object.assign({ headers: { Accept: 'application/json' } }, options || {}));
    if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
    return res.json();
  }

  async function fetchText(url) {
    const res = await fetch(url, { headers: { Accept: 'text/html' } });
    if (!res.ok) throw new Error(res.status + ' ' + res.statusText);
    return res.text();
  }

  // ------------------------------------------------------------------ Syntaxfarben
  function tokGql(line) {
    const out = [];
    let code = line;
    let cmt = '';
    const hi = line.indexOf('#');
    if (hi >= 0 && line.slice(0, hi).split('"').length % 2 === 1) { code = line.slice(0, hi); cmt = line.slice(hi); }
    const re = /("(?:[^"\\]|\\.)*")|(-?\d+(?:\.\d+)?)|([A-Za-z_][A-Za-z0-9_]*)(\s*:)?|(\s+)|([^\s])/g;
    let m;
    let prev = '';
    while ((m = re.exec(code)) !== null) {
      if (m[1]) out.push([m[1], 'str']);
      else if (m[2]) out.push([m[2], 'num']);
      else if (m[3]) {
        const w = m[3];
        let c = 'field';
        if (w === 'query' || w === 'mutation' || w === 'subscription' || w === 'fragment') c = 'kw';
        else if (prev === 'query' || prev === 'mutation' || prev === 'subscription') c = 'op';
        else if (m[4]) c = 'arg';
        else if (/^[A-Z][A-Z0-9_]+$/.test(w) || w === 'true' || w === 'false' || w === 'null') c = 'num';
        out.push([w, c]);
        if (m[4]) out.push([m[4], 'punct']);
        prev = w;
      } else if (m[5]) out.push([m[5], 'punct']);
      else out.push([m[6], 'punct']);
    }
    if (cmt) out.push([cmt, 'cmt']);
    return out;
  }

  function tokJson(line) {
    const out = [];
    const re = /("(?:[^"\\]|\\.)*")(\s*:)?|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|(true|false|null)|(\s+)|([^\s])/g;
    let m;
    while ((m = re.exec(line)) !== null) {
      if (m[1]) { out.push([m[1], m[2] ? 'key' : 'str']); if (m[2]) out.push([m[2], 'punct']); }
      else if (m[3]) out.push([m[3], 'num']);
      else if (m[4]) out.push([m[4], m[4] === 'null' ? 'nul' : 'num']);
      else if (m[5]) out.push([m[5], 'punct']);
      else out.push([m[6], 'punct']);
    }
    return out;
  }

  const SQL_KW = new Set(['select', 'from', 'where', 'and', 'or', 'not', 'exists', 'in', 'order', 'by', 'asc', 'desc',
    'fetch', 'first', 'rows', 'only', 'is', 'null', 'lower', 'upper', 'false', 'true', 'insert', 'into', 'values', 'delete',
    'update', 'set', 'count', 'join', 'left', 'inner', 'on', 'as', 'offset', 'limit', 'group', 'having', 'distinct', 'case',
    'when', 'then', 'else', 'end', 'like', 'escape', 'between', 'union', 'all', 'with']);

  function tokSql(line) {
    if (line.trim().indexOf('--') === 0) return [[line, 'cmt']];
    const out = [];
    const re = /('(?:[^']|'')*')|(:[A-Za-z_]+|\?)|(-?\d+(?:\.\d+)?)|([A-Za-z_][A-Za-z0-9_]*)|(\s+)|([^\s])/g;
    let m;
    while ((m = re.exec(line)) !== null) {
      if (m[1]) out.push([m[1], 'str']);
      else if (m[2] || m[3]) out.push([m[2] || m[3], 'num']);
      else if (m[4]) out.push([m[4], SQL_KW.has(m[4].toLowerCase()) ? 'kw' : 'field']);
      else if (m[5]) out.push([m[5], 'punct']);
      else out.push([m[6], 'punct']);
    }
    return out;
  }

  function toksHtml(toks) {
    if (!toks.length) return ' ';
    return toks.map(([t, c]) => '<span class="t-' + c + '">' + esc(t) + '</span>').join('');
  }

  function formatSql(sql) {
    return String(sql)
      .replace(/,(?=\S)/g, ', ')
      .replace(/\s+(from|where|order by|group by|left join|inner join|join|fetch|offset|values|set)\s+/gi, '\n$1 ')
      .replace(/\s+(and|or)\s+/gi, '\n  $1 ');
  }

  function pretty(v, ind) {
    const nx = ind + '  ';
    if (v === null || typeof v !== 'object') return JSON.stringify(v === undefined ? null : v);
    if (Array.isArray(v)) {
      if (!v.length) return '[]';
      if (v.every((x) => x === null || typeof x !== 'object')) {
        const s1 = '[' + v.map((x) => JSON.stringify(x)).join(', ') + ']';
        if (s1.length + ind.length <= 60) return s1;
      }
      return '[\n' + v.map((x) => nx + pretty(x, nx)).join(',\n') + '\n' + ind + ']';
    }
    const ks = Object.keys(v);
    if (!ks.length) return '{}';
    if (ks.every((k) => v[k] === null || typeof v[k] !== 'object')) {
      const s2 = '{ ' + ks.map((k) => JSON.stringify(k) + ': ' + JSON.stringify(v[k])).join(', ') + ' }';
      if (s2.length + ind.length <= 60) return s2;
    }
    return '{\n' + ks.map((k) => nx + JSON.stringify(k) + ': ' + pretty(v[k], nx)).join(',\n') + '\n' + ind + '}';
  }

  function jsonHtml(value, maxLines) {
    let lines = pretty(value, '').split('\n');
    let more = 0;
    if (maxLines && lines.length > maxLines) { more = lines.length - maxLines; lines = lines.slice(0, maxLines); }
    let html = lines.map((l) => toksHtml(tokJson(l))).join('\n');
    if (more) html += '\n<span class="t-cmt">… ' + more + ' weitere Zeilen</span>';
    return html;
  }

  // ------------------------------------------------------------------ Layout: Ebenen als Baender, Knoten als Baum
  function layout(trace) {
    const mutation = trace.kind === 'mutation';
    const CW = mutation ? 204 : 232, CH = 96, RW = 264, RH = 52, HG = mutation ? 20 : 32, AUXG = 96;
    const GUT = 148, NOMW = 830, B0 = 84, BH = 128, TOP = 12;
    const nodes = trace.nodes.map((n) => Object.assign({}, n, { kids: [], auxs: [], isReq: n.kind === 'root' }));
    const by = {};
    nodes.forEach((n) => { by[n.id] = n; });
    nodes.forEach((n) => {
      if (n.parent && by[n.parent]) (n.aux ? by[n.parent].auxs : by[n.parent].kids).push(n);
    });
    const wOf = (n) => (n.isReq ? RW : CW);
    const hOf = (n) => (n.isReq ? RH : CH);
    // Karte mittig ueber den Kindern; Filter-Suchen rechts neben der Karte
    const measure = (n) => {
      n.kids.forEach(measure);
      n.kw = n.kids.reduce((a, k) => a + k.sw, 0) + Math.max(0, n.kids.length - 1) * HG;
      const aw = n.auxs.length * (AUXG + CW);
      n.lx = Math.max(n.kw / 2, wOf(n) / 2);
      n.rx = Math.max(n.kw / 2, wOf(n) / 2 + aw);
      n.sw = n.lx + n.rx;
    };
    const place = (n, left) => {
      const cx = left + n.lx;
      n.x = cx - wOf(n) / 2;
      let l = cx - n.kw / 2;
      n.kids.forEach((k) => { place(k, l); l += k.sw + HG; });
      n.auxs.forEach((a, i) => { a.x = n.x + wOf(n) + AUXG + i * (CW + AUXG); });
    };
    const root = nodes.find((n) => !n.parent) || nodes[0];
    measure(root);
    place(root, 0);
    let minL = Infinity;
    let maxR = -Infinity;
    nodes.forEach((n) => {
      if (n.x == null) n.x = 0;
      minL = Math.min(minL, n.x);
      maxR = Math.max(maxR, n.x + wOf(n));
    });
    const avail = NOMW - GUT - 24;
    const off = GUT + Math.max(0, (avail - (maxR - minL)) / 2) - minL;
    const bandTop = (k) => TOP + (k === 0 ? 0 : B0 + (k - 1) * BH);
    const bandH = (k) => (k === 0 ? B0 : BH);
    let levels = 0;
    nodes.forEach((n) => {
      n.x = Math.round(n.x + off);
      n.w = wOf(n);
      n.h = hOf(n);
      n.y = Math.round(bandTop(n.level) + (bandH(n.level) - n.h) / 2);
      levels = Math.max(levels, n.level);
    });
    return {
      nodes, by, levels, bandTop, bandH,
      gh: bandTop(levels) + bandH(levels) + 12,
      gw: Math.max(NOMW, Math.round(maxR + off + 24))
    };
  }

  // ------------------------------------------------------------------ Animations-Zeitachse
  // Hop-Fenster an jeder Uebergabe; dazwischen laeuft die Trace-Zeit, kurze Phasen mit
  // Mindestdauer, lange anteilig. Nah beieinander liegende Uebergaben teilen sich ein Fenster.
  function hasReady(n) {
    return n.ready != null && n.ready < n.t1 - 0.05;
  }

  function visualTime(trace) {
    const HOP = 560, MINW = 160, PROP = 2200;
    const total = Math.max(trace.totalMs, 0.001);
    const tol = Math.max(0.3, total * 0.025);
    const nodeKeys = [];
    const alias = new Map();
    const hops = new Set([0, total]);
    const extra = [];
    const snap = (t) => {
      t = clamp(t, 0, total);
      for (const k of nodeKeys) if (Math.abs(k - t) <= tol) return k;
      nodeKeys.push(t);
      return t;
    };
    trace.nodes.forEach((n) => {
      if (n.kind === 'root') return;
      [n.t0, n.t1].forEach((t) => {
        const k = snap(t);
        alias.set(t, k);
        hops.add(k);
      });
      if (hasReady(n)) extra.push(clamp(n.ready, 0, total));
    });
    const keys = Array.from(new Set([0, total].concat(nodeKeys, extra))).sort((a, b) => a - b);
    const segs = [];
    const hopAt = new Map();
    let v = 0;
    keys.forEach((t, i) => {
      if (hops.has(t)) {
        segs.push({ hop: true, t0: t, t1: t, v0: v, v1: v + HOP });
        hopAt.set(t, v);
        v += HOP;
      }
      if (i < keys.length - 1) {
        const vd = MINW + PROP * (keys[i + 1] - t) / total;
        segs.push({ hop: false, t0: t, t1: keys[i + 1], v0: v, v1: v + vd });
        v += vd;
      }
    });
    const vt = { segs, total, V: v };
    vt.tOf = (vv) => {
      for (const sg of segs) {
        if (vv < sg.v1) return sg.hop ? sg.t0 : sg.t0 + (sg.t1 - sg.t0) * Math.max(0, vv - sg.v0) / (sg.v1 - sg.v0);
      }
      return total;
    };
    vt.vOf = (t) => {
      const k = alias.has(t) ? alias.get(t) : t;
      if (hopAt.has(k)) return hopAt.get(k);
      for (const sg of segs) {
        if (!sg.hop && k >= sg.t0 && k <= sg.t1) return sg.v0 + (sg.v1 - sg.v0) * (k - sg.t0) / Math.max(1e-9, sg.t1 - sg.t0);
      }
      return k <= 0 ? 0 : v;
    };
    vt.inHop = (vv) => segs.some((sg) => sg.hop && vv >= sg.v0 && vv < sg.v1);
    return vt;
  }

  // ------------------------------------------------------------------ Texte aus dem Mitschnitt
  function verbs(n) {
    const counts = new Map();
    (n.sql || []).forEach((s) => {
      const w = String(s).trim().split(/\s+/)[0].toUpperCase();
      counts.set(w, (counts.get(w) || 0) + 1);
    });
    return Array.from(counts.entries()).map(([w, c]) => c + ' ' + w).join(' · ');
  }

  function workText(n, trace) {
    switch (n.kind) {
      case 'forward':
      case 'reverse':
        return n.loads + '× load() → ' + (n.batches ? plural(n.batches, 'Batch', 'Batches') : 'Cache');
      case 'remote':
        return 'batchGet · ' + plural(n.keys.length, 'ID', 'IDs');
      case 'search':
        return 'search · ' + (n.args || '');
      case 'mutation':
        return 'EventMutationService.handle';
      case 'step':
        return n.sql && n.sql.length ? verbs(n) : n.resolver;
      default: {
        const search = trace.nodes.some((c) => c.parent === n.id && c.kind === 'search');
        if (search) return 'erst Owner fragen, dann SQL';
        return n.sql && n.sql.length ? plural(n.sql.length, 'SQL', 'SQL') + ' · LatestVersionScope' : 'läuft';
      }
    }
  }

  function bandInfo(trace, lay, k) {
    if (k === 0) {
      return { name: 'Request', l2: 'POST /graphql', l3: trace.status === 'rejected' ? 'abgelehnt' : 'Guardrails ok' };
    }
    const at = lay.nodes.filter((n) => n.level === k && !n.isReq);
    const fields = at.filter((n) => !n.aux && !n.step);
    const steps = at.filter((n) => n.step);
    const ids = new Set(at.map((n) => n.id));
    const sqlCount = trace.spans.filter((s) => s.lane === 'db' && ids.has(s.node)).length;
    const remote = trace.spans.filter((s) => (s.kind === 'remote' || s.kind === 'search') && ids.has(s.node)).length;
    const t0 = Math.min.apply(null, at.map((n) => n.t0));
    const t1 = Math.max.apply(null, at.map((n) => n.t1));
    const dur = at.length ? ' · ' + fmt(t1 - t0) + ' ms' : '';
    if (steps.length && !fields.length) {
      return { name: 'Schritte', l2: 'EventMutationService', l3: plural(steps.length, 'Schritt', 'Schritte') + ' · ' + sqlCount + ' SQL' };
    }
    if (k === 1) {
      if (trace.kind === 'mutation') return { name: 'Mutation', l2: 'Mutation-Feld', l3: 'seriell · 1 Transaktion' };
      const search = at.some((n) => n.aux);
      return {
        name: 'Ebene 1',
        l2: fields.length === 1 ? 'Query-Feld' : fields.length + ' Query-Felder',
        l3: (search ? 'Suche + ' : '') + sqlCount + ' SQL' + dur
      };
    }
    const parts = [];
    if (sqlCount) parts.push(plural(sqlCount, 'Batch', 'Batches'));
    if (remote) parts.push(remote > 1 ? remote + ' Remote parallel' : '1 Remote-Aufruf');
    if (!parts.length) parts.push('aus dem Cache');
    return { name: 'Ebene ' + k, l2: 'dispatchAll()', l3: parts.join(' + ') + dur };
  }

  function egress(trace) {
    switch (trace.status) {
      case 'partial': return { label: '200 · ' + plural(trace.errorCount, 'Fehler', 'Fehler'), err: true };
      case 'failed': return { label: '200 · errors[]', err: true };
      case 'rejected': return { label: '200 · abgelehnt', err: true };
      case 'declined': return { label: '200 · result: error', err: true };
      default: return { label: '200 · JSON', err: false };
    }
  }

  function focusNode(trace) {
    const fields = trace.nodes.filter((n) => n.kind !== 'root');
    const failed = fields.find((n) => n.error);
    if (failed) return failed.id;
    if (!fields.length) return trace.nodes[0].id;
    return fields.reduce((a, b) => (b.t1 - b.t0 > a.t1 - a.t0 ? b : a)).id;
  }

  // ------------------------------------------------------------------ Modell eines Mitschnitts aufbauen
  function model(trace) {
    const lay = layout(trace);
    const vt = visualTime(trace);
    return { trace, lay, vt, total: Math.max(trace.totalMs, 0.001) };
  }

  function buildTree(m) {
    const tree = $('tree');
    const trace = m.trace;
    tree.textContent = '';
    tree.style.width = m.lay.gw + 'px';
    tree.style.height = m.lay.gh + 'px';
    $('empty').hidden = true;

    // Die Beschriftung der Ebenen bleibt beim horizontalen Scrollen am linken Rand stehen
    const labels = el('div', 'band-col');
    labels.style.height = m.lay.gh + 'px';
    tree.append(labels);
    m.bands = [];
    for (let k = 0; k <= m.lay.levels; k++) {
      const top = m.lay.bandTop(k);
      const h = m.lay.bandH(k);
      const info = bandInfo(trace, m.lay, k);
      const band = el('div', 'band');
      band.style.top = top + 'px';
      band.style.height = h + 'px';
      const label = el('div', 'band-label',
        '<span class="band-name">' + esc(info.name) + '</span><span class="band-l2" title="' + esc(info.l2) + '">' + esc(info.l2) +
        '</span><span class="band-l3">' + esc(info.l3) + '</span>');
      label.style.top = (top + (k === 0 ? 19 : 41)) + 'px';
      tree.append(band);
      labels.append(label);
      m.bands.push({ k, band, label, name: info.name });
    }

    m.edges = {};
    m.lay.nodes.forEach((n) => {
      const p = n.parent ? m.lay.by[n.parent] : null;
      if (!p) return;
      let left, top, w, h, d, ex, ey, cx, cy, geo;
      if (n.aux) {
        const y = Math.round(p.y + p.h / 2);
        left = p.x + p.w; top = y - 6; w = n.x - left; h = 12;
        d = 'M ' + w + ' 6 L 0 6'; ex = 0; ey = 6; cx = left + w / 2; cy = y;
        geo = [[left, y], [left, y], [n.x, y], [n.x, y]];
      } else {
        const x1 = p.x + p.w / 2, y1 = p.y + p.h, x2 = n.x + n.w / 2, y2 = n.y;
        left = Math.min(x1, x2) - 6; top = y1; w = Math.abs(x2 - x1) + 12; h = y2 - y1;
        const sx = x1 - left, exx = x2 - left, my = h / 2;
        d = 'M ' + sx + ' 0 C ' + sx + ' ' + my + ' ' + exx + ' ' + my + ' ' + exx + ' ' + h;
        ex = exx; ey = h; cx = (x1 + x2) / 2; cy = (y1 + y2) / 2;
        geo = [[x1, y1], [x1, y1 + my], [x2, y1 + my], [x2, y2]];
      }
      const svg = document.createElementNS(SVGNS, 'svg');
      svg.setAttribute('class', 'edge');
      svg.setAttribute('width', w);
      svg.setAttribute('height', h);
      svg.setAttribute('aria-hidden', 'true');
      svg.style.left = left + 'px';
      svg.style.top = top + 'px';
      const path = document.createElementNS(SVGNS, 'path');
      path.setAttribute('d', d);
      path.setAttribute('fill', 'none');
      path.setAttribute('stroke-width', '1.6');
      path.setAttribute('stroke-linecap', 'round');
      const circle = document.createElementNS(SVGNS, 'circle');
      circle.setAttribute('cx', ex);
      circle.setAttribute('cy', ey);
      circle.setAttribute('r', '2.8');
      svg.append(path, circle);
      const label = el('span', 'edge-label', esc(n.via || ''));
      const pw = Math.round((n.via || '').length * 6.7 + 16);
      label.style.cssText = 'left:' + Math.round(cx - pw / 2) + 'px;top:' + Math.round(cy - 9) + 'px;width:' + pw + 'px';
      if (!n.via) label.hidden = true;
      tree.append(svg, label);
      m.edges[n.id] = { svg, path, circle, label, geo };
    });

    m.nodes = {};
    m.lay.nodes.forEach((n) => {
      const S0 = src(n.source);
      const b = el('button', 'node ' + (n.isReq ? 'req' : 'field'));
      b.type = 'button';
      b.dataset.node = n.id;
      b.style.cssText = 'left:' + n.x + 'px;top:' + n.y + 'px;width:' + n.w + 'px;height:' + n.h + 'px';
      const icon = ICONS[n.isReq ? 'root' : n.aux ? 'filter' : n.kind === 'remote' ? 'remote' : n.step ? 'step' : 'db'];
      const ico = '<span class="n-ico" style="background:' + S0.tint + ';color:' + S0.color + '">' + icon + '</span>';
      const title = '<span class="n-title"><span class="n-field">' + esc(n.field) + '</span><span class="n-sig">' + esc(n.sig) + '</span></span>';
      if (n.isReq) {
        b.innerHTML = '<span class="n-row">' + ico + title + '<span class="n-right"><span class="n-count"></span><span class="n-dur"></span></span></span>';
      } else {
        b.innerHTML = '<span class="n-top">' + ico + title + '<span class="n-count"></span></span><span class="n-rule"></span>' +
          '<span class="n-bottom"><span class="n-chips">' + (n.chips || []).map((c) => '<span class="chip">' + esc(c) + '</span>').join('') +
          '</span><span class="n-work" style="color:' + S0.color + '"></span><span class="n-dur"></span></span>' +
          '<span class="n-prog"><span style="background:' + S0.color + '"></span></span>';
      }
      tree.append(b);
      m.nodes[n.id] = {
        n, b,
        count: b.querySelector('.n-count'),
        dur: b.querySelector('.n-dur'),
        work: b.querySelector('.n-work'),
        prog: b.querySelector('.n-prog > span'),
        workText: n.isReq ? '' : workText(n, trace),
        last: {}
      };
    });

    m.overlay = el('div', 'overlay');
    tree.append(m.overlay);
  }

  function buildQuery(m) {
    const box = $('query');
    const trace = m.trace;
    box.textContent = '';
    $('query-title').textContent = trace.kind === 'mutation' ? 'Mutation' : 'Query';
    const lines = trace.query.replace(/\s+$/, '').split('\n');
    const byLine = {};
    m.lay.nodes.forEach((n) => { (byLine[n.qline] = byLine[n.qline] || []).push(n); });
    m.qlines = lines.map((line, i) => {
      const ns = byLine[i] || [];
      const row = el(ns.length ? 'button' : 'div', 'ql');
      if (ns.length) {
        row.type = 'button';
        row.dataset.line = String(i);
        row.setAttribute('aria-label', 'Knoten wählen: ' + ns.map((n) => (n.isReq ? n.field : n.owner + '.' + n.field)).join(', '));
      }
      const dots = ns.filter((n) => !n.isReq).map((n) => '<span style="background:' + src(n.source).color + '"></span>').join('');
      row.innerHTML = '<span class="ql-dots">' + dots + '</span><span class="ql-text">' + toksHtml(tokGql(line)) + '</span>';
      box.append(row);
      return { row, nodes: ns, last: '' };
    });
  }

  function laneList(m) {
    const trace = m.trace;
    const lanes = [{ id: 'thread', label: 'Request-Thread', sub: trace.thread || '', color: '#E8EBF1' }];
    const dbNode = trace.nodes.find((n) => n.source === 'db');
    lanes.push({ id: 'db', label: dbNode ? dbNode.sourceName.split(' · ')[0] : 'Datenbank', sub: 'EntityManager · Tx', color: SRC.db.color });
    const seen = new Set(['thread', 'db']);
    const add = (id, name) => {
      if (seen.has(id)) return;
      seen.add(id);
      lanes.push({ id, label: name, sub: 'virtueller Thread', color: src(id).color });
    };
    S.services.forEach((name, id) => add(id, name));
    trace.spans.forEach((s) => {
      if (seen.has(s.lane)) return;
      const node = trace.nodes.find((n) => n.id === s.node);
      add(s.lane, node ? node.sourceName : s.lane);
    });
    return lanes;
  }

  function buildTimeline(m) {
    const box = $('lanes');
    const trace = m.trace;
    const total = m.total;
    box.textContent = '';
    const marksRow = el('div', 'marks-row', '<span></span><span></span>');
    trace.marks.filter((mk) => mk.t <= total).forEach((mk) => {
      const s = el('span', 'mark-label', esc(mk.label));
      s.style.left = pct(mk.t / total * 100) + '%';
      marksRow.lastChild.append(s);
    });
    box.append(marksRow);

    m.bars = [];
    laneList(m).forEach((L) => {
      const mine = trace.spans.filter((s) => s.lane === L.id).sort((a, b) => a.t0 - b.t0);
      const threads = Array.from(new Set(mine.map((s) => s.thread).filter(Boolean)));
      const row = el('div', 'lane-row');
      const sub = mine.length ? (L.id === 'thread' || L.id === 'db' ? L.sub : (threads.join(', ') || L.sub)) : 'nicht beteiligt';
      row.innerHTML = '<div class="lane-head"><span class="lane-name" style="color:' + (mine.length ? L.color : '#8C96A8') + '">' + esc(L.label) +
        '</span><span class="lane-sub" title="' + esc(sub) + '">' + esc(sub) + '</span></div><div class="lane-track"></div>';
      const track = row.lastChild;
      mine.forEach((sp) => {
        const err = !!sp.error;
        const wait = sp.kind === 'wait';
        let bg, fg, bc;
        if (L.id === 'thread') { bg = '#2A3343'; fg = '#E8EBF1'; bc = '#3A4558'; }
        else { bg = L.color; fg = '#0A0D12'; bc = L.color; }
        const left = pct(sp.t0 / total * 100);
        const width = pct(Math.max((sp.t1 - sp.t0) / total * 100, 0.4));
        const ghost = el('span', 'ghost');
        ghost.style.cssText = 'left:' + left + '%;width:' + width + '%;background:' + (err ? ERR : bg);
        const label = err && L.id !== 'thread' ? sp.label + ' · Fehler' : sp.label;
        const bar = el('button', 'bar-span' + (wait ? ' wait' : '') + (err ? ' err' : ''), esc(label));
        bar.type = 'button';
        bar.dataset.node = sp.node;
        bar.title = label + ' · ' + fmt(sp.t0) + '–' + fmt(sp.t1) + ' ms' + (sp.detail ? '\n' + sp.detail : '') + (sp.error ? '\n' + sp.error : '');
        bar.style.cssText = 'left:' + left + '%;background:' + bg + ';color:' + fg + ';border-color:' + bc;
        track.append(ghost, bar);
        m.bars.push({ sp, bar, last: {} });
      });
      box.append(row);
    });

    const ticks = el('div', 'ticks-row', '<span>ms</span><span></span>');
    m.ticksBox = ticks.lastChild;
    box.append(ticks);

    const guides = el('div', 'guides');
    trace.marks.filter((mk) => mk.t <= total).forEach((mk) => {
      const g = el('span', 'guide');
      g.style.left = pct(mk.t / total * 100) + '%';
      guides.append(g);
    });
    m.playhead = el('span', 'playhead');
    guides.append(m.playhead);
    box.append(guides);
    timelineBox().scrollLeft = 0;
    layoutTimeline(m);
  }

  // ------------------------------------------------------------------ Zoom und Scrollen der Timeline
  const HEAD = 140;
  const ZMAX = 64;

  function timelineBox() {
    return $('tl-scroll');
  }

  function trackSpace() {
    return Math.max(160, timelineBox().clientWidth - HEAD);
  }

  // Breite und Skala fuer den aktuellen Zoom. anchor = { t, x }: der Zeitpunkt t bleibt an der
  // Position x (relativ zum sichtbaren Bereich) stehen.
  function layoutTimeline(m, anchor) {
    const box = timelineBox();
    const track = Math.floor(trackSpace() * S.zoom);
    m.track = track;
    $('lanes').style.width = (HEAD + track) + 'px';
    const steps = [0.1, 0.2, 0.25, 0.5, 1, 2, 2.5, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000];
    let step = steps[steps.length - 1];
    for (const s of steps) { if (s / m.total * track >= 90) { step = s; break; } }
    m.ticksBox.textContent = '';
    for (let v = 0; v <= m.total + 1e-6; v += step) {
      const t = el('span', 'tick', esc(String(Math.round(v * 100) / 100).replace('.', ',')));
      const at = v / m.total * 100;
      t.style.left = pct(at) + '%';
      // erste und letzte Zahl nicht ueber den Rand schieben
      if (v === 0) t.style.transform = 'none';
      else if (at > 97) t.style.transform = 'translateX(-100%)';
      m.ticksBox.append(t);
    }
    box.classList.toggle('fit', S.zoom <= 1);
    if (anchor) box.scrollLeft = Math.max(0, HEAD + anchor.t / m.total * track - anchor.x);
    $('zoom-label').textContent = S.zoom <= 1 ? 'Gesamt' : (Math.round(S.zoom * 10) / 10).toString().replace('.', ',') + '×';
    document.querySelector('.zoom [data-zoom="out"]').disabled = S.zoom <= 1;
    document.querySelector('.zoom [data-zoom="in"]').disabled = S.zoom >= ZMAX;
    updateScrolled();
  }

  function updateScrolled() {
    const box = timelineBox();
    box.classList.toggle('scrolled', box.scrollLeft > 2);
  }

  // Zeitpunkt unter der Position x im sichtbaren Bereich (Standard: dessen Mitte)
  function anchorAt(x) {
    const m = S.m;
    const box = timelineBox();
    const ax = x == null ? HEAD + (box.clientWidth - HEAD) / 2 : x;
    return { t: clamp((box.scrollLeft + ax - HEAD) / m.track * m.total, 0, m.total), x: ax };
  }

  function setZoom(z, x) {
    const m = S.m;
    if (!m) return;
    const anchor = anchorAt(x);
    S.zoom = clamp(z, 1, ZMAX);
    if (S.zoom < 1.02) S.zoom = 1;
    layoutTimeline(m, anchor);
    frame();
  }

  function buildResponse(m) {
    const trace = m.trace;
    const resp = trace.response || {};
    const errors = Array.isArray(resp.errors) ? resp.errors : [];
    let note = 'vollständig, ohne errors';
    let cls = '';
    if (errors.length) {
      const data = resp.data;
      const allNull = !data || Object.keys(data).every((k) => data[k] === null);
      note = plural(errors.length, 'Fehler', 'Fehler') + ' im errors-Array · ' + (allNull ? 'data ist null' : 'der Rest bleibt erhalten');
      cls = 'err';
    } else if (trace.status === 'declined') {
      note = 'ohne errors · result: "error" ist eine fachliche Antwort';
      cls = 'warn';
    }
    $('panel-resp').innerHTML =
      '<div class="resp-head"><span class="code-badge">HTTP 200</span><span class="mono muted">application/json</span>' +
      '<span class="resp-note ' + cls + '">' + esc(note) + '</span></div>' +
      '<pre class="code resp">' + jsonHtml(resp, 4000) + '</pre>';
  }

  function buildHeader(m) {
    const trace = m.trace;
    $('hdr-kind').textContent = trace.kind;
    $('hdr-name').textContent = trace.operation;
    const st = $('hdr-status');
    const e = egress(trace);
    st.textContent = trace.status === 'ok' ? '200 · ok' : e.label;
    st.style.color = e.err ? ERR : '#AEB6C4';
    $('hdr-total').textContent = fmt(trace.totalMs) + ' ms';
    const fields = m.lay.nodes.filter((n) => !n.isReq && !n.aux && !n.step).length;
    const steps = m.lay.nodes.filter((n) => n.step).length;
    let stats;
    if (trace.status === 'rejected') stats = 'vor der Ausführung abgelehnt';
    else if (trace.kind === 'mutation') stats = plural(fields, 'Mutation-Feld', 'Mutation-Felder') + ' · ' + plural(steps, 'Schritt', 'Schritte') + ' · ' + trace.sqlCount + ' SQL';
    else stats = plural(m.lay.levels, 'Ebene', 'Ebenen') + ' · ' + trace.sqlCount + ' SQL · ' + trace.remoteCount + ' Remote';
    $('stats').textContent = stats;
  }

  // ------------------------------------------------------------------ Bild fuer den aktuellen Zeitpunkt
  const TRAVEL = 380, DOFF = 100, ING = 460, EGR = 480, RIP = 320;

  function bez(g, u) {
    const a = 1 - u;
    const b0 = a * a * a, b1 = 3 * a * a * u, b2 = 3 * a * u * u, b3 = u * u * u;
    return { x: b0 * g[0][0] + b1 * g[1][0] + b2 * g[2][0] + b3 * g[3][0], y: b0 * g[0][1] + b1 * g[1][1] + b2 * g[2][1] + b3 * g[3][1] };
  }

  function frame() {
    const m = S.m;
    if (!m) return;
    const trace = m.trace;
    const vt = m.vt;
    const total = m.total;
    const vNow = S.v == null ? vt.V : clamp(S.v, 0, vt.V);
    const animating = S.v != null;
    const tNow = vt.tOf(vNow);
    const loadsOf = (n) => clamp(n.loads || 1, 1, 5);
    const stagOf = (n) => (loadsOf(n) > 1 ? Math.min(50, 80 / (loadsOf(n) - 1)) : 0);
    const arriveV = (n) => (n.isReq ? vt.vOf(0) + ING : vt.vOf(n.t0) + DOFF + stagOf(n) * (loadsOf(n) - 1) + TRAVEL);
    const departV = (n) => (n.isReq ? vt.vOf(total) : vt.vOf(n.t1));
    const states = {};
    m.lay.nodes.forEach((n) => {
      let st;
      if (vNow < arriveV(n)) st = 'pending';
      else if (vNow < departV(n)) st = hasReady(n) && tNow >= n.ready ? 'waiting' : 'running';
      else st = n.error ? 'error' : 'done';
      states[n.id] = st;
    });
    m.states = states;
    const live = (st) => st === 'running' || st === 'waiting';

    // Knoten
    m.lay.nodes.forEach((n) => {
      const r = m.nodes[n.id];
      const st = states[n.id];
      const S0 = src(n.source);
      const selected = n.id === S.nodeId;
      let bc = '#263041', bs = 'solid', op = 1, countText = n.count, countColor = n.error ? ERR : '#AEB6C4';
      if (st === 'pending') { bs = 'dashed'; op = 0.38; countText = ''; }
      else if (st === 'running') { bc = S0.color; countText = '…'; countColor = S0.color; }
      else if (st === 'waiting') { bc = S0.color; bs = 'dashed'; countText = 'wartet'; countColor = S0.color; }
      else if (st === 'error') { bc = ERR; countText = 'Fehler'; countColor = ERR; }
      if (selected && (st === 'done' || st === 'pending')) bc = '#E8EBF1';
      const el0 = Math.max(0, Math.min(tNow, n.t1) - n.t0);
      const working = live(st) && !n.isReq;
      const cls = 'node ' + (n.isReq ? 'req' : 'field') + (selected ? ' selected' : '') + (working ? ' working' : '') +
        (st === 'running' ? ' run-' + S0.run : '');
      const dur = st === 'pending' ? '' : fmt(el0) + ' ms';
      const key = [cls, bc, bs, op, countText, countColor, dur].join('|');
      if (r.last.key !== key) {
        r.b.className = cls;
        r.b.style.borderColor = bc;
        r.b.style.borderStyle = bs;
        r.b.style.opacity = op;
        r.count.textContent = countText;
        r.count.style.color = countColor;
        r.dur.textContent = dur;
        r.b.setAttribute('aria-pressed', selected ? 'true' : 'false');
        r.b.setAttribute('aria-label', (n.isReq ? n.field : n.owner + '.' + n.field) + ', ' + (countText || 'ausstehend'));
        r.last.key = key;
      }
      if (working) {
        r.work.textContent = st === 'waiting' ? 'Antwort da · Ebene wartet' : r.workText;
        r.prog.style.width = pct(n.t1 > n.t0 ? el0 / (n.t1 - n.t0) * 100 : 0) + '%';
      }
    });

    // Kanten
    m.lay.nodes.forEach((n) => {
      const e = m.edges[n.id];
      if (!e) return;
      const st = states[n.id];
      const color = st === 'error' ? ERR : src(n.source).color;
      const op = st === 'pending' ? 0.3 : 0.9;
      const dash = live(st) ? '6 6' : st === 'pending' ? '3 5' : n.aux ? '4 4' : 'none';
      const key = color + '|' + op + '|' + dash;
      if (e.last !== key) {
        e.path.setAttribute('stroke', color);
        e.path.setAttribute('stroke-dasharray', dash);
        e.path.setAttribute('class', live(st) ? 'eflow' : '');
        e.circle.setAttribute('fill', color);
        e.svg.style.opacity = op;
        e.label.style.color = color;
        e.label.style.opacity = op;
        e.last = key;
      }
    });

    // Baender
    let activeBand = '';
    m.bands.forEach((b) => {
      const active = m.lay.nodes.some((n) => n.level === b.k && !n.isReq && live(states[n.id]));
      b.band.classList.toggle('active', active);
      b.label.classList.toggle('active', active);
      if (active) activeBand = b.name;
    });

    // Pakete: weiss = Anfrage nach unten, farbig = Daten nach oben
    const parts = [];
    const ripple = (x, y, v0, color) => {
      if (vNow < v0 || vNow >= v0 + RIP) return;
      const u = (vNow - v0) / RIP;
      const d = Math.round(10 + 34 * u);
      parts.push('<span class="rip" style="left:' + r1(x) + 'px;top:' + r1(y) + 'px;width:' + d + 'px;height:' + d +
        'px;margin:' + (-d / 2) + 'px 0 0 ' + (-d / 2) + 'px;border-color:' + color + ';opacity:' + r1((1 - u) * 0.8) + '"></span>');
    };
    const pill = (x, y, label, bg, glow) => {
      parts.push('<span class="pkt" style="left:' + r1(x) + 'px;top:' + r1(y) + 'px;background:' + bg +
        ';box-shadow:0 0 0 3px ' + glow + ', 0 6px 16px rgba(0, 0, 0, 0.5)">' + esc(label) + '</span>');
    };
    if (animating) {
      const rq = m.lay.nodes.find((n) => n.isReq);
      const rcx = rq.x + rq.w / 2;
      const v0 = vt.vOf(0);
      if (vNow >= v0 && vNow < v0 + ING) {
        const u = ease((vNow - v0) / ING);
        pill(rcx, -24 + (rq.y - 4 + 24) * u, 'POST /graphql', '#E8EBF1', 'rgba(232, 235, 241, 0.2)');
      }
      ripple(rcx, rq.y, v0 + ING, '#E8EBF1');
      const ve = vt.vOf(total);
      if (vNow >= ve && vNow < ve + EGR) {
        const u = ease((vNow - ve) / EGR);
        const e = egress(trace);
        pill(rcx, rq.y - 4 - (rq.y - 4 + 24) * u, e.label, e.err ? '#FF9F97' : '#E8EBF1', e.err ? 'rgba(255, 159, 151, 0.22)' : 'rgba(232, 235, 241, 0.2)');
      }
      m.lay.nodes.forEach((n) => {
        const e = m.edges[n.id];
        if (!e) return;
        const g = e.geo;
        const cnt = loadsOf(n);
        const stag = stagOf(n);
        const hd = vt.vOf(n.t0);
        for (let k = 0; k < cnt; k++) {
          const s0 = hd + DOFF + k * stag;
          if (vNow >= s0 && vNow < s0 + TRAVEL) {
            const raw = (vNow - s0) / TRAVEL;
            [0.14, 0.07].forEach((du, j) => {
              if (raw - du > 0) {
                const q = bez(g, ease(raw - du));
                parts.push('<span class="trail" style="left:' + r1(q.x) + 'px;top:' + r1(q.y) + 'px;opacity:' + (j ? 0.32 : 0.16) + '"></span>');
              }
            });
            const pt = bez(g, ease(raw));
            parts.push('<span class="dot" style="left:' + r1(pt.x) + 'px;top:' + r1(pt.y) + 'px"></span>');
          }
        }
        ripple(g[3][0], g[3][1], arriveV(n), '#E8EBF1');
        const hu = vt.vOf(n.t1);
        const err = !!n.error;
        const col = err ? ERR : src(n.source).color;
        if (vNow >= hu && vNow < hu + TRAVEL) {
          const pt = bez(g, 1 - ease((vNow - hu) / TRAVEL));
          pill(pt.x, pt.y, err ? 'Fehler' : (n.ret || n.count || '·'), col, err ? 'rgba(255, 123, 114, 0.26)' : src(n.source).glow);
        }
        ripple(g[0][0], g[0][1], hu + TRAVEL, col);
      });
    }
    const overlayHtml = parts.join('');
    if (m.overlayHtml !== overlayHtml) {
      m.overlay.innerHTML = overlayHtml;
      m.overlayHtml = overlayHtml;
    }

    // Query-Zeilen
    const selNode = m.lay.by[S.nodeId];
    m.qlines.forEach((q, i) => {
      const runN = q.nodes.filter((n) => !n.isReq && live(states[n.id]))[0];
      const isSel = selNode && selNode.qline === i;
      const bg = isSel ? 'rgba(232, 235, 241, 0.075)' : runN ? src(runN.source).tint : 'transparent';
      if (q.last !== bg) { q.row.style.background = bg; q.last = bg; }
    });

    // Timeline
    m.bars.forEach((b) => {
      const sp = b.sp;
      const started = tNow > sp.t0 || (!animating);
      const pw = started ? pct(Math.max(Math.min(tNow - sp.t0, sp.t1 - sp.t0) / total * 100, 0.4)) : 0;
      const ring = sp.node === S.nodeId ? '0 0 0 2px #E8EBF1' : 'none';
      const key = started + '|' + pw + '|' + ring;
      if (b.last.key !== key) {
        b.bar.style.display = started ? '' : 'none';
        b.bar.style.width = pw + '%';
        b.bar.style.boxShadow = ring;
        b.last.key = key;
      }
    });
    m.playhead.style.left = pct(tNow / total * 100) + '%';
    if (S.playing && S.followPlayhead && S.zoom > 1 && m.track) {
      const box = timelineBox();
      const px = HEAD + tNow / total * m.track;
      if (px > box.scrollLeft + box.clientWidth - 48 || px < box.scrollLeft + HEAD + 24) {
        box.scrollLeft = Math.max(0, px - HEAD - (box.clientWidth - HEAD) * 0.25);
      }
    }

    // Steuerung
    const V = vt.V;
    const play = $('play');
    play.classList.toggle('playing', S.playing);
    $('play-label').textContent = S.playing ? 'Pause' : (animating && vNow < V ? 'Weiter' : 'Abspielen');
    const scrub = $('scrub');
    scrub.max = String(Math.round(V));
    if (document.activeElement !== scrub || S.playing) scrub.value = String(Math.round(vNow));
    $('phase').textContent = animating ? (vt.inHop(vNow) ? 'Übergabe' : activeBand) : '';
    $('tlabel').textContent = fmt(tNow) + ' / ' + fmt(total) + ' ms';

    // Zustand im Detail-Panel
    const stateEl = document.querySelector('#panel-node [data-node-state]');
    if (stateEl && selNode) {
      const st = states[selNode.id];
      const S0 = src(selNode.source);
      const map = {
        pending: ['ausstehend', '#8C96A8'],
        running: ['läuft', S0.color],
        waiting: ['Antwort da, wartet auf die Ebene', S0.color],
        done: ['abgeschlossen', '#AEB6C4'],
        error: ['Fehler', ERR]
      };
      const [text, color] = map[st] || map.done;
      if (stateEl.textContent !== text) stateEl.textContent = text;
      stateEl.style.color = color;
    }
  }

  // ------------------------------------------------------------------ Wiedergabe
  function stop() {
    if (S.raf) cancelAnimationFrame(S.raf);
    S.raf = 0;
    S.playing = false;
  }

  function play(restart) {
    const m = S.m;
    if (!m) return;
    const V = m.vt.V;
    if (S.playing && !restart) { stop(); frame(); return; }
    const from = restart || S.v == null || S.v >= V ? 0 : S.v;
    const startAt = now() - from / S.tempo;
    stop();
    S.playing = true;
    S.followPlayhead = true;
    S.v = from;
    const tick = (t) => {
      const nv = (t - startAt) * S.tempo;
      if (nv >= V) { S.raf = 0; S.playing = false; S.v = null; frame(); return; }
      S.v = nv;
      frame();
      S.raf = requestAnimationFrame(tick);
    };
    S.raf = requestAnimationFrame(tick);
    frame();
  }

  // ------------------------------------------------------------------ Auswahl
  async function selectTrace(id, opts) {
    const options = opts || {};
    let trace = S.traces.get(id);
    if (!trace) {
      try {
        trace = await fetchJson(BASE + '/api/traces/' + id);
        S.traces.set(id, trace);
      } catch (e) {
        return;
      }
    }
    stop();
    S.trace = trace;
    S.m = model(trace);
    S.v = null;
    buildTree(S.m);
    buildQuery(S.m);
    buildTimeline(S.m);
    buildResponse(S.m);
    buildHeader(S.m);
    markSelected();
    const keep = options.keepNode && S.m.lay.by[S.nodeId];
    // Request-Knoten mittig: dort beginnt die Animation
    const wrap = $('tree-wrap');
    const rq = S.m.lay.nodes.find((n) => n.isReq);
    wrap.scrollTo({ left: Math.max(0, rq.x + rq.w / 2 - wrap.clientWidth / 2), top: 0 });
    selectNode(keep ? S.nodeId : focusNode(trace), { tab: S.tab, reveal: false });
    if (options.play && !REDUCED) play(true);
    else frame();
  }

  function selectNode(id, opts) {
    if (!S.m || !S.m.lay.by[id]) return;
    S.nodeId = id;
    showTab((opts && opts.tab) || 'node');
    loadDetails();
    if (!opts || opts.reveal !== false) revealNode(id);
    frame();
  }

  // Scrollt den Baum so, dass der Knoten sichtbar ist (nur wenn er es nicht schon ist).
  function revealNode(id) {
    const n = S.m && S.m.lay.by[id];
    const wrap = $('tree-wrap');
    if (!n || !wrap) return;
    const pad = 24;
    const visibleX = n.x >= wrap.scrollLeft + pad && n.x + n.w <= wrap.scrollLeft + wrap.clientWidth - pad;
    const visibleY = n.y >= wrap.scrollTop + pad && n.y + n.h <= wrap.scrollTop + wrap.clientHeight - pad;
    if (visibleX && visibleY) return;
    wrap.scrollTo({
      left: visibleX ? wrap.scrollLeft : Math.max(0, n.x + n.w / 2 - wrap.clientWidth / 2),
      top: visibleY ? wrap.scrollTop : Math.max(0, n.y + n.h / 2 - wrap.clientHeight / 2),
      behavior: REDUCED ? 'auto' : 'smooth'
    });
  }

  async function loadDetails() {
    const trace = S.trace;
    const id = S.nodeId;
    const key = trace.id + ':' + id;
    if (S.detailsKey === key) return;
    S.detailsKey = key;
    const panel = $('panel-node');
    try {
      const html = await fetchText(BASE + '/fragments/node?trace=' + encodeURIComponent(trace.id) + '&node=' + encodeURIComponent(id));
      if (S.detailsKey !== key) return;
      panel.innerHTML = html;
    } catch (e) {
      if (S.detailsKey !== key) return;
      panel.innerHTML = '<p class="hint">Details nicht verfügbar (' + esc(e.message) + ').</p>';
      return;
    }
    panel.querySelectorAll('pre.code[data-lang="sql"]').forEach((pre) => {
      pre.innerHTML = formatSql(pre.textContent).split('\n').map((l) => toksHtml(tokSql(l))).join('\n');
    });
    fillResult(panel, trace, S.m.lay.by[id]);
    frame();
  }

  // Ergebnis eines Knotens aus der Antwort: Pfad ueber Listen hinweg, nur die eigenen Felder
  function fillResult(panel, trace, n) {
    const sec = panel.querySelector('[data-result-sec]');
    const box = panel.querySelector('[data-result]');
    if (!sec || !box || !n || n.isReq || n.aux || n.step) return;
    const data = trace.response ? trace.response.data : null;
    if (!data) return;
    const segs = n.path.split('/').filter(Boolean);
    const collect = (value, rest) => {
      if (!rest.length) return value;
      if (Array.isArray(value)) return value.map((x) => collect(x, rest));
      if (value == null || typeof value !== 'object') return null;
      return collect(value[rest[0]], rest.slice(1));
    };
    const keep = n.chips || [];
    const project = (value) => {
      if (Array.isArray(value)) return value.map(project);
      if (value == null || typeof value !== 'object' || !keep.length) return value;
      const out = {};
      keep.forEach((k) => { if (k in value) out[k] = value[k]; });
      return out;
    };
    const result = project(collect(data, segs));
    if (result === undefined) return;
    box.innerHTML = jsonHtml(result, 80);
    const hint = panel.querySelector('[data-result-hint]');
    if (hint) hint.textContent = Array.isArray(collect(data, segs.slice(0, 1))) && segs.length > 1 ? 'je Elternobjekt' : '';
    sec.hidden = false;
  }

  function showTab(tab) {
    S.tab = tab;
    const node = tab === 'node';
    $('tab-node').setAttribute('aria-selected', String(node));
    $('tab-resp').setAttribute('aria-selected', String(!node));
    $('panel-node').hidden = !node;
    $('panel-resp').hidden = node;
    $('tab-hint').textContent = node ? 'Klick im Baum oder in der Timeline' : 'so kommt es beim Client an';
  }

  function clearView() {
    stop();
    S.trace = null;
    S.m = null;
    S.nodeId = null;
    S.detailsKey = null;
    $('tree').textContent = '';
    $('tree').style.height = '0px';
    $('empty').hidden = false;
    $('query').textContent = '';
    $('lanes').textContent = '';
    $('lanes').style.width = '';
    $('panel-node').innerHTML = '<p class="hint">Noch kein Knoten gewählt.</p>';
    $('panel-resp').textContent = '';
    $('hdr-kind').textContent = '';
    $('hdr-name').textContent = 'noch kein Request';
    $('hdr-status').textContent = '';
    $('hdr-total').textContent = '';
    $('stats').textContent = '';
    $('tlabel').textContent = '';
    $('phase').textContent = '';
  }

  // ------------------------------------------------------------------ Request-Liste
  function markSelected() {
    const id = S.trace ? String(S.trace.id) : null;
    document.querySelectorAll('#req-list .rq').forEach((b) => {
      b.setAttribute('aria-pressed', b.dataset.trace === id ? 'true' : 'false');
    });
  }

  async function refreshList(freshId) {
    try {
      const html = await fetchText(BASE + '/fragments/list');
      const holder = document.createElement('div');
      holder.innerHTML = html;
      const list = holder.querySelector('#req-list');
      if (list) $('req-list').replaceWith(list);
    } catch (e) {
      return;
    }
    markSelected();
    if (freshId != null) {
      const item = document.querySelector('#req-list .rq[data-trace="' + freshId + '"]');
      if (item) item.classList.add('fresh');
    }
  }

  function renderPending() {
    const box = $('pending');
    box.textContent = '';
    S.pending.forEach((p) => {
      const item = el('div', 'pending-item',
        '<span class="rq-row"><span class="rq-name mono"><span class="rq-kind">' + (p.kind === 'mutation' ? 'M' : 'Q') + '</span>' +
        esc(p.operation) + '</span><span class="rq-st"><span class="spin"></span>läuft …</span></span>');
      box.append(item);
    });
  }

  // ------------------------------------------------------------------ Live: Server-Sent Events
  function setLive(state) {
    S.live = state;
    const box = $('live');
    box.dataset.state = state;
    box.querySelector('.live-text').textContent =
      state === 'on' ? 'Live' : state === 'retry' ? 'getrennt · verbinde neu …' : state === 'off' ? 'getrennt' : 'verbinde …';
  }

  function connect() {
    if (!window.EventSource) { setLive('off'); return; }
    const es = new EventSource(BASE + '/stream');
    es.onopen = () => setLive('on');
    es.onerror = () => setLive(es.readyState === EventSource.CLOSED ? 'off' : 'retry');
    es.addEventListener('hello', () => setLive('on'));
    es.addEventListener('start', (e) => {
      const p = JSON.parse(e.data);
      S.pending.set(p.id, p);
      renderPending();
      setTimeout(() => { if (S.pending.delete(p.id)) renderPending(); }, 15000);
    });
    es.addEventListener('trace', (e) => {
      const trace = JSON.parse(e.data);
      S.traces.set(trace.id, trace);
      if (S.pending.delete(trace.id)) renderPending();
      refreshList(trace.id);
      if (S.follow || !S.trace) selectTrace(trace.id, { play: true });
    });
    es.addEventListener('cleared', () => {
      S.traces.clear();
      S.pending.clear();
      renderPending();
      clearView();
      $('req-list').innerHTML = '<p class="hint req-empty">Noch keine Requests.</p>';
      refreshList();
    });
  }

  // ------------------------------------------------------------------ Senden und Services
  async function send(button) {
    const name = button.dataset.example;
    const query = button.dataset.query;
    const status = $('send-status');
    button.classList.add('sending');
    status.classList.remove('err');
    status.textContent = name + ' wird gesendet …';
    const t0 = now();
    try {
      const res = await fetch(GRAPHQL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ query, operationName: name })
      });
      const body = await res.json();
      const errors = Array.isArray(body.errors) ? body.errors.length : 0;
      status.textContent = name + ' · HTTP ' + res.status + ' · ' + fmt(now() - t0) + ' ms im Browser' + (errors ? ' · ' + plural(errors, 'Fehler', 'Fehler') : '');
      if (S.live !== 'on') {
        // ohne SSE: Liste und neuesten Mitschnitt selbst holen
        const traces = await fetchJson(BASE + '/api/traces');
        await refreshList(traces.length ? traces[0].id : null);
        if (traces.length && (S.follow || !S.trace)) selectTrace(traces[0].id, { play: true });
      }
    } catch (e) {
      status.classList.add('err');
      status.textContent = name + ' fehlgeschlagen: ' + e.message;
    } finally {
      button.classList.remove('sending');
    }
  }

  async function toggleService(input) {
    const id = input.dataset.service;
    const available = !input.checked;
    try {
      const service = await fetchJson(BASE + '/api/services/' + encodeURIComponent(id), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ available })
      });
      input.checked = !service.available;
    } catch (e) {
      input.checked = !input.checked;
    }
  }

  // ------------------------------------------------------------------ Verdrahtung
  function wire() {
    document.querySelectorAll('input[data-service]').forEach((input) => {
      const text = input.parentElement ? input.parentElement.textContent.trim() : input.dataset.service;
      S.services.set(input.dataset.service, text.replace(/\s*ausfallen lassen\s*$/, ''));
      input.addEventListener('change', () => toggleService(input));
    });
    $('follow').addEventListener('change', (e) => { S.follow = e.target.checked; });
    document.querySelectorAll('button.ex[data-example]').forEach((b) => b.addEventListener('click', () => send(b)));
    $('clear-traces').addEventListener('click', () => {
      fetch(BASE + '/api/traces', { method: 'DELETE' }).catch(() => {});
    });
    document.addEventListener('click', (e) => {
      const rq = e.target.closest('#req-list .rq[data-trace]');
      if (rq) { selectTrace(Number(rq.dataset.trace)); return; }
      const node = e.target.closest('#tree .node[data-node]');
      if (node) { selectNode(node.dataset.node); return; }
      const bar = e.target.closest('#lanes .bar-span[data-node]');
      if (bar) { selectNode(bar.dataset.node); return; }
      const line = e.target.closest('#query .ql[data-line]');
      if (line && S.m) {
        const q = S.m.qlines[Number(line.dataset.line)];
        if (q && q.nodes.length) {
          const ids = q.nodes.map((n) => n.id);
          const k = ids.indexOf(S.nodeId);
          selectNode(ids[(k + 1) % ids.length]);
        }
      }
    });
    $('tab-node').addEventListener('click', () => { showTab('node'); });
    $('tab-resp').addEventListener('click', () => { showTab('resp'); });
    $('play').addEventListener('click', () => play(false));
    $('restart').addEventListener('click', () => play(true));
    $('scrub').addEventListener('input', (e) => {
      if (!S.m) return;
      stop();
      const v = parseFloat(e.target.value || '0');
      S.v = v >= S.m.vt.V - 1 ? null : v;
      frame();
    });
    document.querySelectorAll('.zoom button[data-zoom]').forEach((b) => {
      b.addEventListener('click', () => {
        const z = b.dataset.zoom;
        if (z === 'fit') setZoom(1);
        else setZoom(S.zoom * (z === 'in' ? 1.6 : 1 / 1.6));
      });
    });
    const tl = timelineBox();
    tl.addEventListener('wheel', (e) => {
      S.followPlayhead = false;
      if (!S.m || !(e.ctrlKey || e.metaKey)) return;
      e.preventDefault();
      setZoom(S.zoom * Math.exp(-e.deltaY * 0.0025), e.clientX - tl.getBoundingClientRect().left);
    }, { passive: false });
    tl.addEventListener('scroll', updateScrolled, { passive: true });
    const treeWrap = $('tree-wrap');
    treeWrap.addEventListener('scroll', () => treeWrap.classList.toggle('scrolled', treeWrap.scrollLeft > 2), { passive: true });
    // Ziehen verschiebt die vergroesserte Timeline; ein Klick danach waehlt keinen Knoten
    let pan = null;
    tl.addEventListener('pointerdown', (e) => {
      // Wer selbst scrollt oder zieht, bekommt die Timeline nicht wieder weggedreht
      S.followPlayhead = false;
      if (e.button !== 0 || S.zoom <= 1 || e.target.closest('.lane-head')) return;
      pan = { x: e.clientX, left: tl.scrollLeft, id: e.pointerId, moved: false };
    });
    tl.addEventListener('pointermove', (e) => {
      if (!pan || e.pointerId !== pan.id) return;
      const dx = e.clientX - pan.x;
      if (!pan.moved && Math.abs(dx) < 4) return;
      if (!pan.moved) {
        pan.moved = true;
        tl.setPointerCapture(pan.id);
        tl.classList.add('panning');
      }
      tl.scrollLeft = pan.left - dx;
    });
    const endPan = () => {
      if (!pan) return;
      if (pan.moved) {
        tl.dataset.dragged = '1';
        setTimeout(() => { delete tl.dataset.dragged; }, 80);
      }
      tl.classList.remove('panning');
      pan = null;
    };
    tl.addEventListener('pointerup', endPan);
    tl.addEventListener('pointercancel', endPan);
    tl.addEventListener('click', (e) => {
      if (tl.dataset.dragged) {
        e.stopPropagation();
        e.preventDefault();
        delete tl.dataset.dragged;
      }
    }, true);
    window.addEventListener('resize', () => {
      if (S.m && S.m.track) layoutTimeline(S.m, anchorAt());
    });
    document.querySelectorAll('.tempo button[data-tempo]').forEach((b) => {
      b.addEventListener('click', () => {
        const wasPlaying = S.playing;
        const v = S.v;
        S.tempo = Number(b.dataset.tempo) || 1;
        document.querySelectorAll('.tempo button').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
        if (wasPlaying) { S.v = v; stop(); S.v = v; play(false); }
      });
    });
  }

  async function init() {
    wire();
    connect();
    const first = document.querySelector('#req-list .rq[data-trace]');
    if (first) await selectTrace(Number(first.dataset.trace));
    else clearView();
  }

  init();
})();
