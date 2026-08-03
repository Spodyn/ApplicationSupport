import { ImageResponse } from "next/og"

export function createPwaIcon(size: number, maskable = false) {
  const padding = maskable ? Math.round(size * 0.18) : Math.round(size * 0.08)
  return new ImageResponse(
    <div style={{ width: "100%", height: "100%", display: "flex", alignItems: "center", justifyContent: "center", padding, background: "linear-gradient(145deg, #17375f, #2563a8)", color: "white" }}>
      <div style={{ width: "100%", height: "100%", display: "flex", alignItems: "center", justifyContent: "center", borderRadius: Math.round(size * 0.22), background: "rgba(255,255,255,0.12)", border: `${Math.max(2, Math.round(size * 0.015))}px solid rgba(255,255,255,0.22)`, fontSize: Math.round(size * 0.28), fontWeight: 800, letterSpacing: "-0.06em" }}>
        US
      </div>
    </div>,
    { width: size, height: size },
  )
}
