import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import Aura from '@primeuix/themes/aura';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { provideI18n } from './core/i18n.service';

/**
 * PrimeUI license key, injected at build time by scripts/defines.mjs (PRIMENG_LICENSE variable).
 * It is not in the repository; without a key PrimeNG shows a notice in the bottom-right corner.
 */
declare const PRIMENG_LICENSE_KEY: string | undefined;
const primengLicense = typeof PRIMENG_LICENSE_KEY === 'string' && PRIMENG_LICENSE_KEY ? PRIMENG_LICENSE_KEY : undefined;

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    providePrimeNG({
      license: primengLicense,
      theme: {
        preset: Aura,
        // Dark theme by default: the class is set on <html> in index.html
        options: { darkModeSelector: '.app-dark' },
      },
    }),
    provideI18n(),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      // The SSE connection is always open: don't wait for the app to become "stable"
      registrationStrategy: 'registerImmediately',
    }),
  ],
};
