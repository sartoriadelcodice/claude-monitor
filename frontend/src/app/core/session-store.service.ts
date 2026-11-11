import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AudioService } from './audio.service';
import { AuthService } from './auth.service';
import {
  FocusAgent,
  FocusResult,
  STATUS_META,
  Session,
  SessionEvent,
  SessionStatus,
  requiresAttention,
} from './models';
import { SettingsService } from './settings.service';

/** With no data (heartbeats included) for this long, the SSE connection is considered dead. */
const STREAM_SILENCE_MS = 50_000;
/** Periodic realignment with the server: removes expired ENDED sessions and covers lost events. */
const RELOAD_MS = 60_000;

/** Session state: loading, real-time updates and sound alerts. */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly audio = inject(AudioService);
  private readonly settingsService = inject(SettingsService);

  readonly sessions = signal<Session[]>([]);
  readonly connected = signal(false);
  readonly includeHistory = signal(false);
  /** Machines with the window focus agent connected. */
  readonly focusAgents = signal<readonly FocusAgent[]>([]);
  /** Machines where an agent can bring a session window to the front. */
  readonly focusMachines = computed<ReadonlySet<string>>(() => new Set(this.focusAgents().map((a) => a.machine)));
  /** Machines where the agent can open Remote Control sessions in the Claude app. */
  readonly claudeAppMachines = computed<ReadonlySet<string>>(
    () => new Set(this.focusAgents().filter((a) => a.claudeApp).map((a) => a.machine)),
  );
  /** Clock updated periodically, for the times shown on the cards. */
  readonly now = signal(Date.now());

  /** Sorted sessions: those that need attention first, then by state and age. */
  readonly sorted = computed(() =>
    [...this.sessions()].sort(
      (a, b) =>
        STATUS_META[a.status].order - STATUS_META[b.status].order ||
        b.statusSince.localeCompare(a.statusSince),
    ),
  );

  readonly attentionCount = computed(
    () => this.sessions().filter((s) => !s.archived && requiresAttention(s.status)).length,
  );

  /** Sessions whose card is currently enlarged ("zoomEnabled" setting). */
  readonly zoomed = signal<ReadonlySet<string>>(new Set());
  private readonly zoomTimers = new Map<string, ReturnType<typeof setTimeout>>();

  /** Last known state per session: used to detect transitions. */
  private readonly knownStatus = new Map<string, SessionStatus>();
  private firstLoadDone = false;
  private started = false;
  private abort: AbortController | null = null;
  private timers: ReturnType<typeof setInterval>[] = [];
  private lastAttentionSound = 0;

  private readonly onVisibility = () => {
    if (document.visibilityState === 'visible') {
      // Back in the foreground: resume audio and check the state again
      void this.audio.resumeIfSuspended();
      this.now.set(Date.now());
      void this.load();
      // iPadOS freezes the PWA in the background: the connection must be reopened
      this.abort?.abort();
    }
  };

  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    document.addEventListener('visibilitychange', this.onVisibility);
    this.timers = [
      setInterval(() => this.now.set(Date.now()), 10_000),
      setInterval(() => void this.load(), RELOAD_MS),
      setInterval(() => this.repeatAttentionSound(), 1_000),
    ];
    void this.runStream();
  }

  stop(): void {
    this.started = false;
    document.removeEventListener('visibilitychange', this.onVisibility);
    this.timers.forEach(clearInterval);
    this.timers = [];
    this.abort?.abort();
    this.connected.set(false);
    this.knownStatus.clear();
    this.zoomTimers.forEach(clearTimeout);
    this.zoomTimers.clear();
    this.zoomed.set(new Set());
    this.firstLoadDone = false;
    this.sessions.set([]);
    this.focusAgents.set([]);
  }

  setIncludeHistory(value: boolean): void {
    this.includeHistory.set(value);
    void this.load();
  }

  async load(): Promise<void> {
    void this.loadFocusMachines();
    try {
      const list = await firstValueFrom(
        this.http.get<Session[]>('api/sessions', {
          params: { includeHistory: this.includeHistory() },
        }),
      );
      // No sound on the first load: only later transitions trigger alerts
      const silent = !this.firstLoadDone;
      list.forEach((session) => this.trackTransition(session, silent));
      const ids = new Set(list.map((s) => s.id));
      [...this.knownStatus.keys()].filter((id) => !ids.has(id)).forEach((id) => this.knownStatus.delete(id));
      this.firstLoadDone = true;
      this.sessions.set(list);
    } catch {
      // Network error: retry next round (401 is handled by the interceptor)
    }
  }

  /**
   * Brings the session to the front on the Mac in use: its window, or the Claude app of another Mac when the
   * session is under Remote Control. The server makes the choice. Returns where it was opened, or null when
   * the request does not reach an agent (machine off or agent not running).
   */
  async focus(session: Session): Promise<FocusResult | null> {
    try {
      return await firstValueFrom(
        this.http.post<FocusResult>(`api/sessions/${encodeURIComponent(session.id)}/focus`, null),
      );
    } catch {
      void this.loadFocusMachines();
      return null;
    }
  }

  /** The user's window focus switch; null if the server does not answer. */
  async focusEnabled(): Promise<boolean | null> {
    try {
      return (await firstValueFrom(this.http.get<{ enabled: boolean }>('api/focus/settings'))).enabled;
    } catch {
      return null;
    }
  }

  /**
   * Turns window focus on or off for all the user's machines and sessions. When it is turned off the server
   * disconnects the agents: the buttons disappear from the cards right away. Returns the saved state.
   */
  async setFocusEnabled(enabled: boolean): Promise<boolean> {
    const saved = await firstValueFrom(this.http.put<{ enabled: boolean }>('api/focus/settings', { enabled }));
    if (!saved.enabled) {
      this.focusAgents.set([]);
    }
    void this.loadFocusMachines();
    return saved.enabled;
  }

  /** Machines with the agent connected: they decide which cards show the focus button. */
  private async loadFocusMachines(): Promise<void> {
    try {
      this.focusAgents.set(await firstValueFrom(this.http.get<FocusAgent[]>('api/focus/agents')));
    } catch {
      // Network error: keep the previous list until the next round
    }
  }

  markSeen(session: Session): Promise<void> {
    return this.patch(session.id, { seen: true });
  }

  setArchived(session: Session, archived: boolean): Promise<void> {
    return this.patch(session.id, { archived });
  }

  events(sessionId: string): Promise<SessionEvent[]> {
    return firstValueFrom(this.http.get<SessionEvent[]>(`api/sessions/${encodeURIComponent(sessionId)}/events`));
  }

  private async patch(id: string, body: { seen?: boolean; archived?: boolean }): Promise<void> {
    const updated = await firstValueFrom(
      this.http.patch<Session>(`api/sessions/${encodeURIComponent(id)}`, body),
    );
    this.apply(updated);
  }

  /** Applies an update received via SSE or as the response to a change. */
  private apply(session: Session): void {
    this.trackTransition(session, false);
    const hidden = session.archived && !this.includeHistory();
    this.sessions.update((list) => {
      const others = list.filter((s) => s.id !== session.id);
      return hidden ? others : [...others, session];
    });
  }

  private trackTransition(session: Session, silent: boolean): void {
    const previous = this.knownStatus.get(session.id);
    this.knownStatus.set(session.id, session.status);
    // The sound plays only when the state changes, not on every update
    if (!silent && !session.archived && previous !== session.status && requiresAttention(session.status)) {
      if (session.status === 'NEEDS_ATTENTION') {
        this.lastAttentionSound = Date.now();
      }
      this.audio.notifyTransition(session.status);
      this.zoom(session.id);
    }
  }

  /** Enlarges the card for the configured seconds; a new transition restarts the countdown. */
  private zoom(id: string): void {
    const settings = this.settingsService.settings();
    if (!settings.zoomEnabled || settings.zoomSeconds <= 0) {
      return;
    }
    clearTimeout(this.zoomTimers.get(id));
    this.zoomed.update((set) => new Set(set).add(id));
    this.zoomTimers.set(
      id,
      setTimeout(() => {
        this.zoomTimers.delete(id);
        this.zoomed.update((set) => {
          const next = new Set(set);
          next.delete(id);
          return next;
        });
      }, settings.zoomSeconds * 1000),
    );
  }

  /** Repeats the sound while there is a NEEDS_ATTENTION session not yet seen. */
  private repeatAttentionSound(): void {
    const settings = this.settingsService.settings();
    if (!settings.repeatEnabled || settings.repeatSeconds <= 0) {
      return;
    }
    const pending = this.sessions().some((s) => s.status === 'NEEDS_ATTENTION' && !s.seenAt && !s.archived);
    if (pending && Date.now() - this.lastAttentionSound >= settings.repeatSeconds * 1000) {
      this.lastAttentionSound = Date.now();
      this.audio.play('attention');
    }
  }

  /** Keeps the SSE connection open, reconnecting with increasing delays. */
  private async runStream(): Promise<void> {
    let delay = 1_000;
    while (this.started) {
      const abort = new AbortController();
      this.abort = abort;
      try {
        // EventSource does not allow headers: use streaming fetch with the JWT
        const response = await fetch('api/stream', {
          headers: {
            Authorization: `Bearer ${this.auth.token() ?? ''}`,
            Accept: 'text/event-stream',
            // Prevents the Angular service worker from intercepting the stream
            'ngsw-bypass': 'true',
          },
          cache: 'no-store',
          signal: abort.signal,
        });
        if (response.status === 401) {
          this.auth.logout();
          return;
        }
        if (!response.ok || !response.body) {
          throw new Error(`Stream unavailable: ${response.status}`);
        }
        this.connected.set(true);
        delay = 1_000;
        // On every (re)connection the list is realigned: it covers the events lost meanwhile
        void this.load();
        await this.readStream(response.body, abort);
      } catch {
        // Connection dropped or aborted: retry below
      }
      this.connected.set(false);
      if (!this.started) {
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, delay));
      delay = Math.min(delay * 2, 15_000);
    }
  }

  private async readStream(body: ReadableStream<Uint8Array>, abort: AbortController): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let lastData = Date.now();
    const watchdog = setInterval(() => {
      if (Date.now() - lastData > STREAM_SILENCE_MS) {
        abort.abort();
      }
    }, 5_000);
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          return;
        }
        lastData = Date.now();
        buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
        let separator: number;
        while ((separator = buffer.indexOf('\n\n')) >= 0) {
          this.handleStreamBlock(buffer.slice(0, separator));
          buffer = buffer.slice(separator + 2);
        }
      }
    } finally {
      clearInterval(watchdog);
    }
  }

  private handleStreamBlock(block: string): void {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        data.push(line.slice(5).trimStart());
      }
    }
    if (event !== 'session' || data.length === 0) {
      return;
    }
    try {
      this.apply(JSON.parse(data.join('\n')) as Session);
    } catch {
      // Invalid block: ignored
    }
  }
}
