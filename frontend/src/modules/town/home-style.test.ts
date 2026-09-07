import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelHomePreview, currentHomePreference, observeHomePreference, previewHomeStyle, readHomePreference, saveHomePreference } from './home-style'
beforeEach(() => localStorage.clear())
describe('home preferences', () => {
  it('previews live without persisting and restores saved style on cancel', () => {
    saveHomePreference('alice', { style: 'meadow', hiddenMementos: ['first'] })
    const notify = vi.fn(); const stop = observeHomePreference('alice', notify)
    previewHomeStyle('alice', 'dusk')
    expect(currentHomePreference('alice').style).toBe('dusk')
    expect(readHomePreference('alice').style).toBe('meadow')
    cancelHomePreview('alice')
    expect(notify).toHaveBeenLastCalledWith({ style: 'meadow', hiddenMementos: ['first'] })
    stop()
  })
  it('keeps accounts isolated and survives reopen', () => {
    saveHomePreference('alice', { style: 'dusk', hiddenMementos: ['earned'] })
    expect(readHomePreference('bob')).toEqual({ style: 'original', hiddenMementos: [] })
    expect(readHomePreference('alice')).toEqual({ style: 'dusk', hiddenMementos: ['earned'] })
    expect(saveHomePreference('', { style: 'dusk', hiddenMementos: [] })).toBe(false)
  })
  it('recovers safely from malformed saved content', () => {
    localStorage.setItem('town-home:v1:alice', '{broken')
    expect(readHomePreference('alice').style).toBe('original')
    localStorage.setItem('town-home:v1:alice', JSON.stringify({ style: '__proto__', hiddenMementos: ['earned', 1] }))
    expect(readHomePreference('alice')).toEqual({ style: 'original', hiddenMementos: ['earned'] })
  })
})
