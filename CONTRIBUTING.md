# Contributing to Claude Monitor

Thanks for taking the time to contribute! Bug reports, ideas, translations, documentation fixes and code are all
welcome. This guide explains how to get a working setup and what a good pull request looks like.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- **Report a bug** — open an [issue](https://github.com/sartoriadelcodice/claude-monitor/issues/new/choose) with
  the steps to reproduce it, what you expected and what happened. Include versions (shown in the dashboard
  settings), browser and operating system. Remove hook keys, host names and project names you don't want to share.
- **Suggest a feature** — open a feature request and describe the problem it solves before the solution.
- **Translate** — add a language or improve an existing one (see [Adding a language](#adding-a-language)).
- **Fix or improve the code** — look for issues labelled `good first issue` or `help wanted`, or open an issue first
  for larger changes so we can agree on the approach.
- **Security issues** — never in public issues: follow [SECURITY.md](SECURITY.md).

## Development setup

Requirements: Java 21, Maven, Node 22.22.3 / 24.15 / 26+, Docker with Docker Compose, PostgreSQL (or the one in the
compose file).

```bash
git clone https://github.com/sartoriadelcodice/claude-monitor.git
cd claude-monitor
cp .env.example .env            # set DB_PASSWORD, ADMIN_PASSWORD, JWT_SECRET (and PRIMENG_LICENSE if you have one)
```

**Whole stack** (closest to production):

```bash
docker compose up -d --build    # http://localhost:8088
```

**Backend and frontend separately** (fast reload):

```bash
docker compose up -d postgres   # or any PostgreSQL on localhost:5432
cd backend && mvn spring-boot:run
cd frontend && npm ci && npm start   # http://localhost:4200, proxies /api to localhost:8080
```

**Fake data:** create a hook key in the dashboard, then

```bash
scripts/demo-events.sh http://localhost:8088 <hook-key> seed   # sessions in every state
scripts/demo-events.sh http://localhost:8088 <hook-key> live   # keeps changing states (Ctrl+C to stop)
scripts/demo-events.sh http://localhost:8088 <hook-key> end    # ends them
```

The service worker (PWA, push, sound cache, automatic updates) is active only in the production build, so test those
features with Docker, not with `npm start`.

## Before opening a pull request

Run the same checks as CI:

```bash
cd backend && mvn test
cd frontend && npm run i18n:check && npm run build
```

And make sure that:

- the change is focused: one topic per pull request;
- every change to the event → state mapping (`SessionStateMapper`) comes with a test in `SessionStateMapperTest`;
- database changes are new Flyway migrations (`V<n>__description.sql`); applied migrations are never edited, not
  even their comments, because Flyway checksums the whole file;
- every new query or endpoint on sessions, history, streams or push subscriptions filters by the current user;
- every text shown in the interface goes through a translation key, present in all `frontend/src/i18n/*.json` files;
- colors go through the `--cm-*` CSS variables, so both themes keep working;
- the iPadOS constraints in [CLAUDE.md](CLAUDE.md#ipados-constraints-to-respect) are respected (audio unlocking,
  scrolling, modal dialogs): they come from real bugs;
- user-facing behaviour changes are reflected in the README, the user guide (`docs/`) and their Italian versions,
  and listed under *Unreleased* in [CHANGELOG.md](CHANGELOG.md);
- no secrets, license keys or real machine, client or project names end up in code, screenshots or commit messages.

## Coding conventions

- Code, comments, documentation and commit messages in English. Comments explain *why*, not *what*.
- Backend: Java 21, Spring Boot, packages by feature (`session`, `push`, `focus`…), constructor injection, records
  for DTOs.
- Frontend: Angular standalone components, signals for state, `@if`/`@for` control flow, PrimeNG components,
  `inject()` instead of constructor parameters. Formatting follows `frontend/.prettierrc`.
- Shell scripts: `set -euo pipefail`, quote every variable, keep them POSIX-friendly where possible; the PowerShell
  hook must keep working on Windows PowerShell 5.1.
- Architecture, state model and security rules are described in [CLAUDE.md](CLAUDE.md): read it before changing the
  hooks, the focus agent or the authentication.

## Commit messages

- A short title in the imperative mood (`Add Spanish translation`, `Fix reconnection after sleep`), optionally
  followed by a blank line and a description of what changed and why.
- No references to private repositories or internal tools.

## Adding a language

1. Copy `frontend/src/i18n/en.json` to `frontend/src/i18n/<code>.json` (ISO 639-1 code, e.g. `es`) and translate the
   values. Keep the keys and the `{{ params }}` unchanged; HTML tags such as `<code>` stay as they are.
2. In `frontend/src/app/core/i18n.service.ts` add the code to `LANGUAGES`, its own name to `LANGUAGE_NAMES` and the
   import to `TRANSLATIONS`.
3. In `frontend/src/index.html` add the code to the check that sets `<html lang>` before startup.
4. For push notifications add `backend/src/main/resources/messages_<code>.properties` and the code to
   `PushNotifier.LANGUAGES`, then extend `PushNotifierTest`.
5. Run `npm run i18n:check` and `mvn test`, then check the interface with the language selected in the settings.

## Releases

Maintainers bump the version in `frontend/package.json` and `backend/pom.xml` (kept aligned), move the *Unreleased*
entries of the changelog under the new version and tag the commit `v<version>`.

## License

By contributing you agree that your contributions are licensed under the [MIT License](LICENSE).
