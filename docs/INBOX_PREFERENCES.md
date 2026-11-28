# Personal inbox preferences

Owner-approved extension, 27 November 2026. Canonical semantics are in
[PRODUCT_CONTRACT.md](PRODUCT_CONTRACT.md); this does not alter the Case state machine.

Every authenticated user opens **Inbox preferences / Preferencje skrzynki** from
the profile menu. **Unified inbox / Wspólna skrzynka** is the default.
**Separate by platform / Osobno według platformy** adds compact platform buttons
above the Case list. A successful change needs no logout or refresh and reaches
the same account's other sessions through authenticated personal realtime hints.
Failed writes restore the authoritative preference and show an error.

GET/PUT `/api/v1/users/me/inbox-preferences` reads the session principal. A missing
row means `UNIFIED`; the other transport value is `SEPARATE_BY_PLATFORM`. V58 is
additive and compatible with the previous application. It stores no Case content
and does not rewrite historical data. General organization settings remain
administrator-only. The OpenAPI contract generates the transport client; stable
frontend types and hooks remain behind the service registry.

The filter-options catalog returns distinct configured integration providers.
Disabled connections remain selectable so their historical Cases remain
accessible. An empty catalog renders an empty separated inbox and makes no
unscoped Case list request. Provider names use one shared selector, not separate
inbox implementations. Future provider support extends the existing provider
catalog/domain contract, without another inbox page.

The existing REST `provider` filter scopes every page, matching bulk selection,
Quick Views and unread/SLA attention filters. The existing resolved-read batch
command accepts an optional `provider` scope, included in its idempotency
fingerprint. Separated mode always supplies it (and disables the action when
there is no platform). Omitting the parameter preserves the global command and
its existing replay fingerprint. Search, advanced filters and server
ordering remain in effect when switching platforms. The advanced provider filter
is hidden in separated mode. Each inactive tab requests at most one Case row to
obtain authoritative filtered counters; events invalidate the shared Case query
prefix and REST refetches the appropriate query keys. Fallback polling remains
bounded at 15 seconds, with no additional socket or per-Case timer. Preference
fallback is 30 seconds and reconnect reconciles its account-keyed cache.

A platform or mode scope change clears bulk selections, scoped bulk dialogs and
the selected conversation before actions can target another provider. Bulk retry
commands retain their original account/provider storage scope. Deep links and
reminder navigation resolve the Case's actual provider before opening it.

Public text, private notes, selected File objects and retry identities are retained
per Case in the authenticated account's memory store. Navigation between Cases,
platforms and preferences does not discard them or copy them into another Case.
Pending sends remain disabled across editor remounts. Signing out, replacing the
account, closing the tab or reloading discards this memory; no customer content
is written to local/session storage. Drafts are not synchronized across devices.

Regression sources and their scope are recorded under `INBOX_PREFERENCES` in
[REGRESSION_MATRIX.md](REGRESSION_MATRIX.md) and `config/regression-features.json`.
Automated controlled-stack evidence is separate from live deployed acceptance.
