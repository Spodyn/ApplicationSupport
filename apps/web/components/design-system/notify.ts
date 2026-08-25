import { toast } from "sonner"

/**
 * Cienka warstwa nad `sonner` zapewniająca spójne, polskojęzyczne powiadomienia
 * w całej aplikacji. Komponenty powinny używać tych funkcji zamiast wywoływać
 * `toast()` bezpośrednio.
 */
export const notify = {
  success: (message: string, description?: string) => toast.success(message, { description }),
  error: (message: string, description?: string) => toast.error(message, { description }),
  info: (message: string, description?: string) => toast.info(message, { description }),
  warning: (message: string, description?: string) => toast.warning(message, { description }),
  message: (message: string, description?: string) => toast(message, { description }),
}
