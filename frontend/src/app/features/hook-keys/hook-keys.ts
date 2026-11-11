import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectButtonModule } from 'primeng/selectbutton';

import { AdminApi, CreatedHookKey, HookKey } from '../../core/admin-api.service';
import { HookInstaller, InstallerPlatform, detectPlatform } from '../../core/hook-installer.service';

/** Chiavi hook dell'utente: creazione, elenco e revoca. */
@Component({
  selector: 'app-hook-keys',
  imports: [DatePipe, FormsModule, ButtonModule, DialogModule, InputTextModule, SelectButtonModule],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" header="Chiavi hook"
              [style]="{ width: 'min(680px, 96vw)', maxHeight: '90dvh' }">
      <p class="muted">
        Ogni macchina usa una chiave per inviare gli eventi di Claude Code. Le sessioni che arrivano con una
        tua chiave le vedi solo tu. Conviene una chiave per macchina, così puoi revocarne una sola.
      </p>

      <form class="create" (ngSubmit)="create()">
        <input pInputText name="name" placeholder="Nome, ad esempio MacBook ufficio" maxlength="100"
               [ngModel]="name()" (ngModelChange)="name.set($event)" />
        <p-button type="submit" label="Crea chiave" icon="pi pi-plus" [loading]="busy()"
                  [disabled]="!name().trim()" />
      </form>

      @if (created(); as key) {
        <section class="created">
          <strong>Chiave «{{ key.name }}» creata</strong>
          <p>Copiala adesso: non sarà più visibile. Va in <code>CLAUDE_MONITOR_TOKEN</code> su quella macchina.</p>
          <div class="secret">
            <code>{{ key.key }}</code>
            <p-button [label]="copied() ? 'Copiata' : 'Copia'" icon="pi pi-copy" size="small"
                      severity="secondary" (onClick)="copy(key.key)" />
          </div>

          <div class="install">
            <strong>Installazione automatica</strong>
            <p>
              Scarica lo script sulla macchina da collegare ed eseguilo: installa l'hook e aggiorna le impostazioni
              globali di Claude Code, dopo averne salvato una copia di sicurezza.
            </p>
            <div class="download">
              <p-selectbutton [options]="platforms" optionLabel="label" optionValue="value" [allowEmpty]="false"
                              [ngModel]="platform()" (ngModelChange)="$event && platform.set($event)" size="small" />
              <p-button label="Scarica lo script" icon="pi pi-download" size="small" [loading]="downloading()"
                        (onClick)="download(key.key)" />
            </div>
            <ol>
              @if (platform() === 'windows') {
                <li>Apri PowerShell nella cartella in cui hai scaricato il file.</li>
                <li>
                  Esegui:
                  <code class="command">powershell -ExecutionPolicy Bypass -File .\{{ installer.fileName('windows') }}</code>
                </li>
              } @else {
                <li>Apri un terminale nella cartella in cui hai scaricato il file.</li>
                <li>
                  Esegui:
                  <code class="command">bash {{ installer.fileName('unix') }}</code>
                </li>
              }
              <li>Elimina lo script: contiene la chiave in chiaro.</li>
              <li>Apri una nuova sessione di Claude Code: la card compare nella dashboard.</li>
            </ol>
            <p class="muted note">
              @if (platform() === 'windows') {
                Lo script modifica <code>%USERPROFILE%\.claude\settings.json</code> e ne lascia una copia
                <code>settings.json.backup-data-ora</code> nella stessa cartella.
              } @else {
                Lo script richiede <code>python3</code>. Modifica <code>~/.claude/settings.json</code> e ne lascia
                una copia <code>settings.json.backup-data-ora</code> nella stessa cartella.
              }
            </p>
          </div>
        </section>
      }

      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      <ul class="list">
        @for (key of keys(); track key.id) {
          <li [class.revoked]="key.revokedAt">
            <div class="info">
              <strong>{{ key.name }}</strong>
              <code>{{ key.prefix }}…</code>
              <span class="muted">
                creata il {{ key.createdAt | date: 'dd/MM/yyyy' }} ·
                @if (key.revokedAt) {
                  revocata il {{ key.revokedAt | date: 'dd/MM/yyyy' }}
                } @else if (key.lastUsedAt) {
                  ultimo uso {{ key.lastUsedAt | date: 'dd/MM/yyyy HH:mm' }}
                } @else {
                  mai usata
                }
              </span>
            </div>
            @if (!key.revokedAt) {
              @if (confirming() === key.id) {
                <span class="buttons">
                  <p-button label="Conferma revoca" size="small" severity="danger" (onClick)="revoke(key)" />
                  <p-button label="Annulla" size="small" severity="secondary" (onClick)="confirming.set(null)" />
                </span>
              } @else {
                <p-button label="Revoca" size="small" severity="danger" [outlined]="true"
                          (onClick)="confirming.set(key.id)" />
              }
            }
          </li>
        } @empty {
          <li class="muted">Nessuna chiave: creane una per collegare la prima macchina.</li>
        }
      </ul>

      <section class="update">
        <strong>Aggiornare l'hook su una macchina già collegata</strong>
        <p class="muted">
          Dopo un aggiornamento di Claude Monitor lo script dell'hook può cambiare. Per riscaricarlo esegui
          questo comando sulla macchina: chiave e impostazioni restano quelle che ha già.
        </p>
        <div class="download">
          <p-selectbutton [options]="platforms" optionLabel="label" optionValue="value" [allowEmpty]="false"
                          [ngModel]="platform()" (ngModelChange)="$event && platform.set($event)" size="small" />
          <p-button [label]="updateCopied() ? 'Copiato' : 'Copia il comando'" icon="pi pi-copy" size="small"
                    severity="secondary" (onClick)="copyUpdateCommand()" />
        </div>
        <code class="command">{{ installer.updateCommand(platform()) }}</code>
        <p class="muted note">
          {{ platform() === 'windows' ? 'Da eseguire in PowerShell.' : 'Da eseguire in un terminale.' }}
          Vale dal prossimo evento, anche per le sessioni già aperte.
        </p>
      </section>

      <section class="update">
        <strong>Focus delle finestre (solo macOS)</strong>
        <p class="muted">
          Facoltativo. Con questo comando il Mac avvia un piccolo agente che resta collegato alla dashboard:
          sulle card delle sue sessioni compare un pulsante che porta in primo piano la finestra di Claude Code.
          L'agente riceve solo l'identificativo della sessione: non esegue comandi e non invia tasti.
        </p>
        <p-button [label]="agentCopied() ? 'Copiato' : 'Copia il comando'" icon="pi pi-copy" size="small"
                  severity="secondary" (onClick)="copyAgentCommand()" />
        <code class="command">{{ installer.agentCommand() }}</code>
        <p class="muted note">
          Da eseguire in un terminale del Mac, dopo aver aggiornato l'hook. Lo stesso comando aggiorna l'agente;
          per toglierlo: <code>bash ~/.claude/hooks/claude-monitor-agent.sh uninstall</code>.
        </p>
      </section>

      <ng-template #footer>
        <p-button label="Chiudi" (onClick)="closed.emit()" />
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

  protected readonly platforms: { label: string; value: InstallerPlatform }[] = [
    { label: 'Linux / macOS', value: 'unix' },
    { label: 'Windows', value: 'windows' },
  ];
  /** Proposta in base al browser in uso; si può cambiare se la chiave serve a un'altra macchina. */
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
      this.error.set('Creazione non riuscita.');
    } finally {
      this.busy.set(false);
    }
  }

  protected async copy(key: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(key);
      this.copied.set(true);
    } catch {
      this.error.set('Copia non riuscita: seleziona la chiave e copiala a mano.');
    }
  }

  protected async copyUpdateCommand(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.installer.updateCommand(this.platform()));
      this.updateCopied.set(true);
      setTimeout(() => this.updateCopied.set(false), 3_000);
    } catch {
      this.error.set('Copia non riuscita: seleziona il comando e copialo a mano.');
    }
  }

  protected async copyAgentCommand(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.installer.agentCommand());
      this.agentCopied.set(true);
      setTimeout(() => this.agentCopied.set(false), 3_000);
    } catch {
      this.error.set('Copia non riuscita: seleziona il comando e copialo a mano.');
    }
  }

  protected async download(key: string): Promise<void> {
    this.downloading.set(true);
    this.error.set(null);
    try {
      await this.installer.download(this.platform(), key);
    } catch {
      this.error.set('Script di installazione non disponibile: usa la chiave con l\'installazione manuale.');
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
      this.error.set('Revoca non riuscita.');
    }
  }

  private async reload(): Promise<void> {
    try {
      this.keys.set(await this.api.hookKeys());
    } catch {
      this.error.set('Elenco delle chiavi non disponibile.');
    }
  }
}
