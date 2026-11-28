export interface OperationalDeadline {
  id: string
  kind: "FRT" | "NRT" | "FOLLOW_UP"
  state: "ON_TRACK" | "WARNING" | "BREACHED" | "FOLLOW_UP_DUE"
  dueAt: number
  warningAt: number | null
  observedAt: number
  receivedAt: number
  clockValidUntil: number
  remainingMillis: number
  businessIntervals: ReadonlyArray<{ start: number; end: number }> | null
}

export function operationalClock(deadline: OperationalDeadline, monotonicNow: number) {
  const estimatedNow = deadline.observedAt + Math.max(0, monotonicNow - deadline.receivedAt)
  // Once due, elapsed lateness needs no future business-calendar windows.
  if (estimatedNow >= deadline.dueAt) {
    return { overdue: true, seconds: Math.floor((estimatedNow - deadline.dueAt) / 1_000), warning: false }
  }
  const now = deadline.businessIntervals === null ? estimatedNow : Math.min(deadline.clockValidUntil, estimatedNow)
  const elapsed = deadline.businessIntervals === null ? now - deadline.observedAt
    : deadline.businessIntervals.reduce((total, interval) =>
      total + Math.max(0, Math.min(now, interval.end) - Math.max(deadline.observedAt, interval.start)), 0)
  const seconds = Math.ceil(Math.max(0, deadline.remainingMillis - elapsed) / 1_000)
  const warning = deadline.warningAt !== null && now >= deadline.warningAt
  return { overdue: false, seconds, warning }
}

const durationUnits = ["hour", "minute", "second"] as const
const durationFormatters = new Map<string, Intl.NumberFormat[]>()

export function formatDeadlineDuration(seconds: number, locale: string) {
  const hours = Math.floor(seconds / 3_600)
  const minutes = Math.floor(seconds / 60) % 60
  let formatters = durationFormatters.get(locale)
  if (!formatters) {
    formatters = durationUnits.map(unit => new Intl.NumberFormat(locale, { style: "unit", unit, unitDisplay: "short" }))
    durationFormatters.set(locale, formatters)
  }
  const [formatHours, formatMinutes, formatSeconds] = formatters
  return [
    hours ? formatHours.format(hours) : null,
    hours || minutes ? formatMinutes.format(minutes) : null,
    formatSeconds.format(seconds % 60),
  ].filter(Boolean).join(" ")
}
