import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectButtonModule } from 'primeng/selectbutton';

import { AdminApi, CreatedHookKey, HookKey } from '../../core/admin-api.service';
import { HookInstaller, InstallerPlatform, detectPlatform } from '../../core/hook-installer.service';
import { I18n } from '../../core/i18n.service';

/** The user's hook keys: creation, list and revocation. */
@Component({
  selector: 'app-hook-keys',
  imports: [FormsModule, TranslocoPipe, ButtonModule, DialogModule, InputTextModule, SelectButtonModule],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" [header]="'hookKeys.title' | transloco"
              [style]="{ width: 'min(680px, 96vw)', maxHeight: '90dvh' }">
      <p class="muted">{{ 'hookKeys.intro' | transloco }}</p>

      <form class="create" (ngSubmit)="create()">
        <input pInputText name="name" [placeholder]="'hookKeys.namePlaceholder' | transloco" maxlength="100"
               [ngModel]="name()" (ngModelChange)="name.set($event)" />
        <p-button type="submit" [label]="'hookKeys.create' | transloco" icon="pi pi-plus" [loading]="busy()"
                  [disabled]="!name().trim()" />
      </form>

      @if (created(); as key) {
        <section class="created">
          <strong>{{ 'hookKeys.created' | transloco: { name: key.name } }}</strong>
          <p [innerHTML]="'hookKeys.copyNow' | transloco"></p>
          <div class="secret">
            <code>{{ key.key }}</code>
            <p-button [label]="(copied() ? 'common.copied' : 'common.copy') | transloco" icon="pi pi-copy" size="small"
                      severity="secondary" (onClick)="copy(key.key)" />
          </div>

          <div class="install">
            <strong>{{ 'hookKeys.autoInstall' | transloco }}</strong>
            <p>{{ 'hookKeys.autoInstallIntro' | transloco }}</p>
            <div class="download">
              <p-selectbutton [options]="platforms" optionLabel="label" optionValue="value" [allowEmpty]="false"
                              [ngModel]="platform()" (ngModelChange)="$event && platform.set($event)" size="small" />
              <p-button [label]="'hookKeys.download' | transloco" icon="pi pi-download" size="small" [loading]="downloading()"
                        (onClick)="download(key.key)" />
            </div>
            <ol>
              @if (platform() === 'windows') {
                <li>{{ 'hookKeys.openPowerShell' | transloco }}</li>
                <li>
                  {{ 'hookKeys.run' | transloco }}
                  <code class="command">powershell -ExecutionPolicy Bypass -File .\{{ installer.fileName('windows') }}</code>
                </li>
              } @else {
                <li>{{ 'hookKeys.openTerminal' | transloco }}</li>
                <li>
                  {{ 'hookKeys.run' | transloco }}
                  <code class="command">bash {{ installer.fileName('unix') }}</code>
                </li>
              }
              <li>{{ 'hookKeys.deleteScript' | transloco }}</li>
              <li>{{ 'hookKeys.openSession' | transloco }}</li>
            </ol>
            <p class="muted note"
               [innerHTML]="(platform() === 'windows' ? 'hookKeys.windowsNote' : 'hookKeys.unixNote') | transloco"></p>
          </div>
        </section>
      }

      @if (error(); as error) {
        <p class="error" role="alert">{{ error | transloco }}</p>
      }

      <ul class="list">
        @for (key of keys(); track key.id) {
          <li [class.revoked]="key.revokedAt">
            <div class="info">
              <strong>{{ key.name }}</strong>
              <code>{{ key.prefix }}…</code>
              <span class="muted">
                {{ 'hookKeys.createdOn' | transloco: { date: i18n.date(key.createdAt, 'date') } }} ·
                @if (key.revokedAt) {
                  {{ 'hookKeys.revokedOn' | transloco: { date: i18n.date(key.revokedAt, 'date') } }}
                } @else if (key.lastUsedAt) {
                  {{ 'hookKeys.lastUsed' | transloco: { date: i18n.date(key.lastUsedAt, 'dateTime') } }}
                } @else {
                  {{ 'hookKeys.neverUsed' | transloco }}
                }
              </span>
            </div>
            @if (!key.revokedAt) {
              @if (confirming() === key.id) {
                <span class="buttons">
                  <p-button [label]="'hookKeys.confirmRevoke' | transloco" size="small" severity="danger"
                            (onClick)="revoke(key)" />
                  <p-button [label]="'common.cancel' | transloco" size="small" severity="secondary"
                            (onClick)="confirming.set(null)" />
                </span>
              } @else {
                <p-button [label]="'hookKeys.revoke' | transloco" size="small" severity="danger" [outlined]="true"
                          (onClick)="confirming.set(key.id)" />
              }
            }
          </li>
        } @empty {
          <li class="muted">{{ 'hookKeys.empty' | transloco }}</li>
        }
      </ul>

      <section class="update">
        <strong>{{ 'hookKeys.updateTitle' | transloco }}</strong>
        <p class="muted">{{ 'hookKeys.updateIntro' | transloco }}</p>
        <div class="download">
          <p-selectbutton [options]="platforms" optionLabel="label" optionValue="value" [allowEmpty]="false"
                          [ngModel]="platform()" (ngModelChange)="$event && platform.set($event)" size="small" />
          <p-button [label]="(updateCopied() ? 'common.commandCopied' : 'common.copyCommand') | transloco"
                    icon="pi pi-copy" size="small"
                    severity="secondary" (onClick)="copyUpdateCommand()" />
        </div>
        <code class="command">{{ installer.updateCommand(platform()) }}</code>
        <p class="muted note">
          {{ (platform() === 'windows' ? 'hookKeys.runInPowerShell' : 'hookKeys.runInTerminal') | transloco }}
          {{ 'hookKeys.updateNote' | transloco }}
        </p>
      </section>

      <section class="update">
        <strong>{{ 'hookKeys.focusTitle' | transloco }}</strong>
        <p class="muted">{{ 'hookKeys.focusIntro' | transloco }}</p>
        <p-button [label]="(agentCopied() ? 'common.commandCopied' : 'common.copyCommand') | transloco"
                  icon="pi pi-copy" size="small"
                  severity="secondary" (onClick)="copyAgentCommand()" />
        <code class="command">{{ installer.agentCommand() }}</code>
        <p class="muted note" [innerHTML]="'hookKeys.focusNote' | transloco"></p>
      </section>

      <ng-template #footer>
        <p-button [label]="'common.close' | transloco" (onClick)="closed.emit()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .muted {
      color: var(--cm-muted);
    }
    p {
      margin: 0 0 12px;
    }
    .create {
      display: flex;
      gap: 8px;
      margin-bottom: 16px;
    }
    .create input {
      flex: 1;
      min-width: 0;
    }
    .created {
      padding: 12px;
      margin-bottom: 16px;
      border: 1px solid var(--cm-green);
      border-radius: 10px;
      background: color-mix(in srgb, var(--cm-green) 12%, transparent);
    }
    .secret {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .secret code {
      flex: 1;
      padding: 8px;
      border-radius: 6px;
      background: var(--cm-bg);
      word-break: break-all;
      user-select: all;
    }
    code {
      font-family: ui-monospace, Menlo, monospace;
      font-size: 0.85rem;
    }
    .install {
      margin-top: 14px;
      padding-top: 12px;
      border-top: 1px solid color-mix(in srgb, var(--cm-green) 40%, transparent);
    }
    .install p {
      margin: 4px 0 10px;
    }
    .download {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 10px;
    }
    .install ol {
      margin: 12px 0 8px;
      padding-left: 20px;
    }
    .install li {
      margin-bottom: 6px;
    }
    .command {
      display: block;
      margin-top: 4px;
      padding: 8px;
      border-radius: 6px;
      background: var(--cm-bg);
      word-break: break-all;
      user-select: all;
    }
    .install .note,
    .update .note {
      margin-bottom: 0;
      font-size: 0.85rem;
    }
    .update {
      margin-top: 8px;
      padding-top: 14px;
      border-top: 1px solid var(--cm-border);
    }
    .update p {
      margin: 4px 0 10px;
    }
    .update .command {
      margin: 10px 0 8px;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .list li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 0;
      border-top: 1px solid var(--cm-border);
    }
    .list li.revoked {
      opacity: 0.55;
    }
    .info {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .buttons {
      display: flex;
      gap: 8px;
      flex-shrink: 0;
    }
    .error {
      color: var(--cm-red);
    }
  `,
})
export class HookKeys implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly installer = inject(HookInstaller);
  protected readonly i18n = inject(I18n);

  protected readonly platforms: { label: string; value: InstallerPlatform }[] = [
    { label: 'Linux / macOS', value: 'unix' },
    { label: 'Windows', value: 'windows' },
  ];
  /** Suggested from the current browser; it can be changed when the key is for another machine. */
  protected readonly platform = signal<InstallerPlatform>(detectPlatform());
  protected readonly downloading = signal(false);

  readonly closed = output<void>();

  protected readonly keys = signal<HookKey[]>([]);
  protected readonly name = signal('');
  protected readonly created = signal<CreatedHookKey | null>(null);
  protected readonly copied = signal(false);
  protected readonly updateCopied = signal(false);
  protected readonly agentCopied = signal(false);
  protected readonly confirming = signal<number | null>(null);
  protected readonly busy = signal(false);
  /** Translation key of the last error. */
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    void this.reload();
  }

  protected async create(): Promise<void> {
    const name = this.name().trim();
    if (!name || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      this.created.set(await this.api.createHookKey(name));
      this.copied.set(false);
      this.name.set('');
      await this.reload();
    } catch {
      this.error.set('hookKeys.createFailed');
    } finally {
      this.busy.set(false);
    }
  }

  protected async copy(key: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(key);
      this.copied.set(true);
    } catch {
      this.error.set('hookKeys.copyKeyFailed');
    }
  }

  protected async copyUpdateCommand(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.installer.updateCommand(this.platform()));
      this.updateCopied.set(true);
      setTimeout(() => this.updateCopied.set(false), 3_000);
    } catch {
      this.error.set('hookKeys.copyCommandFailed');
    }
  }

  protected async copyAgentCommand(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.installer.agentCommand());
      this.agentCopied.set(true);
      setTimeout(() => this.agentCopied.set(false), 3_000);
    } catch {
      this.error.set('hookKeys.copyCommandFailed');
    }
  }

  protected async download(key: string): Promise<void> {
    this.downloading.set(true);
    this.error.set(null);
    try {
      await this.installer.download(this.platform(), key);
    } catch {
      this.error.set('hookKeys.downloadFailed');
    } finally {
      this.downloading.set(false);
    }
  }

  protected async revoke(key: HookKey): Promise<void> {
    this.error.set(null);
    try {
      await this.api.revokeHookKey(key.id);
      this.confirming.set(null);
      await this.reload();
    } catch {
      this.error.set('hookKeys.revokeFailed');
    }
  }

  private async reload(): Promise<void> {
    try {
      this.keys.set(await this.api.hookKeys());
    } catch {
      this.error.set('hookKeys.listUnavailable');
    }
  }
}
