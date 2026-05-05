import { useEffect, useState } from 'react';
import { useUpdateEvents, type UpdateStatus } from '../hooks/useUpdateEvents';

interface AppInfo {
  version: string;
  appHome: string;
  lastUpdateCheck: string;
  updateStatus: UpdateStatus;
}

interface Hello {
  message: string;
  version: string;
}

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

export default function Dashboard() {
  const evt = useUpdateEvents();
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [hello, setHello] = useState<Hello | null>(null);
  const [restarting, setRestarting] = useState(false);

  useEffect(() => {
    fetch('/api/info').then((r) => r.json()).then(setInfo).catch(() => {});
    fetch('/api/hello').then((r) => r.json()).then(setHello).catch(() => {});
  }, []);

  // Live status from SSE wins over the snapshot from /api/info.
  const status: UpdateStatus = evt.status ?? info?.updateStatus ?? 'UP_TO_DATE';
  const lastCheck = info?.lastUpdateCheck;
  const pendingVersion = status === 'RESTART_PENDING' ? evt.version ?? '' : '';

  const restart = async () => {
    setRestarting(true);
    try {
      await fetch('/api/updates/restart', { method: 'POST' });
    } finally {
      setRestarting(false);
    }
  };

  return (
    <>
      {status === 'RESTART_PENDING' && (
        <div className="banner">
          <span>Version {pendingVersion} is ready. Restart now to apply.</span>
          <button onClick={restart} disabled={restarting}>
            {restarting ? 'Restarting…' : 'Restart'}
          </button>
        </div>
      )}

      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <div className="row">
            <h1 style={{ margin: 0 }}>MyApp</h1>
            <span className="badge">v{info?.version ?? '…'}</span>
          </div>
          <span className={CHIP_CLASS[status]}>{CHIP_LABEL[status]}</span>
        </div>

        <p style={{ marginTop: '0.75rem', color: 'var(--muted)' }}>Hi, this is a new update</p>

        {status === 'DOWNLOADING' && (
          <div style={{ marginTop: '0.75rem' }}>
            <div style={{ fontSize: '0.85rem', color: 'var(--muted)' }}>
              {evt.downloadingFile}
              {evt.downloadingBytes != null && ` — ${formatBytes(evt.downloadingBytes)}`}
            </div>
            <div className="progress" />
          </div>
        )}

        <div className="kv" style={{ marginTop: '1rem' }}>
          <div className="k">Last update check</div>
          <div className="v">{lastCheck ? new Date(lastCheck).toLocaleString() : '—'}</div>
          <div className="k">Greeting</div>
          <div className="v">{hello ? `${hello.message} (v${hello.version})` : '…'}</div>
        </div>
      </div>
    </>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
  return `${(n / 1024 / 1024).toFixed(2)} MiB`;
}
