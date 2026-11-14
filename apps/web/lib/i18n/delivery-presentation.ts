type DeliveryPresentation = { message: string; retryMessage?: string; integration?: boolean }

const generic = { message: "Nie udało się dostarczyć wiadomości." }
const integration = { message: "Administrator musi ponownie połączyć integrację Slack.", integration: true }
const unavailable = { message: "Slack jest chwilowo niedostępny. Nie udało się dostarczyć wiadomości.",
  retryMessage: "Slack jest chwilowo niedostępny. Wysyłka zostanie ponowiona." }
const restricted = { message: "Aplikacja nie ma uprawnień do wysyłania wiadomości w tym kanale Slack." }

const errors: Record<string, DeliveryPresentation> = {
  SLACK_IS_ARCHIVED: { message: "Kanał Slack jest zarchiwizowany. Nie można wysyłać do niego wiadomości." },
  SLACK_CHANNEL_NOT_FOUND: { message: "Kanał Slack jest niedostępny lub bot utracił do niego dostęp." },
  SLACK_NOT_IN_CHANNEL: { message: "Bot nie należy do tego kanału Slack." },
  SLACK_NO_PERMISSION: restricted,
  SLACK_HTTP_403: restricted,
  SLACK_RESTRICTED_ACTION: restricted,
  SLACK_RESTRICTED_ACTION_NON_THREADABLE_CHANNEL: restricted,
  SLACK_RESTRICTED_ACTION_THREAD_ONLY_CHANNEL: restricted,
  SLACK_POSTING_TO_CHANNEL_DENIED: restricted,
  SLACK_RESTRICTED_ACTION_READ_ONLY_CHANNEL: { message: "Ten kanał Slack jest tylko do odczytu." },
  SLACK_RESTRICTED_ACTION_THREAD_LOCKED: { message: "Ten wątek Slack jest zablokowany." },
  SLACK_ACCOUNT_INACTIVE: integration,
  SLACK_INVALID_AUTH: integration,
  SLACK_NOT_AUTHED: integration,
  SLACK_HTTP_401: integration,
  SLACK_TOKEN_REVOKED: integration,
  SLACK_TOKEN_EXPIRED: integration,
  SLACK_MISSING_SCOPE: integration,
  SLACK_TEAM_ACCESS_NOT_GRANTED: integration,
  SLACK_NOT_ALLOWED_TOKEN_TYPE: integration,
  SLACK_BOT_TOKEN_MISSING: integration,
  SLACK_CREDENTIAL_REFERENCE_MISSING: integration,
  SLACK_RATE_LIMITED: { message: "Osiągnięto limit wysyłki Slack. Nie udało się dostarczyć wiadomości.",
    retryMessage: "Osiągnięto limit wysyłki Slack. Wysyłka zostanie wznowiona automatycznie." },
  SLACK_SERVICE_UNAVAILABLE: unavailable,
  SLACK_REQUEST_TIMEOUT: unavailable,
  SLACK_HTTP_408: unavailable,
  SLACK_INTERNAL_ERROR: unavailable,
  SLACK_FATAL_ERROR: unavailable,
  SLACK_INVALID_RESPONSE: unavailable,
  SLACK_RESPONSE_TS_MISSING: unavailable,
  SLACK_ORG_LOGIN_REQUIRED: unavailable,
  SLACK_TEAM_ADDED_TO_ORG: unavailable,
}

export function deliveryPresentation(code?: string): DeliveryPresentation {
  if (!code) return generic
  if (/^SLACK_HTTP_5\d\d$/.test(code)) return unavailable
  return Object.hasOwn(errors, code) ? errors[code] : generic
}
