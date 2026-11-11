// Values embedded in the bundle with --define: build id and PrimeUI license key.
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/** Build id: UTC date and time. */
function buildId() {
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`
  );
}

/** PrimeUI key: from the PRIMENG_LICENSE variable or, locally, from the .env file in the repository root. */
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

/** --define arguments for ng build and ng serve. */
export function defineArgs({ withBuildId = true } = {}) {
  const license = primengLicense();
  if (!license) {
    console.warn('PRIMENG_LICENSE not set: PrimeNG will show the missing license notice.');
  }
  const args = ['--define', `PRIMENG_LICENSE_KEY=${JSON.stringify(license)}`];
  if (withBuildId) {
    args.push('--define', `APP_BUILD_ID=${JSON.stringify(buildId())}`);
  }
  return args;
}
