import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

import { I18n, Language } from './i18n.service';
import { SettingsService } from './settings.service';

interface PublicKeyResponse {
  enabled: boolean;
  publicKey: string | null;
}

export type PushState = 'unsupported' | 'needs-install' | 'default' | 'granted' | 'denied';

/** Registration of the device for Web Push notifications. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly settingsService = inject(SettingsService);
  private readonly i18n = inject(I18n);
  /** Language last sent to the server with the subscription; null until the first registration. */
  private sentLanguage: Language | null = null;

  readonly state = signal<PushState>(this.readState());
  /** Translation key of the last error. */
  readonly error = signal<string | null>(null);

  constructor() {
    // The server writes the notifications in the device language: tell it when the language changes
    effect(() => {
      const language = this.i18n.language();
      untracked(() => {
        if (this.sentLanguage !== null && this.sentLanguage !== language) {
          void this.syncExisting();
        }
      });
    });
  }

  /** On iPadOS push notifications only work from the PWA installed on the Home Screen. */
  private readState(): PushState {
    if (!('Notification' in window)) {
      return isIos() && !isStandalone() ? 'needs-install' : 'unsupported';
    }
    if (!this.swPush.isEnabled) {
      return 'unsupported';
    }
    return Notification.permission;
  }

  /** At startup: if permission is already granted, realigns the subscription on the backend. */
  async syncExisting(): Promise<void> {
    if (this.state() !== 'granted') {
      return;
    }
    try {
      const existing = await firstValueFrom(this.swPush.subscription);
      if (!this.settingsService.settings().pushEnabled) {
        // Turned off by the user: if a previous deactivation did not complete, finish it
        if (existing) {
          await this.remove(existing);
        }
        return;
      }
      if (existing) {
        await this.send(existing);
      } else {
        await this.enable();
      }
    } catch {
      // It will try again the next time the app opens
    }
  }

  /** Asks for permission and registers the device; to be called inside a user gesture. */
  async enable(): Promise<void> {
    this.error.set(null);
    try {
      const key = await firstValueFrom(this.http.get<PublicKeyResponse>('api/push/public-key'));
      if (!key.enabled || !key.publicKey) {
        this.error.set('push.notConfigured');
        return;
      }
      const subscription = await this.swPush.requestSubscription({ serverPublicKey: key.publicKey });
      await this.send(subscription);
      this.settingsService.update({ pushEnabled: true });
    } catch {
      this.error.set('push.enableFailed');
    } finally {
      this.state.set(this.readState());
    }
  }

  /**
   * Turns notifications off on this device: the browser permission stays, but the subscription is
   * removed from the server and from the device. The user's other devices don't change.
   */
  async disable(): Promise<void> {
    this.error.set(null);
    // The choice applies right away: if removal fails, syncExisting retries it the next time the app opens
    this.settingsService.update({ pushEnabled: false });
    try {
      const existing = await firstValueFrom(this.swPush.subscription);
      if (existing) {
        await this.remove(existing);
      }
    } catch {
      this.error.set('push.disableFailed');
    }
  }

  private async remove(subscription: PushSubscription): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>('api/push/subscriptions', { params: { endpoint: subscription.endpoint } }),
    );
    await this.swPush.unsubscribe();
  }

  private async send(subscription: PushSubscription): Promise<void> {
    const language = this.i18n.language();
    await firstValueFrom(this.http.post<void>('api/push/subscriptions', { ...subscription.toJSON(), language }));
    this.sentLanguage = language;
  }
}

function isStandalone(): boolean {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    (navigator as unknown as { standalone?: boolean }).standalone === true
  );
}

function isIos(): boolean {
  // iPadOS presents itself as a Mac: it is told apart by touch support
  return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.maxTouchPoints > 1 && /Mac/.test(navigator.userAgent));
}
