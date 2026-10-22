import { useCallback, useEffect, useRef, useState } from 'react'
import { getSession, type Identity } from '@/auth/session'
import { catalogError, catalogPage, type CatalogPage } from './gatewayCatalogApi'

export function useCatalogRead(identity: Identity) {
  const pending = useRef<AbortController | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => () => { pending.current?.abort(); pending.current = null }, [identity])
  const read = useCallback(async <T,>(load: (signal: AbortSignal) => Promise<T>, accept: (value: T) => void) => {
    if (pending.current || getSession().identity !== identity) return
    const controller = new AbortController(); pending.current = controller
    setBusy(true); setError('')
    try {
      const value = await load(controller.signal)
      if (!controller.signal.aborted && getSession().identity === identity) accept(value)
    } catch (error) {
      if (!controller.signal.aborted && getSession().identity === identity) setError(catalogError(error))
    } finally { if (pending.current === controller) { pending.current = null; setBusy(false) } }
  }, [identity])
  return { read, busy, error }
}

export function useCatalogPage<T>(identity: Identity, path: string, decode: (value: unknown) => T, key: (value: T) => string) {
  const request = useCatalogRead(identity)
  const [page, setPage] = useState<CatalogPage<T> | null>(null)
  const seen = useRef(new Set<string>())
  const decodeRef = useRef(decode); decodeRef.current = decode
  const keyRef = useRef(key); keyRef.current = key
  const load = useCallback((cursor?: string) => {
    if (request.busy) return
    setPage(null)
    if (!cursor) seen.current.clear()
    void request.read(signal => catalogPage(path, decodeRef.current, keyRef.current, signal, cursor), value => {
      if (cursor) seen.current.add(cursor)
      if (value.nextCursor && seen.current.has(value.nextCursor)) throw new Error('The catalog cursor repeated. Refresh to start again.')
      setPage(value)
    })
  }, [path, request.read, request.busy])
  // The owner keys this component by route and identity. First load runs once for that scope.
  useEffect(() => { load() }, [path, identity]) // eslint-disable-line react-hooks/exhaustive-deps
  return { ...request, page, load }
}
