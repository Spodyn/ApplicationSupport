import { developmentProxyRewrites } from "./config/development-proxy.mjs"
import { parsePublicEnvironment } from "./config/public-environment.mjs"

parsePublicEnvironment(process.env)

/** @type {import('next').NextConfig} */
const nextConfig = {
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return developmentProxyRewrites(process.env, process.env.NODE_ENV)
  },
  async headers() {
    return [
      {
        source: "/sw.js",
        headers: [
          {
            key: "Cache-Control",
            value: "public, max-age=0, must-revalidate",
          },
          {
            key: "Service-Worker-Allowed",
            value: "/",
          },
        ],
      },
    ]
  },
}

export default nextConfig
