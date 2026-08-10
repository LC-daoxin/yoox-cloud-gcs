type SessionCleanupHandler = () => void | Promise<void>

const handlers = new Set<SessionCleanupHandler>()

export function registerSessionCleanup(handler: SessionCleanupHandler) {
  handlers.add(handler)
  return () => handlers.delete(handler)
}

export async function runSessionCleanup() {
  const pending = [...handlers].map((handler) => {
    try {
      return Promise.resolve(handler())
    } catch (reason) {
      return Promise.reject(reason)
    }
  })
  await Promise.allSettled(pending)
}
