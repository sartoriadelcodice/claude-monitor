export type SessionStatus = 'IDLE' | 'WORKING' | 'NEEDS_ATTENTION' | 'WAITING_INPUT' | 'BACKGROUND' | 'ENDED' | 'STALE';

export interface Session {
  id: string;
  machine: string;
  project: string;
  cwd: string | null;
  title: string | null;
  model: string | null;
  status: SessionStatus;
  statusSince: string;
  lastEventAt: string;
  lastNotificationMessage: string | null;
  lastNotificationType: string | null;
  seenAt: string | null;
  archived: boolean;
  createdAt: string;
}

export interface SessionEvent {
  id: number;
  eventName: string;
  notificationType: string | null;
  message: string | null;
  statusAfter: SessionStatus;
  receivedAt: string;
}

interface StatusMeta {
  label: string;
  color: string;
  /** Ordine nella griglia: prima le sessioni che richiedono attenzione. */
  order: number;
}

export const STATUS_META: Record<SessionStatus, StatusMeta> = {
  NEEDS_ATTENTION: { label: 'Richiede attenzione', color: 'var(--cm-red)', order: 0 },
  WAITING_INPUT: { label: 'Tocca a te', color: 'var(--cm-orange)', order: 1 },
  WORKING: { label: 'Al lavoro', color: 'var(--cm-green)', order: 2 },
  BACKGROUND: { label: 'In background', color: 'var(--cm-blue)', order: 3 },
  IDLE: { label: 'In attesa del prompt', color: 'var(--cm-gray)', order: 4 },
  STALE: { label: 'Ferma', color: 'var(--cm-gray)', order: 5 },
  ENDED: { label: 'Terminata', color: 'var(--cm-gray)', order: 6 },
};

export function requiresAttention(status: SessionStatus): boolean {
  return status === 'NEEDS_ATTENTION' || status === 'WAITING_INPUT';
}

/** Nome breve del modello: senza il prefisso "claude-" e senza l'eventuale data finale. */
export function formatModel(model: string): string {
  return model.replace(/^claude-/, '').replace(/-\d{8}$/, '');
}

/** Durata leggibile tra un istante ISO e "adesso". */
export function formatElapsed(sinceIso: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - new Date(sinceIso).getTime()) / 1000));
  if (seconds < 60) {
    return 'meno di un minuto';
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours} h ${String(minutes % 60).padStart(2, '0')} min`;
  }
  const days = Math.floor(hours / 24);
  return `${days} g ${hours % 24} h`;
}
