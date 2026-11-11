import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { SliderModule } from 'primeng/slider';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import { ChangePassword } from '../account/change-password';
import { AudioService } from '../../core/audio.service';
import { AuthService } from '../../core/auth.service';
import { PushService } from '../../core/push.service';
import { SessionStore } from '../../core/session-store.service';
import { SettingsService } from '../../core/settings.service';
import { UpdateService } from '../../core/update.service';
import { APP_BUILD, APP_VERSION } from '../../core/version';

/** Impostazioni della dashboard: suoni, notifiche, storico. */
@Component({
  selector: 'app-settings',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    SliderModule,
    ToggleSwitchModule,
    ChangePassword,
  ],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" header="Impostazioni"
              [style]="{ width: 'min(520px, 96vw)' }">
      <h3>Suoni</h3>
      <div class="row">
        <span>Avvisi sonori</span>
        <p-toggleswitch [ngModel]="settings().soundsEnabled === true" (ngModelChange)="toggleSounds($event)" />
      </div>
      <div class="row">
        <span>Volume</span>
        <p-slider class="slider" [min]="0" [max]="100" [step]="5" [disabled]="!settings().soundsEnabled"
                  [ngModel]="settings().volume" (ngModelChange)="settingsService.update({ volume: $event })" />
      </div>
      <div class="row">
        <span>Prova</span>
        <span class="buttons">
          <p-button label="Attenzione" size="small" severity="danger" [outlined]="true"
                    [disabled]="!audio.enabled()" (onClick)="audio.test('attention')" />
          <p-button label="Tocca a te" size="small" severity="warn" [outlined]="true"
                    [disabled]="!audio.enabled()" (onClick)="audio.test('waiting')" />
        </span>
      </div>
      <p class="muted diagnostics">Stato audio: {{ audio.diagnostics() }}</p>
      <div class="row">
        <span>Ripeti il suono di «Richiede attenzione» finché non è vista</span>
        <p-toggleswitch [ngModel]="settings().repeatEnabled"
                        (ngModelChange)="settingsService.update({ repeatEnabled: $event })" />
      </div>
      <div class="row">
        <span>Intervallo di ripetizione (secondi)</span>
        <p-inputnumber [min]="5" [max]="600" [step]="5" [showButtons]="true" [disabled]="!settings().repeatEnabled"
                       [ngModel]="settings().repeatSeconds" inputStyleClass="seconds"
                       (ngModelChange)="settingsService.update({ repeatSeconds: $event ?? 30 })" />
      </div>

      <h3>Notifiche push</h3>
      @switch (push.state()) {
        @case ('granted') {
          <div class="row">
            <span>Ricevi le notifiche su questo dispositivo</span>
            <p-toggleswitch [ngModel]="settings().pushEnabled" [disabled]="pushBusy()"
                            (ngModelChange)="togglePush($event)" />
          </div>
        }
        @case ('default') {
          <div class="row">
            <span>Ricevi una notifica quando una sessione ti aspetta</span>
            <p-button label="Attiva" size="small" (onClick)="push.enable()" />
          </div>
        }
        @case ('denied') {
          <p class="muted">Permesso negato: riattivalo dalle impostazioni di sistema.</p>
        }
        @case ('needs-install') {
          <p class="muted">Aggiungi Claude Monitor alla schermata Home per ricevere le notifiche.</p>
        }
        @default {
          <p class="muted">Non disponibili in questo browser.</p>
        }
      }
      @if (push.error()) {
        <p class="error">{{ push.error() }}</p>
      }

      <h3>Vista</h3>
      <div class="row">
        <span>Mostra anche lo storico (terminate e archiviate)</span>
        <p-toggleswitch [ngModel]="store.includeHistory()" (ngModelChange)="store.setIncludeHistory($event)" />
      </div>

      <div class="row">
        <span>Ingrandisci la card quando una sessione richiede attenzione</span>
        <p-toggleswitch [ngModel]="settings().zoomEnabled"
                        (ngModelChange)="settingsService.update({ zoomEnabled: $event })" />
      </div>
      <div class="row">
        <span>Durata dell'ingrandimento (secondi)</span>
        <p-inputnumber [min]="1" [max]="600" [step]="5" [showButtons]="true" [disabled]="!settings().zoomEnabled"
                       [ngModel]="settings().zoomSeconds" inputStyleClass="seconds"
                       (ngModelChange)="settingsService.update({ zoomSeconds: $event ?? 10 })" />
      </div>

      <h3>Focus delle finestre</h3>
      <div class="row">
        <span>Consenti alla dashboard di portare in primo piano le finestre di Claude Code</span>
        <p-toggleswitch [ngModel]="focusEnabled() === true" [disabled]="focusEnabled() === null || focusBusy()"
                        (ngModelChange)="toggleFocus($event)" />
      </div>
      <p class="muted diagnostics">
        @if (focusError()) {
          Modifica non riuscita: riprova.
        } @else if (focusEnabled() === false) {
          Spento su tutte le tue macchine e sessioni: gli agenti sono stati scollegati e il server rifiuta
          ogni richiesta di focus finché non lo riaccendi.
        } @else {
          Vale per tutte le tue macchine e sessioni. Spegnendolo gli agenti vengono scollegati subito.
        }
      </p>

      <h3>Versione</h3>
      <div class="row">
        <span>
          Claude Monitor {{ version }}
          <span class="muted build">build {{ build }}</span>
        </span>
        @if (updates.enabled) {
          <p-button label="Controlla aggiornamenti" size="small" severity="secondary"
                    [loading]="updates.checking()" (onClick)="updates.check()" />
        }
      </div>
      @switch (updates.lastCheck()) {
        @case ('up-to-date') {
          <p class="muted">È la versione più recente.</p>
        }
        @case ('updating') {
          <p class="muted">Nuova versione trovata: aggiornamento in corso…</p>
        }
        @case ('error') {
          <p class="muted">Controllo non riuscito: server non raggiungibile.</p>
        }
      }

      <h3>Account · {{ auth.user()?.username }}</h3>
      <app-change-password />

      <ng-template #footer>
        <p-button label="Esci" icon="pi pi-sign-out" severity="secondary" (onClick)="auth.logout()" />
        <p-button label="Chiudi" (onClick)="closed.emit()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    h3 {
      margin: 20px 0 8px;
      font-size: 0.85rem;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--cm-muted);
    }
    h3:first-of-type {
      margin-top: 0;
    }
    .row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      min-height: 48px;
    }
    .slider {
      flex: 0 0 180px;
    }
    .buttons {
      display: flex;
      gap: 8px;
    }
    .muted {
      margin: 0;
      color: var(--cm-muted);
    }
    .error {
      color: var(--cm-red);
    }
    .diagnostics {
      font-size: 0.8rem;
    }
    .build {
      margin-left: 6px;
      font-size: 0.85rem;
    }
    :host ::ng-deep .seconds {
      width: 5rem;
    }
  `,
})
export class Settings implements OnInit {
  protected readonly settingsService = inject(SettingsService);
  protected readonly audio = inject(AudioService);
  protected readonly push = inject(PushService);
  protected readonly store = inject(SessionStore);
  protected readonly auth = inject(AuthService);
  protected readonly updates = inject(UpdateService);
  protected readonly version = APP_VERSION;
  protected readonly build = APP_BUILD;

  readonly closed = output<void>();

  protected readonly settings = this.settingsService.settings;

  protected readonly pushBusy = signal(false);

  /** Interruttore del focus, salvato sul server: null finché non è stato letto. */
  protected readonly focusEnabled = signal<boolean | null>(null);
  protected readonly focusBusy = signal(false);
  protected readonly focusError = signal(false);

  ngOnInit(): void {
    void this.store.focusEnabled().then((enabled) => this.focusEnabled.set(enabled));
  }

  protected async toggleFocus(enabled: boolean): Promise<void> {
    const previous = this.focusEnabled();
    this.focusBusy.set(true);
    this.focusError.set(false);
    // Si mostra subito la scelta; se il salvataggio fallisce si torna al valore di prima
    this.focusEnabled.set(enabled);
    try {
      this.focusEnabled.set(await this.store.setFocusEnabled(enabled));
    } catch {
      this.focusEnabled.set(previous);
      this.focusError.set(true);
    } finally {
      this.focusBusy.set(false);
    }
  }

  protected async togglePush(enabled: boolean): Promise<void> {
    this.pushBusy.set(true);
    try {
      // Il tocco sull'interruttore è il gesto richiesto dal browser per creare la subscription
      await (enabled ? this.push.enable() : this.push.disable());
    } finally {
      this.pushBusy.set(false);
    }
  }

  protected toggleSounds(enabled: boolean): void {
    if (enabled) {
      // Il tocco sull'interruttore è il gesto che sblocca l'audio
      this.audio.enable();
    } else {
      this.audio.disable();
    }
  }
}
