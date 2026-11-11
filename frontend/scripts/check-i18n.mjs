// Checks the interface translations in src/i18n:
//   1. every language has exactly the keys of en.json (the reference), with the same {{ params }};
//   2. every key written literally in the source code exists in en.json.
// Exits with code 1 and lists the problems otherwise. Run with: npm run i18n:check
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const i18nDir = join(root, 'src/i18n');
const reference = 'en';

/** Flattens {"a": {"b": "x"}} into {"a.b": "x"}. */
function flatten(object, prefix = '', out = {}) {
  for (const [key, value] of Object.entries(object)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object') {
      flatten(value, path, out);
    } else {
      out[path] = String(value);
    }
  }
  return out;
}

const params = (text) => [...text.matchAll(/{{\s*(\w+)\s*}}/g)].map((m) => m[1]).sort().join(',');

const languages = Object.fromEntries(
  readdirSync(i18nDir)
    .filter((file) => file.endsWith('.json'))
    .map((file) => [file.replace(/\.json$/, ''), flatten(JSON.parse(readFileSync(join(i18nDir, file), 'utf8')))]),
);
const base = languages[reference];
const problems = [];

for (const [language, keys] of Object.entries(languages)) {
  if (language === reference) {
    continue;
  }
  for (const key of Object.keys(base)) {
    if (!(key in keys)) {
      problems.push(`${language}.json: missing "${key}"`);
    } else if (params(keys[key]) !== params(base[key])) {
      problems.push(`${language}.json: "${key}" has params [${params(keys[key])}], expected [${params(base[key])}]`);
    } else if (!keys[key].trim()) {
      problems.push(`${language}.json: "${key}" is empty`);
    }
  }
  for (const key of Object.keys(keys)) {
    if (!(key in base)) {
      problems.push(`${language}.json: "${key}" is not in ${reference}.json`);
    }
  }
}

// Literal keys in the code: 'namespace.key' with a namespace that exists in en.json
const namespaces = new Set(Object.keys(base).map((key) => key.split('.')[0]));
const sourceFiles = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      walk(path);
    } else if (/\.(ts|html)$/.test(name)) {
      sourceFiles.push(path);
    }
  }
})(join(root, 'src/app'));

for (const file of sourceFiles) {
  const text = readFileSync(file, 'utf8');
  for (const match of text.matchAll(/'([a-z]+)\.([A-Za-z0-9_.]+)'/g)) {
    const [, namespace, rest] = match;
    const key = `${namespace}.${rest}`;
    if (namespaces.has(namespace) && !rest.endsWith('.') && !(key in base)) {
      problems.push(`${relative(root, file)}: unknown key "${key}"`);
    }
  }
}

if (problems.length) {
  console.error(`Translation problems (${problems.length}):\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(`Translations OK: ${Object.keys(base).length} keys in ${Object.keys(languages).join(', ')}.`);
