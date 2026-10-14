# Hosting from your laptop (Cloudflare Tunnel)

The whole stack runs in Docker on your machine; a Cloudflare Tunnel gives it a public https address. The site is up
while the laptop and Docker are on.

## How it fits together

```
browser ──https──> Cloudflare ──tunnel──> cloudflared (container) ──> http://host.docker.internal:5173 (web, nginx)
                                                                         ├─ /api, /oauth2, /login, /logout -> gateway
                                                                         ├─ /realms/payledger, /resources   -> Keycloak (sign-in pages)
                                                                         └─ everything else                 -> the React app
```

One address serves everything, so cookies stay first-party. Only the `payledger` realm's pages go out: Keycloak's
master realm and admin console stay on `localhost:8180`.

## Set it up

1. **Tunnel**: Cloudflare dashboard → Zero Trust → Networks → Tunnels → create one, run its `cloudflared` container
   (`docker run -d --restart unless-stopped cloudflare/cloudflared:latest tunnel --no-autoupdate run --token …`),
   and add a public hostname, e.g. `payledger.example.com` → `http://host.docker.internal:5173`.
2. **`.env`** in the repo root (git ignores it):
   ```
   PUBLIC_URL=https://payledger.example.com
   COOKIE_SECURE=true
   RATE_LIMIT_PER_MINUTE=600
   RATE_LIMIT_WRITES_PER_MINUTE=120
   # Real email, so people can verify their address (Gmail app password; see .env.example):
   SMTP_HOST=smtp.gmail.com
   SMTP_PORT=587
   SMTP_USERNAME=you@gmail.com
   SMTP_PASSWORD=the-16-letter-app-password
   SMTP_AUTH=true
   SMTP_STARTTLS=true
   MAIL_FROM=you@gmail.com
   ```
3. **Start and sync**:
   ```bash
   docker compose --profile web up -d --build
   scripts/keycloak-sync.sh
   ```
   The sync lets sign-in return to `PUBLIC_URL` and points Keycloak's emails at your SMTP.

With `PUBLIC_URL` set, signing in from `http://localhost:5173` also goes through the public address, so the tunnel
must be up. Remove `PUBLIC_URL` (and rerun both commands) to go back to purely local.

## Before you share the link

- **Change the demo password** (Settings → Account → Change password): `demo@payledger.local` / `demo-password` is
  in this repository and is an admin.
- **Change Keycloak's admin password** (`admin` / `admin`): it isn't reachable through the tunnel, but anyone on your
  Wi-Fi can reach `:8180`.
- Without SMTP settings, sign-up emails go to Mailpit on your laptop, so new people can't verify their address.
- Back up: `docker compose exec -T core-db pg_dump -U core core > core-backup.sql` (and the same for `keycloak-db`).
