import { locale } from "@/lib/i18n/catalog"

export function formatDateTime(value: string | Date): string {
  return new Intl.DateTimeFormat(locale(), { day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value))
}
export function formatDate(value: string | Date): string {
  return new Intl.DateTimeFormat(locale(), { day: "2-digit", month: "long", year: "numeric" }).format(new Date(value))
}
export function formatTime(value: string | Date): string {
  return new Intl.DateTimeFormat(locale(), { hour: "2-digit", minute: "2-digit" }).format(new Date(value))
}
export function formatRelative(value: string | Date): string {
  const minutes = Math.round((new Date(value).getTime() - Date.now()) / 60_000)
  const format = new Intl.RelativeTimeFormat(locale(), { numeric: "auto" })
  if (Math.abs(minutes) < 60) return format.format(minutes, "minute")
  const hours = Math.round(minutes / 60)
  if (Math.abs(hours) < 24) return format.format(hours, "hour")
  return format.format(Math.round(hours / 24), "day")
}
export function formatNumber(value: number): string { return new Intl.NumberFormat(locale()).format(value) }
export function formatPercent(value: number): string {
  return new Intl.NumberFormat(locale(), { style: "percent", maximumFractionDigits: 1 }).format(value > 1 ? value / 100 : value)
}
export function formatDuration(minutes: number): string {
  const minute = new Intl.NumberFormat(locale(), { style: "unit", unit: "minute", unitDisplay: "short" })
  if (minutes < 60) return minute.format(Math.round(minutes))
  const hours = Math.floor(minutes / 60)
  const rest = Math.round(minutes % 60)
  const hour = new Intl.NumberFormat(locale(), { style: "unit", unit: "hour", unitDisplay: "short" }).format(hours)
  return rest ? `${hour} ${minute.format(rest)}` : hour
}
