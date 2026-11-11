import {
  EnvironmentProviders,
  Injectable,
  computed,
  effect,
  inject,
  isDevMode,
  makeEnvironmentProviders,
  provideAppInitializer,
  signal,
} from '@angular/core';
import { Translation, TranslocoLoader, TranslocoService, provideTransloco } from '@jsverse/transloco';
import { PrimeNG } from 'primeng/config';
import { firstValueFrom } from 'rxjs';

import { SettingsService } from './settings.service';

/** Interface languages. English is the reference: every key lives in src/i18n/en.json first. */
export const LANGUAGES = ['en', 'it'] as const;
export type Language = (typeof LANGUAGES)[number];

/** Choice saved on the device: a language, or "auto" to follow the browser. */
export type LanguageSetting = Language | 'auto';

/** Names shown in the language picker, each written in its own language. */
export const LANGUAGE_NAMES: Record<Language, string> = { en: 'English', it: 'Italiano' };

/**
 * Translations are bundled as lazy chunks instead of being fetched from a URL: the service worker
 * caches them with the rest of the app, so they work offline and need no extra request at startup.
 */
const TRANSLATIONS: Record<Language, () => Promise<{ default: Translation }>> = {
  en: () => import('../../i18n/en.json'),
  it: () => import('../../i18n/it.json'),
};

@Injectable({ providedIn: 'root' })
class BundledTranslationLoader implements TranslocoLoader {
  getTranslation(lang: string): Promise<Translation> {
    const load = isLanguage(lang) ? TRANSLATIONS[lang] : TRANSLATIONS.en;
    return load().then((module) => module.default);
  }
}

function isLanguage(value: string | undefined): value is Language {
  return (LANGUAGES as readonly string[]).includes(value ?? '');
}

function browserTags(): readonly string[] {
  return navigator.languages?.length ? navigator.languages : [navigator.language];
}

/** First browser language the interface supports; English when there is none. */
export function browserLanguage(): Language {
  for (const tag of browserTags()) {
    const base = tag?.toLowerCase().split('-')[0];
    if (isLanguage(base)) {
      return base;
    }
  }
  return 'en';
}

export function resolveLanguage(setting: LanguageSetting): Language {
  return setting === 'auto' ? browserLanguage() : setting;
}

type DateStyle = 'date' | 'dateTime' | 'timeline';

const DATE_FORMATS: Record<DateStyle, Intl.DateTimeFormatOptions> = {
  date: { day: '2-digit', month: '2-digit', year: 'numeric' },
  dateTime: { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' },
  timeline: { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' },
};

/**
 * Active language of the interface, on top of Transloco.
 * Templates use the `transloco` pipe; code outside templates uses `t()`, which reads the `language`
 * signal so that computed values and templates calling it follow a language change.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly transloco = inject(TranslocoService);
  private readonly settingsService = inject(SettingsService);
  private readonly primeng = inject(PrimeNG);
  private requested: Language | null = null;

  /** Language on screen: it changes only once its translations are loaded. */
  readonly language = signal<Language>('en');

  /** Language chosen in the settings; "auto" follows the browser. */
  readonly setting = computed(() => this.settingsService.settings().language);

  /** Locale for dates: the browser's own when it matches the language (e.g. en-GB), otherwise the language. */
  private readonly locale = computed(() => {
    const language = this.language();
    return browserTags().find((tag) => tag?.toLowerCase().split('-')[0] === language) ?? language;
  });

  private readonly formatters = computed(() => {
    const locale = this.locale();
    const entries = Object.entries(DATE_FORMATS).map(([style, options]) => [
      style,
      new Intl.DateTimeFormat(locale, options),
    ]);
    return Object.fromEntries(entries) as Record<DateStyle, Intl.DateTimeFormat>;
  });

  constructor() {
    // Follows the device setting, including "auto" resolved against the browser languages
    effect(() => void this.use(resolveLanguage(this.setting())));
  }

  /** Loads the language chosen on this device; the app starts only after this, already translated. */
  init(): Promise<void> {
    return this.use(resolveLanguage(this.settingsService.settings().language));
  }

  async use(language: Language): Promise<void> {
    if (this.requested === language) {
      return;
    }
    this.requested = language;
    await firstValueFrom(this.transloco.load(language));
    // A quicker later choice wins over a load that finished late
    if (this.requested !== language) {
      return;
    }
    this.transloco.setActiveLang(language);
    document.documentElement.lang = language;
    this.primeng.setTranslation({
      emptyMessage: this.transloco.translate('primeng.emptyMessage'),
      aria: { close: this.transloco.translate('primeng.close') },
    });
    this.language.set(language);
  }

  t(key: string, params?: Record<string, unknown>): string {
    this.language();
    return this.transloco.translate(key, params);
  }

  date(iso: string | null, style: DateStyle): string {
    return iso ? this.formatters()[style].format(new Date(iso)) : '';
  }
}

export function provideI18n(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideTransloco({
      config: {
        availableLangs: [...LANGUAGES],
        defaultLang: 'en',
        fallbackLang: 'en',
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        missingHandler: { useFallbackTranslation: true, logMissingKey: isDevMode() },
      },
      loader: BundledTranslationLoader,
    }),
    provideAppInitializer(() => inject(I18n).init()),
  ]);
}
