import { Injectable, computed, inject, signal } from '@angular/core';

import { I18n } from './i18n.service';
import { SessionStatus } from './models';
import { SettingsService } from './settings.service';

type SoundKind = 'attention' | 'waiting';

const SOUND_FILES: Record<SoundKind, string> = {
  attention: 'sounds/needs-attention.wav',
  waiting: 'sounds/waiting-input.wav',
};

/** Window in which several simultaneous transitions produce a single sound. */
const COALESCE_MS = 300;

/**
 * Sound alerts with the Web Audio API.
 * Safari on iPadOS keeps audio locked until the user taps the page, every time the app opens:
 * after the first activation, unlocking happens on its own at the first tap anywhere.
 */
@Injectable({ providedIn: 'root' })
export class AudioService {
  private readonly settingsService = inject(SettingsService);
  private readonly i18n = inject(I18n);

  private context: AudioContext | null = null;
  private readonly buffers = new Map<SoundKind, AudioBuffer>();
  private pending: SoundKind | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;
  /** true while an unlock attempt is in progress. */
  private unlocking = false;
  private attempts = 0;
  /** How many times the system refused to start Web Audio since the app opened. */
  private rejections = 0;
  /**
   * Fallback: <audio> elements, which on iPadOS use an engine other than Web Audio and work even
   * when Web Audio does not start. Limitation: on iPadOS they ignore the volume setting.
   */
  private readonly fallback = new Map<SoundKind, HTMLAudioElement>();
  private fallbackReady = false;
  private lastError: string | null = null;

  /** true when the audio context is unlocked and can play. */
  readonly running = signal(false);

  /** Raw audio state, null until the first unlock attempt. */
  private readonly audioState = signal<{
    webAudio: AudioContextState | null;
    fallbackReady: boolean;
    loaded: number;
    attempts: number;
    error: string | null;
  } | null>(null);

  /** Technical audio status, shown in the settings to understand why nothing plays. */
  readonly diagnostics = computed(() => {
    const state = this.audioState();
    if (!state) {
      return this.i18n.t('audio.notInitialized');
    }
    const text = this.i18n.t('audio.diagnostics', {
      state: state.webAudio ?? this.i18n.t('audio.inactive'),
      fallback: this.i18n.t(state.fallbackReady ? 'audio.fallbackReady' : 'audio.fallbackNotReady'),
      loaded: state.loaded,
      attempts: state.attempts,
    });
    return state.error ? `${text} · ${this.i18n.t('audio.lastError', { error: state.error })}` : text;
  });

  /** true until the user has ever chosen: only then the "Enable sounds" prompt is shown. */
  readonly neverChosen = computed(() => this.settingsService.settings().soundsEnabled === null);

  readonly enabled = computed(() => this.settingsService.settings().soundsEnabled === true);

  /**
   * Sounds on but audio still locked by the browser: every time the page opens, any tap is needed
   * before it can play. The user is not asked anything, it is only signalled.
   */
  readonly waitingForGesture = computed(() => this.enabled() && !this.running());

  constructor() {
    // After the first activation sounds stay on: the first tap after each opening unlocks audio.
    // Only click is listened to (plus keydown for keyboards): it is the event that unlocks audio on
    // iPadOS. An attempt on touchend was not accepted and blocked the one of the following click.
    const onGesture = () => {
      if (this.enabled() && !this.running()) {
        this.unlockFallback();
        // After two refusals by the system, stop insisting on Web Audio: the fallback remains
        if (this.rejections < 2) {
          this.unlockContext();
        }
      }
    };
    for (const type of ['click', 'keydown']) {
      document.addEventListener(type, onGesture, { capture: true, passive: true });
    }
  }

  /** Enables sounds; to be called inside a user gesture. */
  enable(): void {
    this.settingsService.update({ soundsEnabled: true });
    this.unlockFallback();
    this.unlockContext();
  }

  /** Quick switch: mutes or unmutes sounds; if audio is still locked, it unlocks it. */
  toggle(): void {
    if (this.waitingForGesture()) {
      // Sounds on but audio still locked: the tap on the button unlocks it instead of muting
      // (unlocking starts from the click listener registered in the constructor)
      this.unlockFallback();
    } else if (this.enabled()) {
      this.disable();
    } else {
      this.enable();
    }
  }

  /**
   * Unlocks the audio context: resume() called inside the gesture, then a silent sound.
   * It is the same sequence as the original "Enable sounds" button, verified on iPadOS.
   * A tap fires several events (touchend and click): only one attempt runs at a time.
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
      // iPadOS sometimes refuses to start ("Failed to start the audio device") and that context stays
      // locked forever: it is discarded, so the next tap creates a new one
      if (this.context === context && context.state !== 'running') {
        this.context = null;
        void context.close().catch(() => undefined);
        this.syncRunning(null);
      }
    };
    // If resume() does not answer (called outside a gesture) the next tap must be able to retry
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
      this.audioState.set({
        webAudio: this.context?.state ?? null,
        fallbackReady: this.fallbackReady,
        loaded: this.buffers.size,
        attempts: this.attempts,
        error: this.lastError,
      });
    }
  }

  /**
   * Enables the fallback <audio> elements: they must be started once inside a user gesture
   * (muted here), after which they can play at any time.
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
          this.lastError = `fallback ${kind}: ${String(e)}`;
        },
      );
    }
  }

  /** Sound test from the settings: the tap on the button unlocks audio, then the sound plays. */
  test(kind: SoundKind): void {
    this.unlockFallback();
    setTimeout(() => this.play(kind), 400);
  }

  disable(): void {
    this.settingsService.update({ soundsEnabled: false });
  }

  /** Back in the foreground, iPadOS may have suspended the context: try to resume it. */
  resumeIfSuspended(): void {
    const context = this.context;
    if (!context || !this.enabled()) {
      return;
    }
    if (context.state !== 'running') {
      // No await: on iPadOS this promise stays pending until a tap arrives,
      // and at that point the listener registered in the constructor takes care of it
      void context.resume().then(
        () => this.syncRunning(context),
        () => this.syncRunning(context),
      );
    }
    this.syncRunning(context);
  }

  /** Schedules the sound for a transition; several close transitions produce a single one. */
  notifyTransition(status: SessionStatus): void {
    const kind: SoundKind | null =
      status === 'NEEDS_ATTENTION' ? 'attention' : status === 'WAITING_INPUT' ? 'waiting' : null;
    if (!kind) {
      return;
    }
    // The more insistent sound wins
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
          this.lastError = `fallback ${kind}: ${String(e)}`;
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
        // File unavailable: that sound stays silent
        this.lastError = `loading ${kind}: ${String(e)}`;
      }
      this.syncRunning(context);
    }
  }
}
