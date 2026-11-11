// Valori inseriti nel bundle con --define: identificativo di build e chiave di licenza PrimeUI.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Identificativo di build: data e ora UTC. */
function buildId() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`
  );
}

/** Chiave PrimeUI: dalla variabile PRIMENG_LICENSE oppure, in locale, dal file .env nella radice. */
function primengLicense() {
  if (process.env.PRIMENG_LICENSE) {
    return process.env.PRIMENG_LICENSE.trim();
  }
  const envFile = fileURLToPath(new URL('../../.env', import.meta.url));
  if (existsSync(envFile)) {
    const line = readFileSync(envFile, 'utf8')
      .split(/\r?\n/)
      .find((l) => l.startsWith('PRIMENG_LICENSE='));
    if (line) {
      return line.slice('PRIMENG_LICENSE='.length).trim().replace(/^(["'])(.*)\1$/, '$2');
    }
  }
  return '';
}

/** Argomenti --define per ng build e ng serve. */
export function defineArgs({ withBuildId = true } = {}) {
  const license = primengLicense();
  if (!license) {
    console.warn('PRIMENG_LICENSE non impostata: PrimeNG mostrerà l\'avviso di licenza mancante.');
  }
  const args = ['--define', `PRIMENG_LICENSE_KEY=${JSON.stringify(license)}`];
  if (withBuildId) {
    args.push('--define', `APP_BUILD_ID=${JSON.stringify(buildId())}`);
  }
  return args;
}
