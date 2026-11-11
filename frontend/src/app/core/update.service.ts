import { Injectable, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';

/** How often the server is asked for a new version. */
const CHECK_INTERVAL_MS = 5 * 60_000;

/**
 * Automatic PWA update: when the service worker has downloaded a new version, it is activated
 * and the page reloads, without waiting for the user to close and reopen the app.
 */
@Injectable({ providedIn: 'root' })
export class UpdateService {
  private readonly swUpdate = inject(SwUpdate);
  private started = false;

  /** false with ng serve or in browsers without service workers. */
  readonly enabled = this.swUpdate.isEnabled;
  readonly checking = signal(false);
  /** Outcome of the last manual check. */
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
    // Service worker cache unusable (for example cleared by the system): start from scratch
    this.swUpdate.unrecoverable.subscribe(() => document.location.reload());

    setInterval(() => void this.check(), CHECK_INTERVAL_MS);
    // iPadOS freezes the PWA in the background: check right away when it comes back to the foreground
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        void this.check();
      }
    });
    void this.check();
  }

  /** Checks for a new version; if there is one, the update starts on its own. */
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
      // Typically no network: try again next round
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
