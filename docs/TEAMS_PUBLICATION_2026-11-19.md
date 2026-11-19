# Teams sandbox publication — 2026-11-19

## Published scope and provenance

This change publishes the already tested local Teams version described in section
11 of [the technical audit](TEAMS_TECHNICAL_AUDIT_2026-11-19.md). It includes the
text/ordinary-thread foundation, inbound/outbound files, asynchronous Connector
HTTP 202 acceptance, and retained deleted-message content and clean attachments.
Recovery, discovery, full installation/revocation lifecycle and live group-chat
acceptance remain follow-up work. No merge or production readiness is claimed.

The source checkout was clean at `fc4cbf82020d2e60237170e10303dbc4807d52d8` on
`codex/USI-181-teams-sandbox`, consisting of these three commits above main:

- `2e0e931`: Teams intake/routing/Connector foundation and setup assets;
- `514a515`: live signing-key and reply-chain corrections;
- `fc4cbf8`: file transport, HTTP 202, deletion retention and audit evidence.

The audited runtime JAR SHA-256 is
`6a7b093f49b39b679c0ae24e1ba8334ca39f570661985122ac8e44bb7d729abc`.
All 656 packaged classes match both the original checkout's compiled classes and
the fresh publication-worktree compilation. The new
publication worktree starts at freshly fetched
`origin/main@f519bfcfc24387f0fcb772984231f2dfb96a0c6d`, which is already an ancestor
of that source checkout. There were no unpublished source edits to recover and no
main conflict to resolve. A squash import preserves the original runtime checkout
and keeps local evidence out of the public branch's commit history.

Application sources, migrations, dependency lockfile and automated tests are
preserved from the source checkout. Publication-only edits anonymize local evidence
in documentation and document reproducible external configuration. Runtime files,
secrets, generated ZIPs, screenshots, logs and local database contents are excluded.
The full original reports remain with the operator's external evidence.

The Next.js/eslint-config-next 16.4.6 pins and lockfile are part of the tested build:
the earlier 16.4.1 patch was rejected by the vulnerability gate. The additional
backend JOSE dependency is required for Connector JWT verification. Neither is an
unrelated dependency modernization in this publication.

## PR #46 dependency resolution

No prerequisite PR needs to merge first. The required setup portion of draft
[PR #46](https://github.com/Spodyn/ApplicationSupport/pull/46), reviewed at
`fa8eda29948fbafdc6e6c3556fa013600ed9970c`, is included here:

| Asset from #46 | Resolution in this PR |
| --- | --- |
| `docs/TEAMS_DEVELOPMENT.md` | Included with current runtime/file guidance |
| `docs/TEAMS_RSC_CAPABILITY.md` | Included, reviewed `groupChat` casing |
| `docs/poc/teams-rsc/manifest.contract.json` | Included |
| `docs/poc/teams-rsc/manifest.template.json` | Included; team/groupChat and exact two RSC permissions |
| `scripts/render_teams_dev_manifest.py` | Included with package/static-tab validation fixes |
| `scripts/tests/test_teams_development_contract.py` | Included |
| `scripts/tests/test_teams_rsc_capability_contract.py` | Included |
| `apps/web/config/development-proxy.mjs` | Keep the newer main version unchanged; do not restore the older #46 proxy |

This PR also contains the tested package helper and its tests. PR #46 remains an
unmerged draft; it should not be merged ahead of this PR merely to provide these
assets. Its broader Jira acceptance/dependency status is not marked complete here.

## Reproducing the local version

Use Node/pnpm from the repository pins, Java 25, Docker and the Maven wrapper.
Install with `pnpm install --frozen-lockfile`. Validate against disposable
infrastructure using [REGRESSION_TESTING.md](REGRESSION_TESTING.md), without copying
the developer's `.env` or real provider credentials into tests.

For a separately authorized sandbox runtime:

1. Start the local infrastructure and API/frontend through the existing developer
   commands. Apply the complete Flyway chain, including V50 routes, V51 transfer
   jobs/file checkpoints, V52 asynchronous acceptance and V53 retention cleanup.
   Configure a reachable ClamAV INSTREAM scanner through the existing
   `usi.attachments.scanner.host` / `usi.attachments.scanner.port` settings; the
   audited local process supplied `USI_ATTACHMENTS_SCANNER_HOST=127.0.0.1` and
   `USI_ATTACHMENTS_SCANNER_PORT=3310`. A blank/unavailable scanner fails closed.
   Keep these local settings and object-storage credentials outside version control.
2. Register/configure the Teams bot as described in
   [TEAMS_DEVELOPMENT.md](TEAMS_DEVELOPMENT.md). Supply non-secret client ID, tenant
   ID and public HTTPS callback through the existing configuration contract. Put
   `teams-client-secret` only in the existing external integration secret store;
   the database stores its `secret_ref`, never the credential.
3. Render the manifest and package icons outside the checkout with the committed
   renderer/package helper. Install in the authorized test team and consent to
   `ChannelMessage.Read.Group` and `ChatMessage.Read.Chat`. No personal/private/shared
   scope or tenant-wide message-read scope is added.
4. Seed an explicitly approved standard channel using
   `scripts/sql/local-teams-sandbox-seed.sql` with the operator's actual non-secret
   tenant/context identifiers and secret reference. Map the customer and enable
   monitoring. This is the current manual setup, not new discovery.
5. For documents, an administrator must explicitly grant Graph application
   `Sites.Selected` to the bot and assign `write` only on the approved sandbox site.
   RSC message permission alone does not grant access to SharePoint files. The
   administrator's temporary Graph session may need delegated
   `Sites.FullControl.All` to assign that selected-site permission; that scope is
   never assigned to the bot. Disconnect the administration session afterwards.
6. Obtain the approved drive ID and each channel folder ID from Graph or the
   administrator. A newly created channel may need its Files tab opened to provision
   the folder. Configure the existing `metadata_json.teamsFiles` after running the
   channel seed. Re-running the seed replaces metadata, so reapply this configuration
   afterwards. An example psql statement (all values are operator-supplied) is:

```sql
UPDATE channels
SET metadata_json = jsonb_set(
  COALESCE(metadata_json, '{}'::jsonb), '{teamsFiles}',
  jsonb_build_object(
    'driveId', :'approved_drive_id',
    'folderId', :'channel_folder_id',
    'sharePointHosts', jsonb_build_array(:'approved_sharepoint_host')
  )
)
WHERE integration_id = :'integration_id'::uuid
  AND external_channel_id = :'channel_id';
```

Use the exact approved SharePoint hostname, not a wildcard. Additional sites or
another tenant require separate explicit approval/configuration. Store all actual
identifiers, rendered packages and evidence outside this repository. No live
consent, installation or tenant mutation is performed merely by publishing this PR.

Only CLEAN files can be sent/downloaded. After repairing file permissions or
storage configuration, use Settings → Integrations → Retry Teams attachments.
Policy/quarantine failures remain blocked. An ambiguous send is not automatically
replayed. See [OPERATIONS.md](OPERATIONS.md#teams-file-transfer-repair) for the
existing retry and HTTP-acceptance behavior.

## Validation handoff

Fresh validation ran in the clean publication worktree without the developer's
`.env`, using disposable infrastructure and synthetic provider HTTP:

| Command/check | Result |
| --- | --- |
| `pnpm install --frozen-lockfile` | PASS |
| `pnpm check` | PASS: dev tools 9, gate tests 71, configuration 68, OpenAPI 14 + adapter smoke 3, web unit 105, UI E2E 36; lint, typecheck, generated-client check and production build pass |
| `bash apps/api/mvnw -f apps/api/pom.xml --batch-mode clean verify -Pcoverage -DexcludedGroups=integration` | PASS: 318 tests, 0 failures/errors/skips |
| `bash infra/minio/build-source-images.sh server` | PASS: pinned synthetic object-store image |
| `bash apps/api/mvnw -f apps/api/pom.xml --batch-mode test -Dgroups=integration` | PASS: 281 tests, 0 failures/errors/skips |
| `pnpm test:fullstack` | PASS: 20/20, zero retries; real disposable PostgreSQL/RabbitMQ/MinIO, production frontend/backend and synthetic Slack HTTP |
| `pnpm test:coverage` | PASS: 105 tests; scoped line/statements/functions/branches coverage 85.00/82.73/81.48/82.75% |
| `python scripts/testing/changed_coverage.py --base-ref f519bfcfc24387f0fcb772984231f2dfb96a0c6d` | PASS: measured changed executable lines covered |
| `pnpm openapi:compat -- --base-ref f519bfcfc24387f0fcb772984231f2dfb96a0c6d` | PASS: additive |
| `infra/tests/verify-compose-lifecycle.sh` | PASS: isolated health, persistence and reset; Windows launcher detail below |
| `pnpm audit --audit-level=high` | PASS: no known vulnerabilities |
| Maven `dependency:list -DoutputFile=target/security-dependencies.txt`, then `python scripts/security_dependencies.py --maven-list apps/api/target/security-dependencies.txt` | PASS: 250 packages, no findings |
| Secret/history scan, regression inventory, decision YAML parse, publication-document local links, `git diff --check` | PASS |

For pnpm checks, `NEXT_PUBLIC_API_BASE_URL=/api/v1` and
`NEXT_PUBLIC_WS_BASE_URL=/ws` match CI. Maven uses the committed wrapper through
Git Bash on Windows. The direct Compose lifecycle attempt hit a Windows Python
CRLF port-string error before container startup. The successful external launcher
exports `python3() { command python "$@" | tr -d '\r'; }` with `set -euo pipefail`
and runs the unchanged lifecycle script. No assertion, timeout or retry policy was
weakened. Logs and generated reports remain outside Git.

Original live Teams evidence and prior automated counts remain in the audit,
separate from these fresh runs. Publication does not repeat live tenant actions.
Recovery, discovery, lifecycle and live group-chat acceptance are deferred; the
extended repeat/mutation workflow was not run during this publication. GitHub CI
must evaluate the published exact HEAD independently. No skipped, blocked or
pending check is a pass. The independent integrator performs the final exact-HEAD
gate and merge; this publication does not assign an approval or mark Jira done.
