import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';

import { I18n } from '../../core/i18n.service';
import { STATUS_META, Session, SessionEvent, formatElapsed, formatModel } from '../../core/models';
import { SessionStore } from '../../core/session-store.service';

/** Detail of a session with its event history. */
@Component({
  selector: 'app-session-detail',
  imports: [TranslocoPipe, ButtonModule, DialogModule],
  template: `
    <p-dialog [visible]="true" appendTo="body" [blockScroll]="false" (visibleChange)="!$event && closed.emit()" [modal]="true"
              [dismissableMask]="true" [draggable]="false" [header]="session().project"
              [style]="{ width: 'min(720px, 96vw)', maxHeight: '90dvh' }">
      <section class="summary" [style.--status]="meta().color">
        <span class="badge">{{ 'status.' + session().status | transloco }}</span>
        <span>{{ 'time.since' | transloco: { duration: elapsed() } }}</span>
        <span class="muted"><i class="pi pi-desktop"></i> {{ session().machine }}</span>
        @if (model(); as modelName) {
          <span class="muted"><i class="pi pi-microchip-ai"></i> {{ modelName }}</span>
        }
        @if (session().remoteSessionId) {
          <span class="muted"><i class="pi pi-globe"></i> {{ 'session.remoteControl' | transloco }}</span>
        }
      </section>
      @if (session().title) {
        <p class="title">{{ session().title }}</p>
      }
      @if (session().cwd) {
        <p class="muted path">{{ session().cwd }}</p>
      }
      @if (session().lastNotificationMessage) {
        <p class="message">{{ session().lastNotificationMessage }}</p>
      }

      <h3>{{ 'session.history' | transloco }}</h3>
      @if (loading()) {
        <p class="muted">{{ 'common.loading' | transloco }}</p>
      } @else if (error()) {
        <p class="muted">{{ 'session.historyUnavailable' | transloco }}</p>
      } @else {
        <ol class="timeline">
          @for (event of events(); track event.id) {
            <li [style.--status]="statusColor(event)">
              <div class="row">
                <strong>{{ event.eventName }}</strong>
                <time [attr.datetime]="event.receivedAt">{{ i18n.date(event.receivedAt, 'timeline') }}</time>
              </div>
              <div class="muted">
                → {{ 'status.' + event.statusAfter | transloco }}
                @if (event.notificationType) {
                  · {{ event.notificationType }}
                }
              </div>
              @if (event.message) {
                <div class="event-message">{{ event.message }}</div>
              }
            </li>
          } @empty {
            <li class="muted">{{ 'session.noEvents' | transloco }}</li>
          }
        </ol>
      }

      <ng-template #footer>
        @if (session().archived) {
          <p-button [label]="'session.restore' | transloco" icon="pi pi-undo" severity="secondary" (onClick)="archive(false)" />
        } @else {
          <p-button [label]="'session.archive' | transloco" icon="pi pi-inbox" severity="secondary" (onClick)="archive(true)" />
        }
        <p-button [label]="'common.close' | transloco" (onClick)="closed.emit()" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .summary {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 12px;
    }
    .badge {
      padding: 4px 10px;
      border-radius: 999px;
      background: color-mix(in srgb, var(--status) 22%, transparent);
      color: var(--status);
      font-weight: 600;
    }
    .muted {
      color: var(--cm-muted);
    }
    .title {
      margin: 12px 0 0;
      font-weight: 600;
    }
    .path {
      margin: 8px 0 0;
      font-family: ui-monospace, Menlo, monospace;
      font-size: 0.85rem;
      word-break: break-all;
    }
    .message {
      margin: 12px 0 0;
      padding: 10px 12px;
      border-radius: 8px;
      background: var(--cm-surface-2);
    }
    h3 {
      margin: 20px 0 8px;
      font-size: 1rem;
    }
    .timeline {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .timeline li {
      padding: 8px 0 8px 14px;
      border-left: 3px solid var(--status, var(--cm-border));
      margin-bottom: 6px;
    }
    .row {
      display: flex;
      justify-content: space-between;
      gap: 12px;
    }
    time {
      color: var(--cm-muted);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .event-message {
      margin-top: 4px;
    }
  `,
})
export class SessionDetail implements OnInit {
  private readonly store = inject(SessionStore);
  protected readonly i18n = inject(I18n);

  readonly session = input.required<Session>();
  readonly closed = output<void>();

  protected readonly events = signal<SessionEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly meta = computed(() => STATUS_META[this.session().status]);
  protected readonly model = computed(() => {
    const model = this.session().model;
    return model ? formatModel(model) : null;
  });
  protected readonly elapsed = computed(() =>
    formatElapsed(this.session().statusSince, this.store.now(), (key, params) => this.i18n.t(key, params)),
  );

  async ngOnInit(): Promise<void> {
    try {
      this.events.set(await this.store.events(this.session().id));
    } catch {
      this.error.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  protected statusColor(event: SessionEvent): string {
    return STATUS_META[event.statusAfter].color;
  }

  protected async archive(archived: boolean): Promise<void> {
    await this.store.setArchived(this.session(), archived);
    this.closed.emit();
  }
}
