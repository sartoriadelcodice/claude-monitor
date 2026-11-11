// Build di produzione con identificativo di build e chiave di licenza PrimeUI inseriti nel bundle.
// Uso: node scripts/build.mjs [altre opzioni di ng build]
import { spawnSync } from 'node:child_process';

import { defineArgs } from './defines.mjs';

const result = spawnSync('npx', ['ng', 'build', ...defineArgs(), ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
process.exit(result.status ?? 1);
