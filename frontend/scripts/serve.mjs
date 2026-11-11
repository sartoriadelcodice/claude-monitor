// Server di sviluppo con la chiave di licenza PrimeUI inserita nel bundle.
// Uso: node scripts/serve.mjs [altre opzioni di ng serve]
import { spawnSync } from 'node:child_process';

import { defineArgs } from './defines.mjs';

const result = spawnSync('npx', ['ng', 'serve', ...defineArgs({ withBuildId: false }), ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
process.exit(result.status ?? 1);
