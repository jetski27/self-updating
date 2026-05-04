import { useEffect, useRef, useState } from 'react';

export type UpdateStatus =
  | 'UP_TO_DATE'
  | 'CHECKING'
  | 'DOWNLOADING'
  | 'RESTART_PENDING'
  | 'ERROR';

export interface UpdateEventState {
  status: UpdateStatus;
  version: string | null;
  downloadingFile: string | null;
  downloadingBytes: number | null;
}

const TYPE_TO_STATUS: Record<string, UpdateStatus> = {
  'up-to-date': 'UP_TO_DATE',
  checking: 'CHECKING',
  downloading: 'DOWNLOADING',
  'restart-pending': 'RESTART_PENDING',
  error: 'ERROR',
};

export function useUpdateEvents(): UpdateEventState {
  const [state, setState] = useState<UpdateEventState>({
    status: 'UP_TO_DATE',
    version: null,
    downloadingFile: null,
    downloadingBytes: null,
  });

  const backoffRef = useRef(2000);
  const sourceRef = useRef<EventSource | null>(null);
  const reconnectRef = useRef<number | null>(null);

  useEffect(() => {
    let cancelled = false;

    const connect = () => {
      if (cancelled) return;
      const es = new EventSource('/api/updates/events');
      sourceRef.current = es;

      es.onopen = () => {
        backoffRef.current = 2000;
      };

      es.onmessage = (evt) => {
        const raw = evt.data as string;
        if (!raw) return;
        const firstColon = raw.indexOf(':');
        if (firstColon < 0) return;
        const type = raw.slice(0, firstColon);
        const payload = raw.slice(firstColon + 1);
        const status = TYPE_TO_STATUS[type] ?? 'ERROR';

        if (status === 'DOWNLOADING') {
          const lastColon = payload.lastIndexOf(':');
          const file = lastColon >= 0 ? payload.slice(0, lastColon) : payload;
          const bytes = lastColon >= 0 ? Number(payload.slice(lastColon + 1)) : null;
          setState((s) => ({
            ...s,
            status,
            downloadingFile: file,
            downloadingBytes: Number.isFinite(bytes) ? bytes : null,
          }));
        } else {
          setState({
            status,
            version: payload || null,
            downloadingFile: null,
            downloadingBytes: null,
          });
        }
      };

      es.onerror = () => {
        es.close();
        if (cancelled) return;
        const delay = backoffRef.current;
        backoffRef.current = Math.min(backoffRef.current * 2, 30000);
        reconnectRef.current = window.setTimeout(connect, delay);
      };
    };

    connect();

    return () => {
      cancelled = true;
      if (reconnectRef.current) window.clearTimeout(reconnectRef.current);
      sourceRef.current?.close();
    };
  }, []);

  return state;
}
