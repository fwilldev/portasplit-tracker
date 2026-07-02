import { LogoMark, Refresh, Settings } from '../icons.jsx';

export default function Header({ checking, onCheck, onSettings }) {
  return (
    <header className="sticky top-0 z-30 border-b border-slate-200/80 bg-white/80 backdrop-blur-xl">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 py-3 flex items-center gap-4 flex-wrap">
        <div className="flex items-center gap-3 mr-auto">
          <div className="grid place-items-center h-10 w-10 rounded-2xl bg-gradient-to-br from-sky-400 to-cyan-500 shadow-lg shadow-sky-400/30">
            <LogoMark className="h-6 w-6 text-white" />
          </div>
          <div>
            <h1 className="text-lg font-extrabold tracking-tight text-slate-900 leading-none">PortaSplit&nbsp;Tracker</h1>
            <p className="text-xs text-slate-500 mt-0.5">Verfügbarkeitstracker für die Midea PortaSplit & PortaSplit Cool</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button onClick={onCheck} disabled={checking}
            className="inline-flex items-center gap-2 rounded-xl bg-brand-500 hover:bg-brand-600 disabled:opacity-50 px-3.5 py-2 text-sm font-semibold text-white shadow-lg shadow-brand-500/25 transition">
            <Refresh className={`h-4 w-4 ${checking ? 'animate-spin' : ''}`} />
            {checking ? 'Prüfe…' : 'Jetzt prüfen'}
          </button>
          <button onClick={onSettings} title="Einstellungen"
            className="inline-flex items-center gap-2 rounded-xl bg-white hover:bg-slate-50 px-2.5 py-2 text-sm font-medium text-slate-600 ring-1 ring-slate-200 shadow-sm transition">
            <Settings className="h-4 w-4" />
            <span className="hidden md:inline">Einstellungen</span>
          </button>
        </div>
      </div>
    </header>
  );
}
