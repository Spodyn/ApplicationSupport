export default async function teardown() {
  const port = Number(process.env.USI_TEST_PORT ?? 3190)
  const response = await fetch(`http://127.0.0.1:${port}/__test/shutdown`, {method: 'POST', signal: AbortSignal.timeout(35_000)})
  if (response.status !== 204) throw Error('Disposable full-stack resources did not shut down cleanly')
}
