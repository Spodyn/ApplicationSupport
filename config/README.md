# Environment and secret handling

USI configuration is deny-by-default and split by process boundary. The
machine-readable source for variable names, types, profile requirements, and
secret classifications is [`environment-contract.json`](environment-contract.json).
The executable preflight is [`scripts/validate_environment.py`](../scripts/validate_environment.py).

## Profiles

The API has exactly four explicit runtime profiles:

| Profile | Intended use | Core credentials | Integration credentials |
| --- | --- | --- | --- |
| `local` | Disposable developer services only | Obvious dummy/dev values in ignored local environment | Dummy/dev values behind filesystem `secret_ref` |
| `test` | Unit/integration/CI with fake providers | Supplied dynamically by the test harness/Testcontainers | In-memory fake secret resolver |
| `staging` | Isolated production-like environment | Required runtime config tree | Required deployment secret store/config tree via `secret_ref` |
| `production` | One dedicated customer deployment | Required runtime config tree | Required deployment secret store/config tree via `secret_ref` |

Spring-compatible profile resources live in
[`apps/api/src/main/resources`](../apps/api/src/main/resources). There is no
implicit production fallback. `SPRING_PROFILES_ACTIVE` must select exactly one
profile, and the staging/production config-tree import is deliberately not
`optional`, so a missing mount fails startup.

The API runtime itself is introduced by its dedicated backend ticket. Until
then, the repository preflight is the executable enforcement of the same
property contract; the profile resources are already in the Spring-standard
location and contain required placeholders rather than defaults for secrets.

## Local setup

Only disposable local credentials may be copied from the examples:

```bash
cp .env.example .env
cp apps/web/.env.example apps/web/.env.local
cp infra/.env.example infra/.env

python3 scripts/validate_environment.py api --env-file .env
python3 scripts/validate_environment.py web --env-file apps/web/.env.local
```

The root example is server-only. The frontend example contains only the two
reviewed, same-origin browser paths. The infra example configures loopback-only
PostgreSQL, RabbitMQ, and MinIO. Keep the local API and infra dummy credentials
aligned when starting both.

Provider callbacks in local development may point to any suitable public HTTPS
tunnel; the checked-in `.invalid` values are inert examples. No tunnel vendor
is part of the product contract.

## Browser-public boundary

Only these values may be exposed to browser JavaScript:

| Variable | Type | Contract value |
| --- | --- | --- |
| `NEXT_PUBLIC_API_BASE_URL` | same-origin path | `/api/v1` |
| `NEXT_PUBLIC_WS_BASE_URL` | same-origin path | `/ws` |

[`apps/web/config/public-environment.mjs`](../apps/web/config/public-environment.mjs)
validates the complete `NEXT_PUBLIC_*` namespace when Next loads its config.
Unknown public variables, absolute/cross-origin URLs, query strings, fragments,
and traversal paths fail startup/build. Signing secrets, provider tokens,
client secrets, passwords, credentials, and private keys are never public
configuration.

Next.js inlines `NEXT_PUBLIC_*` values at build time. These two paths are stable
same-origin contract paths; deployment-varying or sensitive data must remain on
the server and must not be added to the Next config `env` option.

## Server non-secret properties

The API receives URLs and identifiers through typed configuration properties:

- public application origin and explicit Slack/Teams/Telegram callback URLs;
- PostgreSQL JDBC URL without embedded credentials;
- RabbitMQ host, port, and vhost;
- object-storage endpoint, region, and bucket names;
- optional provider client/tenant identifiers and Telegram bot username;
- an empty CORS origin list by default (same-origin, deny/off).

Staging and production require HTTPS for public and object-storage URLs. Callback
URLs require HTTPS in every profile. URLs containing user info, credential query
parameters, wildcard CORS, or callback query data are rejected.

## Staging and production secret injection

Core service credentials are mounted into the directory named by
`USI_CORE_SECRETS_DIRECTORY` (normally a Docker/deployment secret mount). The
directory must be absolute, end in `/`, exist at startup, and contain these
non-empty files:

```text
spring.datasource.username
spring.datasource.password
spring.rabbitmq.username
spring.rabbitmq.password
usi.object-storage.access-key
usi.object-storage.secret-key
```

Before starting a staging/production process, the entrypoint must run the
preflight against the actual environment and mounts:

```bash
python3 scripts/validate_environment.py api --check-secret-files
```

The preflight reports property/file names only; it never prints values.
Plaintext core credential environment aliases are rejected in staging and
production to avoid ambiguous precedence over the config tree.

Provider credentials use a separate directory/deployment secret-store boundary
named by `USI_INTEGRATION_SECRETS_DIRECTORY`. They are intentionally not bulk
imported into Spring's environment. Persistence stores only an opaque,
resolver-owned `secret_ref`; it never stores a Slack signing secret/client
secret/bot token, Teams client secret, Telegram bot token, or Telegram webhook
secret token. API responses, logs, audit, diagnostics, tests, Jira, and browser
bundles must not contain resolved values.

## Repository protection

`.gitignore` ignores `.env*` at every depth and permits only files named exactly
`.env.example`. CI verifies this behavior, validates both checked-in examples,
tests missing/invalid profile behavior and mounted-secret failures, and runs:

```bash
python3 scripts/secret_scan.py
```

The scanner covers non-ignored worktree files plus available Git history. It
rejects tracked non-example env files, private-key headers, common credential
formats, literal high-entropy secret assignments, and sensitive
`NEXT_PUBLIC_*` names. Test fixtures construct detector samples at runtime; no
real credential is stored in source or tests.
