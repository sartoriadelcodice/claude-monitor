# Claude Monitor

Dashboard that shows the state of every active Claude Code session across several machines and highlights the
ones that need attention. Used mostly as a PWA on iPad, with sound alerts and push notifications.

## Architecture

1. **Claude Code hooks** (`hooks/`): on every machine a script sends the events to the backend.
2. **Backend** (`backend/`): Java 21, Spring Boot 3.5, PostgreSQL, Flyway. Exposes REST, SSE and Web Push.
3. **Frontend** (`frontend/`): Angular 22, PrimeNG 22, PWA with the Angular service worker, Transloco for i18n.
4. **Deployment** (`docker-compose.yml`): postgres, backend, frontend served by nginx. The frontend is the only
   entry point and proxies `/api`. The `tunnel` profile adds cloudflared for local testing: the tunnel is managed
   from the Cloudflare dashboard and only receives `CLAUDE_MONITOR_TUNNEL_TOKEN`.

## Commands

### Backend

```bash
cd backend
mvn test                     # all tests
mvn test -Dtest=SessionStateMapperTest
mvn spring-boot:run          # needs PostgreSQL on localhost:5432 and the variables of the .env file
```

### Frontend

Angular 22 needs Node 22.22.3, 24.15 or 26 and later. On the development Mac the default Node is older:
prefix `npm` and `npx` commands with `PATH=/opt/homebrew/opt/node@26/bin:$PATH`.

```bash
cd frontend
npm ci
npm start                    # http://localhost:4200, proxies /api to localhost:8080
npm run build                # production build in dist/frontend/browser, with build identifier
npm run i18n:check           # translations aligned with en.json and every key used in the code defined
```

The service worker is active only in the production build: PWA, push and sound caching can't be tested with
`ng serve`.

### Full stack

```bash
cp .env.example .env         # then set the values
docker compose up -d --build                    # dashboard on http://localhost:8088
docker compose --profile tunnel up -d           # adds the Cloudflare tunnel (http2 protocol)
scripts/demo-events.sh http://localhost:8088 <hook key> seed   # fake sessions, for trying the UI
```

## State model

The event → state mapping lives entirely in `SessionStateMapper` (pure logic, covered by tests).
States: `IDLE`, `WORKING`, `NEEDS_ATTENTION`, `WAITING_INPUT`, `BACKGROUND`, `ENDED`, `STALE`.

- `Notification` leads to `NEEDS_ATTENTION` only for `permission_prompt` and `elicitation_dialog`.
- `PostToolUse` brings a `NEEDS_ATTENTION`, `IDLE` or `STALE` session back to `WORKING`; it doesn't touch
  `WAITING_INPUT` because hooks are asynchronous and can arrive out of order.
- `Stop` leads to `BACKGROUND` instead of `WAITING_INPUT` when `background_tasks` or `session_crons` in the payload
  are not empty: the session will resume on its own, so no sound and no push. The next `Stop` without work in
  progress leads to `WAITING_INPUT`.
- `PostToolUse` also brings a `BACKGROUND` session back to `WORKING`, but only when it does not come from a subagent
  (`agent_id` missing): tools of background subagents don't mean the session woke up.
- `STALE` is set by `StaleSessionJob` after `STALE_AFTER` without events.
- `ENDED` sessions are archived right away: they leave the main view and stay in the history.
  A new event that changes the state (a resume, for example) brings them back into view.
- Project name and session folder are fixed at creation and at every `SessionStart` with `source` `startup` or
  `resume`: in other events `cwd` follows Claude's `cd` and must not be used to rename the card.
- `PostModelSwitch` doesn't change the state: it only updates the model shown on the card.
- The card model comes from `SessionStart` (not always present), from `PostModelSwitch` and, when missing,
  from the transcript: the hook script adds it as `model` to `Stop` and `PostToolUse`.

## Backend structure

Base package `it.sartoriadelcodice.claudemonitor`, organized by feature:
`config`, `auth`, `user`, `hookkey`, `event`, `session`, `stream`, `push`, `focus`.

- Session changes publish a `SessionChangedEvent`; `SessionChangeListener` forwards it after commit to SSE
  (`SessionBroadcaster`) and Web Push (`PushNotifier`).
- Authentication always with the `Authorization: Bearer` header: personal hook key for `POST /api/events`
  and for `/api/agent/**` (role `HOOK`), JWT for everything else (roles `USER` and `ADMIN`). `AuthFilter` reloads
  the user from the database on every request, so disabling a user and changing a role take effect immediately.
- The hook payload is stored without the conversation content (`HookEvent.withoutPrivateContent`); a new field
  carrying prompt text, answers or commands must be added there and in the scripts in `hooks/`.
- The endpoint of a push subscription is an address the backend sends requests to: it always goes through
  `PushEndpointValidator` (https to public addresses only).
- Push notifications are written in the language of each device: the dashboard sends `language` with the
  subscription and again when the interface language changes. Texts are in `messages*.properties`
  (English default, `fallback-to-system-locale: false`); `PushNotifier.LANGUAGES` lists the supported ones.
- Hook keys (`hookkey`) are random with the `cmk_` prefix; the database stores only their SHA-256 hash.
- Window focus (`focus` package): the dashboard calls `POST /api/sessions/{id}/focus` and `FocusAgentRegistry`
  forwards the session identifier to the agent of the machine, connected to `GET /api/agent/stream` with the hook
  key. Only the identifier may travel to the agent: application, terminal and folder stay in the local file
  written by the hook. The agent (`hooks/claude-monitor-agent.sh`, macOS only, enabled by hand) must never run
  commands received from the network nor send keystrokes.
- Remote Control (Claude Code Remote Control): on every event the hook sends `remote_session_id`, the
  `bridgeSessionId` read from `~/.claude/sessions/<pid>.json`; when it is missing, Remote Control is off.
  For these sessions `FocusService` picks the Mac used last: it asks the agents for their idle seconds
  (`probe` event, answer on `POST /api/agent/activity`) and brings the window forward on the session's Mac,
  or sends the `remote` event to a Mac with the Claude app, whose agent opens `claude://code/<id>`.
  Here too only the identifier travels, accepted only in the `session_`/`cse_` form by both `HookEvent` and the
  agent, which builds the link itself. Idle time is reported only on request, never continuously.
- Focus protections, not to be loosened: the agent counts anomalous requests (and separately the idle-time
  requests, with their own hourly limit) and locks itself (`agent.locked`, removed only on the Mac with `unlock`,
  never from the network); the user's switch (`users.focus_enabled`, `FocusService`) disconnects the agents and
  makes requests and streams answer 403.
- Multi-user: sessions, history, SSE stream and push subscriptions belong to a user.
  Every new query or endpoint on this data must filter by the `userId` taken from `AuthUser`.
- `AdminBootstrap` creates the initial administrator from `ADMIN_USERNAME` and `ADMIN_PASSWORD` only when no users
  exist, and assigns it the data without an owner and the `HOOK_TOKEN`, if set.
- The database schema changes only through new Flyway migrations in `src/main/resources/db/migration`;
  Hibernate runs in `validate` mode. Never edit an applied migration, not even its comments: Flyway checksums
  the whole file. That is why V1–V5 keep their original Italian comments.
- Error messages of the API are in English and are not shown as they are: the frontend maps status codes to
  translated messages.

## Frontend structure

- `core/`: services (`SessionStore`, `AudioService`, `PushService`, `SettingsService`, `AuthService`, `AdminApi`,
  `I18n`).
- `features/`: `login`, `dashboard`, `session-detail`, `settings`, `hook-keys`, `users`, `account`.
- Standalone components, state with signals, templates with `@if` and `@for`.
- The SSE stream uses `fetch` with the `Authorization` header and `ngsw-bypass`, not `EventSource`.
- Sounds play only on the state transitions detected in `SessionStore.trackTransition`.
- The first tap on a highlighted card only marks it as seen (stops highlight and sound repetition); the detail
  opens by tapping a card already seen (`Dashboard.open`).
- Cards of machines with the agent connected (`SessionStore.focusMachines`) have the focus button: a full-width
  "Open window" bar attached below the card (outside the card button, because a button can't contain another
  one), filled with the state color while the card is alerting. `Dashboard.focus` brings the window forward and
  marks the card as seen; the outcome tells on which machine the session opened. Remote Control sessions of
  machines without an agent get the "Open in Claude" bar if another Mac with the agent has the Claude app
  (`SessionStore.claudeAppMachines`).
- Light and dark theme: all colors go through the `--cm-*` variables of `styles.scss`, never hard-coded values in
  components. The `app-dark` class on `<html>` enables the dark theme for PrimeNG too; `SettingsService` sets it
  and, before startup, so does the script in `index.html` (same `cm.settings` key).
- After the first activation sounds stay on: `AudioService` unlocks audio on its own at the first tap after each
  opening (a browser limitation), without asking the user.
- `UpdateService` checks for new versions every 5 minutes and when the app returns to the foreground; when the
  service worker has downloaded one, it activates it and reloads the page.
- After a hook key is created the dialog offers the installation script: `HookInstaller` composes it in the
  browser from the `hooks/install.sh` and `hooks/install.ps1` templates and the hook scripts.
  The build publishes them under `/hooks` through the `frontend/hook-scripts` symlink; that is why the Docker build
  context of the frontend is the repository root.
- Icons and sounds in `frontend/public` are generated by `scripts/generate-assets.py`
  (`python3 scripts/generate-assets.py frontend/public`, needs Pillow): don't edit them by hand.
- PrimeNG 22 needs a PrimeUI license key. It is not in the repository: it is the `PRIMENG_LICENSE` variable of the
  `.env` file, which `frontend/scripts/defines.mjs` puts into the bundle as `PRIMENG_LICENSE_KEY`
  (`npm start` and `npm run build`; in Docker it arrives as a build argument). The terms allow it in the bundle,
  but it must not be published for others to use. A direct `npx ng serve` starts without the key.

## Internationalization

- The interface is in English and Italian, with Transloco. Translations are in `frontend/src/i18n/<lang>.json`;
  `en.json` is the reference. They are bundled as lazy chunks (`I18n`, `BundledTranslationLoader`), so they are
  cached by the service worker and work offline; the app starts only after the active language is loaded.
- Every text shown to the user goes through a key: the `transloco` pipe in templates, `I18n.t()` in code (it reads
  the `language` signal, so computed values follow a language change). Error signals hold keys, not texts.
- A new key goes into every language file; `npm run i18n:check` (also in CI) fails on missing keys, different
  `{{ params }}` or unknown keys used in the code.
- Dates use `I18n.date()` (`Intl`, with the browser locale when it matches the language).
- The language is a device setting (`Settings.language`, `auto` follows the browser). `index.html` sets
  `<html lang>` before startup with the same rule.
- To add a language: a new `src/i18n/<lang>.json`, the code in `LANGUAGES`, `LANGUAGE_NAMES` and `TRANSLATIONS`
  of `i18n.service.ts`, the check in `index.html`, `messages_<lang>.properties` and `PushNotifier.LANGUAGES` in the
  backend.
- Hook scripts, agent, logs, API errors and documentation are in English only.

## iPadOS: constraints to respect

The dashboard is used mostly as a PWA on iPad, where Safari behaves in ways Chrome doesn't show.
The rules below come from real problems: don't "simplify" them without testing on an iPad.

- **Audio**: unlocking happens only inside a `click` (or `keydown`) event. `pointerdown` and `touchend` don't count
  as a gesture; `resume()` must never be called at startup or outside a gesture.
- **Audio**: don't create and close `AudioContext` instances repeatedly. It can leave the iPad audio stuck with
  `InvalidStateError: Failed to start the audio device` until the device is restarted.
  If that error shows up, restart the iPad before touching the code.
- **Audio**: if Web Audio is refused, `AudioService` falls back to `<audio>` elements, which on iPadOS ignore the
  volume setting. The "Audio status" line in the settings shows which path is active.
- **Taps**: the document never scrolls (`html` and `body` with `overflow: hidden`); only `app-dashboard` scrolls.
  Modal dialogs use `appendTo="body"`. If the page scrolls under a modal dialog, on iPadOS taps land one row off.
- **Click anywhere**: on touch devices `body` has `cursor: pointer`, otherwise iPadOS doesn't fire `click` on
  non-interactive areas and the first tap doesn't unlock audio.
- **Testing**: the PWA on the iPad can't be inspected from the Mac (it needs Developer Mode).
  To diagnose, have the app send a temporary trace to the server and read it from the nginx logs.

## Current environment

- The stack runs locally on the Mac with docker compose, exposed by the Cloudflare tunnel of the `tunnel` profile.
- Repository: `github.com/sartoriadelcodice/claude-monitor`, public: no secrets, license keys or data about
  machines and clients in files and commit messages. Screenshots come from the demo stack
  (`scripts/demo-events.sh`), never from real sessions.
- CI (`.github/workflows/ci.yml`) runs the backend tests, the translation check and the frontend build on every
  push and pull request; `.github/workflows/pages.yml` publishes the `site/` folder to GitHub Pages.
- The tunnel token is called `CLAUDE_MONITOR_TUNNEL_TOKEN` and not `TUNNEL_TOKEN`, because a variable with that
  name already set in the shell would take precedence over the `.env` file.
- Hooks are installed in `~/.claude/settings.json` of the monitored machines; the up-to-date example is
  `hooks/settings.example.json`.

## Versions

- The version is the one in `frontend/package.json`, aligned with `backend/pom.xml`: bump it at every release and
  add the changes to `CHANGELOG.md`.
- `frontend/scripts/build.mjs` adds the build identifier (UTC date and time). Both appear in the dashboard settings.
- To publish a frontend change it is enough to rebuild the image: devices update on their own.

## Conventions

- Code, comments, documentation and commit messages in English. The interface is translated (see
  Internationalization); `README.it.md` and `site/it/` are the Italian versions of the README and of the site and
  must follow changes to their English counterparts.
- Every change to the state mapping comes with its test in `SessionStateMapperTest`.
- No secrets in the repository: `.env` is excluded from git.
- Commit messages: title and description only. No `Co-Authored-By` lines, no links to Claude Code sessions and no
  references to private repositories or other hosting services.
- Commit author: `Sartoria del Codice <info@sartoriadelcodice.it>` (already set in the local git configuration of the
  repository).
