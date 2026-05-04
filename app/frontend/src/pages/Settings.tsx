import { useEffect, useState } from 'react';

interface AppInfo {
  version: string;
  appHome: string;
  lastUpdateCheck: string;
  updateStatus: string;
}

const OWNER = 'jetski27';
const REPO = 'self-updating';

export default function Settings() {
  const [info, setInfo] = useState<AppInfo | null>(null);
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    fetch('/api/info').then((r) => r.json()).then(setInfo).catch(() => {});
  }, []);

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

  return (
    <div className="card">
      <h2>Settings</h2>
      <div className="kv">
        <div className="k">GitHub owner</div>
        <div className="v">{OWNER}</div>
        <div className="k">GitHub repo</div>
        <div className="v">{REPO}</div>
        <div className="k">app.home</div>
        <div className="v">{info?.appHome ?? '—'}</div>
        <div className="k">Version</div>
        <div className="v">{info?.version ?? '—'}</div>
      </div>
      <div style={{ marginTop: '1rem' }}>
        <button onClick={checkNow} disabled={checking}>
          {checking ? 'Checking…' : 'Check for updates now'}
        </button>
      </div>
    </div>
  );
}
