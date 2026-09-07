import { describe, expect, it, vi } from 'vitest'
import { postcardAttempt } from './town-visits'
describe('postcard retry identity',()=>{
 it('supports HTTP development origins where randomUUID is not exposed',()=>{
  const original = crypto.randomUUID
  Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: undefined })
  try { expect(postcardAttempt(null,'friend','hello').requestKey).toMatch(/^[a-f0-9]{32}$/) }
  finally { Object.defineProperty(crypto, 'randomUUID', { configurable: true, value: original }) }
 })
 it('reuses a key for identical retries and changes it for a different friend or message',()=>{
  const first=postcardAttempt(null,'friend',' hello ')
  expect(postcardAttempt(first,'friend','hello')).toBe(first)
  expect(postcardAttempt(first,'other','hello').requestKey).not.toBe(first.requestKey)
  expect(postcardAttempt(first,'friend','hello again').requestKey).not.toBe(first.requestKey)
 })
})
