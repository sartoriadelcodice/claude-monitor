## What and why

<!-- What does this change do, and which problem does it solve? Link the issue: "Closes #123". -->

## How it was tested

<!-- Commands run, devices and browsers tried (iPad/iPhone if the change touches audio, scrolling or dialogs). -->

## Checklist

- [ ] `mvn test` and `npm run i18n:check && npm run build` pass
- [ ] Changes to `SessionStateMapper` have a test in `SessionStateMapperTest`
- [ ] Database changes are a new Flyway migration (applied migrations untouched)
- [ ] New interface texts have keys in every `frontend/src/i18n/*.json` file
- [ ] README, user guide and their Italian versions are updated if behaviour changed
- [ ] `CHANGELOG.md` has an entry under *Unreleased*
- [ ] No secrets, keys or real machine, client or project names in code, screenshots or messages
