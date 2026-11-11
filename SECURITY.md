# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Report them privately, either:

- through GitHub: **Security → Report a vulnerability** on the repository page (private vulnerability reporting), or
- by email to **info@sartoriadelcodice.it**, with "Claude Monitor security" in the subject.

Include a description of the issue, the steps to reproduce it, the affected version (shown in the dashboard settings)
and its possible impact. You will get an acknowledgement within a few working days; once the issue is confirmed we
will work on a fix and agree with you on when to disclose it. Credit is given in the changelog unless you prefer
otherwise.

## Supported versions

Claude Monitor is released from the `main` branch: security fixes go into the latest version. Installations update
the frontend automatically once the new image is deployed; rebuild the containers to get backend and hook fixes.

## Threat model in short

Claude Monitor is meant to be self-hosted and exposed only over HTTPS. What it protects and how:

- **Conversation content.** The hook removes prompts, Claude's answers, tool input and output and background task
  commands before sending (always with PowerShell, with `jq` on macOS and Linux); the backend removes them again before
  saving (`HookEvent.withoutPrivateContent`).
- **Credentials.** Passwords are stored with bcrypt, hook keys only as SHA-256 hashes; a key is shown in clear text
  only once. Logins are JWTs (`JWT_TTL`, 30 days by default); disabling a user takes effect on the next request.
  Sign-in attempts are rate limited by nginx.
- **Hook keys.** A hook key can only send events and listen for the focus requests of its own machine. It cannot
  read sessions or history, nor request a focus.
- **Push endpoints.** Subscription endpoints are addresses the server sends requests to: only `https` URLs pointing to
  public addresses are accepted, to prevent server-side request forgery.
- **Window focus agent (macOS, opt-in).** The agent receives only a session identifier: it never runs commands
  received from the network, never sends keystrokes and needs no Accessibility permission. It resolves the window
  from a local file written by the hook. It counts anomalous requests (malformed identifiers, unknown sessions,
  bursts, excessive volumes) and locks itself; only a command typed on the Mac unlocks it. Someone controlling the
  server could at most bring a session window to the front. A per-user switch disconnects all agents at once.
- **Multi-user isolation.** Sessions, history, streams and push subscriptions belong to a user and every query
  filters by the authenticated user.

Known limitations: changing a password doesn't sign out the other devices; the event history has no retention limit;
behind a reverse proxy the sign-in rate limit applies to all clients together unless the proxy forwards the client
address.
