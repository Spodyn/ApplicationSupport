# Unified Support Inbox - canonical documentation

**Status:** Frozen v1 contract  
**Decision freeze:** complete  
**Primary Jira epic:** USI-5 / E00 - Product Contract & Architecture Freeze

This directory consolidates the approved product, architecture and autonomous-development contract for Unified Support Inbox (USI).

## Source-of-truth precedence

When two sources appear to disagree, use this order:

1. The current Jira ticket and any later `FINAL DECISION FREEZE`, `CONTRACT OVERRIDE` or `USER_DECISION_RESOLVED` comment on that ticket.
2. The frozen product decisions in E00 and the approved parent-epic pre-flight decisions for E01-E25.
3. `PRODUCT_CONTRACT.md` and `decision-registry.yaml` in this directory.
4. `PRODUCT_SPEC.md`, `WORKFLOW_MATRIX.md`, `INTEGRATIONS.md`, `SECURITY.md`, `ARCHITECTURE.md` and `OPERATIONS.md`.
5. Older domain notes, mock documentation and implementation details, provided they do not conflict with the items above.

An older Jira description, historical `USER_DECISION_REQUIRED` or earlier resolved comment does not override a later higher-precedence resolution.

## Canonical documents

- **PRODUCT_CONTRACT.md** - concise normative product/architecture rules.
- **PRODUCT_SPEC.md** - human-readable end-to-end product specification.
- **WORKFLOW_MATRIX.md** - canonical Case state machine, action guards and side effects.
- **ARCHITECTURE.md** - production architecture and implementation boundaries.
- **INTEGRATIONS.md** - Slack, Microsoft Teams and Telegram behavior.
- **SECURITY.md** - authentication, authorization, content/file/security boundaries.
- **OPERATIONS.md** - deployment, observability, DR, performance and release gates.
- **DECISION_REGISTRY.md** - readable indexed decision catalog.
- **decision-registry.yaml** - machine-readable frozen product decisions.
- **ORCHESTRATOR_CONTRACT.md** - lifecycle, dependency, worker, review, merge and restart rules for autonomous development.
- **orchestrator-policy.yaml** - machine-readable orchestrator/agent policy.

Focused documents such as `AUTHENTICATION.md`, `CASE_WORKFLOW.md`, `CASE_GROUPING.md`, `PERMISSION_MATRIX.md`, `RETENTION.md`, `CURRENT_STATE.md`, `DOMAIN_MODEL.md` and `TESTING.md` remain useful but are subordinate to the precedence above.

## Scope

USI v1 is a single-tenant-per-deployment support inbox that unifies Slack, Microsoft Teams and Telegram. E-mail support and AI functionality are deliberately outside v1.

## Agent rule

Routine technical choices are delegated. Agents must not stop for naming, package layout, ordinary indexes, equivalent locking strategies, retry details, test structure, local refactors, compatible vendor SDK/API details or Git conflict resolution when those choices remain within the frozen contract.

Human approval is required only for a real contract change such as v1 scope expansion, a fundamental workflow change, a new role or permission, a new security boundary, destructive data semantics outside the contract, a paid third-party service, production credentials/actions, pricing/customer commitments, or a new legal/compliance decision.

Development/review/merge automation follows `ORCHESTRATOR_CONTRACT.md`; that automation never grants autonomous production authority.