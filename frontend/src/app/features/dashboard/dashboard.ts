import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';

import { AudioService } from '../../core/audio.service';
import { AuthService } from '../../core/auth.service';
import { I18n } from '../../core/i18n.service';
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
  /** null when the cards are not grouped. */
  label: string | null;
  /** Sessions of the group that need attention and have not been seen yet. */
  attention: number;
  sessions: Session[];
}

/** Focus request in progress, or outcome of the last one with the machine where the session opened. */
type FocusState = { status: 'sending' } | { status: 'ok'; result: FocusResult } | { status: 'error' };

/** Main view: counter of the sessions waiting for the user and grid of cards. */
@Component({
  selector: 'app-dashboard',
  imports: [
    FormsModule,
    TranslocoPipe,
    ButtonModule,
    SelectModule,
    SelectButtonModule,
    SessionDetail,
    Settings,
    HookKeys,
    Users,
  ],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard implements OnInit, OnDestroy {
  protected readonly store = inject(SessionStore);
  protected readonly audio = inject(AudioService);
  protected readonly push = inject(PushService);
  protected readonly auth = inject(AuthService);
  protected readonly settingsService = inject(SettingsService);
  private readonly i18n = inject(I18n);

  protected readonly groupOptions = computed<{ label: string; value: GroupBy }[]>(() => [
    { label: this.i18n.t('dashboard.groupNone'), value: 'none' },
    { label: this.i18n.t('dashboard.groupProject'), value: 'project' },
    { label: this.i18n.t('dashboard.groupMachine'), value: 'machine' },
    { label: this.i18n.t('dashboard.groupBoth'), value: 'both' },
  ]);
  protected readonly darkTheme = computed(() => this.settingsService.settings().theme === 'dark');
  protected readonly groupBy = computed(() => this.settingsService.settings().groupBy);

  protected readonly machine = signal<string | null>(null);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly settingsOpen = signal(false);
  protected readonly hookKeysOpen = signal(false);
  protected readonly usersOpen = signal(false);
  /** Outcome of the last focus request per session, shown on the button for a few seconds. */
  protected readonly focusState = signal<ReadonlyMap<string, FocusState>>(new Map());

  protected readonly machines = computed(() =>
    [...new Set(this.store.sessions().map((s) => s.machine))].sort((a, b) => a.localeCompare(b)),
  );

  protected readonly visible = computed(() => {
    const machine = this.machine();
    return this.store.sorted().filter((s) => !machine || s.machine === machine);
  });

  /**
   * Cards split into groups as chosen by the user. Groups with sessions that need attention come
   * first; within each group the general ordering applies.
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
    // The first session of each group is the most urgent one: it decides the position of the group
    return [...byKey.values()].sort(
      (a, b) =>
        STATUS_META[a.sessions[0].status].order - STATUS_META[b.sessions[0].status].order ||
        a.key.localeCompare(b.key),
    );
  });

  /** Session open in the detail dialog, always read from the store to stay up to date. */
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
    // Tapping the selected button again yields null: keep the current value
    if (value) {
      this.settingsService.update({ groupBy: value });
    }
  }

  protected model(session: Session): string | null {
    return session.model ? formatModel(session.model) : null;
  }

  protected elapsed(session: Session): string {
    return formatElapsed(session.statusSince, this.store.now(), (key, params) => this.i18n.t(key, params));
  }

  /** Visual highlight: the session needs attention and has not been seen yet. */
  protected highlighted(session: Session): boolean {
    return requiresAttention(session.status) && !session.seenAt;
  }

  /** Temporary enlargement: it ends when the time is up or as soon as the session is seen. */
  protected zoomed(session: Session): boolean {
    return this.store.zoomed().has(session.id) && this.highlighted(session);
  }

  /**
   * Tap on the card. While the card is alerting, the first tap only marks it as seen: it stops the highlight
   * and the sound repetition without opening anything. Tapping a card already seen opens the detail.
   */
  protected open(session: Session): void {
    if (this.highlighted(session)) {
      void this.store.markSeen(session);
      return;
    }
    this.selectedId.set(session.id);
  }

  /**
   * The focus button appears on open sessions whose machine has the agent connected, or on Remote Control
   * sessions when another Mac with the agent has the Claude app.
   */
  protected focusable(session: Session): boolean {
    return (
      !session.archived &&
      session.status !== 'ENDED' &&
      (this.store.focusMachines().has(session.machine) || this.claudeAppOnly(session))
    );
  }

  /** The session can only be opened in the Claude app of another Mac: its own machine has no agent. */
  private claudeAppOnly(session: Session): boolean {
    return (
      !this.store.focusMachines().has(session.machine) &&
      !!session.remoteSessionId &&
      [...this.store.claudeAppMachines()].some((machine) => machine !== session.machine)
    );
  }

  /**
   * Brings the session to the front on the Mac in use: its window, or the Claude app of another Mac when the
   * session is under Remote Control. The server makes the choice.
   * Whoever goes to the window has seen the alert: the card is also marked as seen.
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
        return this.i18n.t('focus.sending');
      case 'ok':
        // Where the session ended up: with Remote Control it can be a Mac other than its own
        return this.i18n.t(state.result.claudeApp ? 'focus.claudeAppOn' : 'focus.windowOn', {
          machine: state.result.machine,
        });
      case 'error':
        return this.i18n.t('focus.unreachable');
      default:
        return this.i18n.t(this.claudeAppOnly(session) ? 'focus.openInClaude' : 'focus.openWindow');
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
