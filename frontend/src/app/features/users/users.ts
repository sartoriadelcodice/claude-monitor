import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import { AdminApi, UserAccount } from '../../core/admin-api.service';
import { AuthService, Role } from '../../core/auth.service';

const MIN_PASSWORD_LENGTH = 10;

/** Gestione utenti, visibile solo agli amministratori. */
@Component({
  selector: 'app-users',
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, ToggleSwitchModule],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" header="Utenti"
              [style]="{ width: 'min(680px, 96vw)', maxHeight: '90dvh' }">
      <form class="create" (ngSubmit)="create()">
        <input pInputText name="username" placeholder="Nome utente" autocapitalize="none" autocomplete="off"
               [ngModel]="username()" (ngModelChange)="username.set($event)" />
        <input pInputText name="password" type="password" placeholder="Password (minimo 10 caratteri)"
               autocomplete="new-password" [ngModel]="password()" (ngModelChange)="password.set($event)" />
        <label class="admin">
          <p-toggleswitch name="admin" [ngModel]="admin()" (ngModelChange)="admin.set($event)" />
          Amministratore
        </label>
        <p-button type="submit" label="Crea utente" icon="pi pi-user-plus" [loading]="busy()"
                  [disabled]="!canCreate()" />
      </form>

      @if (error()) {
        <p class="error" role="alert">{{ error() }}</p>
      }

      <ul class="list">
        @for (user of users(); track user.id) {
          <li [class.disabled]="!user.enabled">
            <div class="info">
              <strong>{{ user.username }}</strong>
              <span class="muted">
                {{ user.role === 'ADMIN' ? 'Amministratore' : 'Utente' }}
                @if (!user.enabled) {
                  · disattivato
                }
                @if (isSelf(user)) {
                  · sei tu
                }
              </span>
            </div>
            <span class="buttons">
              @if (resetting() === user.id) {
                <input pInputText type="password" placeholder="Nuova password" autocomplete="new-password"
                       [ngModel]="newPassword()" (ngModelChange)="newPassword.set($event)" />
                <p-button label="Salva" size="small" [disabled]="newPassword().length < minLength"
                          (onClick)="resetPassword(user)" />
                <p-button label="Annulla" size="small" severity="secondary" (onClick)="resetting.set(null)" />
              } @else {
                <p-button label="Password" size="small" severity="secondary" (onClick)="startReset(user)" />
                @if (!isSelf(user)) {
                  <p-button [label]="user.role === 'ADMIN' ? 'Rendi utente' : 'Rendi admin'" size="small"
                            severity="secondary" (onClick)="toggleRole(user)" />
                  <p-button [label]="user.enabled ? 'Disattiva' : 'Riattiva'" size="small"
                            [severity]="user.enabled ? 'danger' : 'secondary'" [outlined]="true"
                            (onClick)="toggleEnabled(user)" />
                }
              }
            </span>
          </li>
        }
      </ul>

      <ng-template #footer>
        <p-button label="Chiudi" (onClick)="closed.emit()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .create {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-bottom: 16px;
    }
    .create input {
      flex: 1 1 180px;
      min-width: 0;
    }
    .admin {
      display: inline-flex;
      align-items: center;
      gap: 8px;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .list li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 0;
      border-top: 1px solid var(--cm-border);
    }
    .list li.disabled .info {
      opacity: 0.55;
    }
    .info {
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .buttons {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .muted {
      color: var(--cm-muted);
    }
    .error {
      color: var(--cm-red);
    }
  `,
})
export class Users implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly auth = inject(AuthService);

  readonly closed = output<void>();

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly users = signal<UserAccount[]>([]);
  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly admin = signal(false);
  protected readonly resetting = signal<number | null>(null);
  protected readonly newPassword = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    void this.reload();
  }

  protected canCreate(): boolean {
    return this.username().trim().length >= 3 && this.password().length >= MIN_PASSWORD_LENGTH;
  }

  protected isSelf(user: UserAccount): boolean {
    return user.username.toLowerCase() === this.auth.user()?.username.toLowerCase();
  }

  protected async create(): Promise<void> {
    if (!this.canCreate() || this.busy()) {
      return;
    }
    this.busy.set(true);
    await this.run(async () => {
      const role: Role = this.admin() ? 'ADMIN' : 'USER';
      await this.api.createUser(this.username().trim(), this.password(), role);
      this.username.set('');
      this.password.set('');
      this.admin.set(false);
    });
    this.busy.set(false);
  }

  protected startReset(user: UserAccount): void {
    this.newPassword.set('');
    this.resetting.set(user.id);
  }

  protected resetPassword(user: UserAccount): Promise<void> {
    return this.run(async () => {
      await this.api.updateUser(user.id, { password: this.newPassword() });
      this.resetting.set(null);
    });
  }

  protected toggleRole(user: UserAccount): Promise<void> {
    return this.run(() => this.api.updateUser(user.id, { role: user.role === 'ADMIN' ? 'USER' : 'ADMIN' }));
  }

  protected toggleEnabled(user: UserAccount): Promise<void> {
    return this.run(() => this.api.updateUser(user.id, { enabled: !user.enabled }));
  }

  /** Esegue una modifica, mostra l'eventuale errore del server e ricarica l'elenco. */
  private async run(action: () => Promise<unknown>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describe(e));
    }
    await this.reload();
  }

  private async reload(): Promise<void> {
    try {
      this.users.set(await this.api.users());
    } catch {
      this.error.set('Elenco utenti non disponibile.');
    }
  }
}

function describe(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 409) {
      return (error.error as { detail?: string; message?: string } | null)?.detail
        ?? 'Operazione non consentita: nome già in uso oppure è l\'ultimo amministratore attivo.';
    }
    if (error.status === 400) {
      return 'Dati non validi: il nome utente accetta lettere, cifre e . _ @ -, la password almeno 10 caratteri.';
    }
  }
  return 'Operazione non riuscita.';
}
