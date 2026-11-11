# User guide

[🇮🇹 Italiano](user-guide.it.md) · [README](../README.md)

- [The main screen](#the-main-screen)
- [Cards](#cards)
- [Session detail](#session-detail)
- [Connecting a machine](#connecting-a-machine)
- [Sounds](#sounds)
- [Push notifications](#push-notifications)
- [Window focus (macOS)](#window-focus-macos)
- [Settings](#settings)
- [Users](#users)

## The main screen

At the top left is the counter of the sessions waiting for you (*Needs attention* and *Your turn*): it turns red
when there is at least one. On the right, in order:

| Control             | What it does                                                                 |
| ------------------- | ---------------------------------------------------------------------------- |
| "Live"              | State of the connection with the server; "Reconnecting…" when it dropped     |
| Machine list        | Shows only the sessions of one machine (appears with more than one machine)  |
| Speaker             | Mutes or unmutes sounds on this device                                       |
| Sun or moon         | Switches between the dark and the light theme                                |
| Key                 | Hook keys: connect a new machine or revoke a key                             |
| People              | User management (administrators only)                                        |
| Gear                | Settings                                                                     |

Below, **Group by** splits the cards by folder, by device or by both. Groups with sessions waiting for you come
first.

## Cards

Every card is a Claude Code session and shows its state, the folder it was started from, the session name (if it
has one), the machine, the model in use and how long it has been in that state. When Claude asks for a permission
or asks a question, the card also shows the text of the request.

Cards are sorted by urgency: *Needs attention* first, then *Your turn*, then the others. The meaning of each state
is in the [README](../README.md#session-states).

A card that is waiting for you and that you haven't seen yet is highlighted with the color of its state.

- **First tap on a highlighted card:** marks it as seen. The highlight stops and so does the sound repetition, if
  enabled. The detail does not open.
- **Tap on a card that is not highlighted:** opens the detail.

If the session changes state again, the card is highlighted again.

## Session detail

It shows the state, the full path of the folder, the last message of Claude Code and the history of the events
received, most recent first, with the state each one led to.

**Archive** removes the session from the main view; it stays in the history, from which **Restore** brings it back.
Ended sessions are archived automatically. An archived session that starts working again returns to the main view.

## Connecting a machine

1. Tap the key button and create a hook key, naming it after the machine.
2. The key is shown in clear text only once: copy it right away or use the automatic installation.
3. **Automatic installation:** choose the system (macOS and Linux, or Windows), download the script and run it on
   the machine to connect. It installs the hook and updates the global Claude Code settings, after backing them up.
   Then delete the script, because it contains the key.
4. Start a new Claude Code session on that machine: its card appears on the dashboard.

When Claude Monitor is updated, the hook script may change. At the bottom of the **Hook keys** dialog there is the
command that downloads it again on a machine already connected, without creating a new key.

One key per machine is a good idea: if needed you revoke just one. The list shows, for each key, when it was
created and last used. Manual installation is described in [hooks/README.md](../hooks/README.md).

## Sounds

On first sign-in tap **Enable sounds**. There are two sounds: a more insistent one for *Needs attention* and a softer
one for *Your turn*. They play only when a session enters that state.

In the settings you can set the volume, test both sounds and enable the repetition of the *Needs attention* sound:
it repeats at the chosen interval until the card is seen.

On iPad the browser enables audio only after a tap: every time the app opens, just tap anywhere on the screen.
The **Audio status** line in the settings tells whether audio is active.

## Push notifications

Tap **Enable notifications** and grant the permission: you get a notification when a session enters *Needs
attention* or *Your turn*, even with the app closed. Tapping the notification opens the dashboard.

Notifications are enabled per device: from the settings you can turn them off on one device and keep them on the
others. They are written in the language chosen on each device. On iPad and iPhone they work only from the app added
to the Home Screen.

## Window focus (macOS)

Optional. In the **Hook keys** dialog, the *Window focus* section has a command that starts a small agent on a Mac.
The cards of that Mac's sessions get an **Open window** bar that brings the Claude Code terminal window to the front.

With Claude Code Remote Control, the session is opened on the Mac you are using: its own window if you are at that
Mac, otherwise the Claude app on the Mac you are using (**Open in Claude**). After the tap the bar shows where the
session opened.

The agent only receives the session identifier: it doesn't run commands and doesn't send keystrokes. A switch in the
settings turns focus off for all your machines at once. Details and protections are in
[hooks/README.md](../hooks/README.md#window-focus-macos-optional).

## Settings

| Section            | What it contains                                                                              |
| ------------------ | --------------------------------------------------------------------------------------------- |
| Language           | Interface language: automatic (from the browser), English or Italiano                         |
| Sounds             | Enable, volume, sound test, audio status, repetition and its interval                         |
| Push notifications | Enable on this device                                                                         |
| View               | History of ended and archived sessions; temporary enlargement of a card that starts waiting for you, and its duration |
| Window focus       | Allow the dashboard to bring Claude Code windows to the front, for all your machines         |
| Version            | Installed version and build, update check                                                     |
| Account            | Password change and sign out                                                                  |

Theme, language, sounds, grouping and the other preferences are saved on the device: each device has its own.

The app updates itself: it checks every five minutes and whenever it returns to the foreground; when it finds a new
version it installs it and reloads.

## Users

Only administrators see the users button. From there they create users (passwords of at least 10 characters), reset
a password, switch the role between user and administrator, and disable or re-enable a user. Each user then creates
their own hook keys and sees only their own sessions.
