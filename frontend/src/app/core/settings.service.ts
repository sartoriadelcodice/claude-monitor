import { Injectable, signal } from '@angular/core';

import type { LanguageSetting } from './i18n.service';

const SETTINGS_KEY = 'cm.settings';

/** How the dashboard groups its cards. */
export type GroupBy = 'none' | 'project' | 'machine' | 'both';

export type Theme = 'dark' | 'light';

/** Background color per theme, also used by the browser for the title bar (meta theme-color). */
const THEME_BACKGROUND: Record<Theme, string> = { dark: '#0b0f17', light: '#f3f5f9' };

export interface Settings {
  /** null until the user has chosen whether to enable sounds. */
  soundsEnabled: boolean | null;
  /** Volume from 0 to 100. */
  volume: number;
  /** Repetition of the NEEDS_ATTENTION sound. */
  repeatEnabled: boolean;
  repeatSeconds: number;
  groupBy: GroupBy;
  /** Push notifications on this device: false when the user turned them off in the settings. */
  pushEnabled: boolean;
  /** Temporary enlargement of the card that moves to a state requiring attention. */
  zoomEnabled: boolean;
  zoomSeconds: number;
  theme: Theme;
  /** Interface language; "auto" follows the browser. */
  language: LanguageSetting;
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
  language: 'auto',
};

/** Dashboard settings, saved on the device. */
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
      // Storage unavailable: the settings only last until the app is closed
    }
  }
}

/** The "app-dark" class on <html> drives both the --cm-* variables and the PrimeNG theme. */
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
