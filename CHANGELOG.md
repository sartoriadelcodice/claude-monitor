# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.7.0] - 2026-11-11

### Added

- Interface in English and Italian with [Transloco](https://jsverse.gitbook.io/transloco): the language follows the
  browser or a per-device choice in the settings; translations work offline.
- Push notifications in the language of each device.
- `scripts/demo-events.sh`, which fills a dashboard with fake sessions to try it out or take screenshots.
- `npm run i18n:check`, which verifies that translations are complete and every key used in the code exists.
- Continuous integration (backend tests, translation check, frontend build) and a project website on GitHub Pages.
- README in English and Italian, user guide, contributing guide, code of conduct, security policy, issue and pull
  request templates.

### Changed

- Code comments, logs, API error messages, hook scripts, the focus agent and the documentation are now in English.
- The hook and the backend use `unknown` as fallback machine and project name.

## [0.6.2] - 2026-11-11

### Changed

- Remote Control sessions open on the Mac in use: the window on the session's own Mac, or the Claude app on another
  Mac with the focus agent.

## [0.6.0] - 2026-11-11

### Added

- Window focus from the dashboard on macOS: an optional agent brings the Claude Code window of a session to the front,
  with self-locking protections and a per-user switch.

## [0.5.0] - 2026-11-11

### Added

- First public release: live dashboard of Claude Code sessions across machines, sound alerts, Web Push, multi-user
  accounts with personal hook keys, installable PWA, light and dark theme, hook installers for macOS, Linux and
  Windows.

[Unreleased]: https://github.com/sartoriadelcodice/claude-monitor/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/sartoriadelcodice/claude-monitor/compare/e348709...v0.7.0
[0.6.2]: https://github.com/sartoriadelcodice/claude-monitor/compare/6059004...e348709
[0.6.0]: https://github.com/sartoriadelcodice/claude-monitor/compare/4374e16...6059004
[0.5.0]: https://github.com/sartoriadelcodice/claude-monitor/commit/4374e16
