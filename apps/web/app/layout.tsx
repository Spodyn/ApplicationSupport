import { Analytics } from '@vercel/analytics/next'
import type { Metadata, Viewport } from 'next'
import { Inter, JetBrains_Mono } from 'next/font/google'
import { headers } from 'next/headers'
import { Providers } from '@/components/providers'
import { AppShell } from '@/components/layout/app-shell'
import './globals.css'

const inter = Inter({
  subsets: ['latin', 'latin-ext'],
  variable: '--font-inter',
  display: 'swap',
})

const jetbrainsMono = JetBrains_Mono({
  subsets: ['latin', 'latin-ext'],
  variable: '--font-jetbrains-mono',
  display: 'swap',
})

const description =
  'Ujednolicona skrzynka wsparcia dla zespołów obsługujących zgłoszenia ze Slacka, Microsoft Teams i Telegrama.'

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers()
  const host = requestHeaders.get('x-forwarded-host') ?? requestHeaders.get('host') ?? 'localhost:3000'
  const protocol = requestHeaders.get('x-forwarded-proto') ?? (host.startsWith('localhost') ? 'http' : 'https')
  const origin = `${protocol}://${host}`

  return {
    title: 'Unified Support Inbox',
    description,
    applicationName: 'Unified Support Inbox',
    manifest: '/manifest.webmanifest',
    appleWebApp: {
      capable: true,
      title: 'Support Inbox',
      statusBarStyle: 'default',
    },
    formatDetection: { telephone: false },
    openGraph: {
      title: 'Unified Support Inbox',
      description: 'Jedna skrzynka. Wszystkie kanały.',
      type: 'website',
      locale: 'pl_PL',
      images: [
        {
          url: `${origin}/og.png`,
          width: 1734,
          height: 907,
          alt: 'Unified Support Inbox — jedna skrzynka, wszystkie kanały',
        },
      ],
    },
    twitter: {
      card: 'summary_large_image',
      title: 'Unified Support Inbox',
      description: 'Jedna skrzynka. Wszystkie kanały.',
      images: [`${origin}/og.png`],
    },
  }
}

export const viewport: Viewport = {
  colorScheme: 'light dark',
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: 'white' },
    { media: '(prefers-color-scheme: dark)', color: 'black' },
  ],
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="pl" suppressHydrationWarning className="bg-background">
      <body className={`${inter.variable} ${jetbrainsMono.variable} font-sans antialiased`}>
        <Providers>
          <AppShell>{children}</AppShell>
        </Providers>
        {process.env.NODE_ENV === 'production' && <Analytics />}
      </body>
    </html>
  )
}
