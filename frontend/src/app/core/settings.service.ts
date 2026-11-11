import { Injectable, signal } from '@angular/core';

const SETTINGS_KEY = 'cm.settings';

/** Raggruppamento delle card nella dashboard. */
export type GroupBy = 'none' | 'project' | 'machine' | 'both';

export type Theme = 'dark' | 'light';

/** Colore di sfondo per tema, usato anche dal browser per la barra del titolo (meta theme-color). */
const THEME_BACKGROUND: Record<Theme, string> = { dark: '#0b0f17', light: '#f3f5f9' };

export interface Settings {
  /** null finché l'utente non ha scelto se attivare i suoni. */
  soundsEnabled: boolean | null;
  /** Volume da 0 a 100. */
  volume: number;
  /** Ripetizione del suono di NEEDS_ATTENTION. */
  repeatEnabled: boolean;
  repeatSeconds: number;
  groupBy: GroupBy;
  /** Notifiche push su questo dispositivo: false quando l'utente le ha spente dalle impostazioni. */
  pushEnabled: boolean;
  /** Ingrandimento temporaneo della card che passa a uno stato che richiede attenzione. */
  zoomEnabled: boolean;
  zoomSeconds: number;
  theme: Theme;
}

const DEFAULTS: Settings = {
  soundsEnabled: null,
  volume: 70,
  repeatEnabled: false,
  repeatSeconds: 30,
  groupBy: 'none',
  pushEnabled: true,
  zoomEnabled: false,
  zoomSeconds: 10,
  theme: 'dark',
};

/** Impostazioni della dashboard, salvate sul dispositivo. */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  readonly settings = signal<Settings>(load());

  constructor() {
    applyTheme(this.settings().theme);
  }

  toggleTheme(): void {
    this.update({ theme: this.settings().theme === 'dark' ? 'light' : 'dark' });
  }

  update(patch: Partial<Settings>): void {
    const next = { ...this.settings(), ...patch };
    this.settings.set(next);
    applyTheme(next.theme);
    try {
      localStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
    } catch {
      // Archiviazione non disponibile: le impostazioni valgono solo per questa apertura
    }
  }
}

/** La classe "app-dark" su <html> governa sia le variabili --cm-* sia il tema di PrimeNG. */
function applyTheme(theme: Theme): void {
  document.documentElement.classList.toggle('app-dark', theme === 'dark');
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', THEME_BACKGROUND[theme]);
}

function load(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    return raw ? { ...DEFAULTS, ...(JSON.parse(raw) as Partial<Settings>) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}
