import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';

import { AdminApi, UserAccount } from '../../core/admin-api.service';
import { AuthService, Role } from '../../core/auth.service';

const MIN_PASSWORD_LENGTH = 10;

/** User management, visible to administrators only. */
@Component({
  selector: 'app-users',
  imports: [FormsModule, TranslocoPipe, ButtonModule, DialogModule, InputTextModule, ToggleSwitchModule],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" [header]="'users.title' | transloco"
              [style]="{ width: 'min(680px, 96vw)', maxHeight: '90dvh' }">
      <form class="create" (ngSubmit)="create()">
        <input pInputText name="username" [placeholder]="'users.username' | transloco" autocapitalize="none" autocomplete="off"
               [ngModel]="username()" (ngModelChange)="username.set($event)" />
        <input pInputText name="password" type="password" [placeholder]="'users.password' | transloco"
               autocomplete="new-password" [ngModel]="password()" (ngModelChange)="password.set($event)" />
        <label class="admin">
          <p-toggleswitch name="admin" [ngModel]="admin()" (ngModelChange)="admin.set($event)" />
          {{ 'users.admin' | transloco }}
        </label>
        <p-button type="submit" [label]="'users.create' | transloco" icon="pi pi-user-plus" [loading]="busy()"
                  [disabled]="!canCreate()" />
      </form>

      @if (error(); as error) {
        <p class="error" role="alert">{{ error | transloco }}</p>
      }

      <ul class="list">
        @for (user of users(); track user.id) {
          <li [class.disabled]="!user.enabled">
            <div class="info">
              <strong>{{ user.username }}</strong>
              <span class="muted">
                {{ (user.role === 'ADMIN' ? 'users.roleAdmin' : 'users.roleUser') | transloco }}
                @if (!user.enabled) {
                  · {{ 'users.disabled' | transloco }}
                }
                @if (isSelf(user)) {
                  · {{ 'users.you' | transloco }}
                }
              </span>
            </div>
            <span class="buttons">
              @if (resetting() === user.id) {
                <input pInputText type="password" [placeholder]="'users.newPassword' | transloco" autocomplete="new-password"
                       [ngModel]="newPassword()" (ngModelChange)="newPassword.set($event)" />
                <p-button [label]="'common.save' | transloco" size="small" [disabled]="newPassword().length < minLength"
                          (onClick)="resetPassword(user)" />
                <p-button [label]="'common.cancel' | transloco" size="small" severity="secondary"
                          (onClick)="resetting.set(null)" />
              } @else {
                <p-button [label]="'users.passwordButton' | transloco" size="small" severity="secondary"
                          (onClick)="startReset(user)" />
                @if (!isSelf(user)) {
                  <p-button [label]="(user.role === 'ADMIN' ? 'users.makeUser' : 'users.makeAdmin') | transloco" size="small"
                            severity="secondary" (onClick)="toggleRole(user)" />
                  <p-button [label]="(user.enabled ? 'users.disable' : 'users.enable') | transloco" size="small"
                            [severity]="user.enabled ? 'danger' : 'secondary'" [outlined]="true"
                            (onClick)="toggleEnabled(user)" />
                }
              }
            </span>
          </li>
        }
      </ul>

      <ng-template #footer>
        <p-button [label]="'common.close' | transloco" (onClick)="closed.emit()" />
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
  /** Translation key of the last error. */
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
    // On creation a conflict means the username is taken
    await this.run('users.usernameTaken', async () => {
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
    return this.run('users.failed', async () => {
      await this.api.updateUser(user.id, { password: this.newPassword() });
      this.resetting.set(null);
    });
  }

  protected toggleRole(user: UserAccount): Promise<void> {
    // On role and status changes a conflict means the last active administrator would be lost
    return this.run('users.lastAdmin', () =>
      this.api.updateUser(user.id, { role: user.role === 'ADMIN' ? 'USER' : 'ADMIN' }),
    );
  }

  protected toggleEnabled(user: UserAccount): Promise<void> {
    return this.run('users.lastAdmin', () => this.api.updateUser(user.id, { enabled: !user.enabled }));
  }

  /** Runs a change, shows the server error if any and reloads the list. */
  private async run(conflictKey: string, action: () => Promise<unknown>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describe(e, conflictKey));
    }
    await this.reload();
  }

  private async reload(): Promise<void> {
    try {
      this.users.set(await this.api.users());
    } catch {
      this.error.set('users.listUnavailable');
    }
  }
}

/** Translation key for a failed change; the server message is not shown, so that it can be translated. */
function describe(error: unknown, conflictKey: string): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 409) {
      return conflictKey;
    }
    if (error.status === 400) {
      return 'users.invalid';
    }
  }
  return 'users.failed';
}
