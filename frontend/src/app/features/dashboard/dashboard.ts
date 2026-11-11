import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';

import { AudioService } from '../../core/audio.service';
import { AuthService } from '../../core/auth.service';
import {
  FocusResult,
  STATUS_META,
  Session,
  formatElapsed,
  formatModel,
  requiresAttention,
} from '../../core/models';
import { PushService } from '../../core/push.service';
import { SessionStore } from '../../core/session-store.service';
import { GroupBy, SettingsService } from '../../core/settings.service';
import { HookKeys } from '../hook-keys/hook-keys';
import { SessionDetail } from '../session-detail/session-detail';
import { Settings } from '../settings/settings';
import { Users } from '../users/users';

interface SessionGroup {
  key: string;
  /** null quando le card non sono raggruppate. */
  label: string | null;
  /** Sessioni del gruppo che richiedono attenzione e non sono ancora state viste. */
  attention: number;
  sessions: Session[];
}

/** Richiesta di focus in corso o esito dell'ultima, con la macchina su cui si è aperta la sessione. */
type FocusState = { status: 'sending' } | { status: 'ok'; result: FocusResult } | { status: 'error' };

/** Vista principale: contatore delle sessioni in attesa e griglia di card. */
@Component({
  selector: 'app-dashboard',
  imports: [FormsModule, ButtonModule, SelectModule, SelectButtonModule, SessionDetail, Settings, HookKeys, Users],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {
  protected readonly store = inject(SessionStore);
  protected readonly audio = inject(AudioService);
  protected readonly push = inject(PushService);
  protected readonly auth = inject(AuthService);
  protected readonly settingsService = inject(SettingsService);

  protected readonly groupOptions: { label: string; value: GroupBy }[] = [
    { label: 'Nessuno', value: 'none' },
    { label: 'Cartella', value: 'project' },
    { label: 'Device', value: 'machine' },
    { label: 'Entrambi', value: 'both' },
  ];
  protected readonly darkTheme = computed(() => this.settingsService.settings().theme === 'dark');
  protected readonly groupBy = computed(() => this.settingsService.settings().groupBy);

  protected readonly machine = signal<string | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly settingsOpen = signal(false);
  protected readonly hookKeysOpen = signal(false);
  protected readonly usersOpen = signal(false);
  /** Esito dell'ultima richiesta di focus per sessione, mostrato per qualche secondo sul pulsante. */
  protected readonly focusState = signal<ReadonlyMap<string, FocusState>>(new Map());

  protected readonly machines = computed(() =>
    [...new Set(this.store.sessions().map((s) => s.machine))].sort((a, b) => a.localeCompare(b)),
  );

  protected readonly visible = computed(() => {
    const machine = this.machine();
    return this.store.sorted().filter((s) => !machine || s.machine === machine);
  });

  /**
   * Card divise in gruppi secondo la scelta dell'utente. I gruppi con sessioni che richiedono
   * attenzione vengono prima; dentro ogni gruppo vale l'ordinamento generale.
   */
  protected readonly groups = computed<SessionGroup[]>(() => {
    const sessions = this.visible();
    const groupBy = this.groupBy();
    if (groupBy === 'none') {
      return [{ key: 'all', label: null, attention: 0, sessions }];
    }
    const byKey = new Map<string, SessionGroup>();
    for (const session of sessions) {
      const label =
        groupBy === 'project'
          ? session.project
          : groupBy === 'machine'
            ? session.machine
            : `${session.machine} · ${session.project}`;
      let group = byKey.get(label);
      if (!group) {
        group = { key: label, label, attention: 0, sessions: [] };
        byKey.set(label, group);
      }
      group.sessions.push(session);
      if (this.highlighted(session)) {
        group.attention++;
      }
    }
    // La prima sessione di ogni gruppo è la più urgente: decide la posizione del gruppo
    return [...byKey.values()].sort(
      (a, b) =>
        STATUS_META[a.sessions[0].status].order - STATUS_META[b.sessions[0].status].order ||
        a.key.localeCompare(b.key),
    );
  });

  /** Sessione aperta nel dettaglio, sempre letta dallo store per restare aggiornata. */
  protected readonly selected = computed(() => {
    const id = this.selectedId();
    return id ? (this.store.sessions().find((s) => s.id === id) ?? null) : null;
  });

  ngOnInit(): void {
    this.store.start();
    void this.auth.refreshUser();
    void this.push.syncExisting();
  }

  ngOnDestroy(): void {
    this.store.stop();
  }

  protected meta(session: Session) {
    return STATUS_META[session.status];
  }

  protected setGroupBy(value: GroupBy | null): void {
    // Il pulsante già selezionato, se ritoccato, restituisce null: si resta sul valore attuale
    if (value) {
      this.settingsService.update({ groupBy: value });
    }
  }

  protected model(session: Session): string | null {
    return session.model ? formatModel(session.model) : null;
  }

  protected elapsed(session: Session): string {
    return formatElapsed(session.statusSince, this.store.now());
  }

  /** Evidenziazione visiva: richiede attenzione e non è ancora stata vista. */
  protected highlighted(session: Session): boolean {
    return requiresAttention(session.status) && !session.seenAt;
  }

  /** Ingrandimento temporaneo: finisce allo scadere del tempo o appena la sessione viene vista. */
  protected zoomed(session: Session): boolean {
    return this.store.zoomed().has(session.id) && this.highlighted(session);
  }

  /**
   * Tocco sulla card. Se sta avvisando, il primo tocco la segna solo come vista: spegne evidenziazione
   * e ripetizione del suono senza aprire nulla. Il dettaglio si apre toccando una card già vista.
   */
  protected open(session: Session): void {
    if (this.highlighted(session)) {
      void this.store.markSeen(session);
      return;
    }
    this.selectedId.set(session.id);
  }

  /**
   * Il pulsante del focus compare sulle sessioni ancora aperte se la loro macchina ha l'agente collegato,
   * oppure se sono in controllo remoto e un altro Mac con l'agente ha l'app Claude.
   */
  protected focusable(session: Session): boolean {
    return (
      !session.archived &&
      session.status !== 'ENDED' &&
      (this.store.focusMachines().has(session.machine) || this.claudeAppOnly(session))
    );
  }

  /** La sessione si può aprire solo nell'app Claude di un altro Mac: la sua macchina non ha l'agente. */
  private claudeAppOnly(session: Session): boolean {
    return (
      !this.store.focusMachines().has(session.machine) &&
      !!session.remoteSessionId &&
      [...this.store.claudeAppMachines()].some((machine) => machine !== session.machine)
    );
  }

  /**
   * Porta in primo piano la sessione sul Mac in uso: la sua finestra, oppure l'app Claude di un altro Mac
   * se la sessione è in controllo remoto. La scelta la fa il server.
   * Chi va alla finestra ha visto l'avviso: la card viene anche segnata come vista.
   */
  protected async focus(session: Session): Promise<void> {
    if (this.focusStatus(session) === 'sending') {
      return;
    }
    this.setFocusState(session.id, { status: 'sending' });
    if (this.highlighted(session)) {
      void this.store.markSeen(session);
    }
    const result = await this.store.focus(session);
    this.setFocusState(session.id, result ? { status: 'ok', result } : { status: 'error' });
    setTimeout(() => this.setFocusState(session.id, null), 3_000);
  }

  protected focusStatus(session: Session): FocusState['status'] | null {
    return this.focusState().get(session.id)?.status ?? null;
  }

  protected focusIcon(session: Session): string {
    switch (this.focusStatus(session)) {
      case 'sending':
        return 'pi pi-spin pi-spinner';
      case 'ok':
        return 'pi pi-check';
      case 'error':
        return 'pi pi-times';
      default:
        return this.claudeAppOnly(session) ? 'pi pi-external-link' : 'pi pi-window-maximize';
    }
  }

  protected focusLabel(session: Session): string {
    const state = this.focusState().get(session.id);
    switch (state?.status) {
      case 'sending':
        return 'Apertura…';
      case 'ok':
        // Dove è finita la sessione: con il controllo remoto può essere un Mac diverso dal suo
        return state.result.claudeApp ? `App Claude su ${state.result.machine}` : `Finestra su ${state.result.machine}`;
      case 'error':
        return 'Non raggiungibile';
      default:
        return this.claudeAppOnly(session) ? 'Apri in Claude' : 'Apri finestra';
    }
  }

  private setFocusState(id: string, state: FocusState | null): void {
    this.focusState.update((map) => {
      const next = new Map(map);
      if (state) {
        next.set(id, state);
      } else {
        next.delete(id);
      }
      return next;
    });
  }
}
