<img align="right" src="https://i.imgur.com/zrE80HY.png" height="200" width="200">

# JMusicBot
> [!NOTE]
> This is a fork of [JMusicBot](https://github.com/jagrosh/MusicBot) from jagrosh and arif-banai
> I wanted to make a few tweaks without interfering with the main repo.

[![Downloads](https://img.shields.io/github/downloads/ragnarrok/JMusicBot/total.svg)](https://github.com/ragnarrok/JMusicBot/releases/latest)
[![Stars](https://img.shields.io/github/stars/ragnarrok/JMusicBot.svg)](https://github.com/ragnarrok/JMusicBot/stargazers)
[![Release](https://img.shields.io/github/release/ragnarrok/JMusicBot.svg)](https://github.com/ragnarrok/JMusicBot/releases/latest)
[![License](https://img.shields.io/github/license/ragnarrok/JMusicBot.svg)](https://github.com/ragnarrok/JMusicBot/blob/master/LICENSE)
[![Auto Release](https://github.com/ragnarrok/JMusicBot/actions/workflows/auto-release.yml/badge.svg)](https://github.com/ragnarrok/JMusicBot/actions/workflows/auto-release.yml)

A cross-platform Discord music bot with a clean interface, and that is easy to set up and run yourself!

## Requirements

*   **Java 25 or higher.**
*   **jdave/udpqueue:** You **must** have **glibc >= 2.38**. *If you are using Docker, this is already handled for you.*
*   **Privileged Gateway Intents:** You **must** enable the **Message Content Intent** in your [Discord Developer Portal](https://discord.com/developers/applications).
    *   *Navigate to: Your Application > Bot > Privileged Gateway Intents > Toggle "Message Content Intent" to ON.*
    *   *Without this, the bot will not see your prefix commands (e.g. `!play`). Slash commands (`/play`) work without it.*

## Features
  * Easy to run (just make sure Java is installed, and run!)
  * Fast loading of songs
  * No external keys needed (besides a Discord Bot token)
  * Smooth playback
  * Server-specific setup for the "DJ" role that can moderate the music
  * Slash commands and interactive playback buttons, alongside classic prefix commands
  * Playback history (`history` command and a Previous button on the now-playing message)
  * Clean and beautiful menus
  * Supports many sites, including Youtube, Soundcloud, and more
  * Supports many online radio/streams
  * Optional auto-reconnect for live streams (internet radio, Azuracast) that drop
  * Supports local files
  * Playlist support (both web/youtube, and local)

## Supported sources and formats
JMusicBot supports all sources and formats supported by [lavaplayer](https://github.com/lavalink-devs/lavaplayer#supported-formats):
### Sources
  * YouTube
  * SoundCloud
  * Bandcamp
  * Vimeo
  * Twitch streams
  * Niconico
  * GetYarn
  * Local files
  * HTTP URLs (direct audio files and internet radio streams such as Azuracast/Icecast/Shoutcast)

Each source can be enabled or disabled individually under `playback.audioSources` in `config.txt`.
### Formats
  * MP3
  * FLAC
  * WAV
  * Matroska/WebM (AAC, Opus or Vorbis codecs)
  * MP4/M4A (AAC codec)
  * OGG streams (Opus, Vorbis and FLAC codecs)
  * AAC streams
  * Stream playlists (M3U and PLS)

## Example
![Loading Example...](https://i.imgur.com/kVtTKvS.gif)

## Setup
Follow the upstream [Setup Page](https://jmusicbot.com/setup) to create the bot account, get a token, and invite the bot. Then download the jar from this repository's [releases](https://github.com/ragnarrok/JMusicBot/releases/latest) (or use the Docker image below) and run it as described in the next section. All configuration lives in `config.txt`; the options added by this fork are documented further down.

## Running Directly (Without Docker)

When running JMusicBot directly (not in Docker), make sure to pass these JVM flags:

**Linux / macOS / Windows (CMD):**
```bash
java -Dfile.encoding=UTF-8 -Dnogui=true --enable-native-access=ALL-UNNAMED -jar JMusicBot-0.7.0-All.jar
```

**Windows (PowerShell):** PowerShell treats `-D` as its own parameter. Quote each JVM option so they are passed to `java` correctly:
```powershell
java "-Dfile.encoding=UTF-8" "-Dnogui=true" "--enable-native-access=ALL-UNNAMED" "-jar" ".\JMusicBot-0.7.0-All.jar"
```
Alternatively, use the stop-parsing token so the rest of the line is passed literally: `java --% -Dfile.encoding=UTF-8 -Dnogui=true ...`

`-Dfile.encoding=UTF-8` ensures non-English characters (Cyrillic, Japanese, etc.) display correctly in Discord. On Windows or older JDKs, omitting it can cause mojibake in slash-command autocomplete and embeds.

Omit `-Dnogui=true` if you want the desktop GUI with the performance and health monitors; it can also be turned off permanently with `gui.enabled = false` in `config.txt`.

On Linux/macOS, [`scripts/run_jmusicbot.sh`](scripts/run_jmusicbot.sh) downloads the latest release jar, passes the required flags, and restarts the bot in a loop after the `shutdown` command.

### Linux System Requirements

**Important:** Your system **must have glibc version 2.38 or higher**. Failure to meet this requirement will result in errors when loading the jdave and udpqueue native libraries.

> **Note:** Ubuntu 24.04 "Noble" and Debian 13 "Trixie" already include a compatible glibc version out of the box.

You can check your glibc version with:
```bash
ldd --version
```

On Debian/Ubuntu-based systems, you may also need to install the following native audio library dependencies:

```bash
# Install required native library dependencies
sudo apt-get update
sudo apt-get install -y libopus0 libsodium23
```

If your version is below 2.38, you will need to upgrade your system or use a compatible runtime. (You can also use Docker, which is recommended.)

## Docker

JMusicBot can be run using Docker for easy deployment and management. Pre-built images are available from the GitHub Container Registry. The container is configured to run headless and automatically generate a default `config.txt` on first run.

### Quick Start

#### For Existing Users (Migrating from JAR)

If you already have a directory with your `config.txt`, `Playlists/` folder, and other bot files, either provide the path to that directory or run the container from within it:

```bash
docker run --rm -it \
  --name jmusicbot \
  -v "$(pwd):/musicbot" \
  ghcr.io/ragnarrok/jmusicbot:latest
```

This mounts your current directory as the musicbot volume, so the bot will use your existing configuration and playlists.

#### For New Users

1. **Create a directory for your bot and run the container:**
   ```bash
   mkdir -p /path/to/jmusicbot

   docker run --rm -it \
     --name jmusicbot \
     -v "/path/to/musicbot:/musicbot" \
     ghcr.io/ragnarrok/jmusicbot:latest
   ```

2. **First Run:**
   - On first run, if the mounted directory is empty, the bot will automatically generate a default `config.txt` file.
   - Edit `/path/to/musicbot/config.txt` on your host and add your Discord bot token.
   - Run the container again.

#### Using Docker Compose (Optional)

If you prefer docker-compose, copy the example compose file and update the volume path:

```bash
cp docker-compose.example.yml docker-compose.yml
# Edit docker-compose.yml and update the volume path
docker compose up -d
```

Example `docker-compose.yml`:

```yaml
services:
  jmusicbot:
    image: ghcr.io/ragnarrok/jmusicbot:latest
    container_name: jmusicbot
    volumes:
      - /path/to/musicbot:/musicbot
    restart: unless-stopped
```

Check the [Docker Compose Example](docker-compose.example.yml) for more details.

### Important Notes

- **Config Persistence:** The `/musicbot` volume **must** be mounted for your configuration to persist. The bot reads and writes `config.txt` from `/musicbot` (the container's working directory).
- **First Run:** If `config.txt` doesn't exist, the bot will generate a default one automatically. You'll need to edit it with your bot token before the bot can start.
- **Image Tags:**
  - Use `ghcr.io/ragnarrok/jmusicbot:latest` for the newest release (every code push to master becomes a release)
  - Use `ghcr.io/ragnarrok/jmusicbot:0.7.0` (replace with actual version) to pin a specific release version
  - Every build is also tagged `sha-<commit>`. Maintainers can publish `preview-<branch>` images for any ref by running the "Publish Preview Image" workflow manually
  - **Recommendation:** For production, pin your image tag rather than using `latest`
- **File Permissions:** The container runs as the non-root user `jmusicbot` (UID 10001). Make sure the mounted directory is writable by that UID (`chown 10001:10001 /path/to/musicbot`), or set `user:` in your compose file to your own UID/GID.
- **JAVA_OPTS:** The container uses ZGC and AlwaysPreTouch by default. Set `JAVA_OPTS` to add heap limits (e.g. `-Xms256m -Xmx512m`) or other flags. See [Performance Tuning](#performance-tuning) for details.

To view published images, visit: `https://github.com/ragnarrok/JMusicBot/pkgs/container/jmusicbot`

## Performance Tuning

For optimal audio quality with minimal stuttering, the following JVM and configuration options are recommended.

### JVM Flags (Recommended)

The bot works best with ZGC (Z Garbage Collector) which provides sub-millisecond pause times:

```bash
java -Dfile.encoding=UTF-8 \
     -XX:+UseZGC \
     -XX:+AlwaysPreTouch \
     -Dnogui=true \
     --enable-native-access=ALL-UNNAMED \
     -jar JMusicBot-*.jar
```

**Flag explanations:**
- `-Dfile.encoding=UTF-8`: Ensures non-English characters display correctly in Discord (required on Windows or older JDKs)
- `-XX:+UseZGC`: Sub-millisecond GC pauses (generational mode is the default since JDK 23)
- `-XX:+AlwaysPreTouch`: Pre-allocates memory at startup to avoid page faults
- `-Xms` / `-Xmx`: Optional; set heap size if you want to limit or fix memory (e.g. `-Xms256m -Xmx512m`)

The Docker image uses ZGC and AlwaysPreTouch by default. Set `JAVA_OPTS` to add heap limits or override:
```yaml
environment:
  - JAVA_OPTS=-Xms256m -Xmx512m -XX:+UseZGC -XX:+AlwaysPreTouch
```

### Audio Buffer Configuration

The bot includes configurable audio buffers that protect against GC pauses. These can be tuned in `config.txt`:

```hocon
performance {
  # NAS buffer duration in ms (protects against JVM pauses up to this duration)
  nasBufferMs = 800

  # Lavaplayer frame buffer duration in ms (amount of decoded audio to buffer)
  frameBufferMs = 2000
}
```

Higher values provide more protection against stuttering but add latency. The defaults (800ms NAS, 2000ms frame buffer) should work well for most setups.

## Live Stream Persistence

Internet radio streams (Azuracast, Icecast, Shoutcast, etc.) occasionally drop: the server restarts, the connection is reset, or the stream stalls and stops sending data. By default the bot treats that like the end of a track and moves on, so the stream has to be queued again by hand.

Turn on stream persistence in `config.txt` (or in the desktop GUI under the Config tab, "Live Streams") and the bot will reconnect to the same stream automatically instead:

```hocon
playback {
  streams {
    # Reconnect live streams that stop unexpectedly
    persist = true

    # Wait before the first reconnect attempt; doubles after each consecutive failure
    reconnectDelaySeconds = 5
    reconnectMaxDelaySeconds = 60

    # Consecutive failed attempts before giving up (0 = retry forever)
    reconnectMaxAttempts = 0

    # Queue the stream again and rejoin the voice channel after a bot restart
    resumeOnRestart = false

    # Show the station's current song in the now-playing message
    metadata {
      enabled = true
      pollIntervalSeconds = 15
    }
  }
}
```

**How it works:**
- Only live streams (tracks with no known duration) are affected. Normal songs, playlists and local files behave exactly as before.
- A stream is reconnected when it ends on its own, fails to load, or stalls for longer than lavaplayer's stuck threshold (10 seconds). The now-playing message is kept while the bot reconnects.
- `stop`, `skip`, `forceskip`, `skipto` and playing something else never trigger a reconnect. Queuing another track while a reconnect is pending cancels the reconnect.
- Each consecutive failure doubles the wait (5s, 10s, 20s, 40s, 60s, 60s, ...). The counter resets once the stream has played for 30 seconds, so a brief blip always retries quickly.
- Combine with `voice.stayInChannel = true` if you want the bot to stay in the voice channel even when it gives up after `reconnectMaxAttempts`.
- With `resumeOnRestart = true`, the stream and voice channel are remembered in `streamresume.json` while a live stream plays. After an update, crash, or host reboot the bot queues the same stream and rejoins that channel on startup. The entry is forgotten when the stream is stopped or something else is played. If the stream server is unreachable at startup, loading is retried five times, 15 seconds apart.

**Station metadata.** Lavaplayer does not read the in-stream song titles, so on its own the now-playing message only shows the stream URL. With `metadata.enabled = true` (the default) the bot asks the station what it is playing every `pollIntervalSeconds` and updates the now-playing message in place with the station name, song, artist, artwork, and listener count. With `presence.songInStatus = true` the song also shows in the bot's status. Two server types are supported, picked automatically from the stream URL:
- **Azuracast**: stream URLs of the form `https://host/listen/<station>/<mount>`, read from `https://host/api/nowplaying/<station>`.
- **Icecast / Shoutcast-style**: any other http(s) URL, read from `/status-json.xsl` on the same host and port, matched by mount name.

Stations that expose neither endpoint are left alone; nothing else changes for them.

**Rejoining voice after a disconnect.** Stream persistence covers the stream dying. If instead the bot itself is thrown out of the voice channel while playing (a Discord voice outage, a region change, or someone dragging it out), enable this in the `voice` section:

```hocon
voice {
  rejoinOnDisconnect = true
  rejoinDelaySeconds = 5
  rejoinMaxAttempts = 5   # per guild, per 5 minutes; 0 = unlimited
}
```

The bot rejoins the channel it was in (or the server's default voice channel if that one is gone) as long as something is still playing. A watchdog also checks every 30 seconds for a playing bot that is not in voice. Stopping playback with the bot's own commands never triggers a rejoin, so use `stop` when you want it to leave for good.

## Proxy Configuration

JMusicBot supports granular proxy configuration, allowing you to route specific components through a proxy while letting others connect directly. This is useful when you need to proxy audio traffic (e.g., to bypass regional restrictions) without affecting Discord API communication.

### Configuration

Add the following to your `config.txt`:

```hocon
proxy {
  # Proxy server hostname and port
  host = "127.0.0.1"
  port = 8080

  # Enable proxy for specific components
  lavaplayer = true   # Audio source requests (YouTube, SoundCloud, etc.)
  jda = false         # Discord API traffic
  github = false      # Version check requests
}
```

### Common Use Cases

**Route only audio traffic through proxy** (most common):
```hocon
proxy {
  host = "127.0.0.1"
  port = 18080
  lavaplayer = true
  jda = false
  github = false
}
```

**Route all traffic through proxy**:
```hocon
proxy {
  host = "proxy.example.com"
  port = 8080
  lavaplayer = true
  jda = true
  github = true
}
```

### Notes

- Leave `host` empty or `port` as 0 to disable proxy entirely
- Each component can be independently enabled/disabled
- HTTP proxies are supported (SOCKS proxies are not currently supported)

## Development Workflow

This project follows a **trunk-based development** workflow. The `master` branch is always releasable, and all work happens in short-lived branches:

- **`feature/<slug>`** - New features (e.g., `feature/new-player-ui`)
- **`fix/<slug>`** - Bug fixes (e.g., `fix/youtube-oauth`)
- **`chore/<slug>`** - Maintenance tasks (e.g., `chore/update-deps`)
- **`deps/<slug>`** - Dependency experiments (e.g., `deps/youtube-source-pr195`)
- **`release/<version>`** - Release stabilization (optional, e.g., `release/0.7.1`)

Branch names are automatically validated by CI to ensure consistency (lowercase letters, digits and hyphens only; `feat/` is not accepted, use `feature/`). For detailed information about the development workflow, branch naming rules, and best practices, see [DEVELOPMENT_WORKFLOW.md](docs/DEVELOPMENT_WORKFLOW.md). An overview of the code structure lives in [ARCHITECTURE.md](docs/ARCHITECTURE.md).

Every code push to `master` is tested and, if green, automatically released: the next patch version is worked out from the existing tags, the merged commit is tagged `vX.Y.Z`, a GitHub release with the JAR is created, and a matching Docker image is published. Nothing is committed back to `master`, so the branch can be fully protected. Bump the minor or major version in `pom.xml` yourself when a change warrants it.

To build from source you need JDK 25+ and Maven 3.9+:

```bash
mvn verify
```

The runnable jar is written to `target/JMusicBot-<version>-All.jar`.

## Questions and Bug Reports
Please search the [issues](https://github.com/ragnarrok/JMusicBot/issues) before opening a new one. For a reproducible bug, include the bot version (logged at startup), the relevant part of `config.txt` with the token removed, and the log output around the problem.

This bot is built on:
 - [**JDA**](https://github.com/discord-jda/JDA)
 - [**lavaplayer**](https://github.com/lavalink-devs/lavaplayer)
 - [**youtube-source**](https://github.com/lavalink-devs/youtube-source)

## Contributing
Pull requests are welcome. Branch from `master` using the naming rules above, keep changes focused, and make sure `mvn verify` passes. Everything merged to `master` is released automatically. The code is licensed under the Apache 2.0 License; see [LICENSE](LICENSE).
