import { translate as t } from "@/lib/i18n/catalog"

const MiB = 1024 * 1024

export function attachmentLimitError(files: readonly File[], uploadedSizes: readonly number[] = []): Error | undefined {
  const sizes = [...files.map((file) => file.size), ...uploadedSizes]
  if (sizes.length > 10) return new Error(t("Jedna wiadomość może zawierać maksymalnie 10 plików."))
  if (sizes.some((size) => size > 25 * MiB)) return new Error(t("Pojedynczy plik nie może przekraczać 25 MiB."))
  if (sizes.reduce((total, size) => total + size, 0) > 50 * MiB) {
    return new Error(t("Łączny rozmiar plików nie może przekraczać 50 MiB."))
  }
}
