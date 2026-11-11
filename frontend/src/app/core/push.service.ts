import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

import { SettingsService } from './settings.service';

interface PublicKeyResponse {
  enabled: boolean;
  publicKey: string | null;
}

export type PushState = 'unsupported' | 'needs-install' | 'default' | 'granted' | 'denied';

/** Registrazione del dispositivo alle notifiche Web Push. */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly http = inject(HttpClient);
  private readonly swPush = inject(SwPush);
  private readonly settingsService = inject(SettingsService);

  readonly state = signal<PushState>(this.readState());
  readonly error = signal<string | null>(null);

  /** Su iPadOS le notifiche push funzionano solo dalla PWA installata nella schermata Home. */
  private readState(): PushState {
    if (!('Notification' in window)) {
      return isIos() && !isStandalone() ? 'needs-install' : 'unsupported';
    }
    if (!this.swPush.isEnabled) {
      return 'unsupported';
    }
    return Notification.permission;
  }

  /** All'avvio: se il permesso c'è già, riallinea la subscription sul backend. */
  async syncExisting(): Promise<void> {
    if (this.state() !== 'granted') {
      return;
    }
    try {
      const existing = await firstValueFrom(this.swPush.subscription);
      if (!this.settingsService.settings().pushEnabled) {
        // Spente dall'utente: se una disattivazione precedente non è andata a buon fine, la si completa
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
      // Ritenterà alla prossima apertura
    }
  }

  /** Chiede il permesso e registra il dispositivo; da chiamare dentro un gesto dell'utente. */
  async enable(): Promise<void> {
    this.error.set(null);
    try {
      const key = await firstValueFrom(this.http.get<PublicKeyResponse>('api/push/public-key'));
      if (!key.enabled || !key.publicKey) {
        this.error.set('Notifiche push non configurate sul server (chiavi VAPID mancanti).');
        return;
      }
      const subscription = await this.swPush.requestSubscription({ serverPublicKey: key.publicKey });
      await this.send(subscription);
      this.settingsService.update({ pushEnabled: true });
    } catch {
      this.error.set('Non è stato possibile attivare le notifiche.');
    } finally {
      this.state.set(this.readState());
    }
  }

  /**
   * Spegne le notifiche su questo dispositivo: il permesso del browser resta, ma la subscription
   * viene rimossa dal server e dal dispositivo. Gli altri dispositivi dell'utente non cambiano.
   */
  async disable(): Promise<void> {
    this.error.set(null);
    // La scelta vale subito: se la rimozione fallisce, syncExisting la ritenta alla prossima apertura
    this.settingsService.update({ pushEnabled: false });
    try {
      const existing = await firstValueFrom(this.swPush.subscription);
      if (existing) {
        await this.remove(existing);
      }
    } catch {
      this.error.set('Non è stato possibile completare la disattivazione: verrà ritentata alla prossima apertura.');
    }
  }

  private async remove(subscription: PushSubscription): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>('api/push/subscriptions', { params: { endpoint: subscription.endpoint } }),
    );
    await this.swPush.unsubscribe();
  }

  private async send(subscription: PushSubscription): Promise<void> {
    await firstValueFrom(this.http.post<void>('api/push/subscriptions', subscription.toJSON()));
  }
}

function isStandalone(): boolean {
  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    (navigator as unknown as { standalone?: boolean }).standalone === true
  );
}

function isIos(): boolean {
  // iPadOS si presenta come Mac: si distingue dal supporto touch
  return /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.maxTouchPoints > 1 && /Mac/.test(navigator.userAgent));
}
