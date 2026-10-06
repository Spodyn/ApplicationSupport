"use client"

import { useState, type ComponentType, type PropsWithChildren, type ReactNode } from "react"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { ThemeProvider, type ThemeProviderProps } from "next-themes"
import { TooltipProvider } from "@/components/ui/tooltip"
import { Toaster } from "@/components/ui/sonner"
import { ServiceWorkerRegistration } from "@/components/pwa/service-worker-registration"

const AppThemeProvider = ThemeProvider as ComponentType<PropsWithChildren<ThemeProviderProps>>

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: false,
            retry: 1,
          },
        },
      }),
  )

  return (
    <AppThemeProvider
      attribute="class"
      defaultTheme="light"
      enableSystem={false}
      themes={["light", "dark"]}
      storageKey="support-inbox-theme"
      disableTransitionOnChange
    >
      <QueryClientProvider client={queryClient}>
        <TooltipProvider delay={200}>{children}</TooltipProvider>
        <Toaster richColors position="top-right" />
        <ServiceWorkerRegistration />
      </QueryClientProvider>
    </AppThemeProvider>
  )
}
