"use client"

import { useState } from "react"
import { Download, File, FileSpreadsheet, FileText, ImageIcon, Presentation } from "lucide-react"
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import type { InboxAttachment } from "@/lib/domain/inbox"
import { useT } from "@/lib/i18n/provider"

const documentIcons = {
  pdf: FileText, docx: FileText, txt: FileText, md: FileText,
  csv: FileSpreadsheet, xlsx: FileSpreadsheet, pptx: Presentation,
}

export function MessageAttachments({ attachments, canManageIntegrations }: {
  attachments?: InboxAttachment[]
  canManageIntegrations: boolean
}) {
  if (!attachments?.length) return null
  return <ul className="mt-3 flex min-w-0 flex-wrap gap-2">
    {attachments.map((attachment) => <MessageAttachment key={attachment.id} file={attachment} admin={canManageIntegrations} />)}
  </ul>
}

function MessageAttachment({ file, admin }: { file: InboxAttachment; admin: boolean }) {
  const t = useT()
  const [failedPreview, setFailedPreview] = useState(false)
  const extension = /[.]([a-z0-9]{1,12})$/i.exec(file.fileName)?.[1].toLowerCase()
  const Icon = file.type === "image" ? ImageIcon : documentIcons[extension as keyof typeof documentIcons] ?? File
  const sizeAndType = [extension?.toUpperCase(), file.size].filter(Boolean).join(" · ")
  const status = file.failureStage === "DOWNLOAD" ? "Nie udało się pobrać pliku ze Slacka."
    : file.scanStatus === "ERROR" ? "Nie udało się zeskanować pliku."
    : file.scanStatus === "INFECTED" ? "W pliku wykryto zagrożenie."
    : file.processingStage === "DOWNLOAD" ? "Pobieranie pliku ze Slacka…"
    : file.scanStatus === "PENDING" || file.scanStatus === "SCANNING" ? "Skanowanie pliku…"
    : undefined
  const text = <span className="min-w-0 [overflow-wrap:anywhere]">
    {file.fileName} · {status ? t(status) : sizeAndType}
  </span>
  const card = "inline-flex min-w-0 max-w-full items-center gap-2 rounded-md border border-white/10 bg-black/10 px-2.5 py-1.5 text-xs"

  return <li data-case-attachment={file.id} data-file-type={extension} className="min-w-0 max-w-full">
    {file.downloadUrl && file.previewUrl && !failedPreview && (
      <Dialog>
        <DialogTrigger render={<button type="button" className="mb-2 block max-w-full rounded-md text-left focus-visible:ring-2 focus-visible:ring-violet-300" aria-label={t("Otwórz podgląd {{name}}", { name: file.fileName })} />}>
          <AttachmentImage src={file.previewUrl} name={file.fileName} onError={() => setFailedPreview(true)} />
        </DialogTrigger>
        <DialogContent className="max-h-[90dvh] overflow-auto sm:max-w-[min(920px,calc(100%-2rem))]">
          <DialogTitle className="pr-8 [overflow-wrap:anywhere]">{file.fileName}</DialogTitle>
          <AttachmentImage src={file.previewUrl} name={file.fileName} large />
          <a className="inline-flex w-fit items-center gap-2 underline" href={file.downloadUrl} download>
            <Download className="size-4" aria-hidden="true" />{t("Pobierz oryginał")}
          </a>
        </DialogContent>
      </Dialog>
    )}
    {file.downloadUrl
      ? <a href={file.downloadUrl} download className={card + " text-violet-200 hover:bg-white/5"}>
          <Icon className="size-4 shrink-0" aria-hidden="true" />{text}<Download className="size-4 shrink-0" aria-hidden="true" />
        </a>
      : <div className={card + " text-[#cbd3df]"}><Icon className="size-4 shrink-0" aria-hidden="true" />{text}</div>}
    {admin && file.requiredSlackScope && (
      <p className="mt-1 text-xs text-amber-200">{t("Brakujące uprawnienie Slacka: {{scope}}", { scope: file.requiredSlackScope })}</p>
    )}
  </li>
}

function AttachmentImage({ src, name, large = false, onError }: {
  src: string
  name: string
  large?: boolean
  onError?: () => void
}) {
  const t = useT()
  const [failed, setFailed] = useState(false)
  if (failed) return <p className="text-sm">{t("Podgląd obrazu jest niedostępny.")}</p>
  // The session-protected same-origin URL must go directly to the browser rather than through the image optimizer.
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={src} alt={name} loading={large ? "eager" : "lazy"}
    onError={() => { setFailed(true); onError?.() }}
    className={large ? "max-h-[70dvh] max-w-full rounded-md object-contain" : "max-h-64 max-w-full rounded-md object-contain"} />
}
