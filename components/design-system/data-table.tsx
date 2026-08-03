"use client"

import type { ReactNode } from "react"
import { cn } from "@/lib/utils"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { TableSkeleton } from "./data-states"

export interface DataTableColumn<T> {
  id: string
  header: ReactNode
  /** Renderuje zawartość komórki dla danego wiersza. */
  cell: (row: T) => ReactNode
  className?: string
  headClassName?: string
  align?: "start" | "center" | "end"
}

/**
 * Generyczna, wielokrotnego użytku tabela danych z obsługą stanów
 * ładowania i pustej listy. Prezentacja komórek pozostaje po stronie wywołującego.
 */
export function DataTable<T>({
  columns,
  data,
  getRowId,
  onRowClick,
  isLoading = false,
  emptyState,
  className,
}: {
  columns: DataTableColumn<T>[]
  data: T[]
  getRowId: (row: T) => string
  onRowClick?: (row: T) => void
  isLoading?: boolean
  emptyState?: ReactNode
  className?: string
}) {
  const alignClass = {
    start: "text-left",
    center: "text-center",
    end: "text-right",
  } as const

  if (isLoading) {
    return (
      <div className={cn("rounded-lg border bg-card", className)}>
        <TableSkeleton columns={columns.length} />
      </div>
    )
  }

  if (data.length === 0 && emptyState) {
    return <div className={cn("rounded-lg border bg-card p-2", className)}>{emptyState}</div>
  }

  return (
    <div className={cn("overflow-hidden rounded-lg border bg-card", className)}>
      <Table>
        <TableHeader>
          <TableRow className="bg-muted/40 hover:bg-muted/40">
            {columns.map((column) => (
              <TableHead
                key={column.id}
                className={cn(
                  "text-xs font-medium tracking-wide text-muted-foreground uppercase",
                  column.align && alignClass[column.align],
                  column.headClassName,
                )}
              >
                {column.header}
              </TableHead>
            ))}
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.map((row) => (
            <TableRow
              key={getRowId(row)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={cn(onRowClick && "cursor-pointer")}
            >
              {columns.map((column) => (
                <TableCell
                  key={column.id}
                  className={cn(
                    column.align && alignClass[column.align],
                    column.className,
                  )}
                >
                  {column.cell(row)}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
