import test from 'node:test'
import assert from 'node:assert/strict'
import { documentFingerprint, uploadDocument, DOCUMENT_CHUNK_SIZE } from './documentUpload.js'

const ok = data => ({ ok: true, json: async () => data })
const fail = status => ({ ok: false, status, text: async () => 'request failed' })
function storage() {
  const values = new Map()
  return { values, getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const file = () => new File([new Uint8Array(DOCUMENT_CHUNK_SIZE), 'last'], 'book.txt')
async function sessionFor(file, uploadedChunks = []) {
  return { uploadId: 'session', fingerprint: await documentFingerprint(file), totalSize: file.size,
    chunkSize: DOCUMENT_CHUNK_SIZE, totalChunks: Math.ceil(file.size / DOCUMENT_CHUNK_SIZE), uploadedChunks, status: 'UPLOADING' }
}
const keyFor = session => `document-upload:v1:7:${session.fingerprint}:book.txt`

test('resume skips already acknowledged chunks, then clears credential after completion', async () => {
  const input = file(), session = await sessionFor(input, [0]), saved = storage(), calls = []
  saved.setItem(keyFor(session), 'session')
  const result = await uploadDocument(input, 7, { storage: saved, request: async (path) => {
    calls.push(path)
    if (path.includes('upload-status')) return ok(session)
    if (path.includes('upload-chunk')) return ok({})
    return ok({ id: 42 })
  } })
  assert.equal(result.id, 42)
  assert.equal(calls.filter(path => path.includes('upload-chunk')).length, 1)
  assert.ok(calls.some(path => path.endsWith('index=1')))
  assert.equal(saved.values.size, 0)
})

test('unknown server/network failure preserves existing credential and never initializes', async () => {
  const input = file(), session = await sessionFor(input), saved = storage(), calls = []
  saved.setItem(keyFor(session), 'session')
  await assert.rejects(uploadDocument(input, 7, { storage: saved, wait: async () => {}, request: async path => {
    calls.push(path)
    return fail(503)
  } }))
  assert.equal(saved.getItem(keyFor(session)), 'session')
  assert.equal(calls.length, 4)
  assert.ok(calls.every(path => path.includes('upload-status')))
})

test('missing session reinitializes but unauthorized session is preserved', async () => {
  for (const status of [404, 403]) {
    const input = file(), session = await sessionFor(input), saved = storage(), calls = []
    saved.setItem(keyFor(session), 'session')
    const action = uploadDocument(input, 7, { storage: saved, request: async path => {
      calls.push(path)
      if (path.includes('upload-status')) return fail(status)
      if (path.includes('init-upload')) return ok(session)
      return ok({ id: 1 })
    } })
    if (status === 403) {
      await assert.rejects(action)
      assert.equal(saved.getItem(keyFor(session)), 'session')
      assert.equal(calls.length, 1)
    } else {
      await action
      assert.ok(calls.some(path => path.includes('init-upload')))
    }
  }
})

test('lost completion response retries same session without retransmitting chunks', async () => {
  const input = file(), session = await sessionFor(input, [0, 1]), saved = storage()
  saved.setItem(keyFor(session), 'session')
  let completes = 0
  await uploadDocument(input, 7, { storage: saved, wait: async () => {}, request: async path => {
    if (path.includes('upload-status')) return ok(session)
    assert.ok(path.includes('complete-upload'))
    if (++completes === 1) throw new Error('connection lost')
    return ok({ id: 42 })
  } })
  assert.equal(completes, 2)
  assert.equal(saved.values.size, 0)
})

test('completed session recovers original result without uploading', async () => {
  const input = file(), session = { ...await sessionFor(input), status: 'COMPLETED' }, saved = storage()
  saved.setItem(keyFor(session), 'session')
  await uploadDocument(input, 7, { storage: saved, request: async path => {
    if (path.includes('upload-status')) return ok(session)
    assert.ok(path.includes('complete-upload'))
    return ok({ id: 42 })
  } })
})

test('pause after an acknowledged chunk keeps credential for next selection', async () => {
  const input = file(), session = await sessionFor(input), saved = storage(), controller = new AbortController()
  await assert.rejects(uploadDocument(input, 7, { storage: saved, signal: controller.signal,
    request: async path => {
      if (path.includes('init-upload')) return ok(session)
      controller.abort()
      return ok({})
    }
  }), error => error.name === 'AbortError')
  assert.equal(saved.getItem(keyFor(session)), 'session')
})

test('content fingerprint distinguishes equal-size files with the same name', async () => {
  assert.notEqual(await documentFingerprint(new File(['abc'], 'same.txt')),
    await documentFingerprint(new File(['abd'], 'same.txt')))
})
