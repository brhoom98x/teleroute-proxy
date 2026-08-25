# TeleRoute proxy (server)

*Developed by brhoom98x.*

The same route-steering logic as the [TeleRoute Android app](../TeleRoute), but running on a
server as a real SOCKS5 proxy that a phone points at over the network.

## Why this exists

The Android app runs the proxy **on the phone**, on `127.0.0.1`, and Telegram connects to
loopback. That design cannot work on iOS: iOS suspends an app as soon as you leave it, so the
moment you switch to Telegram the listener stops accepting. There is no foreground-service
equivalent to keep it alive.

Moving the proxy to a machine that is always running sidesteps the problem entirely — and works
for iOS, Android and desktop at once, with no Apple developer account, no Network Extension and
no App Store.

### The trade-off, stated plainly

The phone build picks the fastest Telegram front-end **from your phone's network position** and
adds zero hops. This build picks the fastest front-end from **the server's** position, and adds
your phone → server hop on top.

That is a real cost, and it is not automatically a win:

- **Good case.** Your direct path to Telegram is throttled or partly blocked, and the server sits
  on a well-connected network with a clean path. Then routing through it beats connecting direct.
- **Bad case.** Your direct path is already fine, or the server is on a home connection reached
  over mobile data. Then you have added latency for nothing.

Measure before committing. This helps when the direct path is the problem, not merely when it is
slow.

## Security

This listener faces the network, which the Android one never did. Three defaults follow from
that, and two of them are not optional:

**Authentication is mandatory.** The proxy refuses to start without a username and password, and
rejects passwords under 12 characters. An anonymous SOCKS5 proxy on a public port is an open
relay; scanners find those within hours, and the traffic that follows comes from your IP address.

**Only Telegram destinations are relayed.** `allowNonTelegram=false` is the default. The proxy
checks the requested address against Telegram's published networks and refuses everything else,
so a leaked password costs you Telegram bandwidth rather than handing someone a general-purpose
relay. The Android build relays anything, which is safe there precisely because only apps on that
phone can reach loopback.

**Concurrent connections are capped** at 512, so one client cannot exhaust file descriptors.

Credentials are compared with `MessageDigest.isEqual`, which does not return early on the first
wrong byte — a plain comparison leaks how much of a guessed password was right through response
timing.

> **Strongly preferred: do not expose the port publicly at all.** Put the server behind WireGuard
> or Tailscale and set `bind` to the tunnel address. Then the proxy is unreachable from the open
> internet and the password is a second layer rather than the only one.

## Build

Needs a JDK 17 and Gradle; `build.ps1` uses the same portable toolchain as the Android project.

```powershell
.\build.ps1
```

`.\build.ps1 test` runs the tests, `.\build.ps1 run` runs it locally against `./teleroute.conf`.

Output is a self-contained jar: `teleroute-proxy-1.0-all.jar`. Deployment is that file plus a JRE.

## Install on any Linux host

One command on any systemd distribution. It installs a JRE if missing, fetches the latest
release, generates a password, and starts the service.

**If the repository is public:**

```bash
curl -fsSL https://github.com/brhoom98x/teleroute-proxy/releases/latest/download/install.sh -o install.sh
sudo bash install.sh
```

**If the repository is private** every target machine needs a token with `Contents: read` on this
repository — create a fine-grained PAT at github.com/settings/tokens:

```bash
export GITHUB_TOKEN=github_pat_xxxxxxxx
curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/octet-stream" \
  "$(curl -fsSL -H "Authorization: Bearer $GITHUB_TOKEN" \
     https://api.github.com/repos/brhoom98x/teleroute-proxy/releases/latest \
     | jq -r '.assets[] | select(.name=="install.sh") | .url')" -o install.sh
sudo -E bash install.sh
```

> Distributing a read token to every machine is worse for security than a public repository that
> contains no secrets. There are none here: the password is generated per install and never
> committed. If this is going on more than one or two hosts, consider making the repository public
> and using the simple form above.

Options, all optional:

| Variable | Default | Purpose |
| --- | --- | --- |
| `BIND` | `0.0.0.0` | Set to a WireGuard/Tailscale address to keep it off the public internet |
| `PORT` | `19808` | Listening port |
| `VERSION` | latest | Install a specific tag, e.g. `v1.0` |
| `REPO` | `brhoom98x/teleroute-proxy` | Source repository |

```bash
sudo BIND=10.66.0.3 PORT=19808 bash install.sh
```

The installer is **safe to re-run** — an existing `/etc/teleroute/teleroute.conf` is never
overwritten, so upgrading does not invalidate the credentials already saved in your Telegram
client. It verifies the jar against `SHA256SUMS` and refuses to install on a mismatch.

Read the password back at any time:

```bash
sudo grep ^password= /etc/teleroute/teleroute.conf
```

### Firewall

The proxy authenticates every client and relays only to Telegram, but exposing it is still a
choice. Prefer a tunnel; if you must open the port, scope it:

```bash
ufw allow from <your phone's network> to any port 19808 proto tcp
```

## Deploy by hand

If you would rather not run a script, the installer does nothing exotic:

```bash
apt install -y default-jre-headless
adduser --system --group --no-create-home teleroute
mkdir -p /opt/teleroute /etc/teleroute
# copy teleroute-proxy-<version>.jar to /opt/teleroute/teleroute-proxy.jar
cp teleroute.conf.example /etc/teleroute/teleroute.conf
openssl rand -base64 24          # paste as the password
nano /etc/teleroute/teleroute.conf
chown teleroute:teleroute /etc/teleroute/teleroute.conf
chmod 600 /etc/teleroute/teleroute.conf
cp teleroute-proxy.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now teleroute-proxy
journalctl -u teleroute-proxy -f
```

The unit runs as an unprivileged user with an empty capability set, a read-only system and only
`AF_INET`/`AF_INET6` allowed, because the process needs a socket and nothing else.


## Point Telegram at it

Telegram, on **any** platform including iOS: **Settings → Data and Storage → Proxy Settings →
Add Proxy → SOCKS5**, then server, port, username and password.

Telegram's deep link carries credentials too, so this opens the sheet pre-filled:

```
tg://socks?server=<host>&port=19808&user=<username>&pass=<password>
```

Treat that link as a secret — it contains the password in clear text.

## What it does

1. Every `scanIntervalSeconds`, probes each Telegram front-end and ranks them by TCP handshake
   latency, measured from the server.
2. On CONNECT to a Telegram address, tries the fastest measured addresses **of that same data
   centre** first, then the address the client actually asked for.

Substitution stays inside one data centre because each DC holds its own auth key — sending DC4
traffic to DC2 would break the session. That constraint is what makes this a route optimiser
rather than a session breaker.

Unlike the phone build there is no battery-driven backoff: the server is on mains power, so the
scan interval is flat.

## Shared code

`TelegramRoutes.kt` and `RouteScanner.kt` are copies of the Android originals with only the
package line changed. **The DC address table is therefore duplicated across the two projects** —
if Telegram's front-end addresses change, both copies need updating. Worth unifying into a shared
module if this pair is going to be maintained long term.

## Tests

```
.\build.ps1 test
```

Seven tests, all offline. The ones worth knowing about cover the behaviour that only exists here
and fails silently if it regresses: that anonymous SOCKS5 is refused, that a wrong password is
rejected, and that an authenticated client still cannot relay to a non-Telegram address.
