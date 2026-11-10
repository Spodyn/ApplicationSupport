import { pathToFileURL } from 'node:url'

export const REGRESSION_JOBS = Object.freeze([
  'configuration', 'backend', 'integration', 'infrastructure', 'openapi', 'web', 'fullstack',
])

export function regressionGate(results) {
  if (!results || typeof results !== 'object' || Array.isArray(results)) return ['Missing job evidence']
  const failures = REGRESSION_JOBS.filter(name => results[name]?.result !== 'success')
    .map(name => `${name}: ${results[name]?.result ?? 'missing'}`)
  for (const [name, job] of Object.entries(results)) {
    if (!REGRESSION_JOBS.includes(name) || job?.result !== 'success') {
      if (!failures.some(value => value.startsWith(`${name}:`))) failures.push(`${name}: unexpected/non-success job`)
    }
  }
  return failures
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const failures = regressionGate(JSON.parse(process.env.USI_JOB_RESULTS ?? 'null'))
    if (failures.length) { console.error(failures.join('\n')); process.exitCode = 1 }
    else console.log('All mandatory regression jobs succeeded.')
  } catch { console.error('Invalid job evidence'); process.exitCode = 1 }
}
