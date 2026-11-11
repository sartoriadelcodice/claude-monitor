// Development server with the PrimeUI license key embedded in the bundle.
// Usage: node scripts/serve.mjs [other ng serve options]
import { spawnSync } from 'node:child_process';

import { defineArgs } from './defines.mjs';

const result = spawnSync('npx', ['ng', 'serve', ...defineArgs({ withBuildId: false }), ...process.argv.slice(2)], {
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
process.exit(result.status ?? 1);
