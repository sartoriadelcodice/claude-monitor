import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import Aura from '@primeuix/themes/aura';
import { providePrimeNG } from 'primeng/config';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';

/**
 * Chiave di licenza PrimeUI, inserita in fase di build da scripts/defines.mjs (variabile PRIMENG_LICENSE).
 * Non sta nel repository; senza chiave PrimeNG mostra un avviso in basso a destra.
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
        // Tema scuro di default: la classe è impostata su <html> in index.html
        options: { darkModeSelector: '.app-dark' },
      },
    }),
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      // La connessione SSE resta sempre aperta: non si aspetta che l'app sia "stabile"
      registrationStrategy: 'registerImmediately',
    }),
  ],
};
