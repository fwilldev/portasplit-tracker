import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Telegram, Branch, Check, Alert, MapPin, Clock, Search } from '../icons.jsx';

// A toggle row: label + description on the left, an iOS-style switch on the right.
function Switch({ checked, disabled, onChange, accent = 'emerald' }) {
  const on = accent === 'sky' ? 'peer-checked:bg-sky-500' : 'peer-checked:bg-emerald-500';
  return (
    <label className={`relative inline-flex shrink-0 ${disabled ? 'opacity-50 cursor-wait' : 'cursor-pointer'}`}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} className="peer sr-only" />
      <span className={`h-6 w-11 rounded-full bg-slate-200 transition ${on} after:absolute after:top-0.5 after:left-0.5 after:h-5 after:w-5 after:rounded-full after:bg-white after:shadow after:transition peer-checked:after:translate-x-5`} />
    </label>
  );
}

// Whole-seconds view of a millisecond value (intervals are stored/transported in ms, edited in s).
const toSec = (ms) => String(Math.round(ms / 1000));
const toMs = (sec) => Math.round(parseFloat(String(sec).replace(',', '.')) * 1000);

// One source's poll interval: Min / Max poll window + initial delay, all edited in seconds. Seeds its
// local fields from `it` and re-seeds whenever the saved values change (so a reset/save reflects back).
function IntervalRow({ it, onSaved, toast }) {
  const [min, setMin] = useState(toSec(it.minIntervalMs));
  const [max, setMax] = useState(toSec(it.maxIntervalMs));
  const [initial, setInitial] = useState(toSec(it.initialDelayMs));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setMin(toSec(it.minIntervalMs));
    setMax(toSec(it.maxIntervalMs));
    setInitial(toSec(it.initialDelayMs));
  }, [it.minIntervalMs, it.maxIntervalMs, it.initialDelayMs]);

  const dirty = toMs(min) !== it.minIntervalMs || toMs(max) !== it.maxIntervalMs || toMs(initial) !== it.initialDelayMs;

  const send = async (body, okMsg) => {
    setBusy(true);
    try {
      const r = await api(`/api/settings/intervals/${it.type}`, { method: 'PUT', body: JSON.stringify(body) });
      onSaved(r);
      toast(okMsg, 'success');
    } catch (e) {
      toast('Intervall speichern fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const save = () => {
    const minMs = toMs(min), maxMs = toMs(max), initialMs = toMs(initial);
    if ([minMs, maxMs, initialMs].some((v) => Number.isNaN(v))) { toast('Bitte gültige Sekundenwerte angeben', 'info'); return; }
    if (maxMs < minMs) { toast('Max darf nicht kleiner als Min sein', 'info'); return; }
    send({ minIntervalMs: minMs, maxIntervalMs: maxMs, initialDelayMs: initialMs }, `Intervall für ${it.label} gespeichert`);
  };

  const reset = () => send({ reset: true }, `Intervall für ${it.label} auf Standard zurückgesetzt`);

  const field = (label, value, onChange) => (
    <label className="block">
      <span className="text-[10px] font-semibold text-slate-400 uppercase tracking-wide">{label}</span>
      <div className="mt-0.5 flex items-center rounded-lg bg-white ring-1 ring-slate-200 focus-within:ring-2 focus-within:ring-brand-400">
        <input value={value} onChange={(e) => onChange(e.target.value)} type="number" min="0" step="5" inputMode="numeric"
          className="w-full rounded-lg bg-transparent outline-none px-2 py-1.5 text-sm text-slate-700 tabular-nums" />
        <span className="pr-2 text-[11px] text-slate-400">s</span>
      </div>
    </label>
  );

  return (
    <div className="px-3.5 py-3 space-y-2">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-slate-800 truncate">{it.label}</div>
          <div className="text-[11px] text-slate-400 truncate">{it.subtitle}</div>
        </div>
        {it.customized && <span className="shrink-0 rounded-full bg-amber-50 text-amber-700 ring-1 ring-amber-200 px-2 py-0.5 text-[10px] font-bold">angepasst</span>}
      </div>
      <div className="grid grid-cols-3 gap-2">
        {field('Min', min, setMin)}
        {field('Max', max, setMax)}
        {field('Start', initial, setInitial)}
      </div>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[10px] text-slate-400">
          Standard: {toSec(it.defaultMinIntervalMs)}-{toSec(it.defaultMaxIntervalMs)} s · Start {toSec(it.defaultInitialDelayMs)} s
        </span>
        <div className="flex items-center gap-2">
          {it.customized && (
            <button onClick={reset} disabled={busy}
              className="rounded-lg px-2.5 py-1 text-[11px] font-semibold text-slate-500 ring-1 ring-slate-200 hover:bg-slate-50 disabled:opacity-50 transition">
              Standard
            </button>
          )}
          <button onClick={save} disabled={busy || !dirty}
            className="rounded-lg bg-brand-500 hover:bg-brand-600 disabled:opacity-40 px-3 py-1 text-[11px] font-semibold text-white shadow-sm transition">
            {busy ? '…' : 'Speichern'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function SettingsModal({
  onClose, toast,
  tgTesting, onTelegramTest,
  onRadiusChanged,
}) {
  const [notif, setNotif] = useState(null);
  const [notifBusy, setNotifBusy] = useState(false);

  const [radius, setRadius] = useState(null);
  const [radiusBusy, setRadiusBusy] = useState(false);
  const [radiusSaving, setRadiusSaving] = useState(false);
  const [plz, setPlz] = useState('');
  const [km, setKm] = useState('');

  const [tr, setTr] = useState(null);
  const [trEmail, setTrEmail] = useState('');
  const [trPw, setTrPw] = useState('');
  const [trBusy, setTrBusy] = useState(false);
  const [trSaving, setTrSaving] = useState(false);

  const [intervals, setIntervals] = useState(null);

  const [ka, setKa] = useState(null);
  const [kaUrl, setKaUrl] = useState('');
  const [kaSaving, setKaSaving] = useState(false);

  useEffect(() => {
    api('/api/settings/notifications').then(setNotif).catch(() => {});
    api('/api/settings/radius').then((d) => { setRadius(d); setKm(d.km ? String(d.km) : '50'); }).catch(() => {});
    api('/api/toom-reserve/status').then(setTr).catch(() => {});
    api('/api/settings/intervals').then(setIntervals).catch(() => {});
    api('/api/settings/kleinanzeigen').then((d) => { setKa(d); setKaUrl(d.url || ''); }).catch(() => {});
  }, []);

  // Replace the one updated source in the list (returned by the PUT) without a full reload.
  const onIntervalSaved = (updated) =>
    setIntervals((list) => (list || []).map((i) => (i.type === updated.type ? updated : i)));

  const applyRadius = async (patch, { busy = false } = {}) => {
    const setBusy = busy ? setRadiusBusy : setRadiusSaving;
    setBusy(true);
    try {
      const r = await api('/api/settings/radius', { method: 'PUT', body: JSON.stringify(patch) });
      setRadius(r);
      setKm(r.km ? String(r.km) : '50');
      if (patch.plz) setPlz('');
      onRadiusChanged?.();
      return r;
    } catch (e) {
      toast('Umkreis speichern fehlgeschlagen: ' + e.message, 'error');
      throw e;
    } finally {
      setBusy(false);
    }
  };

  const toggleRadius = async (value) => {
    try {
      await applyRadius({ enabled: value }, { busy: true });
      toast(`Umkreissuche ${value ? 'aktiviert' : 'deaktiviert'}`, 'info');
    } catch { /* handled in applyRadius */ }
  };

  const saveRadiusCenter = async () => {
    const kmNum = parseFloat(String(km).replace(',', '.'));
    const patch = {};
    if (plz.trim()) patch.plz = plz.trim();
    if (!Number.isNaN(kmNum) && kmNum > 0) patch.km = kmNum;
    if (!patch.plz && patch.km === undefined) {
      toast('Bitte PLZ und/oder Radius angeben', 'info');
      return;
    }
    try {
      await applyRadius(patch);
      toast('Umkreis gespeichert', 'success');
    } catch { /* handled in applyRadius */ }
  };

  const setTelegramNotify = async (value) => {
    setNotifBusy(true);
    setNotif((n) => ({ ...n, telegramNotify: value })); // optimistic
    try {
      const r = await api('/api/settings/notifications', { method: 'PUT', body: JSON.stringify({ telegramNotify: value }) });
      setNotif(r);
      toast(`Telegram-Benachrichtigungen ${value ? 'aktiviert' : 'pausiert'}`, 'info');
    } catch (e) {
      setNotif((n) => ({ ...n, telegramNotify: !value }));
      toast('Speichern fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setNotifBusy(false);
    }
  };

  const toggleToomReserve = async (value) => {
    setTrBusy(true);
    setTr((t) => ({ ...t, featureEnabled: value })); // optimistic
    try {
      const r = await api('/api/toom-reserve/enabled', { method: 'PUT', body: JSON.stringify({ enabled: value }) });
      setTr(r);
      toast(`toom Auto-Reservierung ${value ? 'aktiviert' : 'deaktiviert'}`, 'info');
    } catch (e) {
      setTr((t) => ({ ...t, featureEnabled: !value }));
      toast('Speichern fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setTrBusy(false);
    }
  };

  const saveToomCreds = async () => {
    if (!trEmail.trim() || !trPw) { toast('E-Mail und Passwort angeben', 'info'); return; }
    setTrSaving(true);
    try {
      const r = await api('/api/toom-reserve/credentials', { method: 'PUT', body: JSON.stringify({ email: trEmail.trim(), password: trPw }) });
      setTr(r); setTrPw('');
      toast(r.loggedIn ? 'toom-Login erfolgreich' : ('toom-Login fehlgeschlagen' + (r.lastError ? ': ' + r.lastError : '')), r.loggedIn ? 'success' : 'error');
    } catch (e) {
      toast('Speichern fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setTrSaving(false);
    }
  };

  const toomLogin = async () => {
    setTrSaving(true);
    try {
      const r = await api('/api/toom-reserve/login', { method: 'POST' });
      setTr(r);
      toast(r.loggedIn ? 'toom-Login erfolgreich' : ('Login fehlgeschlagen' + (r.lastError ? ': ' + r.lastError : '')), r.loggedIn ? 'success' : 'error');
    } catch (e) {
      toast('Login fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setTrSaving(false);
    }
  };

  const saveKleinanzeigen = async () => {
    setKaSaving(true);
    try {
      const r = await api('/api/settings/kleinanzeigen', { method: 'PUT', body: JSON.stringify({ url: kaUrl.trim() }) });
      setKa(r); setKaUrl(r.url || '');
      toast(r.enabled ? 'Kleinanzeigen-Link gespeichert' : 'Kleinanzeigen deaktiviert (kein Link)', r.enabled ? 'success' : 'info');
    } catch (e) {
      toast('Speichern fehlgeschlagen: ' + e.message, 'error');
    } finally {
      setKaSaving(false);
    }
  };

  const telegramConfigured = notif?.telegramConfigured;
  const notifyOn = !!notif?.telegramNotify;

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4">
      <div className="absolute inset-0 bg-slate-900/40 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-xl card rounded-2xl ring-1 ring-slate-200 shadow-2xl animate-fade-in max-h-[90vh] overflow-y-auto">
        <div className="sticky top-0 flex items-center justify-between border-b border-slate-200 bg-white/90 backdrop-blur px-5 py-4 rounded-t-2xl">
          <h3 className="font-extrabold text-slate-900">Einstellungen</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-900">✕</button>
        </div>

        <div className="px-5 py-5 space-y-6">
          {/* ---- Telegram ---- */}
          <section>
            <div className="flex items-center gap-2.5 mb-3">
              <span className="h-9 w-9 grid place-items-center rounded-xl bg-sky-50"><Telegram className="h-4 w-4 text-sky-500" /></span>
              <div>
                <h4 className="text-sm font-extrabold text-slate-900">Telegram</h4>
                <p className="text-[11px] text-slate-400">Benachrichtigungen bei Verfügbarkeit</p>
              </div>
              <span className={`ml-auto inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold ring-1 ${
                telegramConfigured ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-slate-100 text-slate-500 ring-slate-200'}`}>
                <span className={`h-2 w-2 rounded-full ${telegramConfigured ? 'bg-emerald-500' : 'bg-slate-400'}`} />
                {telegramConfigured ? 'verbunden' : 'nicht konfiguriert'}
              </span>
            </div>

            <div className="rounded-xl ring-1 ring-slate-200 divide-y divide-slate-100">
              <div className="flex items-center justify-between gap-4 px-3.5 py-3">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-800">Benachrichtigungen senden</div>
                  <p className="text-xs text-slate-400">Aus = es werden keine Telegram-Alarme verschickt.</p>
                </div>
                <Switch checked={notifyOn} disabled={notifBusy || notif === null} onChange={setTelegramNotify} />
              </div>
              <div className="flex items-center justify-between gap-4 px-3.5 py-3">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-800">Verbindung testen</div>
                  <p className="text-xs text-slate-400">Sendet eine Testnachricht - auch wenn pausiert.</p>
                </div>
                <button onClick={onTelegramTest} disabled={tgTesting}
                  className="inline-flex items-center gap-2 rounded-lg bg-white hover:bg-slate-50 px-3 py-1.5 text-sm font-semibold text-slate-700 ring-1 ring-slate-200 shadow-sm disabled:opacity-50 transition">
                  <Telegram className="h-4 w-4 text-sky-500" />
                  {tgTesting ? 'Sende…' : 'Testen'}
                </button>
              </div>
            </div>
          </section>

          {/* ---- Umkreissuche (radius filter) ---- */}
          <section>
            <div className="flex items-center gap-2.5 mb-3">
              <span className="h-9 w-9 grid place-items-center rounded-xl bg-emerald-50"><MapPin className="h-4 w-4 text-emerald-500" /></span>
              <div>
                <h4 className="text-sm font-extrabold text-slate-900">Umkreissuche</h4>
                <p className="text-[11px] text-slate-400">Filialen nach Entfernung filtern</p>
              </div>
              {radius?.centerResolved && (
                <span className="ml-auto inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold ring-1 bg-emerald-50 text-emerald-700 ring-emerald-200">
                  <MapPin className="h-3 w-3" /> {radius.centerLabel}{radius.km ? ` · ${radius.km} km` : ''}
                </span>
              )}
            </div>

            <div className="rounded-xl ring-1 ring-slate-200 divide-y divide-slate-100">
              <div className="flex items-center justify-between gap-4 px-3.5 py-3">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-800">Umkreis aktiv</div>
                  <p className="text-xs text-slate-400 leading-snug">
                    An: nur Filialen im Umkreis werden angezeigt und melden per Telegram. Alle Filialen
                    werden weiterhin gescraped; Online-Shops sind immer dabei.
                  </p>
                </div>
                <Switch checked={!!radius?.enabled} disabled={radiusBusy || radius === null} onChange={toggleRadius} />
              </div>

              <div className="px-3.5 py-3 space-y-3">
                <div className="grid grid-cols-2 gap-3">
                  <label className="block">
                    <span className="text-xs font-semibold text-slate-500">Zentrum (PLZ)</span>
                    <input value={plz} onChange={(e) => setPlz(e.target.value)} inputMode="numeric"
                      placeholder={radius?.centerLabel || 'z. B. 10176'}
                      className="mt-1 w-full rounded-lg bg-white ring-1 ring-slate-200 focus:ring-2 focus:ring-brand-400 outline-none px-3 py-2 text-sm text-slate-700" />
                  </label>
                  <label className="block">
                    <span className="text-xs font-semibold text-slate-500">Radius (km)</span>
                    <input value={km} onChange={(e) => setKm(e.target.value)} type="number" min="1" step="5"
                      className="mt-1 w-full rounded-lg bg-white ring-1 ring-slate-200 focus:ring-2 focus:ring-brand-400 outline-none px-3 py-2 text-sm text-slate-700" />
                  </label>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <p className="text-[11px] text-slate-400 min-w-0 truncate">
                    {radius?.centerResolved
                      ? `Zentrum: ${radius.centerLabel} · ${radius.centerLat?.toFixed(3)}, ${radius.centerLon?.toFixed(3)}`
                      : 'Noch kein Zentrum gesetzt - PLZ eingeben und speichern.'}
                  </p>
                  <button onClick={saveRadiusCenter} disabled={radiusSaving}
                    className="rounded-lg bg-brand-500 hover:bg-brand-600 disabled:opacity-50 px-3.5 py-1.5 text-sm font-semibold text-white shadow-sm transition shrink-0">
                    {radiusSaving ? 'Speichern…' : 'Speichern'}
                  </button>
                </div>
                {radius && !radius.geocoderAvailable && (
                  <p className="text-[11px] text-amber-600">PLZ-Datenbank nicht geladen - bitte Koordinaten direkt setzen.</p>
                )}
                {radius?.enabled && !radius?.centerResolved && (
                  <p className="text-[11px] text-amber-600">Umkreis ist aktiv, aber kein Zentrum gesetzt - es wird noch nicht gefiltert.</p>
                )}
              </div>
            </div>
          </section>

          {/* ---- Kleinanzeigen (search-URL = on/off switch) ---- */}
          <section>
            <div className="flex items-center gap-2.5 mb-3">
              <span className="h-9 w-9 grid place-items-center rounded-xl bg-green-50"><Search className="h-4 w-4 text-green-600" /></span>
              <div>
                <h4 className="text-sm font-extrabold text-slate-900">Kleinanzeigen</h4>
                <p className="text-[11px] text-slate-400">Such-Link überwachen - kein Link = deaktiviert</p>
              </div>
              <span className={`ml-auto inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold ring-1 ${
                ka?.enabled ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-slate-100 text-slate-500 ring-slate-200'}`}>
                <span className={`h-2 w-2 rounded-full ${ka?.enabled ? 'bg-emerald-500' : 'bg-slate-400'}`} />
                {ka?.enabled ? 'aktiv' : 'deaktiviert'}
              </span>
            </div>

            <div className="rounded-xl ring-1 ring-slate-200 divide-y divide-slate-100">
              <div className="px-3.5 py-3 space-y-3">
                <label className="block">
                  <span className="text-xs font-semibold text-slate-500">Such-URL (kleinanzeigen.de)</span>
                  <input value={kaUrl} onChange={(e) => setKaUrl(e.target.value)} type="url" inputMode="url" autoComplete="off"
                    placeholder="https://www.kleinanzeigen.de/s-…  (leer = deaktiviert)"
                    className="mt-1 w-full rounded-lg bg-white ring-1 ring-slate-200 focus:ring-2 focus:ring-brand-400 outline-none px-3 py-2 text-sm text-slate-700" />
                </label>
                <div className="flex items-center justify-between gap-2">
                  <p className="text-[11px] text-slate-400 min-w-0 truncate">
                    {ka?.enabled
                      ? 'Überwacht die Suchseite und meldet frisch eingestellte Angebote.'
                      : 'Kein Link hinterlegt - die Überwachung ist aus.'}
                  </p>
                  <button onClick={saveKleinanzeigen} disabled={kaSaving || ka === null}
                    className="rounded-lg bg-brand-500 hover:bg-brand-600 disabled:opacity-50 px-3.5 py-1.5 text-sm font-semibold text-white shadow-sm transition shrink-0">
                    {kaSaving ? 'Speichern…' : 'Speichern'}
                  </button>
                </div>
              </div>
            </div>
          </section>

          {/* ---- toom Auto-Reservierung ---- */}
          <section>
            <div className="flex items-center gap-2.5 mb-3">
              <span className="h-9 w-9 grid place-items-center rounded-xl bg-emerald-50"><Branch className="h-4 w-4 text-emerald-500" /></span>
              <div>
                <h4 className="text-sm font-extrabold text-slate-900">toom Auto-Reservierung</h4>
                <p className="text-[11px] text-slate-400">Eingeloggt in den Warenkorb legen + Telegram (nur Umkreis)</p>
              </div>
              <span className={`ml-auto inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-bold ring-1 ${
                tr?.loggedIn ? 'bg-emerald-50 text-emerald-700 ring-emerald-200' : 'bg-slate-100 text-slate-500 ring-slate-200'}`}>
                <span className={`h-2 w-2 rounded-full ${tr?.loggedIn ? 'bg-emerald-500' : 'bg-slate-400'}`} />
                {tr?.loggedIn ? 'angemeldet' : 'nicht angemeldet'}
              </span>
            </div>

            <div className="rounded-xl ring-1 ring-slate-200 divide-y divide-slate-100">
              <div className="flex items-center justify-between gap-4 px-3.5 py-3">
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-slate-800">Auto-Reservierung aktiv</div>
                  <p className="text-xs text-slate-400 leading-snug">
                    Bei Verfügbarkeit wird versucht, die PortaSplit in deinen Warenkorb (als eingeloggter User) zu legen. War dies erfolgreich, bekommst du eine Telegram Nachricht - den Abschluss machst du selbst.
                  </p>
                </div>
                <Switch checked={!!tr?.featureEnabled} disabled={trBusy || tr === null} onChange={toggleToomReserve} accent="sky" />
              </div>

              <div className="px-3.5 py-3 space-y-3">
                {tr && !tr.cryptoConfigured && (
                  <p className="text-[11px] text-amber-600 flex items-center gap-1">
                    <Alert className="h-3.5 w-3.5" /> Kein Crypto-Key gesetzt (APP_TOOM_RESERVE_CRYPTO_KEY) - Login deaktiviert.
                  </p>
                )}
                <div className="flex items-center gap-2 text-xs">
                  <span className="font-semibold text-slate-500">Konto:</span>
                  {tr?.hasCredentials
                    ? <span className="inline-flex items-center gap-1 text-emerald-600 font-semibold"><Check className="h-3.5 w-3.5" /> {tr.email || 'hinterlegt'}</span>
                    : <span className="inline-flex items-center gap-1 text-slate-400 font-semibold">nicht hinterlegt</span>}
                </div>
                <label className="block">
                  <span className="text-xs font-semibold text-slate-500">toom E-Mail</span>
                  <input value={trEmail} onChange={(e) => setTrEmail(e.target.value)} type="email" autoComplete="off"
                    placeholder={tr?.email || 'name@example.de'}
                    className="mt-1 w-full rounded-lg bg-white ring-1 ring-slate-200 focus:ring-2 focus:ring-brand-400 outline-none px-3 py-2 text-sm text-slate-700" />
                </label>
                <label className="block">
                  <span className="text-xs font-semibold text-slate-500">Passwort <span className="font-normal text-slate-400">(verschlüsselt gespeichert)</span></span>
                  <input value={trPw} onChange={(e) => setTrPw(e.target.value)} type="password" autoComplete="new-password"
                    placeholder="leer lassen = unverändert"
                    className="mt-1 w-full rounded-lg bg-white ring-1 ring-slate-200 focus:ring-2 focus:ring-brand-400 outline-none px-3 py-2 text-sm text-slate-700" />
                </label>
                {tr?.lastError && <p className="text-[11px] text-amber-600">{tr.lastError}</p>}
                <div className="flex justify-end gap-2">
                  {tr?.hasCredentials && (
                    <button onClick={toomLogin} disabled={trSaving || !tr?.cryptoConfigured}
                      className="rounded-lg bg-white hover:bg-slate-50 disabled:opacity-50 px-3.5 py-1.5 text-sm font-semibold text-slate-700 ring-1 ring-slate-200 shadow-sm transition">
                      {trSaving ? 'Anmelden…' : 'Neu anmelden'}
                    </button>
                  )}
                  <button onClick={saveToomCreds} disabled={trSaving || !tr?.cryptoConfigured}
                    className="rounded-lg bg-brand-500 hover:bg-brand-600 disabled:opacity-50 px-3.5 py-1.5 text-sm font-semibold text-white shadow-sm transition">
                    {trSaving ? 'Anmelden…' : 'Speichern & anmelden'}
                  </button>
                </div>
              </div>
            </div>
          </section>

          {/* ---- Prüfintervalle (per-source poll cadence) ---- */}
          <section>
            <div className="flex items-center gap-2.5 mb-3">
              <span className="h-9 w-9 grid place-items-center rounded-xl bg-slate-100"><Clock className="h-4 w-4 text-slate-500" /></span>
              <div>
                <h4 className="text-sm font-extrabold text-slate-900">Prüfintervalle</h4>
                <p className="text-[11px] text-slate-400">Poll-Abstand je Quelle - zufällig zwischen Min und Max, plus Startverzögerung</p>
              </div>
            </div>

            <div className="rounded-xl ring-1 ring-slate-200 divide-y divide-slate-100">
              {intervals === null ? (
                <div className="px-3.5 py-4 text-xs text-slate-400">Lade Intervalle…</div>
              ) : (
                intervals.filter((it) => it.type !== 'HAGEBAU').map((it) => (
                  <IntervalRow key={it.type} it={it} onSaved={onIntervalSaved} toast={toast} />
                ))
              )}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
