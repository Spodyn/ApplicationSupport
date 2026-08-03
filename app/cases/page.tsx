import { CasesPage } from "@/components/cases/cases-page"

export default async function CasesRoute({
  searchParams,
}: {
  searchParams: Promise<{ caseId?: string }>
}) {
  const { caseId } = await searchParams
  return <CasesPage initialCaseId={caseId} />
}
