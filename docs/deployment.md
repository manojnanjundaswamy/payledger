# Deploying PayLedger for free

| | **Option A: Oracle Cloud Always Free VM** (recommended) | **Option B: Render + Neon** |
|---|---|---|
| What you get | A real Linux VM (Ampere ARM; 2–4 OCPU / 12–24 GB depending on current Oracle limits) | Managed container + managed Postgres |
| Always on? | ✅ Yes | ❌ App sleeps after 15 min idle (~1 min cold start) |
| Room for Kafka, Redis, Grafana (later phases) | ✅ Plenty | ❌ 512 MB RAM total |
| Effort | ~1 hour once | ~15 minutes |
| Interview story | "I run it on a hardened VM with Docker Compose, TLS, backups, CI-built multi-arch images" | "Deployed on a PaaS" |

> Free tiers change often. Check Oracle's *Always Free resources* page and Render/Neon pricing pages before relying on exact limits.

---

## Pipeline (both options)

```
git push main ──▶ GitHub Actions: tests (Testcontainers) ──▶ multi-arch image (amd64+arm64) + SBOM + provenance
                                                               └─▶ ghcr.io/<you>/payledger/ledger-service:{latest, sha-xxxxxxx}
```

After the first successful run, open GitHub → your profile → **Packages** → `payledger/ledger-service` → *Package settings* → set visibility to **Public** (so servers can pull without credentials), or log in on the server with a read-only token.

---

## Option A: Oracle Cloud Always Free VM

### 1. Create the VM
1. Sign up at cloud.oracle.com (a card is required for identity verification; Always Free resources are not charged).
2. *Compute → Instances → Create*: image **Ubuntu 24.04**, shape **VM.Standard.A1.Flex** (Ampere ARM), 2 OCPU / 12 GB.
   If you see "out of capacity", try another availability domain or retry later.
3. Add your SSH public key. Note the public IP.
4. *Networking → your VCN → Security List*: add ingress rules for TCP **80** and **443** (and UDP 443 for HTTP/3) from `0.0.0.0/0`.

### 2. Prepare the server
```bash
ssh ubuntu@<public-ip>

# Oracle's Ubuntu images ship with an iptables REJECT rule, so open 80/443 on the host too:
sudo iptables -I INPUT 5 -p tcp --dport 80  -j ACCEPT
sudo iptables -I INPUT 5 -p tcp --dport 443 -j ACCEPT
sudo iptables -I INPUT 5 -p udp --dport 443 -j ACCEPT
sudo apt-get update && sudo apt-get install -y iptables-persistent && sudo netfilter-persistent save

# Docker Engine (official repository)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

# Automatic security updates
sudo apt-get install -y unattended-upgrades
```

### 3. Free domain + HTTPS
1. Get a free subdomain at duckdns.org (e.g. `payledger-yourname.duckdns.org`) and point it to the VM's public IP.
2. Caddy fetches and renews a Let's Encrypt certificate automatically on first start.

### 4. Deploy
```bash
mkdir -p ~/payledger && cd ~/payledger
# copy deploy/compose.prod.yml, deploy/Caddyfile and deploy/.env.example from the repo (scp or git clone)
cp .env.example .env
nano .env       # DOMAIN, LEDGER_IMAGE, POSTGRES_PASSWORD (openssl rand -base64 32), API_KEYS (openssl rand -hex 32)
chmod 600 .env
docker compose -f compose.prod.yml up -d
docker compose -f compose.prod.yml ps        # all healthy?
curl https://$DOMAIN/swagger-ui.html -I
```

### 5. Operate
| Task | Command |
|---|---|
| Deploy a new version | `docker compose -f compose.prod.yml pull && docker compose -f compose.prod.yml up -d` |
| Roll back | set `LEDGER_IMAGE=...:sha-<previous>` in `.env`, then `up -d` |
| Logs (JSON) | `docker compose -f compose.prod.yml logs -f ledger-service` |
| Metrics | `docker compose -f compose.prod.yml exec ledger-service bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf "GET /actuator/prometheus HTTP/1.0\r\n\r\n" >&3; cat <&3'` (Grafana comes in Phase 4) |
| Backups | nightly in `./backups`; restore: `pg_restore -h ... -d ledger --clean <file>` |

---

## Option B: Render (app) + Neon (Postgres)

1. **Neon**: create a project and copy the connection details. JDBC URL format:
   `jdbc:postgresql://<host>/<db>?sslmode=require`
2. **Render**: *New → Web Service → Existing image* `ghcr.io/<you>/payledger/ledger-service:latest`, instance type **Free**.
3. Environment variables:
   ```
   SPRING_PROFILES_ACTIVE=prod
   DB_URL=jdbc:postgresql://<neon-host>/<db>?sslmode=require
   DB_USER=<neon-user>
   DB_PASSWORD=<neon-password>
   API_KEYS=<openssl rand -hex 32>
   DB_POOL_SIZE=5
   PORT=8080
   MANAGEMENT_PORT=8080
   MANAGEMENT_ENDPOINTS=health
   ```
   (Render exposes only one port, so actuator shares it and only `health` is exposed.)
4. Health check path: `/actuator/health/readiness`.

---

## What makes this "production-grade" (and how to talk about it)

- **Image**: multi-stage build, JRE-only runtime, **non-root** user, layered jar (a code change ships about 100 KB, not 80 MB), multi-arch, SBOM + provenance attestations.
- **Runtime**: read-only root filesystem, all Linux capabilities dropped, `no-new-privileges`, memory limits, JVM heap sized from the container limit, fail fast on OOM.
- **Network**: only the reverse proxy is public; the database sits on an `internal` network with no internet route; actuator runs on a separate private port.
- **Edge**: automatic TLS, HSTS, security headers, request-size limits, JSON access logs.
- **App**: graceful shutdown, readiness/liveness probes, Prometheus metrics, structured ECS JSON logs, refuses to start in prod without API keys, constant-time key comparison.
- **Data**: Flyway-versioned schema, nightly backups with retention.
- **Supply chain**: Dependabot for Maven, Docker base images and GitHub Actions.

### Known gaps (planned)
- API keys → OAuth2/OIDC with Keycloak (per-client identity and scopes)
- Rate limiting (Redis, Phase 3)
- Grafana/Prometheus/Loki + OpenTelemetry tracing (Phase 4)
- Off-site backup copies (e.g. rclone to object storage) and a tested restore drill
- Separate DB roles: a migration owner vs. a least-privilege runtime user
