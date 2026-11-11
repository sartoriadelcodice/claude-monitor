import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AudioService } from './audio.service';
import { AuthService } from './auth.service';
import { STATUS_META, Session, SessionEvent, SessionStatus, requiresAttention } from './models';
import { SettingsService } from './settings.service';

/** Senza dati (heartbeat compresi) per questo tempo la connessione SSE è considerata morta. */
const STREAM_SILENCE_MS = 50_000;
/** Riallineamento periodico con il server: fa sparire le ENDED scadute e copre eventi persi. */
const RELOAD_MS = 60_000;

/** Stato delle sessioni: caricamento, aggiornamenti in tempo reale e avvisi sonori. */
@Injectable({ providedIn: 'root' })
export class SessionStore {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly audio = inject(AudioService);
  private readonly settingsService = inject(SettingsService);

  readonly sessions = signal<Session[]>([]);
  readonly connected = signal(false);
  readonly includeHistory = signal(false);
  /** Macchine su cui un agente può portare in primo piano la finestra di una sessione. */
  readonly focusMachines = signal<ReadonlySet<string>>(new Set());
  /** Orologio aggiornato periodicamente, per i tempi mostrati sulle card. */
  readonly now = signal(Date.now());

  /** Sessioni ordinate: prima quelle che richiedono attenzione, poi per stato e anzianità. */
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

  /** Sessioni la cui card è ingrandita in questo momento (impostazione "zoomEnabled"). */
  readonly zoomed = signal<ReadonlySet<string>>(new Set());
  private readonly zoomTimers = new Map<string, ReturnType<typeof setTimeout>>();

  /** Ultimo stato noto per sessione: serve a riconoscere le transizioni. */
  private readonly knownStatus = new Map<string, SessionStatus>();
  private firstLoadDone = false;
  private started = false;
  private abort: AbortController | null = null;
  private timers: ReturnType<typeof setInterval>[] = [];
  private lastAttentionSound = 0;

  private readonly onVisibility = () => {
    if (document.visibilityState === 'visible') {
      // Ritorno in primo piano: si riattiva l'audio e si ricontrolla lo stato
      void this.audio.resumeIfSuspended();
      this.now.set(Date.now());
      void this.load();
      // iPadOS congela la PWA in background: la connessione va rifatta
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
    this.focusMachines.set(new Set());
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
      // Al primo caricamento nessun suono: si avvisa solo sulle transizioni successive
      const silent = !this.firstLoadDone;
      list.forEach((session) => this.trackTransition(session, silent));
      const ids = new Set(list.map((s) => s.id));
      [...this.knownStatus.keys()].filter((id) => !ids.has(id)).forEach((id) => this.knownStatus.delete(id));
      this.firstLoadDone = true;
      this.sessions.set(list);
    } catch {
      // Errore di rete: si ritenta al prossimo giro (il 401 è gestito dall'interceptor)
    }
  }

  /**
   * Chiede alla macchina della sessione di portare in primo piano la finestra che la ospita.
   * Restituisce false se la richiesta non arriva a un agente (macchina spenta o agente non attivo).
   */
  async focus(session: Session): Promise<boolean> {
    try {
      await firstValueFrom(this.http.post(`api/sessions/${encodeURIComponent(session.id)}/focus`, null));
      return true;
    } catch {
      void this.loadFocusMachines();
      return false;
    }
  }

  /** Interruttore del focus dell'utente; null se il server non risponde. */
  async focusEnabled(): Promise<boolean | null> {
    try {
      return (await firstValueFrom(this.http.get<{ enabled: boolean }>('api/focus/settings'))).enabled;
    } catch {
      return null;
    }
  }

  /**
   * Accende o spegne il focus per tutte le macchine e le sessioni dell'utente. Allo spegnimento il server
   * scollega gli agenti: i pulsanti spariscono subito dalle card. Restituisce lo stato salvato.
   */
  async setFocusEnabled(enabled: boolean): Promise<boolean> {
    const saved = await firstValueFrom(this.http.put<{ enabled: boolean }>('api/focus/settings', { enabled }));
    if (!saved.enabled) {
      this.focusMachines.set(new Set());
    }
    void this.loadFocusMachines();
    return saved.enabled;
  }

  /** Macchine con l'agente collegato: solo sulle loro card compare il pulsante del focus. */
  private async loadFocusMachines(): Promise<void> {
    try {
      const machines = await firstValueFrom(this.http.get<string[]>('api/focus/machines'));
      this.focusMachines.set(new Set(machines));
    } catch {
      // Errore di rete: resta l'elenco precedente fino al prossimo giro
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

  /** Applica un aggiornamento arrivato via SSE o come risposta a una modifica. */
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
    // Il suono parte solo quando lo stato cambia, non a ogni aggiornamento
    if (!silent && !session.archived && previous !== session.status && requiresAttention(session.status)) {
      if (session.status === 'NEEDS_ATTENTION') {
        this.lastAttentionSound = Date.now();
      }
      this.audio.notifyTransition(session.status);
      this.zoom(session.id);
    }
  }

  /** Ingrandisce la card per i secondi impostati; una nuova transizione fa ripartire il conteggio. */
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

  /** Ripete il suono finché c'è una sessione NEEDS_ATTENTION non ancora vista. */
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

  /** Mantiene aperta la connessione SSE, riconnettendosi con attesa crescente. */
  private async runStream(): Promise<void> {
    let delay = 1_000;
    while (this.started) {
      const abort = new AbortController();
      this.abort = abort;
      try {
        // EventSource non permette header: si usa fetch in streaming con il JWT
        const response = await fetch('api/stream', {
          headers: {
            Authorization: `Bearer ${this.auth.token() ?? ''}`,
            Accept: 'text/event-stream',
            // Evita che il service worker di Angular intercetti lo stream
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
          throw new Error(`Stream non disponibile: ${response.status}`);
        }
        this.connected.set(true);
        delay = 1_000;
        // A ogni (ri)connessione si riallinea l'elenco: copre gli eventi persi nel frattempo
        void this.load();
        await this.readStream(response.body, abort);
      } catch {
        // Connessione caduta o annullata: si riprova sotto
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
      // Blocco non valido: ignorato
    }
  }
}
