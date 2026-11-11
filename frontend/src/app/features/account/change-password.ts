import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';

import { AuthService } from '../../core/auth.service';

const MIN_PASSWORD_LENGTH = 10;

/** Modulo per cambiare la propria password, usato nelle impostazioni. */
@Component({
  selector: 'app-change-password',
  imports: [FormsModule, ButtonModule, InputTextModule],
  template: `
    <form (ngSubmit)="submit()">
      <input pInputText type="password" name="current" placeholder="Password attuale"
             autocomplete="current-password" [ngModel]="current()" (ngModelChange)="current.set($event)" />
      <input pInputText type="password" name="next" placeholder="Nuova password (minimo 10 caratteri)"
             autocomplete="new-password" [ngModel]="next()" (ngModelChange)="next.set($event)" />
      <p-button type="submit" label="Cambia password" size="small" severity="secondary" [loading]="busy()"
                [disabled]="!current() || next().length < minLength" />
    </form>
    @if (message(); as m) {
      <p [class.error]="m.error" role="status">{{ m.text }}</p>
    }
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    input {
      flex: 1 1 180px;
      min-width: 0;
    }
    p {
      margin: 8px 0 0;
      color: var(--cm-green);
    }
    p.error {
      color: var(--cm-red);
    }
  `,
})
export class ChangePassword {
  private readonly auth = inject(AuthService);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly current = signal('');
  protected readonly next = signal('');
  protected readonly busy = signal(false);
  protected readonly message = signal<{ text: string; error: boolean } | null>(null);

  protected async submit(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    try {
      await this.auth.changePassword(this.current(), this.next());
      this.current.set('');
      this.next.set('');
      this.message.set({ text: 'Password cambiata.', error: false });
    } catch (e) {
      const wrongCurrent = e instanceof HttpErrorResponse && e.status === 400;
      this.message.set({
        text: wrongCurrent ? 'Password attuale non corretta o nuova password non valida.' : 'Cambio non riuscito.',
        error: true,
      });
    } finally {
      this.busy.set(false);
    }
  }
}
