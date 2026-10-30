import { afterEach, describe, expect, it, vi } from "vitest"

import type { WorkScheduleSettings } from "@/lib/domain/administration"
import {
  apiAdministrationSettingsRepository,
  mapApiBusinessHours,
  mapScheduleExceptionsToApi,
  mapWorkScheduleToApiIntervals,
  type ApiBusinessHoursSchedule,
} from "@/lib/services/api/administration-settings-adapter"

describe("business-hours administration adapter", () => {
  it("maps two backend intervals into the existing break editor without losing wall-clock semantics", () => {
    const apiSchedule: ApiBusinessHoursSchedule = {
      id: "00000000-0000-0000-0000-000000000001",
      timezone: "Europe/Warsaw",
      active: true,
      intervals: [
        { dayOfWeek: 1, start: "08:00", end: "12:30" },
        { dayOfWeek: 1, start: "13:00", end: "17:00" },
        { dayOfWeek: 5, start: "09:00", end: "15:00" },
      ],
      updatedBy: "admin@example.com",
      updatedAt: "2026-08-29T07:00:00Z",
    }

    const mapped = mapApiBusinessHours(apiSchedule, [])

    expect(mapped.timezone).toBe("Europe/Warsaw")
    expect(mapped.days[0]).toMatchObject({
      key: "mon",
      enabled: true,
      start: "08:00",
      end: "17:00",
      breakEnabled: true,
      breakStart: "12:30",
      breakEnd: "13:00",
    })
    expect(mapped.days[1].enabled).toBe(false)
    expect(mapped.days[4]).toMatchObject({
      enabled: true,
      start: "09:00",
      end: "15:00",
      breakEnabled: false,
    })
  })

  it("converts the current editor shape back into disjoint backend intervals", () => {
    const schedule: WorkScheduleSettings = {
      timezone: "UTC",
      exceptions: [],
      days: [
        { key: "mon", label: "Poniedziałek", enabled: true, start: "08:00", end: "17:00", breakEnabled: true, breakStart: "12:00", breakEnd: "13:00" },
        { key: "tue", label: "Wtorek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "wed", label: "Środa", enabled: true, start: "10:00", end: "16:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "thu", label: "Czwartek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "fri", label: "Piątek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "sat", label: "Sobota", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "sun", label: "Niedziela", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
      ],
    }

    expect(mapWorkScheduleToApiIntervals(schedule)).toEqual([
      { dayOfWeek: 1, start: "08:00", end: "12:00" },
      { dayOfWeek: 1, start: "13:00", end: "17:00" },
      { dayOfWeek: 3, start: "10:00", end: "16:00" },
    ])
  })

  it("fails loudly rather than collapsing a schedule the current editor cannot represent", () => {
    const apiSchedule: ApiBusinessHoursSchedule = {
      id: "00000000-0000-0000-0000-000000000001",
      timezone: "UTC",
      active: true,
      intervals: [
        { dayOfWeek: 1, start: "08:00", end: "09:00" },
        { dayOfWeek: 1, start: "10:00", end: "11:00" },
        { dayOfWeek: 1, start: "12:00", end: "13:00" },
      ],
      updatedBy: "admin@example.com",
      updatedAt: "2026-08-29T07:00:00Z",
    }

    expect(() => mapApiBusinessHours(apiSchedule)).toThrow(/maksymalnie dwa/)
  })
})


afterEach(() => vi.unstubAllGlobals())

describe("USI-153 persisted exceptions and OOO preview", () => {
  const baseSchedule: ApiBusinessHoursSchedule = {
    id: "018f0000-0000-7000-8000-000000000001",
    timezone: "Europe/Warsaw",
    active: true,
    intervals: [{ dayOfWeek: 1, start: "09:00", end: "17:00" }],
    exceptions: [
      { date: "2026-12-24", type: "CLOSED", start: null, end: null, note: "Wigilia" },
      { date: "2026-12-31", type: "OPEN", start: "09:30", end: "13:00", note: "Dyżur" },
      { date: "2026-11-02", type: "OVERRIDE", start: "10:00", end: "16:00", note: "Skrócone godziny" },
    ],
    updatedBy: "admin",
    updatedAt: "2026-10-30T12:00:00Z",
  }

  it("maps server exceptions and preserves OPEN versus OVERRIDE on a round trip", () => {
    const mapped = mapApiBusinessHours(baseSchedule)
    expect(mapped.exceptions).toMatchObject([
      { name: "Wigilia", closed: true, type: "CLOSED" },
      { name: "Dyżur", closed: false, type: "OPEN", start: "09:30", end: "13:00" },
      { name: "Skrócone godziny", closed: false, type: "OVERRIDE" },
    ])
    expect(mapScheduleExceptionsToApi(mapped.exceptions)).toEqual(baseSchedule.exceptions)
  })

  it("fails before network I/O on invalid exception dates and intervals", () => {
    expect(() => mapScheduleExceptionsToApi([
      { id: "x", date: "not-a-date", name: "bad", closed: true },
    ])).toThrow(/RRRR-MM-DD/)
    expect(() => mapScheduleExceptionsToApi([
      { id: "x", date: "2026-12-24", name: "bad", closed: false, type: "OPEN", start: "16:00", end: "08:00" },
    ])).toThrow(/poprawnych godzin/)
  })

  it("writes weekly hours, then real exceptions, and rereads authoritative server state", async () => {
    const saved = structuredClone(baseSchedule)
    const json = (value: unknown) => new Response(JSON.stringify(value), {
      status: 200, headers: { "content-type": "application/json" },
    })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ ...saved, exceptions: [] }))
      .mockResolvedValueOnce(json(saved))
      .mockResolvedValueOnce(json(saved))
    vi.stubGlobal("fetch", fetchMock)
    const form = mapApiBusinessHours(baseSchedule)
    const result = await apiAdministrationSettingsRepository.saveSection("schedule", form)
    expect(result.exceptions).toHaveLength(3)
    expect(result.exceptions[1]).toMatchObject({ type: "OPEN", name: "Dyżur" })
    expect(fetchMock.mock.calls.map((call) => [call[0], call[1].method])).toEqual([
      ["/api/v1/admin/business-hours", "PUT"],
      ["/api/v1/admin/business-hours/exceptions", "PUT"],
      ["/api/v1/admin/business-hours", "GET"],
    ])
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(saved.exceptions)
  })

  it("surfaces partial save instead of claiming success after exceptions fail", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(baseSchedule), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        code: "CONFLICT", detail: "Another administrator changed the schedule.",
      }), { status: 409, headers: { "content-type": "application/problem+json" } }))
    vi.stubGlobal("fetch", fetchMock)
    await expect(apiAdministrationSettingsRepository.saveSection("schedule", mapApiBusinessHours(baseSchedule)))
      .rejects.toThrow(/Zapisano godziny tygodnia, ale nie zapisano wyjątków/)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it("previews the server-owned schedule with no invented future opening", async () => {
    const preview = { message: "Brak terminu ponownego otwarcia.", nextOpening: null, timezone: "UTC" }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(preview), {
      status: 200, headers: { "content-type": "application/json" },
    }))
    vi.stubGlobal("fetch", fetchMock)
    await expect(apiAdministrationSettingsRepository.previewOutOfOffice("Joanno")).resolves.toEqual(preview)
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/admin/out-of-office/preview?customerName=Joanno")
    expect(fetchMock.mock.calls[0][1].method).toBe("GET")
  })

  it("propagates schedule preview permission denial rather than fabricating a preview", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: "ACCESS_DENIED", status: 403, detail: "Forbidden" }),
      { status: 403, headers: { "content-type": "application/problem+json" } },
    )))
    await expect(apiAdministrationSettingsRepository.previewOutOfOffice()).rejects.toMatchObject({ status: 403 })
  })
})
