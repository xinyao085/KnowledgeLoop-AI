import { apiRequest } from './api.js'

export const DOCUMENT_CHUNK_SIZE = 5 * 1024 * 1024
export const MAX_DOCUMENT_BYTES = 2 * 1024 * 1024 * 1024

const hex = buffer => Array.from(new Uint8Array(buffer), byte => byte.toString(16).padStart(2, '0')).join('')
const aborted = signal => { if (signal?.aborted) throw new DOMException('上传已暂停', 'AbortError') }

// Hash each bounded-size chunk, then hash the ordered ASCII hex hashes. Never load the whole file.
export async function documentFingerprint(file, { signal, onProgress = () => {} } = {}) {
  if (!globalThis.crypto?.subtle) throw new Error('文件校验需要 HTTPS 或 localhost，请使用安全地址访问')
  let hashes = ''
  for (let start = 0; start < file.size; start += DOCUMENT_CHUNK_SIZE) {
    aborted(signal)
    const bytes = await file.slice(start, start + DOCUMENT_CHUNK_SIZE).arrayBuffer()
    hashes += hex(await crypto.subtle.digest('SHA-256', bytes))
    onProgress(Math.min(100, Math.round((start + bytes.byteLength) / file.size * 100)))
  }
  aborted(signal)
  return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(hashes)))
}

const delay = (ms, signal) => new Promise((resolve, reject) => {
  aborted(signal)
  const onAbort = () => { clearTimeout(timer); reject(new DOMException('上传已暂停', 'AbortError')) }
  const timer = setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve() }, ms)
  signal?.addEventListener('abort', onAbort, { once: true })
})

export async function uploadDocument(file, userId, {
  signal, onProgress = () => {}, request = apiRequest,
  storage = globalThis.localStorage, wait = delay
} = {}) {
  if (!file?.size || file.size > MAX_DOCUMENT_BYTES) throw new Error('请选择非空且不超过 2 GiB 的文档')
  const fingerprint = await documentFingerprint(file, {
    signal, onProgress: percent => onProgress({ phase: 'hashing', percent })
  })
  const totalChunks = Math.ceil(file.size / DOCUMENT_CHUNK_SIZE)
  const key = `document-upload:v1:${userId}:${fingerprint}:${encodeURIComponent(file.name)}`
  const read = () => { try { return storage.getItem(key) } catch { return null } }
  const save = id => { try { storage.setItem(key, id) } catch { /* Current upload still works. */ } }
  const clear = () => { try { storage.removeItem(key) } catch { /* Best effort. */ } }

  async function send(path, options = {}, retry = true) {
    for (let attempt = 0; ; attempt++) {
      aborted(signal)
      try {
        const response = await request(path, { ...options, signal })
        if (response.ok) return await response.json()
        const error = new Error(await response.text() || '上传请求失败')
        error.status = response.status
        throw error
      } catch (error) {
        if (signal?.aborted || error.name === 'AbortError') throw error
        const retriable = error.status == null || [408, 429].includes(error.status) || error.status >= 500
        if (!retry || !retriable || attempt >= 3) throw error
        await wait(800 * 2 ** attempt, signal)
      }
    }
  }

  let uploadId = read()
  let session
  if (uploadId) {
    try {
      session = await send(`/documents/upload-status?uploadId=${encodeURIComponent(uploadId)}`)
    } catch (error) {
      // An uncertain network/server/auth response must never destroy the resume credential.
      if (error.status !== 404 && error.status !== 410) throw error
      clear()
      uploadId = null
    }
  }
  if (!uploadId) {
    session = await send('/documents/init-upload', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ filename: file.name, mimeType: file.type || 'application/octet-stream',
        totalChunks, totalSize: file.size, chunkSize: DOCUMENT_CHUNK_SIZE, fingerprint })
    }, false)
    uploadId = session.uploadId
    save(uploadId)
  }
  if (session.fingerprint !== fingerprint || session.totalSize !== file.size
      || session.chunkSize !== DOCUMENT_CHUNK_SIZE || session.totalChunks !== totalChunks) {
    throw new Error('上传会话与文件不匹配，请选择原文件')
  }
  const uploaded = new Set(session.uploadedChunks || [])
  const emit = () => onProgress({ phase: 'uploading', percent: Math.round(uploaded.size / totalChunks * 95),
    resumedChunks: (session.uploadedChunks || []).length })
  emit()
  if (session.status !== 'COMPLETED') {
    for (let index = 0; index < totalChunks; index++) {
      if (uploaded.has(index)) continue
      const form = new FormData()
      form.append('file', file.slice(index * DOCUMENT_CHUNK_SIZE, (index + 1) * DOCUMENT_CHUNK_SIZE))
      await send(`/documents/upload-chunk?uploadId=${encodeURIComponent(uploadId)}&index=${index}`, {
        method: 'POST', body: form
      })
      uploaded.add(index)
      emit()
    }
  }
  onProgress({ phase: 'merging', percent: 95 })
  const document = await send(`/documents/complete-upload?uploadId=${encodeURIComponent(uploadId)}`, { method: 'POST' })
  clear()
  onProgress({ phase: 'completed', percent: 100 })
  return document
}
