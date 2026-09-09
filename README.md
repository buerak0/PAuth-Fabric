# PAuth

**Automatic premium detection + password login for offline-mode (`online-mode=false`) Minecraft servers.** Server-side only — vanilla and modded clients connect without installing anything.

PAuth brings the "FastLogin + AuthMe" experience to Fabric: players who own a genuine Minecraft account are logged in **instantly, with no password**, while cracked players register and log in with `/register` and `/login`. No client mod, no proxy required.

---

## Short summary

> Server-side auth for cracked (offline-mode) servers: real account owners join instantly via Mojang session verification, everyone else uses `/register` & `/login`. No client mod needed.

---

## How premium detection works

On an `online-mode=false` server the vanilla handshake normally skips Mojang's session check. PAuth selectively re-enables that check **per player**:

1. A player connects and sends their username.
2. PAuth decides how to authenticate them:
   - known **premium** (verified before) → require a license check;
   - registered **cracked** account → password flow;
   - unknown name → a quick Mojang API lookup decides.
3. **Premium candidates** are put through the real online-mode flow (encryption request → client session token → Mojang `hasJoined`). A genuine account owner passes automatically; anyone else is rejected. Verified owners are remembered and skip the check next time.
4. **Cracked players** join a frozen "limbo" state and must `/register` or `/login` before they can do anything.

The result: licensed players never touch a password, cracked players are fully protected, and their usernames can't be stolen.

---

## Features

- ⚡ **Instant login for account owners** — verified through Mojang's session server, no password.
- 🔒 **Password auth for everyone else** — `/register` and `/login`, hashed with **PBKDF2‑SHA256** (no plaintext, no external libraries).
- 🧍 **Limbo** — unauthenticated players are frozen, blinded, and blocked from chat, commands, movement, block interaction, item pickup/drop and combat until they log in.
- 🛡️ **Username protection (`strict` mode)** — a name that belongs to a real Mojang account can only be used by its owner.
- 🌐 **Mojang API caching + safe fallbacks** — cached lookups, and configurable behaviour when the API is unreachable (kick with "try later" instead of letting anyone squat a premium name).
- 🔁 **Optional IP sessions** — skip `/login` on quick reconnects from the same IP (off by default; see security notes).
- 🆔 **UUID mode** — keep real Mojang UUIDs for premium players, or force offline-style UUIDs for stable player data across auth modes.
- 🌍 **Bilingual** — all player-facing messages available in **English and Russian**, selectable with the `language` config option.
- 🧩 **Server-side only** — clients need nothing and join with a vanilla client; the mod declares itself server-environment only.

---

## Commands

| Command | Description |
|---|---|
| `/register <password> <password>` | Register a new cracked account (alias `/reg`) |
| `/login <password>` | Log into a registered account (alias `/l`) |
| `/changepassword <old> <new>` | Change your password |
| `/pauth info <name>` | (admin) Show a player's auth record |
| `/pauth unregister <name>` | (admin) Delete a player's account |

---

## Configuration

`config/pauth.json`:

| Option | Default | Description |
|---|---|---|
| `language` | `ru` | Language for player-facing messages: `ru` or `en` |
| `premiumMode` | `STRICT` | `STRICT` protects licensed names for their owners; `LENIENT` never checks unknown names against Mojang |
| `kickOnApiError` | `true` | When Mojang is unreachable, refuse unknown names instead of letting a premium name be squatted |
| `offlineUuidForPremium` | `false` | Give premium players offline-style UUIDs (stable data, breaks skins) instead of their real Mojang UUID |
| `loginTimeoutSeconds` | `60` | How long an unauthenticated player may stay before being kicked |
| `sessionMinutes` | `30` | Skip `/login` on reconnect from the same IP within this window (`0` disables) |
| `maxLoginAttempts` | `3` | Wrong-password attempts before a kick |
| `mojangApiTimeoutMs` | `5000` | Connect and request timeout for a single name-lookup request |
| `mojangApiAttempts` | `2` | How many times the whole endpoint list is retried before a lookup is given up on |
| `mojangApiEndpoints` | Mojang's two official lookup APIs | Name-lookup URLs, tried in order until one answers; `%s` is the username. Useful when one host is unreachable from the server — but only add mirrors you trust, since an endpoint that lies about a name lets that name be squatted |
| `minPasswordLength` | `4` | Minimum password length |
| `applyBlindness` | `true` | Blind players while they are not logged in |

Accounts are stored as JSON in `config/pauth/users.json`.

---

## Supported versions

| Minecraft | Loader | Branch |
|---|---|---|
| 26.2 | Fabric | `main` |

Each build is version-specific — download the file that matches your server.

The Forge/NeoForge builds for 1.21.1 and older live in the original repository,
[buerak0/PAuth](https://github.com/buerak0/PAuth).

---

## Building

The version tree is managed with [Stonecutter](https://stonecutter.kikugie.dev/).
Shared settings are in `gradle.properties`; per-version values live in
`versions/<version>/gradle.properties`.

```bash
./gradlew build
```

The jar lands in `build/libs/`. To switch the tree to another version:

```bash
./gradlew "Set active project to <version>"
```

**On older Minecraft.** This tree only covers the non-obfuscated era (26.x and up).
Loom 1.17 is built for it: it performs no remapping, rejects `officialMojangMappings()`,
and requires every mod's access widener to be in the official namespace — which the
Fabric API artifacts for 1.21.x and older are not. Those versions need an older Loom,
and a Gradle build resolves its plugin classpath once, so they cannot share this tree.
They belong on their own branch.

---

## Security notes

- **`strict` mode** rejects cracked players who try to use a licensed player's name (this is the username-protection feature). Use `lenient` if you want any free name to be registerable.
- **IP sessions** (`sessionMinutes`) trust the client IP. On shared IPs (household NAT, CGNAT, VPNs) another person from the same IP could resume a session without the password. It is safest to leave sessions disabled (`0`) unless you understand the trade-off.
- Premium verification itself cannot be spoofed — it relies on Mojang's session server, which requires a valid account token.

---

## Notes

- Player-facing messages are available in **English and Russian** (`language` option).
- Requires the server to run with `online-mode=false`.

*Not affiliated with Mojang or Microsoft.*
