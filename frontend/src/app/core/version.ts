import { version } from '../../../package.json';

/** Sostituita in fase di build da scripts/build.mjs; assente con ng serve. */
declare const APP_BUILD_ID: string | undefined;

/** Versione dell'app: quella di package.json, da incrementare a ogni rilascio. */
export const APP_VERSION: string = version;

/** Identificativo della build (data e ora UTC), utile per distinguere due build della stessa versione. */
export const APP_BUILD: string = typeof APP_BUILD_ID === 'string' ? APP_BUILD_ID : 'sviluppo';
