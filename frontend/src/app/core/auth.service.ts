import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

const TOKEN_KEY = 'cm.token';
const USER_KEY = 'cm.user';

export type Role = 'USER' | 'ADMIN';

export interface CurrentUser {
  username: string;
  role: Role;
}

interface LoginResponse extends CurrentUser {
  token: string;
  expiresAt: string;
}

/** Login e custodia del JWT e dei dati dell'utente collegato. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly token = signal<string | null>(read(TOKEN_KEY));
  readonly user = signal<CurrentUser | null>(readUser());
  readonly isAdmin = computed(() => this.user()?.role === 'ADMIN');

  isAuthenticated(): boolean {
    return this.token() !== null;
  }

  async login(username: string, password: string): Promise<void> {
    const response = await firstValueFrom(
      this.http.post<LoginResponse>('api/auth/login', { username, password }),
    );
    const user: CurrentUser = { username: response.username, role: response.role };
    localStorage.setItem(TOKEN_KEY, response.token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    this.token.set(response.token);
    this.user.set(user);
  }

  /** Rilegge nome e ruolo dal server: serve a chi aveva già un token e dopo un cambio di ruolo. */
  async refreshUser(): Promise<void> {
    try {
      const me = await firstValueFrom(this.http.get<CurrentUser>('api/me'));
      const user: CurrentUser = { username: me.username, role: me.role };
      localStorage.setItem(USER_KEY, JSON.stringify(user));
      this.user.set(user);
    } catch {
      // Il 401 è gestito dall'interceptor; per gli altri errori restano i dati salvati
    }
  }

  changePassword(currentPassword: string, newPassword: string): Promise<void> {
    return firstValueFrom(this.http.post<void>('api/me/password', { currentPassword, newPassword }));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.token.set(null);
    this.user.set(null);
    void this.router.navigate(['/login']);
  }
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function readUser(): CurrentUser | null {
  try {
    const raw = read(USER_KEY);
    return raw ? (JSON.parse(raw) as CurrentUser) : null;
  } catch {
    return null;
  }
}
