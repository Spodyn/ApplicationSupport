import { translate } from "./catalog"
import { ApiHttpError } from "@/lib/services/api/http-transport"

const problems: Record<string, string> = {
  VALIDATION_FAILED: "Nieprawidłowe żądanie. Sprawdź wprowadzone wartości.",
  INVALID_CURSOR: "Dane zmieniły się. Odśwież widok i spróbuj ponownie.",
  AUTHENTICATION_REQUIRED: "Wymagane logowanie.",
  ACCESS_DENIED: "Nie masz uprawnień do wykonania tej akcji.",
  RESOURCE_NOT_FOUND: "Nie znaleziono zasobu.",
  CONFLICT: "Dane zmieniły się. Odśwież widok i spróbuj ponownie.",
  CASE_ALREADY_CLAIMED: "Case został już przejęty przez innego agenta.",
  RATE_LIMITED: "Zbyt wiele żądań. Spróbuj ponownie później.",
  PROVIDER_FAILURE: "Błąd dostawcy. Spróbuj ponownie później.",
}
/** Stable problem codes are localized at presentation; diagnostics remain intact. */
export function userError(error: unknown): string {
  if (error instanceof ApiHttpError) return translate(problems[error.problem?.code ?? ""] ?? "Nie udało się wykonać żądania. Spróbuj ponownie.")
  return error instanceof Error ? translate(error.message) : translate("Spróbuj ponownie.")
}
