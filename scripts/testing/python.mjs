import {spawnSync} from 'node:child_process'

// Windows may expose an unrelated python3 shim; use the installed Python launcher name.
const child = spawnSync(process.platform === 'win32' ? 'python' : 'python3', process.argv.slice(2), {stdio: 'inherit'})
if (child.error) console.error(child.error.message)
process.exitCode = child.status ?? 1
