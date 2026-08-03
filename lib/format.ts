/** Wspólne funkcje formatujące w polskiej lokalizacji. */

const dateTimeFormatter = new Intl.DateTimeFormat("pl-PL", {
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
})

const dateFormatter = new Intl.DateTimeFormat("pl-PL", {
  day: "2-digit",
  month: "long",
  year: "numeric",
})

const timeFormatter = new Intl.DateTimeFormat("pl-PL", {
  hour: "2-digit",
  minute: "2-digit",
})

export function formatDateTime(value: string | Date): string {
  return dateTimeFormatter.format(new Date(value))
}

export function formatDate(value: string | Date): string {
  return dateFormatter.format(new Date(value))
}

export function formatTime(value: string | Date): string {
  return timeFormatter.format(new Date(value))
}

/** Względny czas typu "2 godz. temu" w języku polskim. */
export function formatRelative(value: string | Date): string {
  const date = new Date(value)
  const diffMs = date.getTime() - Date.now()
  const diffMinutes = Math.round(diffMs / 60000)
  const rtf = new Intl.RelativeTimeFormat("pl-PL", { numeric: "auto" })

  const abs = Math.abs(diffMinutes)
  if (abs < 60) return rtf.format(diffMinutes, "minute")
  const diffHours = Math.round(diffMinutes / 60)
  if (Math.abs(diffHours) < 24) return rtf.format(diffHours, "hour")
  const diffDays = Math.round(diffHours / 24)
  return rtf.format(diffDays, "day")
}

/** Formatuje liczbę w polskiej lokalizacji. */
export function formatNumber(value: number): string {
  return new Intl.NumberFormat("pl-PL").format(value)
}

/** Formatuje wartość procentową (0–1 lub 0–100). */
export function formatPercent(value: number): string {
  const normalized = value > 1 ? value / 100 : value
  return new Intl.NumberFormat("pl-PL", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(normalized)
}

/** Zamienia minuty na czytelny zapis, np. "1 godz. 20 min". */
export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${Math.round(minutes)} min`
  const hours = Math.floor(minutes / 60)
  const rest = Math.round(minutes % 60)
  return rest > 0 ? `${hours} godz. ${rest} min` : `${hours} godz.`
}
