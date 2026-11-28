# Unified Support Inbox - Canonical Workflow Matrix

**Status:** FROZEN for v1  
**Related Jira:** USI-35, USI-36, USI-37, USI-39 and E09/E10/E13 freeze decisions

## 1. State diagram

```text
NEW / PARTIALLY_IGNORED -- eligible Claim --> VERIFICATION (OPEN)
NEW -- Ignore (<2) --> PARTIALLY_IGNORED -- Ignore (>=2) --> IGNORED
NEW -- Ignore (>=2) --> IGNORED
VERIFICATION -- provider-confirmed Reply --> WAITING_FOR_CUSTOMER (ASKED)
Owned WAITING_FOR_CUSTOMER -- further successful Reply --> WAITING_FOR_CUSTOMER with a restarted follow-up clock
Owned WAITING_FOR_CUSTOMER -- customer reply --> VERIFICATION
Historical unassigned WAITING_FOR_CUSTOMER -- eligible Claim --> VERIFICATION
Historical unassigned WAITING_FOR_CUSTOMER -- customer reply --> NEW
WAITING_FOR_CUSTOMER -- follow-up deadline --> WAITING_FOR_CUSTOMER with internal reminder
VERIFICATION / owned WAITING_FOR_CUSTOMER -- owner Resolve --> RESOLVED
VERIFICATION -- admin Unassign --> NEW
Any nonterminal -- admin Force Resolve --> RESOLVED
RESOLVED / IGNORED + later customer message --> active or new linked NEW Case
```

## 2. Status invariants

| Legacy v1 status | Owner | Terminal | Normal support reply | Notes |
|---|---|---:|---:|---|
| `NEW` | none | no | no | Active unclaimed work |
| `PARTIALLY_IGNORED` | none | no | no | Ignore points below threshold |
| `VERIFICATION` | exactly one | no | current owner only | Active owned work |
| `WAITING_FOR_CUSTOMER` | retained after Reply, null for historical rows | no | current owner only | Wait starts after successful delivery |
| `IGNORED` | none | yes | no | Never reopened |
| `RESOLVED` | historical last owner or null | yes | no | Explicit administrative undo only |

## 3. Action matrix

| Action | Actor / permission | Allowed from | Preconditions | Result | Owner after | Primary side effects |
|---|---|---|---|---|---|---|
| Claim | active USER/ADMIN | `NEW`, `PARTIALLY_IGNORED`, historical unassigned `WAITING_FOR_CUSTOMER` | target remains unowned; actor is not lifetime ignore voter | `VERIFICATION` | actor | deactivate active partial ignore votes; clear stale snoozes; audit Claim; start/resume in-progress, pause unclaimed |
| Reply | current owner | `VERIFICATION`, `WAITING_FOR_CUSTOMER` | actor has not ever voted Ignore in this Case | waits after successful delivery if the Case has not moved on | unchanged | durable SUPPORT Message + outbox, configured follow-up clock starts or restarts; legacy SLA pause on entry, first-response completion |
| Ignore | eligible viewer | `NEW`, `PARTIALLY_IGNORED` | effective weight calculated by backend | partial or `IGNORED` | none | store immutable vote snapshot; update points; actor becomes lifetime-ineligible for Claim/Reply |
| Resolve | current owner | `VERIFICATION`, `WAITING_FOR_CUSTOMER` | optional controlled resolution category | `RESOLVED` | preserves owner | end SLA timers; clear snoozes; audit resolution |
| Admin assign | `ADMIN + reassign_cases` | `NEW`, `PARTIALLY_IGNORED` | target active/eligible/not lifetime ignore voter | `VERIFICATION` | target | audit assignment; not counted as Claim metric |
| Admin reassign | `ADMIN + reassign_cases` | `VERIFICATION` | target active/eligible/not lifetime ignore voter | `VERIFICATION` | target | in-progress time continues; audit change |
| Admin unassign | `ADMIN + reassign_cases` | `VERIFICATION` | none | `NEW` | null | preserve elapsed SLA; audit; unclaimed resumes |
| Force resolve | `ADMIN + force_resolve` | any nonterminal | none | `RESOLVED` | previous owner if any, otherwise null | `forced=true`; admin actor only in audit; end timers/snoozes |
| Undo resolution | `ADMIN + force_resolve` | `RESOLVED` | serialize with intake for the full provider context | return the active generation or reopen original as `NEW` | unchanged on redirect, otherwise none | audit, preserve previous SLA history and restart the unclaimed clock for an actual reopen |
| Personal Snooze | any active viewer | any nonterminal | 5m-30d | status unchanged | unchanged | mark read for actor; private reminder; SLA unchanged |
| Mark read | current user | any visible Case | frontend actually displayed content | status unchanged | unchanged | advance only current user's stable read cursor |

## 4. Ignore vote detailed semantics

Threshold: `2`.

Effective weight:

- default 1;
- temporary 2 may be granted by `ADMIN + manage_users` with optional expiry;
- the weight on a vote is an immutable snapshot.

Duplicate command:

- if the actor already has an active vote, return idempotent success;
- do not create another vote or add points again.

Reset behavior:

- a successful Claim by another user deactivates active partial votes;
- a new customer message before threshold deactivates active partial votes;
- history remains;
- an old voter may vote again after reset;
- lifetime Claim/Reply prohibition remains forever for that Case.

## 5. Public replies and waiting

1. The eligible owner queues a public reply with text, CLEAN attachments, or both. The Case retains its state and owner while delivery is pending or retrying.
2. Provider acceptance moves OPEN to ASKED only when the Case version, owner and customer activity still permit it. The transition sets the waiting deadline and applies the existing SLA pause policy.
3. A further current successful reply while ASKED restarts the follow-up deadline and retains ownership. Permanent failure records Message FAILED and leaves the workflow state intact.

### 5.1 Customer reply

Customer inbound while waiting:

- stores/dedupes the customer Message;
- Case -> `VERIFICATION` with retained owner for owned waiting, or `NEW` for unassigned waiting;
- clears waiting deadline;
- marks Case unread for eligible users;
- ends personal snoozes;
- resumes applicable SLA timers with preserved accumulated business time.

### 5.2 Follow-up expiry

At the follow-up deadline, the Case retains its status, owner, and deadline. The deadline can generate one internal reminder per eligible recipient. A subsequent customer message switches to the operational response clock. A subsequent successful public reply establishes its own follow-up deadline.

## 6. Terminal Case and linked successor

`IGNORED` remains terminal. An explicit administrative undo can reopen ordinary or forced `RESOLVED` Cases when no active generation exists.

When the next customer message arrives for the same provider conversation identity:

1. persist/deduplicate inbound event;
2. create exactly one new `NEW` Case under concurrency protection;
3. set `new.related_case_id` to the immediately previous terminal Case;
4. route concurrent or immediately following customer messages into that same newly created Case.

The result is a chain of Cases rather than mutation of historic terminal outcomes.

## 7. Read/unread matrix

| Event | Marks unread? | For whom? |
|---|---:|---|
| New customer Message | yes | all active users who may see Case |
| Customer Message edit | yes | all eligible users |
| Customer Message delete | no new unread by itself | none |
| Human support outgoing | no | none |
| OOO/system customer-facing | no | none |
| Claim/reassign/status-only change | no | none |
| Audit/activity-only event | no | none |

Read acknowledgement affects only the current user.

## 8. SLA state interaction

| Workflow situation | First-response | Unclaimed | In-progress |
|---|---|---|---|
| `NEW` / `PARTIALLY_IGNORED` | running until completed | running | paused |
| `VERIFICATION` | running until first human `SENT` | paused | running |
| `WAITING_FOR_CUSTOMER` | if not already completed, policy semantics remain; provider acceptance of a human reply can complete it | active-unowned semantics preserved but waiting is not counted as active unclaimed work | paused; waiting pause policy applies |
| `RESOLVED` / `IGNORED` | stopped | stopped | stopped |

The table above describes legacy aging metrics. The inbox uses the latest message’s operational FRT or NRT deadline, or a separate follow-up deadline. Historical response metrics keep their original FRT and NRT anchors. Terminal cases remain neutral and historical breaches remain visible. Timers preserve accumulated business time across pause/resume. Reassign never resets in-progress time. A Reassign to the current owner has no workflow, audit or notification effect, and bulk assignment reports that item as skipped.

## 9. Concurrency requirements

Implementation must prove through tests that:

- two simultaneous Claims cannot produce two owners;
- two simultaneous terminal transitions cannot both win inconsistently;
- repeated commands with the same idempotency key create one business effect;
- duplicate provider events create one inbound business effect;
- simultaneous Ignore votes produce a correct threshold transition exactly once;
- a post-terminal burst creates exactly one linked successor Case;
- scheduler workers use safe claiming (for example `FOR UPDATE SKIP LOCKED`) so a deadline/event is not processed concurrently by multiple workers.

The exact locking/query implementation is a delegated technical choice so long as these invariants hold.

## Owner-authorized bulk composition (2026-11-10)

Bulk Claim, Assign/Reassign, normal Resolve and Ignore compose the individual commands above. Availability is re-evaluated on the server under each Case row lock; no new transition or permission exists. Normal bulk Resolve remains owner-only. Each item has an independent transaction, audit/outbox and durable outcome, so ineligible/failed items do not prevent later items. See [UX_BULK_I18N.md](UX_BULK_I18N.md) for bounded selection and replay semantics.

History recovery (owner override 2026-11-20): recovered Slack/Teams customer Messages use the same active-generation/terminal-linking and unread transitions as live intake. No new CaseStatus or frontend state machine is introduced. New applicable FRT/NRT starts at durable addition in both manual and automatic mode; original provider time is display/history data. Existing Messages and SLA cycles survive rescans unchanged. See [history recovery](HISTORY_RECOVERY.md).

## Personal inbox modes (owner decision, 2026-11-27)

Personal Unified/Separate by platform inbox mode changes only query scope. Workflow eligibility remains authoritative server-side. Switching platform clears selected IDs, selected conversation and scoped action dialogs before new actions; text/note/attachment drafts and retry identities remain bound to their original Case. No workflow transition is caused by switching modes or platforms.

## Message mutation invariants

Editing/deleting one’s eligible delivered support message is independent of ownership and Case transitions. Confirmation changes only message content/deletion revision and Case preview/update metadata; it preserves workflow status/version, owner, send/activity timestamps and all SLA anchors/history/deadlines. It cannot reopen a terminal Case or create a new response cycle. Lifetime Ignore restrictions still deny mutation. [Details](OUTBOUND_MESSAGE_MUTATIONS.md).
