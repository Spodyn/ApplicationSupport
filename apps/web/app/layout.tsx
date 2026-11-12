import type { Metadata, Viewport } from 'next'
import { Inter, JetBrains_Mono } from 'next/font/google'
import { DEFAULT_LANGUAGE, translate } from '@/lib/i18n/catalog'
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
  const selectedLanguage = DEFAULT_LANGUAGE
  const t = (key: string) => translate(key, undefined, selectedLanguage)
  const requestHeaders = await headers()
  const host = requestHeaders.get('x-forwarded-host') ?? requestHeaders.get('host') ?? 'localhost:3000'
  const protocol = requestHeaders.get('x-forwarded-proto') ?? (host.startsWith('localhost') ? 'http' : 'https')
  const origin = `${protocol}://${host}`

  return {
    title: 'Unified Support Inbox',
    description: t(description),
    applicationName: 'Unified Support Inbox',
    manifest: `/pwa/manifest/${selectedLanguage}`,
    appleWebApp: {
      capable: true,
      title: 'Support Inbox',
      statusBarStyle: 'default',
    },
    formatDetection: { telephone: false },
    openGraph: {
      title: 'Unified Support Inbox',
      description: t('Jedna skrzynka. Wszystkie kanały.'),
      type: 'website',
      locale: selectedLanguage === 'en' ? 'en_GB' : 'pl_PL',
      images: [
        {
          url: `${origin}/og.png`,
          width: 1734,
          height: 907,
          alt: t('Unified Support Inbox — jedna skrzynka, wszystkie kanały'),
        },
      ],
    },
    twitter: {
      card: 'summary_large_image',
      title: 'Unified Support Inbox',
      description: t('Jedna skrzynka. Wszystkie kanały.'),
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

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  const initialLanguage = DEFAULT_LANGUAGE
  const nonce = (await headers()).get('x-nonce') ?? undefined
  return (
    <html lang={initialLanguage} suppressHydrationWarning className="bg-background">
      <body className={`${inter.variable} ${jetbrainsMono.variable} font-sans antialiased`}>
        <Providers nonce={nonce} initialLanguage={initialLanguage}>
          <AppShell>{children}</AppShell>
        </Providers>
      </body>
    </html>
  )
}
