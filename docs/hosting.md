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

## Auto-capture: bank emails by forwarding

Each person gets an address like `k3pf98qvpvxa@in.example.com`. Cloudflare Email Routing receives mail for that
subdomain and hands it to a small Worker, which posts it to PayLedger (`/inbound/email`) with a shared secret.

1. **Email Routing on a subdomain** (so your own domain's mail is untouched): Cloudflare dashboard → your domain →
   Email → Email Routing → enable it for `in.example.com`; Cloudflare adds the MX and SPF records.
2. **The Worker**: Workers & Pages → Create → Hello World → paste `deploy/cloudflare/email-worker.js` → Deploy.
   Settings → Variables: `INBOUND_URL` = `https://payledger.example.com/inbound/email`, and `INBOUND_SECRET` (as a
   secret) = a long random value (`openssl rand -hex 32`).
3. **Route everything to it**: Email Routing → Routing rules → Catch-all → Send to a Worker → the Worker above.
4. **`.env`**: `CAPTURE_DOMAIN=in.example.com` and the same `INBOUND_SECRET`, then
   `docker compose --profile web up -d core-service`.

People then follow the guide on the Auto-capture page (forwarding address, Gmail's confirmation code, a filter
for bank senders). Pasting SMS works without any of this.

## Google sign-in ("Continue with Google")

1. [Google Cloud Console](https://console.cloud.google.com/projectcreate): create a project (e.g. "PayLedger").
2. [Google Auth Platform → Branding](https://console.cloud.google.com/auth/branding): app name, support email,
   authorised domain `example.com`; Audience: External, then Publish (only the basic `openid email profile` scopes,
   which need no review).
3. [Clients → Create client](https://console.cloud.google.com/auth/clients/create): Web application.
   - Authorised JavaScript origin: `https://payledger.example.com`
   - Authorised redirect URIs: `https://payledger.example.com/realms/payledger/broker/google/endpoint`
     (and `http://localhost:8180/realms/payledger/broker/google/endpoint` for the laptop on its own)
4. Put the client id and secret in `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, then run
   `scripts/keycloak-sync.sh`. The sign-in and sign-up pages show "Google".
