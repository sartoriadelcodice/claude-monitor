import { version } from '../../../package.json';

/** Replaced at build time by scripts/build.mjs; missing with ng serve. */
declare const APP_BUILD_ID: string | undefined;

/** App version: the one in package.json, to be bumped at every release. */
export const APP_VERSION: string = version;

/** Build identifier (UTC date and time), to tell apart two builds of the same version; null in development. */
export const APP_BUILD: string | null = typeof APP_BUILD_ID === 'string' ? APP_BUILD_ID : null;
