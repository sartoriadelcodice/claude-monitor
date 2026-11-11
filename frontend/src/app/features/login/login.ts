import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';

import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule, ButtonModule, InputTextModule],
  template: `
    <main class="login">
      <form class="panel" (ngSubmit)="submit()">
        <img src="icons/icon-192x192.png" alt="" width="72" height="72" />
        <h1>Claude Monitor</h1>
        <label>
          <span>Utente</span>
          <input pInputText name="username" autocomplete="username" autocapitalize="none"
                 [ngModel]="username()" (ngModelChange)="username.set($event)" required />
        </label>
        <label>
          <span>Password</span>
          <input pInputText type="password" name="password" autocomplete="current-password"
                 [ngModel]="password()" (ngModelChange)="password.set($event)" required />
        </label>
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
        <p-button type="submit" label="Accedi" [loading]="loading()" styleClass="submit" />
      </form>
    </main>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
      overflow-y: auto;
    }
    .login {
      min-height: 100%;
      display: grid;
      place-items: center;
      padding: 16px;
    }
    .panel {
      width: min(380px, 100%);
      display: flex;
      flex-direction: column;
      gap: 16px;
      padding: 32px 24px;
      background: var(--cm-surface);
      border: 1px solid var(--cm-border);
      border-radius: 16px;
    }
    img {
      align-self: center;
      border-radius: 16px;
    }
    h1 {
      margin: 0 0 8px;
      text-align: center;
      font-size: 1.5rem;
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 6px;
      color: var(--cm-muted);
      font-size: 0.9rem;
    }
    input {
      width: 100%;
      font-size: 1rem;
    }
    .error {
      margin: 0;
      color: var(--cm-red);
    }
    :host ::ng-deep .submit {
      width: 100%;
    }
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected async submit(): Promise<void> {
    if (!this.username() || !this.password() || this.loading()) {
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      await this.auth.login(this.username(), this.password());
      await this.router.navigate(['/']);
    } catch {
      this.error.set('Accesso non riuscito: controlla utente e password.');
    } finally {
      this.loading.set(false);
    }
  }
}
