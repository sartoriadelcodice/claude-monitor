import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';

/** Ogni quanto si chiede al server se c'è una versione nuova. */
const CHECK_INTERVAL_MS = 5 * 60_000;

/**
 * Aggiornamento automatico della PWA: quando il service worker ha scaricato una nuova versione
 * la si attiva e si ricarica la pagina, senza aspettare che l'utente chiuda e riapra l'app.
 */
@Injectable({ providedIn: 'root' })
export class UpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private started = false;

  /** false con ng serve o nei browser senza service worker. */
  readonly enabled = this.swUpdate.isEnabled;
  readonly checking = signal(false);
  /** Esito dell'ultimo controllo manuale. */
  readonly lastCheck = signal<'none' | 'up-to-date' | 'updating' | 'error'>('none');

  start(): void {
    if (this.started || !this.swUpdate.isEnabled) {
      return;
    }
    this.started = true;

    this.swUpdate.versionUpdates.subscribe((event) => {
      if (event.type === 'VERSION_READY') {
        this.lastCheck.set('updating');
        void this.applyUpdate();
      }
    });
    // Cache del service worker inutilizzabile (ad esempio svuotata dal sistema): si riparte da zero
    this.swUpdate.unrecoverable.subscribe(() => document.location.reload());

    setInterval(() => void this.check(), CHECK_INTERVAL_MS);
    // iPadOS congela la PWA in background: al ritorno in primo piano si controlla subito
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        void this.check();
      }
    });
    void this.check();
  }

  /** Controlla se c'è una versione nuova; se c'è, l'aggiornamento parte da solo. */
  async check(): Promise<void> {
    if (!this.swUpdate.isEnabled || this.checking()) {
      return;
    }
    this.checking.set(true);
    try {
      const found = await this.swUpdate.checkForUpdate();
      if (!found && this.lastCheck() !== 'updating') {
        this.lastCheck.set('up-to-date');
      }
    } catch {
      // Tipicamente rete assente: si riprova al prossimo giro
      this.lastCheck.set('error');
    } finally {
      this.checking.set(false);
    }
  }

  private async applyUpdate(): Promise<void> {
    try {
      await this.swUpdate.activateUpdate();
    } finally {
      document.location.reload();
    }
  }
}
