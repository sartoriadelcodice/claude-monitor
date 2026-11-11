import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { SliderModule } from 'primeng/slider';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import { ChangePassword } from '../account/change-password';
import { AudioService } from '../../core/audio.service';
import { AuthService } from '../../core/auth.service';
import { I18n, LANGUAGES, LANGUAGE_NAMES, LanguageSetting, browserLanguage } from '../../core/i18n.service';
import { PushService } from '../../core/push.service';
import { SessionStore } from '../../core/session-store.service';
import { SettingsService } from '../../core/settings.service';
import { UpdateService } from '../../core/update.service';
import { APP_BUILD, APP_VERSION } from '../../core/version';

/** Dashboard settings: language, sounds, notifications, view, window focus, version and account. */
@Component({
  selector: 'app-settings',
  imports: [
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    SelectModule,
    SliderModule,
    ToggleSwitchModule,
    ChangePassword,
  ],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" [header]="'settings.title' | transloco"
              [style]="{ width: 'min(520px, 96vw)' }">
      <h3>{{ 'settings.languageTitle' | transloco }}</h3>
      <div class="row">
        <span>{{ 'settings.language' | transloco }}</span>
        <p-select [options]="languageOptions()" optionLabel="label" optionValue="value" appendTo="body"
                  [ngModel]="i18n.setting()" (ngModelChange)="setLanguage($event)"
                  [ariaLabel]="'settings.language' | transloco" />
      </div>

      <h3>{{ 'settings.soundsTitle' | transloco }}</h3>
      <div class="row">
        <span>{{ 'settings.soundAlerts' | transloco }}</span>
        <p-toggleswitch [ngModel]="settings().soundsEnabled === true" (ngModelChange)="toggleSounds($event)" />
      </div>
      <div class="row">
        <span>{{ 'settings.volume' | transloco }}</span>
        <p-slider class="slider" [min]="0" [max]="100" [step]="5" [disabled]="!settings().soundsEnabled"
                  [ngModel]="settings().volume" (ngModelChange)="settingsService.update({ volume: $event })" />
      </div>
      <div class="row">
        <span>{{ 'settings.test' | transloco }}</span>
        <span class="buttons">
          <p-button [label]="'settings.testAttention' | transloco" size="small" severity="danger" [outlined]="true"
                    [disabled]="!audio.enabled()" (onClick)="audio.test('attention')" />
          <p-button [label]="'settings.testWaiting' | transloco" size="small" severity="warn" [outlined]="true"
                    [disabled]="!audio.enabled()" (onClick)="audio.test('waiting')" />
        </span>
      </div>
      <p class="muted diagnostics">{{ 'settings.audioStatus' | transloco: { status: audio.diagnostics() } }}</p>
      <div class="row">
        <span>{{ 'settings.repeat' | transloco }}</span>
        <p-toggleswitch [ngModel]="settings().repeatEnabled"
                        (ngModelChange)="settingsService.update({ repeatEnabled: $event })" />
      </div>
      <div class="row">
        <span>{{ 'settings.repeatSeconds' | transloco }}</span>
        <p-inputnumber [min]="5" [max]="600" [step]="5" [showButtons]="true" [disabled]="!settings().repeatEnabled"
                       [ngModel]="settings().repeatSeconds" inputStyleClass="seconds"
                       (ngModelChange)="settingsService.update({ repeatSeconds: $event ?? 30 })" />
      </div>

      <h3>{{ 'settings.pushTitle' | transloco }}</h3>
      @switch (push.state()) {
        @case ('granted') {
          <div class="row">
            <span>{{ 'settings.pushReceive' | transloco }}</span>
            <p-toggleswitch [ngModel]="settings().pushEnabled" [disabled]="pushBusy()"
                            (ngModelChange)="togglePush($event)" />
          </div>
        }
        @case ('default') {
          <div class="row">
            <span>{{ 'settings.pushOffer' | transloco }}</span>
            <p-button [label]="'settings.pushEnable' | transloco" size="small" (onClick)="push.enable()" />
          </div>
        }
        @case ('denied') {
          <p class="muted">{{ 'settings.pushDenied' | transloco }}</p>
        }
        @case ('needs-install') {
          <p class="muted">{{ 'settings.pushNeedsInstall' | transloco }}</p>
        }
        @default {
          <p class="muted">{{ 'settings.pushUnsupported' | transloco }}</p>
        }
      }
      @if (push.error(); as error) {
        <p class="error">{{ error | transloco }}</p>
      }

      <h3>{{ 'settings.viewTitle' | transloco }}</h3>
      <div class="row">
        <span>{{ 'settings.history' | transloco }}</span>
        <p-toggleswitch [ngModel]="store.includeHistory()" (ngModelChange)="store.setIncludeHistory($event)" />
      </div>

      <div class="row">
        <span>{{ 'settings.zoom' | transloco }}</span>
        <p-toggleswitch [ngModel]="settings().zoomEnabled"
                        (ngModelChange)="settingsService.update({ zoomEnabled: $event })" />
      </div>
      <div class="row">
        <span>{{ 'settings.zoomSeconds' | transloco }}</span>
        <p-inputnumber [min]="1" [max]="600" [step]="5" [showButtons]="true" [disabled]="!settings().zoomEnabled"
                       [ngModel]="settings().zoomSeconds" inputStyleClass="seconds"
                       (ngModelChange)="settingsService.update({ zoomSeconds: $event ?? 10 })" />
      </div>

      <h3>{{ 'settings.focusTitle' | transloco }}</h3>
      <div class="row">
        <span>{{ 'settings.focusToggle' | transloco }}</span>
        <p-toggleswitch [ngModel]="focusEnabled() === true" [disabled]="focusEnabled() === null || focusBusy()"
                        (ngModelChange)="toggleFocus($event)" />
      </div>
      <p class="muted diagnostics">
        @if (focusError()) {
          {{ 'settings.focusError' | transloco }}
        } @else if (focusEnabled() === false) {
          {{ 'settings.focusOff' | transloco }}
        } @else {
          {{ 'settings.focusOn' | transloco }}
        }
      </p>

      <h3>{{ 'settings.versionTitle' | transloco }}</h3>
      <div class="row">
        <span>
          Claude Monitor {{ version }}
          <span class="muted build">
            {{ 'settings.build' | transloco: { build: build ?? ('settings.devBuild' | transloco) } }}
          </span>
        </span>
        @if (updates.enabled) {
          <p-button [label]="'settings.checkUpdates' | transloco" size="small" severity="secondary"
                    [loading]="updates.checking()" (onClick)="updates.check()" />
        }
      </div>
      @switch (updates.lastCheck()) {
        @case ('up-to-date') {
          <p class="muted">{{ 'settings.upToDate' | transloco }}</p>
        }
        @case ('updating') {
          <p class="muted">{{ 'settings.updating' | transloco }}</p>
        }
        @case ('error') {
          <p class="muted">{{ 'settings.updateError' | transloco }}</p>
        }
      }

      <h3>{{ 'settings.accountTitle' | transloco: { username: auth.user()?.username } }}</h3>
      <app-change-password />

      <ng-template #footer>
        <p-button [label]="'settings.signOut' | transloco" icon="pi pi-sign-out" severity="secondary"
                  (onClick)="auth.logout()" />
        <p-button [label]="'common.close' | transloco" (onClick)="closed.emit()" />
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
  protected readonly i18n = inject(I18n);
  protected readonly version = APP_VERSION;
  protected readonly build = APP_BUILD;

  readonly closed = output<void>();

  protected readonly settings = this.settingsService.settings;

  protected readonly pushBusy = signal(false);

  /** "Automatic" shows the language it resolves to; each language is named in its own language. */
  protected readonly languageOptions = computed<{ label: string; value: LanguageSetting }[]>(() => [
    { label: this.i18n.t('settings.languageAuto', { language: LANGUAGE_NAMES[browserLanguage()] }), value: 'auto' },
    ...LANGUAGES.map((language) => ({ label: LANGUAGE_NAMES[language], value: language })),
  ]);

  /** Window focus switch, saved on the server: null until it has been read. */
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
    // Show the choice right away; if saving fails, go back to the previous value
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
      // The tap on the switch is the gesture the browser requires to create the subscription
      await (enabled ? this.push.enable() : this.push.disable());
    } finally {
      this.pushBusy.set(false);
    }
  }

  protected setLanguage(language: LanguageSetting | null): void {
    if (language) {
      this.settingsService.update({ language });
    }
  }

  protected toggleSounds(enabled: boolean): void {
    if (enabled) {
      // The tap on the switch is the gesture that unlocks audio
      this.audio.enable();
    } else {
      this.audio.disable();
    }
  }
}
