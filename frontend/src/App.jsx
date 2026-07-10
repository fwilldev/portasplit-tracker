import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api.js';
import { dateTime, relative, productLabel, eventLabel, eventTextCls, eventDot } from './format.js';
import { Snowflake, SunSnow, Search, Activity, ExternalLink, MapPin } from './icons.jsx';
import Header from './components/Header.jsx';
import ShopTable from './components/ShopTable.jsx';
import HistoryModal from './components/HistoryModal.jsx';
import SettingsModal from './components/SettingsModal.jsx';
import Toasts from './components/Toasts.jsx';
import JobsPanel from './components/JobsPanel.jsx';
import Logbook from './components/Logbook.jsx';
import { jobMeta } from './jobs.jsx';
import { BrandLogo } from './logos.jsx';

// Chains hidden from the dashboard (still checked on the backend, see JobsPanel.HIDDEN_SOURCES).
const HIDDEN_CHAINS = new Set(['Hagebau']);

export default function App() {
  const [overview, setOverview] = useState(null);
  const [events, setEvents] = useState([]);
  const [kleinanzeigen, setKleinanzeigen] = useState(null);
  const [jobs, setJobs] = useState(null);
  const [jobsAt, setJobsAt] = useState(Date.now());
  const [jobLog, setJobLog] = useState([]);
  // In-flight per-source toggles: { [type]: desiredEnabled }. Mirrored into a ref so the 3s jobs
  // poll can read it without re-creating loadJobs (and clobbering the optimistic switch state).
  const [pendingToggles, setPendingToggles] = useState({});
  const pendingRef = useRef(pendingToggles);
  pendingRef.current = pendingToggles;
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState(false);
  const [tgTesting, setTgTesting] = useState(false);

  const [search, setSearch] = useState('');
  const [chainFilter, setChainFilter] = useState('');
  const [onlyAvailable, setOnlyAvailable] = useState(false);
  

// === NEW SORTING STATE ===
  const [sortBy, setSortBy] = useState('distance'); // 'distance', 'name', or 'updated'
  const [sortDir, setSortDir] = useState('asc');    // 'asc' or 'desc'
  
  // "Alle anzeigen" bypasses the radius filter (fetches every shop). Mirrored to a ref so the periodic
  // overview poll reads the current value without re-creating loadOverview.
  const [showAll, setShowAll] = useState(false);

  const showAllRef = useRef(showAll);
  showAllRef.current = showAll;

  const [now, setNow] = useState(Date.now());

  const [toasts, setToasts] = useState([]);
  const toastSeq = useRef(0);

  const [history, setHistory] = useState({ open: false, shop: null, product: 'PORTASPLIT', events: [], loading: false });
  const [settingsOpen, setSettingsOpen] = useState(false);

  const toast = useCallback((text, type = 'info') => {
    const id = ++toastSeq.current;
    setToasts((ts) => [...ts, { id, text, type }]);
    setTimeout(() => setToasts((ts) => ts.filter((t) => t.id !== id)), 4500);
  }, []);

  const loadOverview = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api(`/api/overview${showAllRef.current ? '?all=true' : ''}`);
      setOverview(data);
    } catch (e) {
      toast('Laden fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const loadEvents = useCallback(async () => {
    try { setEvents(await api('/api/events?limit=40')); } catch { /* ignore */ }
  }, []);

  const loadKleinanzeigen = useCallback(async () => {
    try { setKleinanzeigen(await api('/api/kleinanzeigen')); } catch { /* ignore */ }
  }, []);

  const loadJobs = useCallback(async () => {
    try {
      const data = await api('/api/jobs');
      // Keep the optimistic switch position for any source whose toggle is still in flight, so the
      // periodic poll doesn't momentarily snap it back before the POST commits.
      const pend = pendingRef.current;
      if (data?.sources && Object.keys(pend).length > 0) {
        data.sources = data.sources.map((s) => (pend[s.type] !== undefined ? { ...s, enabled: pend[s.type] } : s));
      }
      setJobs(data);
      setJobsAt(Date.now());
    } catch { /* ignore */ }
  }, []);

  const loadJobLog = useCallback(async () => {
    try { setJobLog(await api('/api/jobs/log?limit=300')); } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    loadEvents();
    loadKleinanzeigen();
    loadJobs();
    loadJobLog();
    const tick = setInterval(() => setNow(Date.now()), 1000);
    const poll = setInterval(loadOverview, 20000);
    const evPoll = setInterval(loadEvents, 30000);
    const kaPoll = setInterval(loadKleinanzeigen, 20000);
    // Jobs + logbook poll fast so a running check feels live.
    const jobPoll = setInterval(() => { loadJobs(); loadJobLog(); }, 3000);
    return () => {
      clearInterval(tick); clearInterval(poll); clearInterval(evPoll);
      clearInterval(kaPoll); clearInterval(jobPoll);
    };
  }, [loadOverview, loadEvents, loadKleinanzeigen, loadJobs, loadJobLog]);

  // Initial overview load and reload whenever "Alle anzeigen" is toggled (radius filter on/off).
  useEffect(() => { loadOverview(); }, [showAll, loadOverview]);

  const runCheck = async () => {
    setChecking(true);
    try {
      // The check now runs asynchronously on the server's job queue: this returns immediately with
      // the queue snapshot, and the Jobs panel / Logbuch show progress as it runs.
      const r = await api('/api/check', { method: 'POST' });
      if (r && r.sources) { setJobs(r); setJobsAt(Date.now()); }
      toast('Prüfung gestartet - läuft im Hintergrund', 'info');
      loadJobLog();
    } catch (e) {
      toast('Konnte Prüfung nicht starten: ' + e.message, 'error');
    } finally {
      setChecking(false);
    }
  };

  // Optimistically flips a source's enabled flag.
  const flip = (j, type, enabled) => {
    if (!j) return j;
    const sources = j.sources.map((s) => (s.type === type ? { ...s, enabled } : s));
    return { ...j, sources };
  };

  const toggleWorker = async (type, enabled) => {
    if (pendingRef.current[type] !== undefined) return; // ignore re-entrant clicks while in flight
    // Mark in flight + optimistically flip the card; the pending entry also shields it from the poll.
    setPendingToggles((p) => ({ ...p, [type]: enabled }));
    setJobs((j) => flip(j, type, enabled));
    try {
      const r = await api(`/api/jobs/${type}/enabled`, { method: 'POST', body: JSON.stringify({ enabled }) });
      if (r && r.sources) { setJobs(r); setJobsAt(Date.now()); }
      toast(`${jobMeta(type).label} ${enabled ? 'aktiviert' : 'deaktiviert'}`, 'info');
    } catch (e) {
      toast('Umschalten fehlgeschlagen: ' + e.message, 'error');
      // Revert the optimistic flip; subsequent polls reconcile to server truth.
      setJobs((j) => flip(j, type, !enabled));
    } finally {
      setPendingToggles((p) => { const n = { ...p }; delete n[type]; return n; });
    }
  };

  // Manually trigger a single source now (its own worker enqueues + runs it in the background).
  const triggerWorker = async (type) => {
    try {
      const r = await api(`/api/jobs/${type}/check`, { method: 'POST' });
      if (r && r.sources) { setJobs(r); setJobsAt(Date.now()); }
      toast(`${jobMeta(type).label}: Prüfung gestartet`, 'info');
      loadJobLog();
    } catch (e) {
      toast('Konnte Prüfung nicht starten: ' + e.message, 'error');
    }
  };

  const testTelegram = async () => {
    setTgTesting(true);
    try {
      const r = await api('/api/telegram/test', { method: 'POST' });
      toast(r.message, r.sent ? 'success' : (r.configured ? 'error' : 'info'));
    } catch (e) {
      toast('Telegram-Test fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setTgTesting(false);
    }
  };


  // history
  const loadHistory = useCallback(async (shop, product) => {
    setHistory((h) => ({ ...h, loading: true }));
    try {
      const data = await api(`/api/history?shopId=${shop.id}&product=${product}`);
      setHistory((h) => ({ ...h, events: data, loading: false }));
    } catch (e) {
      toast('Verlauf konnte nicht geladen werden: ' + e.message, 'error');
      setHistory((h) => ({ ...h, loading: false }));
    }
  }, [toast]);
  const openHistory = (shop, product) => { setHistory({ open: true, shop, product, events: [], loading: true }); loadHistory(shop, product); };
  const switchHistory = (product) => { setHistory((h) => ({ ...h, product })); loadHistory(history.shop, product); };
  const closeHistory = () => setHistory((h) => ({ ...h, open: false }));

  // derived
  const visibleShops = useMemo(() => (overview?.shops || []).filter((s) => !HIDDEN_CHAINS.has(s.chain)), [overview]);
  const chains = useMemo(() => [...new Set(visibleShops.map((s) => s.chain))].sort(), [visibleShops]);
  const filteredShops = useMemo(() => {
    let list = visibleShops;cd

    // Existing filters
    const q = search.trim().toLowerCase();
    if (q) list = list.filter((s) => [s.name, s.city, s.plz, s.chain].filter(Boolean).some((v) => String(v).toLowerCase().includes(q)));
    if (chainFilter) list = list.filter((s) => s.chain === chainFilter);
    if (onlyAvailable) list = list.filter((s) => s.anyAvailable);

    // === NEW: SORTING ===
    list = [...list].sort((a, b) => {
      let cmp = 0;
      
      if (sortBy === 'name') {
        cmp = (a.name || '').localeCompare(b.name || '');
      } else if (sortBy === 'distance') {
        // Shops without distance (online-only, etc.) go to the end
        const da = a.distanceKm ?? Infinity;
        const db = b.distanceKm ?? Infinity;
        cmp = da - db;
      } else if (sortBy === 'updated') {
        const ta = new Date(a.lastCheckedAt || 0).getTime();
        const tb = new Date(b.lastCheckedAt || 0).getTime();
        cmp = ta - tb;
      }
      
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return list;
  }, [visibleShops, search, chainFilter, onlyAvailable, sortBy, sortDir]);

  // The two headline numbers: in how many shops each product is currently in stock.
  const availFor = (prod) => visibleShops
    .filter((sh) => (sh.products || []).some((p) => p.product === prod && p.available)).length;
  const availCards = [
    { label: 'PortaSplit', icon: SunSnow, count: overview ? availFor('PORTASPLIT') : null },
    { label: 'PortaSplit Cool', icon: Snowflake, count: overview ? availFor('PORTASPLIT_COOL') : null },
  ];

  const chipCls = (active) => active
    ? 'bg-brand-500 text-white ring-brand-500 shadow-sm shadow-brand-500/25'
    : 'bg-white text-slate-600 ring-slate-200 hover:bg-slate-50 hover:text-slate-900';

  return (
    <>
      <Header
        checking={checking}
        onCheck={runCheck} onSettings={() => setSettingsOpen(true)}
      />

      <main className="mx-auto max-w-7xl px-4 sm:px-6 py-6 space-y-6">
        {/* Headline: availability per product */}
        <section className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          {availCards.map((c) => {
            const hot = c.count > 0;
            const Icon = c.icon;
            return (
              <div key={c.label}
                className={`card rounded-2xl ring-1 p-5 flex items-center gap-4 animate-fade-in transition ${hot ? 'ring-emerald-200 !bg-emerald-50/70' : 'ring-slate-200'}`}>
                <span className={`h-12 w-12 grid place-items-center rounded-2xl shrink-0 ${hot ? 'bg-emerald-100' : 'bg-slate-100'}`}>
                  <Icon className={`h-6 w-6 ${hot ? 'text-emerald-600' : 'text-slate-400'}`} />
                </span>
                <div className="min-w-0">
                  <div className="flex items-baseline gap-2">
                    <span className={`font-display text-4xl font-extrabold tracking-tight ${hot ? 'text-emerald-600' : 'text-slate-900'}`}>{c.count ?? '-'}</span>
                    <span className="text-sm font-bold text-slate-700 truncate">{c.label} verfügbar</span>
                  </div>
                  <div className="text-xs text-slate-400 mt-0.5">
                    {c.count == null ? 'lädt…' : hot ? `in ${c.count} ${c.count === 1 ? 'Shop' : 'Shops'} auf Lager` : 'aktuell nirgends auf Lager'}
                  </div>
                </div>
              </div>
            );
          })}
        </section>

        {/* Per-source check jobs */}
        <JobsPanel jobs={jobs} now={now} jobsAt={jobsAt} onToggle={toggleWorker} onTrigger={triggerWorker} pending={pendingToggles} />

        {/* Controls */}
        <section className="flex flex-wrap items-center gap-3">
          <div className="relative grow min-w-[220px] max-w-sm">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
            <input value={search} onChange={(e) => setSearch(e.target.value)} type="search" placeholder="Shop, Stadt oder PLZ suchen…"
              className="w-full rounded-xl bg-white ring-1 ring-slate-200 shadow-sm focus:ring-2 focus:ring-brand-400 outline-none pl-9 pr-3 py-2 text-sm text-slate-700 placeholder:text-slate-400" />
          </div>
          <div className="flex flex-wrap items-center gap-1.5">
            <button onClick={() => setChainFilter('')} className={`rounded-full px-3 py-1.5 text-xs font-semibold ring-1 transition ${chipCls(chainFilter === '')}`}>Alle</button>
            {chains.map((ch) => (
              <button key={ch} onClick={() => setChainFilter(chainFilter === ch ? '' : ch)}
                className={`inline-flex items-center gap-1.5 rounded-full pl-1.5 pr-3 py-1 text-xs font-semibold ring-1 transition ${chipCls(chainFilter === ch)}`}>
                <BrandLogo name={ch} label={ch} className="h-5 w-5" pad="p-0.5" fallback={null} />
                {ch}
              </button>
            ))}
          </div>
          <label className="ml-auto inline-flex items-center gap-2 text-sm text-slate-600 cursor-pointer select-none">
            <input type="checkbox" checked={onlyAvailable} onChange={(e) => setOnlyAvailable(e.target.checked)} className="peer sr-only" />
            <span className="relative h-5 w-9 rounded-full bg-slate-200 transition peer-checked:bg-emerald-500 after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:shadow after:transition peer-checked:after:translate-x-4" />
            Nur verfügbare
          </label>
          {/* === NEW: SORT CONTROLS === */}
          <div className="flex items-center gap-2 ml-4">
            <span className="text-xs text-slate-500 whitespace-nowrap">Sortieren:</span>
            <button 
              onClick={() => { 
                if (sortBy === 'name') setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
                else { setSortBy('name'); setSortDir('asc'); }
              }} 
              className={`px-3 py-1 text-xs rounded-full ring-1 transition ${sortBy === 'name' ? 'bg-brand-500 text-white ring-brand-500' : 'bg-white ring-slate-200 hover:bg-slate-50'}`}>
              Shop {sortBy === 'name' && (sortDir === 'asc' ? '↑' : '↓')}
            </button>
            
            <button 
              onClick={() => { 
                if (sortBy === 'distance') setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
                else { setSortBy('distance'); setSortDir('asc'); }
              }} 
              className={`px-3 py-1 text-xs rounded-full ring-1 transition ${sortBy === 'distance' ? 'bg-brand-500 text-white ring-brand-500' : 'bg-white ring-slate-200 hover:bg-slate-50'}`}>
              Entfernung {sortBy === 'distance' && (sortDir === 'asc' ? '↑' : '↓')}
            </button>
            
            <button 
              onClick={() => { 
                if (sortBy === 'updated') setSortDir(sortDir === 'asc' ? 'desc' : 'asc');
                else { setSortBy('updated'); setSortDir('desc'); }
              }} 
              className={`px-3 py-1 text-xs rounded-full ring-1 transition ${sortBy === 'updated' ? 'bg-brand-500 text-white ring-brand-500' : 'bg-white ring-slate-200 hover:bg-slate-50'}`}>
              Aktualisiert {sortBy === 'updated' && (sortDir === 'asc' ? '↑' : '↓')}
            </button>
          </div>  
        </section>

        {overview?.radius?.active && (
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-emerald-50/70 ring-1 ring-emerald-200 px-4 py-2.5 text-sm">
            <span className="text-emerald-800 inline-flex items-center gap-2">
              <MapPin className="h-4 w-4 text-emerald-600 shrink-0" />
              Umkreis <b>{overview.radius.km} km</b> um <b>{overview.radius.centerLabel}</b>
              <span className="text-emerald-700">·</span>
              <b>{overview.radius.branchesInRadius}</b> von {overview.radius.totalBranches} Filialen
              {showAll && <span className="text-emerald-600">(alle eingeblendet)</span>}
            </span>
            <label className="inline-flex items-center gap-2 text-emerald-800 cursor-pointer select-none shrink-0">
              <input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} className="peer sr-only" />
              <span className="relative h-5 w-9 rounded-full bg-emerald-200 transition peer-checked:bg-emerald-500 after:absolute after:top-0.5 after:left-0.5 after:h-4 after:w-4 after:rounded-full after:bg-white after:shadow after:transition peer-checked:after:translate-x-4" />
              Alle anzeigen
            </label>
          </div>
        )}

        <ShopTable
          shops={filteredShops} loading={loading} hasOverview={!!overview}
          onHistory={openHistory}
        />

        {/* Kleinanzeigen new-offer watcher */}
        {kleinanzeigen?.enabled && (
          <section className="card rounded-2xl ring-1 ring-slate-200 p-4">
            <div className="flex items-center justify-between mb-3 gap-3 flex-wrap">
              <h2 className="text-sm font-bold text-slate-900 flex items-center gap-2.5">
                <BrandLogo name="KLEINANZEIGEN" label="Kleinanzeigen" className="h-8 w-8"
                  fallback={<Search className="h-4 w-4 text-brand-500" />} />
                Kleinanzeigen
                <span className="text-xs font-medium text-slate-400">
                  {kleinanzeigen.total} Angebote{kleinanzeigen.fresh > 0 ? ` · ${kleinanzeigen.fresh} neu` : ''}
                </span>
              </h2>
              <div className="flex items-center gap-3 text-xs">
                <span className={kleinanzeigen.lastOk ? 'text-slate-400' : 'text-rose-600'}>
                  {kleinanzeigen.lastOk ? 'geprüft ' : 'Lesefehler · '}{relative(kleinanzeigen.lastCheckedAt)}
                </span>
                {kleinanzeigen.searchUrl && (
                  <a href={kleinanzeigen.searchUrl} target="_blank" rel="noreferrer"
                    className="text-slate-500 hover:text-brand-600 transition inline-flex items-center gap-1">
                    Suche öffnen <ExternalLink className="h-3 w-3" />
                  </a>
                )}
              </div>
            </div>
            <ol className="space-y-1 max-h-96 overflow-y-auto pr-1">
              {kleinanzeigen.offers.map((o) => (
                <li key={o.adId} className="flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-slate-50">
                  {o.fresh
                    ? <span className="text-[10px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700 shrink-0">Neu</span>
                    : <span className="h-2 w-2 rounded-full bg-slate-300 shrink-0" />}
                  <a href={o.url} target="_blank" rel="noreferrer"
                    className="text-sm text-slate-700 font-medium truncate hover:text-brand-600">
                    {o.title || 'Anzeige'}
                  </a>
                  {o.topAd && <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded bg-amber-100 text-amber-700 shrink-0">TOP</span>}
                  {o.price && <span className="text-xs font-bold text-emerald-600 shrink-0">{o.price}</span>}
                  <span className="text-xs text-slate-400 truncate hidden sm:block">{o.location}</span>
                  <span className="ml-auto text-xs text-slate-400 tabular-nums shrink-0">{o.posted || '-'}</span>
                </li>
              ))}
              {kleinanzeigen.offers.length === 0 && (
                <li className="text-center text-slate-400 text-sm py-6">
                  {kleinanzeigen.lastCheckedAt ? 'Keine passenden Angebote gefunden.' : 'Noch nicht geprüft…'}
                </li>
              )}
            </ol>
            <p className="text-[11px] text-slate-400 mt-2">
              Telegram-Alarm nur bei frisch eingestellten Angeboten (jünger als {kleinanzeigen.freshnessMinutes} Min).
            </p>
          </section>
        )}

        {/* Recent activity */}
        <section className="card rounded-2xl ring-1 ring-slate-200 p-4">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-bold text-slate-900 flex items-center gap-2">
              <Activity className="h-4 w-4 text-brand-500" /> Letzte Änderungen
            </h2>
            <button onClick={loadEvents} className="text-xs text-slate-500 hover:text-brand-600 transition">Aktualisieren</button>
          </div>
          <ol className="space-y-1 max-h-80 overflow-y-auto pr-1">
            {events.map((e) => (
              <li key={e.id} className="flex items-center gap-3 rounded-lg px-2 py-1.5 hover:bg-slate-50">
                <span className={`h-2 w-2 rounded-full shrink-0 ${eventDot(e)}`} />
                <span className="text-xs text-slate-400 tabular-nums w-28 shrink-0">{dateTime(e.createdAt)}</span>
                <BrandLogo name={e.chain || e.shopName} label={e.shopName} className="h-5 w-5" pad="p-0.5" fallback={null} />
                <span className="text-sm text-slate-700 font-medium truncate">{e.shopName}</span>
                <span className="text-xs text-slate-400 truncate">{productLabel(e.product)}</span>
                <span className={`ml-auto text-xs font-bold shrink-0 ${eventTextCls(e)}`}>{eventLabel(e)}</span>
              </li>
            ))}
            {events.length === 0 && <li className="text-center text-slate-400 text-sm py-6">Noch keine Änderungen aufgezeichnet.</li>}
          </ol>
        </section>

        {/* Technical logbook */}
        <Logbook lines={jobLog} onRefresh={loadJobLog} />

        <footer className="text-center text-xs text-slate-400 pt-2 pb-8">
          PortaSplit Tracker
        </footer>
      </main>

      {history.open && (
        <HistoryModal
          shop={history.shop} product={history.product} events={history.events} loading={history.loading}
          onClose={closeHistory} onSwitchProduct={switchHistory}
        />
      )}
      {settingsOpen && (
        <SettingsModal
          onClose={() => setSettingsOpen(false)}
          toast={toast}
          tgTesting={tgTesting}
          onTelegramTest={testTelegram}
          onRadiusChanged={loadOverview}
        />
      )}
      <Toasts toasts={toasts} />
    </>
  );
}
