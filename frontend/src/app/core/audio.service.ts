import { Injectable, computed, inject, signal } from '@angular/core';

import { SessionStatus } from './models';
import { SettingsService } from './settings.service';

type SoundKind = 'attention' | 'waiting';

const SOUND_FILES: Record<SoundKind, string> = {
  attention: 'sounds/needs-attention.wav',
  waiting: 'sounds/waiting-input.wav',
};

/** Finestra in cui più transizioni contemporanee producono un solo suono. */
const COALESCE_MS = 300;

/**
 * Avvisi sonori con Web Audio API.
 * Safari su iPadOS tiene l'audio bloccato finché l'utente non tocca la pagina, a ogni apertura:
 * dopo la prima attivazione lo sblocco avviene da solo al primo tocco qualsiasi.
 */
@Injectable({ providedIn: 'root' })
export class AudioService {
  private readonly settingsService = inject(SettingsService);

  private context: AudioContext | null = null;
  private readonly buffers = new Map<SoundKind, AudioBuffer>();
  private pending: SoundKind | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;
  /** true mentre è in corso un tentativo di sblocco. */
  private unlocking = false;
  private attempts = 0;
  /** Quante volte il sistema ha rifiutato l'avvio di Web Audio in questa apertura. */
  private rejections = 0;
  /**
   * Via di riserva: elementi <audio>, che su iPadOS usano un motore diverso da Web Audio e
   * funzionano anche quando quello non parte. Limite: su iPadOS ignorano il volume impostato.
   */
  private readonly fallback = new Map<SoundKind, HTMLAudioElement>();
  private fallbackReady = false;
  private lastError: string | null = null;

  /** true quando il contesto audio è sbloccato e può suonare. */
  readonly running = signal(false);

  /** Stato tecnico dell'audio, mostrato nelle impostazioni per capire perché non suona. */
  readonly diagnostics = signal('non inizializzato');

  /** true finché l'utente non ha mai scelto: solo allora si mostra l'invito "Attiva suoni". */
  readonly neverChosen = computed(() => this.settingsService.settings().soundsEnabled === null);

  readonly enabled = computed(() => this.settingsService.settings().soundsEnabled === true);

  /**
   * Suoni attivi ma audio ancora bloccato dal browser: a ogni apertura della pagina serve un tocco
   * qualsiasi prima che possa suonare. Non si chiede nulla all'utente, lo si segnala soltanto.
   */
  readonly waitingForGesture = computed(() => this.enabled() && !this.running());

  constructor() {
    // Dopo la prima attivazione i suoni restano attivi: il primo tocco di ogni apertura sblocca l'audio.
    // Si ascolta solo click (più keydown per la tastiera): è l'evento con cui lo sblocco funziona
    // su iPadOS. Un tentativo su touchend non veniva accettato e bloccava quello del click successivo.
    const onGesture = () => {
      if (this.enabled() && !this.running()) {
        this.unlockFallback();
        // Dopo due rifiuti del sistema non si insiste con Web Audio: resta la via di riserva
        if (this.rejections < 2) {
          this.unlockContext();
        }
      }
    };
    for (const type of ['click', 'keydown']) {
      document.addEventListener(type, onGesture, { capture: true, passive: true });
    }
  }

  /** Attiva i suoni; da chiamare dentro un gesto dell'utente. */
  enable(): void {
    this.settingsService.update({ soundsEnabled: true });
    this.unlockFallback();
    this.unlockContext();
  }

  /** Interruttore rapido: muta o riattiva i suoni; se l'audio è ancora bloccato lo sblocca. */
  toggle(): void {
    if (this.waitingForGesture()) {
      // Suoni attivi ma audio ancora bloccato: il tocco sul pulsante serve a sbloccarlo, non a mutare
      // (lo sblocco parte dall'ascoltatore del click registrato nel costruttore)
      this.unlockFallback();
    } else if (this.enabled()) {
      this.disable();
    } else {
      this.enable();
    }
  }

  /**
   * Sblocca il contesto audio: resume() chiamato dentro il gesto, poi un suono muto.
   * È la stessa sequenza del pulsante "Attiva suoni" originale, verificata su iPadOS.
   * Un tocco genera più eventi (touchend e click): si esegue un solo tentativo alla volta.
   */
  private unlockContext(): void {
    if (this.unlocking) {
      return;
    }
    const context = this.ensureContext();
    if (!context) {
      return;
    }
    this.unlocking = true;
    this.attempts++;
    const done = () => {
      this.unlocking = false;
      this.syncRunning(context);
      // iPadOS a volte rifiuta l'avvio ("Failed to start the audio device") e quel contesto resta
      // bloccato per sempre: lo si scarta, così il prossimo tocco ne crea uno nuovo
      if (this.context === context && context.state !== 'running') {
        this.context = null;
        void context.close().catch(() => undefined);
        this.syncRunning(null);
      }
    };
    // Se resume() non risponde (chiamata fuori da un gesto) il prossimo tocco deve poter riprovare
    setTimeout(done, 1000);
    context.resume().then(() => {
      try {
        const source = context.createBufferSource();
        source.buffer = context.createBuffer(1, 1, 22050);
        source.connect(context.destination);
        source.start(0);
      } catch (e) {
        this.lastError = String(e);
      }
      done();
    }, (e) => {
      this.lastError = String(e);
      this.rejections++;
      done();
    });
    void this.loadBuffers(context);
  }

  private syncRunning(context: AudioContext | null): void {
    if (context === null || this.context === context) {
      const webAudio = this.context?.state === 'running';
      this.running.set(webAudio || this.fallbackReady);
      this.diagnostics.set(
        `Web Audio ${this.context?.state ?? 'non attivo'} · riserva ${this.fallbackReady ? 'pronta' : 'non pronta'}` +
          ` · suoni caricati ${this.buffers.size}/2 · tentativi ${this.attempts}` +
          (this.lastError ? ` · errore: ${this.lastError}` : ''),
      );
    }
  }

  /**
   * Abilita gli elementi <audio> di riserva: vanno avviati una volta dentro un gesto dell'utente
   * (qui in muto), dopodiché si possono far suonare in qualsiasi momento.
   */
  private unlockFallback(): void {
    if (this.fallbackReady) {
      return;
    }
    for (const kind of Object.keys(SOUND_FILES) as SoundKind[]) {
      let element = this.fallback.get(kind);
      if (!element) {
        element = new Audio(SOUND_FILES[kind]);
        element.preload = 'auto';
        this.fallback.set(kind, element);
      }
      const el = element;
      el.muted = true;
      el.play().then(
        () => {
          el.pause();
          el.currentTime = 0;
          el.muted = false;
          if (!this.fallbackReady) {
            this.fallbackReady = true;
            this.syncRunning(null);
          }
        },
        (e) => {
          el.muted = false;
          this.lastError = `riserva ${kind}: ${String(e)}`;
        },
      );
    }
  }

  /** Prova di un suono dalle impostazioni: il tocco sul pulsante sblocca l'audio, poi si riproduce. */
  test(kind: SoundKind): void {
    this.unlockFallback();
    setTimeout(() => this.play(kind), 400);
  }

  disable(): void {
    this.settingsService.update({ soundsEnabled: false });
  }

  /** Al ritorno in primo piano iPadOS può aver sospeso il contesto: si prova a riattivarlo. */
  resumeIfSuspended(): void {
    const context = this.context;
    if (!context || !this.enabled()) {
      return;
    }
    if (context.state !== 'running') {
      // Senza await: su iPadOS questa promessa resta in sospeso finché non arriva un tocco,
      // e a quel punto ci pensa l'ascoltatore registrato nel costruttore
      void context.resume().then(
        () => this.syncRunning(context),
        () => this.syncRunning(context),
      );
    }
    this.syncRunning(context);
  }

  /** Prenota il suono per una transizione; più transizioni ravvicinate ne producono uno solo. */
  notifyTransition(status: SessionStatus): void {
    const kind: SoundKind | null =
      status === 'NEEDS_ATTENTION' ? 'attention' : status === 'WAITING_INPUT' ? 'waiting' : null;
    if (!kind) {
      return;
    }
    // Il suono più insistente ha la precedenza
    if (this.pending !== 'attention') {
      this.pending = kind;
    }
    if (this.pendingTimer === null) {
      this.pendingTimer = setTimeout(() => {
        const toPlay = this.pending;
        this.pending = null;
        this.pendingTimer = null;
        if (toPlay) {
          this.play(toPlay);
        }
      }, COALESCE_MS);
    }
  }

  play(kind: SoundKind): void {
    const settings = this.settingsService.settings();
    const context = this.context;
    const buffer = this.buffers.get(kind);
    if (!settings.soundsEnabled) {
      return;
    }
    if (!context || context.state !== 'running' || !buffer) {
      const element = this.fallback.get(kind);
      if (this.fallbackReady && element) {
        element.currentTime = 0;
        element.volume = Math.min(1, Math.max(0, settings.volume / 100));
        element.play().catch((e) => {
          this.lastError = `riserva ${kind}: ${String(e)}`;
        });
      }
      return;
    }
    const gain = context.createGain();
    gain.gain.value = Math.min(1, Math.max(0, settings.volume / 100));
    const source = context.createBufferSource();
    source.buffer = buffer;
    source.connect(gain).connect(context.destination);
    source.start(0);
  }

  private ensureContext(): AudioContext | null {
    if (this.context) {
      return this.context;
    }
    const Ctor =
      window.AudioContext ??
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) {
      return null;
    }
    const context = new Ctor();
    context.onstatechange = () => this.syncRunning(context);
    this.context = context;
    return context;
  }

  private async loadBuffers(context: AudioContext): Promise<void> {
    for (const kind of Object.keys(SOUND_FILES) as SoundKind[]) {
      if (this.buffers.has(kind)) {
        continue;
      }
      try {
        const response = await fetch(SOUND_FILES[kind]);
        const data = await response.arrayBuffer();
        this.buffers.set(kind, await context.decodeAudioData(data));
      } catch (e) {
        // File non disponibile: quel suono resta muto
        this.lastError = `caricamento ${kind}: ${String(e)}`;
      }
      this.syncRunning(context);
    }
  }
}
