import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Role } from './auth.service';

export interface HookKey {
  id: number;
  name: string;
  prefix: string;
  createdAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
}

/** Response to creation: the only moment the key is available in clear text. */
export interface CreatedHookKey {
  id: number;
  name: string;
  prefix: string;
  createdAt: string;
  key: string;
}

export interface UserAccount {
  id: number;
  username: string;
  role: Role;
  enabled: boolean;
  createdAt: string;
}

/** Calls for the user's hook keys and, for administrators, for the users. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  hookKeys(): Promise<HookKey[]> {
    return firstValueFrom(this.http.get<HookKey[]>('api/hook-keys'));
  }

  createHookKey(name: string): Promise<CreatedHookKey> {
    return firstValueFrom(this.http.post<CreatedHookKey>('api/hook-keys', { name }));
  }

  revokeHookKey(id: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`api/hook-keys/${id}`));
  }

  users(): Promise<UserAccount[]> {
    return firstValueFrom(this.http.get<UserAccount[]>('api/users'));
  }

  createUser(username: string, password: string, role: Role): Promise<UserAccount> {
    return firstValueFrom(this.http.post<UserAccount>('api/users', { username, password, role }));
  }

  updateUser(id: number, patch: { password?: string; enabled?: boolean; role?: Role }): Promise<UserAccount> {
    return firstValueFrom(this.http.patch<UserAccount>(`api/users/${id}`, patch));
  }
}
