// Production build with the build id and the PrimeUI license key embedded in the bundle.
// Usage: node scripts/build.mjs [other ng build options]
import { spawnSync } from 'node:child_process';

import { defineArgs } from './defines.mjs';

const result = spawnSync('npx', ['ng', 'build', ...defineArgs(), ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
process.exit(result.status ?? 1);
