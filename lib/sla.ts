import type { SlaState } from "@/lib/domain/types"

export interface SlaSnapshot {
  state: SlaState
  dueAt?: string
}

/**
 * Oblicza stan widoczny w interfejsie. Termin, który właśnie minął, zawsze
 * reprezentuje przekroczenie, nawet jeśli ostatnia odpowiedź API nadal niesie
 * wcześniejszy stan ostrzegawczy.
 */
export function getEffectiveSlaState(
  sla: SlaSnapshot,
  now = Date.now(),
): SlaState {
  if (sla.state === "paused") return "paused"
  if (sla.dueAt && new Date(sla.dueAt).getTime() <= now) return "breached"
  return sla.state
}
