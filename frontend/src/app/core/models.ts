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
  /** Claude Code Remote Control identifier (session_...), null when Remote Control is off. */
  remoteSessionId: string | null;
}

/** Machine with the window focus agent connected. */
export interface FocusAgent {
  machine: string;
  /** The Mac has the Claude app: the agent opens there the Remote Control sessions of other machines. */
  claudeApp: boolean;
}

/** Where the focus request opened the session. */
export interface FocusResult {
  machine: string;
  /** Opened in the Claude app (Remote Control) instead of its own window. */
  claudeApp: boolean;
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
  color: string;
  /** Position in the grid: sessions that need attention come first. */
  order: number;
}

/** Color and order of each state; the label is the translation key `status.<STATE>`. */
export const STATUS_META: Record<SessionStatus, StatusMeta> = {
  NEEDS_ATTENTION: { color: 'var(--cm-red)', order: 0 },
  WAITING_INPUT: { color: 'var(--cm-orange)', order: 1 },
  WORKING: { color: 'var(--cm-green)', order: 2 },
  BACKGROUND: { color: 'var(--cm-blue)', order: 3 },
  IDLE: { color: 'var(--cm-gray)', order: 4 },
  STALE: { color: 'var(--cm-gray)', order: 5 },
  ENDED: { color: 'var(--cm-gray)', order: 6 },
};

export function requiresAttention(status: SessionStatus): boolean {
  return status === 'NEEDS_ATTENTION' || status === 'WAITING_INPUT';
}

/** Short model name: without the "claude-" prefix and without the trailing date, if any. */
export function formatModel(model: string): string {
  return model.replace(/^claude-/, '').replace(/-\d{8}$/, '');
}

/** Translation function, as provided by I18n.t. */
export type Translate = (key: string, params?: Record<string, unknown>) => string;

/** Readable duration between an ISO instant and "now". */
export function formatElapsed(sinceIso: string, now: number, t: Translate): string {
  const seconds = Math.max(0, Math.floor((now - new Date(sinceIso).getTime()) / 1000));
  if (seconds < 60) {
    return t('time.lessThanMinute');
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return t('time.minutes', { minutes });
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return t('time.hours', { hours, minutes: String(minutes % 60).padStart(2, '0') });
  }
  const days = Math.floor(hours / 24);
  return t('time.days', { days, hours: hours % 24 });
}
