import {spawnSync} from 'node:child_process'

// A new branch push has an all-zero before SHA; PR/merge-group bases stay authoritative.
const supplied = process.env.BASE_SHA
const candidate = !supplied || /^0+$/.test(supplied) ? 'HEAD^' : supplied
const resolved = spawnSync('git', ['rev-parse', '--verify', '--end-of-options', `${candidate}^{commit}`], {encoding: 'utf8'})
const sha = resolved.stdout?.trim()
if (resolved.status !== 0 || !/^[0-9a-f]{40}$/.test(sha ?? '')) {
  console.error('Could not resolve an exact validation base commit'); process.exitCode = 1
} else console.log(sha)
