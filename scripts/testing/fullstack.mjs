import { spawn, spawnSync } from 'node:child_process'
import { createServer, request as proxyRequest } from 'node:http'
import { mkdirSync, mkdtempSync, readFileSync, createWriteStream, existsSync, watch, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve, delimiter } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../../', import.meta.url))
const api = resolve(root, 'apps/api')
const web = resolve(root, 'apps/web')
const port = Number(process.env.USI_TEST_PORT ?? 3190)
if (!Number.isInteger(port) || port < 1024 || port > 65534) throw Error('Invalid isolated browser test port')
const origin = `http://127.0.0.1:${port}`
const env = Object.fromEntries(Object.entries(process.env).filter(([name]) =>
  /^(PATH|PATHEXT|SystemRoot|WINDIR|COMSPEC|ComSpec|HOME|USERPROFILE|LOCALAPPDATA|APPDATA|TEMP|TMP|TMPDIR|JAVA_HOME|MAVEN_USER_HOME|DOCKER_HOST|TESTCONTAINERS_HOST_OVERRIDE|DOCKER_TLS_VERIFY|DOCKER_CERT_PATH|HTTP_PROXY|HTTPS_PROXY|NO_PROXY)$/i.test(name)))
const children = []
mkdirSync(resolve(api, 'target'), {recursive: true})
const logs = createWriteStream(resolve(api, 'target/fullstack-runtime.log'), {flags: 'a'})
let temporary
let metadata
let nextReady = false
let cleaning
const gateway = createServer(async (req, res) => {
  if (req.url === '/__test/shutdown' && req.method === 'POST') {
    const clean = await cleanup()
    res.writeHead(clean ? 204 : 500); res.end(); return
  }
  if (req.url === '/__test/ready') { res.writeHead(metadata && nextReady ? 200 : 503); res.end(); return }
  if (req.url === '/__test/fixture') {
    res.setHeader('Cache-Control', 'no-store')
    res.setHeader('Content-Type', 'application/json')
    res.end(JSON.stringify(metadata ?? {})); return
  }
  const provider = req.url === '/__test/observed' || req.url === '/__test/account' || req.url === '/__test/agent'
  const backend = req.url?.startsWith('/api/') || req.url?.startsWith('/ws')
  const target = provider ? metadata?.provider : backend ? metadata?.backend : `http://127.0.0.1:${port + 1}`
  if (!target) { res.writeHead(503); res.end(); return }
  const path = provider ? req.url.replace('/__test', '') : req.url
  const upstream = proxyRequest(new URL(path, target), {method: req.method, headers: req.headers}, response => {
    res.writeHead(response.statusCode, response.headers); response.pipe(res)
  })
  upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end() })
  req.pipe(upstream)
})
gateway.on('upgrade', (req, socket, head) => {
  if (!metadata || req.url !== '/ws') { socket.destroy(); return }
  const upstream = proxyRequest(new URL(req.url, metadata.backend), {headers: req.headers})
  upstream.on('upgrade', (response, peer, peerHead) => {
    socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(response.headers).map(([key,value]) => `${key}: ${value}`).join('\r\n')}\r\n\r\n`)
    if (peerHead.length) socket.write(peerHead)
    if (head.length) peer.write(head)
    socket.pipe(peer).pipe(socket)
    peer.on('error', () => socket.destroy()); socket.on('error', () => peer.destroy())
  })
  upstream.on('error', () => socket.destroy()); upstream.end()
})

function cleanup() {
  cleaning ??= (async () => {
    let clean = true
    const java = children[0]
    if (metadata && java?.exitCode === null) {
      // HTTP shutdown runs JVM hooks on Windows too; TerminateProcess alone skips them.
      const exited = new Promise(resolveExit => java.once('exit', () => resolveExit(true)))
      try { await fetch(metadata.provider + '/shutdown', {method: 'POST', signal: AbortSignal.timeout(2000)}) } catch { clean = false }
      let deadline
      const finished = await Promise.race([exited, new Promise(resolveExit => { deadline = setTimeout(() => resolveExit(false), 30_000) })])
      clearTimeout(deadline); clean &&= finished
    }
    for (const child of children) if (child.exitCode === null) child.kill('SIGTERM')
    if (temporary && temporary.startsWith(resolve(tmpdir(), 'usi-fullstack-'))) rmSync(temporary, {recursive: true, force: true})
    return clean
  })()
  return cleaning
}
async function stop(code) {
  await cleanup()
  gateway.close(); logs.end(); process.exit(code)
}
process.on('SIGINT', () => stop(130)); process.on('SIGTERM', () => stop(0))

try {
  // Fail on an occupied test port. Never reuse the user's running development app.
  await new Promise((ready, reject) => gateway.once('error', reject).listen(port, '127.0.0.1', ready))
  const wrapper = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : './mvnw'
  const buildArgs = [...(process.platform === 'win32' ? ['./mvnw'] : []), '--batch-mode', '-q',
    'test-compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/test-classpath.txt']
  const build = spawnSync(wrapper, buildArgs, {cwd: api, env, encoding: 'utf8'})
  logs.write(build.stdout ?? ''); logs.write(build.stderr ?? '')
  if (build.status !== 0) throw Error('Test server compilation failed; inspect apps/api/target/fullstack-runtime.log')
  temporary = mkdtempSync(resolve(tmpdir(), 'usi-fullstack-'))
  const metadataFile = resolve(temporary, 'fixture.json')
  const classpath = [resolve(api, 'target/test-classes'), resolve(api, 'target/classes'),
    readFileSync(resolve(api, 'target/test-classpath.txt'), 'utf8').trim()].join(delimiter)
  const java = spawn('java', ['-cp', classpath,
    'com.unifiedsupportinbox.provider.slack.internal.FullStackTestServer', metadataFile, origin], {cwd: api, env})
  children.push(java); java.stdout.pipe(logs, {end: false}); java.stderr.pipe(logs, {end: false})
  java.once('error', error => { console.error(error.message); stop(1) })
  await new Promise((ready, reject) => {
    const deadline = setTimeout(() => finish(Error('Isolated test backend startup timed out')), 180_000)
    const watcher = watch(temporary, () => inspect())
    function finish(error) { clearTimeout(deadline); watcher.close(); error ? reject(error) : ready() }
    function inspect() {
      if (!existsSync(metadataFile)) return
      try { metadata = JSON.parse(readFileSync(metadataFile, 'utf8')); finish() } catch { /* wait for completed file write */ }
    }
    java.once('exit', code => finish(Error(`Isolated test backend exited (${code}); inspect fullstack-runtime.log`)))
    inspect()
  })
  const next = spawn(process.execPath, [resolve(web, 'node_modules/next/dist/bin/next'), 'start', '--hostname', '127.0.0.1', '--port', String(port + 1)], {
    cwd: web, env: {...env, NODE_ENV: 'production', NEXT_TELEMETRY_DISABLED: '1', NEXT_PUBLIC_API_BASE_URL: '/api/v1', NEXT_PUBLIC_WS_BASE_URL: '/ws'},
  })
  children.push(next); next.stderr.pipe(logs, {end: false})
  next.once('error', error => { console.error(error.message); stop(1) })
  next.stdout.on('data', data => { logs.write(data); if (data.toString().includes('Ready in')) nextReady = true })
  for (const child of children) child.once('exit', code => { if (!cleaning && code !== null) stop(code || 1) })
  console.log('Isolated full-stack gateway started; readiness includes actual backend and production frontend.')
} catch (error) { console.error(error.message); stop(1) }
