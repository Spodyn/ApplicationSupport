# Contabo staging deployment

Scope: a fresh, isolated staging instance on Ubuntu 24.04, not production or a
migration of the developer's database. The owner authorized preparation, VPN/SSH
setup and the first deployment **after this PR merges**. The developer does not
merge the PR or change provider webhook configuration.

## Public ingress and durable services

- Host: `158.220.75.88`; URL: <https://application-support.duckdns.org>.
- Caddy publishes TCP 80/443, obtains/renews public ACME certificates, redirects
  HTTP and routes `/api`, `/api/*`, `/ws`, `/ws/*` to API, with all other application
  traffic going to Next.js. `/actuator*` is denied publicly. Caddy overwrites
  forwarded proxy headers; the API trusts headers only behind this private ingress.
- PostgreSQL 18, RabbitMQ 4.3, MinIO, ClamAV, API and web have no host-published
  data/admin ports. The Docker `data`/`ingress` networks are internal; only API,
  scanner and Caddy have explicit egress paths. Docker publishing is not secured by
  UFW alone: only Caddy is allowed a published port in the checked Compose model.
- Named volumes retain PostgreSQL, RabbitMQ, objects, scanner signatures and
  Caddy ACME/CA state when containers are recreated. Do not run `down --volumes`
  against `usi-staging`.
- Web/API run as non-root with read-only root filesystems, bounded writable tmpfs
  and dropped capabilities. API has a 1.5 GiB limit, web 512 MiB, ClamAV 2 GiB.
  The 4-vCPU/8-GB VPS is a small staging machine, not the frozen production capacity
  benchmark or a successful load/soak test.
- Next standalone includes monorepo tracing, public assets and static chunks.
  Runtime `USI_DEPLOYMENT_PROFILE=staging` marks the UI and page title STAGING.
  Browser API/WS paths remain exactly `/api/v1` and `/ws`; no provider secret enters
  the frontend build.

## Host preparation and secret locations

Use the owner's existing administrator key from a trusted Windows terminal:

```powershell
ssh -i "$env:USERPROFILE/.ssh/usi_contabo" -o BatchMode=yes root@158.220.75.88
```

`infra/staging/bootstrap-host.sh` installs signed Docker Ubuntu packages, WireGuard,
UFW and age. It preserves administrator access from the source IP of that trusted
SSH session, permits public 80/443 TCP and 51820 UDP, and permits deployment SSH
only from VPN peer `10.77.0.2` to `10.77.0.1`. An administrator whose public IP
changes must add the new trusted IP through the existing VPN/admin path or Contabo
console; do not globally open database ports or disable the firewall.

The operator supplies public files `/root/usi-bootstrap/actions-ssh.pub` and
`runner-wg.pub` before running the helper. Never reuse the administrator's private
SSH key for Actions. Keep the generated runner private keys in the existing
operator's external protected evidence/credential directory and GitHub environment
secrets, not in the checkout. Host WireGuard private material remains root-only
under `/etc/wireguard`. The deploy account has no Docker-group access and cannot
select a different command, destination path or shell through its key.

Core configuration is initialized explicitly, once, on the VPS:

The prepared helper is copied from `infra/staging` to
`/root/usi-bootstrap/staging` over the trusted administrator SSH session. This
keeps setup files separate from immutable application releases.

```bash
install -d -m700 /srv/usi-staging
python3 /root/usi-bootstrap/staging/init_state.py --directory /srv/usi-staging/state \
  --admin-email admin@application-support.duckdns.org
```

The login above is the initial staging default, not a promise of email delivery.
Use the owner's chosen email before first deployment if supplied. Initialization
preserves existing credentials and configuration; it never rotates them silently.

`/srv/usi-staging/state/core` contains the existing Spring configtree filenames,
cursor signing key and bootstrap password; `integrations` is the existing
`secret_ref`-based provider store, initially empty. The API reads these mounted
files, not plaintext provider environment variables. Permissions grant API UID/GID
10001 read access while host directories remain protected. MinIO/init receive only
their two object-store credentials; PostgreSQL/Rabbit receive their individual
password file. No development database, customer objects or provider credentials
are copied.

`state/minio-tls` and `state/trust/CAs` provide private MinIO TLS. Java adds the
public CA to a temporary copy of its normal truststore; mc uses its documented CA
directory. Certificate/hostname checks stay enabled. The CA private key is not
mounted into any container. The initial leaf certificate lasts one year; renew
it under that CA before expiry and recreate MinIO/API, without silently replacing
the CA or core credentials.

One-time administrator bootstrap is enabled only when PostgreSQL reports that the
existing singleton bootstrap state has not been consumed. After healthy startup,
deployment recreates API with the trigger disabled. On a resumed deployment the
database consumed flag is authoritative, so a crash cannot create a second admin.
The password stays in the protected operator file/store, never PR text or logs.

## GitHub Actions and WireGuard

Environment settings:
<https://github.com/Spodyn/ApplicationSupport/settings/environments> → `staging` →
Deployment branches and tags → Selected branches and tags → branch `main` only.
No production environment, branch protection, reviewer or auto-merge setting is
changed. The following environment secrets are needed; preparation can set them
with `gh secret set NAME --env staging --repo Spodyn/ApplicationSupport` through
stdin, so values do not appear in command arguments:

| Secret | Value source |
| --- | --- |
| `STAGING_SSH_KEY` | Dedicated Actions Ed25519 private key; its public key is forced-command/from-VPN restricted |
| `STAGING_SSH_KNOWN_HOSTS` | `10.77.0.1` plus VPS host public key obtained over the already trusted administrator SSH connection |
| `STAGING_WG_PRIVATE_KEY` | Dedicated Actions WireGuard peer private key |
| `STAGING_WG_SERVER_PUBLIC_KEY` | VPS WireGuard public key |

Only `10.77.0.1/32` enters the runner VPN; no default route, NAT or forwarded
application/data network is granted. The public endpoint is `158.220.75.88:51820`.
Runner keys/config are removed in the workflow's always-run cleanup step. VPN
private files and SSH known-host pinning must never be printed in workflow logs.

`Deploy staging` runs on successful `CI` completion for a **main push**, or manually
through Actions → Deploy staging → Run workflow → branch `main`. It checks out
trusted main, requires the exact current main SHA and all eight successful jobs
from one latest main-push CI attempt, and refuses missing/pending/skipped/cancelled
jobs, PR-only success, stale commits or arbitrary branch inputs. Main advancing
during a build requires a new dispatch. No untrusted PR artifact is consumed.

Build and deployment are separate jobs. The build job has package-write access
but no VPS secrets. It builds API/web and the already-reviewed source-pinned
MinIO/mc recipes, pushes GHCR SHA tags and records immutable digests. Data-service
base images are pinned by digest in Compose. The deploy job receives only the
immutable envelope, environment SSH/VPN secrets and its ephemeral package-read
GitHub token. This short-lived token is sent through encrypted VPN+SSH stdin and
used in a temporary root-only Docker config, which is deleted at command exit;
there is no permanent GHCR PAT on the VPS and images need not be public.

Application image scanning is a mandatory infrastructure and release-build gate.
Pinned Trivy scans isolated saved-image archives without Docker socket or runtime
secret access. Any HIGH/CRITICAL result fails, including unfixed findings; there is
no suppression/ignore-unfixed switch. The web runtime copies only Node and traced
application files into patched Ubuntu 24.04, leaving npm/build tooling in the build
stage. The initial Debian/npm runtime failed this gate and was replaced before
release rather than suppressing its findings.

The root-owned forced command accepts only `check` or `deploy <40-character-SHA>`.
It rechecks main and CI, fetches that main commit from the public repository,
extracts only the reviewed staging directory, validates image repositories/digests
and revision labels, and serializes host deployment with a lock. It does not
accept a shell, SCP upload, supplied deployment script or target directory.

## Release, verification and recovery boundaries

Runtime configuration: `/srv/usi-staging/state/runtime.env`; immutable release
manifests/config: `/srv/usi-staging/releases/<SHA>`; successful pointers:
`current-release`, `previous-release`. Core credentials are outside those release
directories and images. Docker/Compose logs use bounded local rotation, not an
unbounded build or secret-containing archive.

Preflight requires green CI and immutable images. A subsequent deployment takes an
age-encrypted PostgreSQL custom dump before starting new API code. The age key in
`state/recovery/backup.agekey` must also be held off-host in the operator's protected
store; it is not included in ordinary backups. This is a staging database checkpoint,
not production WAL/PITR, off-host object backups, HA or proven RPO/RTO. Those remain
release/operations acceptance work. Do not claim production backup compliance.
Full production monitoring/alerting and its acceptance are also outside this small
staging bootstrap; bounded container diagnostics do not replace those systems.

Flyway runs the existing append-only migration chain. Deployment waits for private
dependency/API/web health, then trusted public HTTPS. First deployment additionally
runs `smoke.py`: actual UI and staging marker, login, Secure/HttpOnly/SameSite=Lax
session, authenticated API, WebSocket upgrade and STOMP CONNECTED, logout and 401.
No password, cookie or provider credential is emitted. It does not reuse the initial
bootstrap password on future releases after the owner may have changed it.

On failed readiness/smoke the workflow fails; it does not mark success, purge data
or roll schema backward. Inspect the protected host's `docker compose ... ps/logs`
using the recorded release files. An administrator may select `previous-release`
and restart those exact compatible images after checking migrations/backup state.
Do not assume all old binaries support a newer schema, and do not automatically
revert a database after a failed application launch.

After successful HTTPS acceptance, the owner can set the Slack Event Subscriptions
Request URL to:
`https://application-support.duckdns.org/api/v1/providers/slack/events`.
Do this only after separately configuring the intended **sandbox** integration,
provider secret reference, channel/customer mapping and consent in the fresh
staging instance. Publishing/deploying this PR does not perform that cutover or
transfer the local provider account/data.

## Validation

```bash
pnpm install --frozen-lockfile
NEXT_PUBLIC_API_BASE_URL=/api/v1 NEXT_PUBLIC_WS_BASE_URL=/ws pnpm check
python3 -m unittest discover -s scripts/tests -p test_staging_deployment.py -v
docker build -f apps/api/Dockerfile -t usi-staging-test-api:local .
docker build -f apps/web/Dockerfile -t usi-staging-test-web:local .
bash infra/minio/build-source-images.sh all
python3 infra/staging/test_compose.py
python3 infra/staging/scan_images.py usi-staging-test-api:local usi-staging-test-web:local
```

The staging acceptance script generates its own random project/state and synthetic
account, uses trusted local Caddy CA verification, checks real login/API/STOMP and
encrypted object I/O, recreates containers, and verifies DB/object/ACME persistence
and exactly one admin. Only its generated `usi-staging-test-*` volumes are removed.
CI's mandatory `infrastructure` job runs it; no VPS or provider secret is available
to PR CI. The ordinary backend/integration/web/fullstack/security gates remain
mandatory. Public ACME and the first real VPS app deployment are NOT RUN until merge.

Official implementation references: [Docker Ubuntu install](https://docs.docker.com/engine/install/ubuntu/),
[Caddy HTTPS](https://caddyserver.com/docs/automatic-https),
[Caddy reverse proxy/WebSocket](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy),
[WireGuard](https://www.wireguard.com/quickstart/),
[GitHub environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments),
[Next standalone](https://nextjs.org/docs/app/api-reference/config/next-config-js/output),
[MinIO client CA directory](https://min.io/docs/minio/linux/reference/minio-mc.html).

Provider onboarding: [PROVIDER_ONBOARDING.md](PROVIDER_ONBOARDING.md). The API entrypoint supports `USI_JAVA_PREFER_IPV4`; staging defaults it to true because the live VPS Java HTTP client timed out on the advertised IPv6 route while the identical authenticated path over IPv4 succeeded. TLS certificate/hostname verification remains enabled, and other instances can explicitly select false. Bot identifiers and credentials are independent per instance.
