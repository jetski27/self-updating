import { useEffect, useState } from 'react';
import { useUpdateEvents, type UpdateStatus } from '../hooks/useUpdateEvents';

interface AppInfo {
  version: string;
  appHome: string;
  lastUpdateCheck: string;
  updateStatus: UpdateStatus;
}

const OWNER = 'jetski27';
const REPO = 'self-updating';

const CHIP_CLASS: Record<UpdateStatus, string> = {
  UP_TO_DATE: 'chip green',
  CHECKING: 'chip blue',
  DOWNLOADING: 'chip amber',
  RESTART_PENDING: 'chip amber',
  ERROR: 'chip red',
};

const CHIP_LABEL: Record<UpdateStatus, string> = {
  UP_TO_DATE: 'Up to date',
  CHECKING: 'Checking…',
  DOWNLOADING: 'Downloading',
  RESTART_PENDING: 'Restart required',
  ERROR: 'Error',
};

export default function Settings() {
  const evt = useUpdateEvents();
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [restarting, setRestarting] = useState(false);

  useEffect(() => {
    fetch('/api/info').then((r) => r.json()).then(setInfo).catch(() => {});
  }, []);

  const status: UpdateStatus = evt.status ?? info?.updateStatus ?? 'UP_TO_DATE';
  const pendingVersion = status === 'RESTART_PENDING' ? evt.version ?? '' : '';
  const lastCheck = info?.lastUpdateCheck;

  const checkNow = async () => {
    setChecking(true);
    try {
      await fetch('/api/updates/check', { method: 'POST' });
      const fresh = await fetch('/api/info').then((r) => r.json());
      setInfo(fresh);
    } finally {
      setChecking(false);
    }
  };

  const restart = async () => {
    setRestarting(true);
    try {
      await fetch('/api/updates/restart', { method: 'POST' });
    } finally {
      setRestarting(false);
    }
  };

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0 }}>Settings</h2>
        <span className={CHIP_CLASS[status]}>{CHIP_LABEL[status]}</span>
      </div>

      <div className="kv" style={{ marginTop: '1rem' }}>
        <div className="k">GitHub owner</div>
        <div className="v">{OWNER}</div>
        <div className="k">GitHub repo</div>
        <div className="v">{REPO}</div>
        <div className="k">app.home</div>
        <div className="v">{info?.appHome ?? '—'}</div>
        <div className="k">Installed version</div>
        <div className="v">{info?.version ?? '—'}</div>
        <div className="k">Latest version</div>
        <div className="v">
          {pendingVersion
            ? `${pendingVersion} — restart required`
            : status === 'UP_TO_DATE' && info?.version
            ? `${info.version} (up to date)`
            : '—'}
        </div>
        <div className="k">Last update check</div>
        <div className="v">{lastCheck ? new Date(lastCheck).toLocaleString() : '—'}</div>
      </div>

      <div className="row" style={{ marginTop: '1rem', gap: '0.5rem' }}>
        <button onClick={checkNow} disabled={checking}>
          {checking ? 'Checking…' : 'Check for updates now'}
        </button>
        {status === 'RESTART_PENDING' && (
          <button onClick={restart} disabled={restarting}>
            {restarting ? 'Restarting…' : `Restart to apply v${pendingVersion}`}
          </button>
        )}
      </div>
    </div>
  );
}
