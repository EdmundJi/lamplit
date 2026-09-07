import { afterEach, describe, expect, it, vi } from 'vitest'
import { soundMix, TownSoundscape } from './soundscape'

function audioDouble() {
  const nodes: { disconnect: ReturnType<typeof vi.fn> }[] = []
  const parameter = () => ({ value: 0, setTargetAtTime: vi.fn(), setValueAtTime: vi.fn(), linearRampToValueAtTime: vi.fn(), exponentialRampToValueAtTime: vi.fn() })
  function makeNode() {
    const node = { connect: vi.fn(), disconnect: vi.fn(), start: vi.fn(), stop: vi.fn(), gain: parameter(), frequency: parameter(), onended: null, type: '', loop: false }
    nodes.push(node)
    return node
  }
  const context = {
    state: 'suspended', currentTime: 0, sampleRate: 64, destination: {},
    createGain: vi.fn(makeNode), createBiquadFilter: vi.fn(makeNode), createOscillator: vi.fn(makeNode), createBufferSource: vi.fn(makeNode),
    createBuffer: vi.fn(() => ({ getChannelData: () => new Float32Array(192) })),
    resume: vi.fn(async () => { context.state = 'running' }),
    suspend: vi.fn(async () => { context.state = 'suspended' }),
    close: vi.fn(async () => { context.state = 'closed' }),
  }
  return { context, nodes, factory: vi.fn(() => context as unknown as AudioContext) }
}

afterEach(() => { vi.useRealTimers() })

describe('procedural town sound lifecycle', () => {
  it('allocates nothing until opt-in and remains silent when visibility changes', () => {
    vi.useFakeTimers()
    const audio = audioDouble(), sound = new TownSoundscape(() => ({ weather: 'rain', minutes: 1080 }), audio.factory)
    sound.refresh(); sound.setVisible(false); sound.setVisible(true); sound.setIndoor(true)
    expect(audio.factory).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
    sound.destroy()
  })

  it('suspends hidden/disabled sound, resumes the same context, and releases every source/node/timer on destroy', async () => {
    vi.useFakeTimers()
    const audio = audioDouble(), sound = new TownSoundscape(() => ({ weather: 'clear', minutes: 720 }), audio.factory)
    sound.setEnabled(true)
    await Promise.resolve()
    expect(audio.context.createBufferSource).toHaveBeenCalledTimes(2)
    expect(audio.context.createOscillator).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(1)
    sound.setVisible(false)
    expect(audio.context.suspend).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
    sound.setVisible(true)
    expect(audio.factory).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(1)
    sound.setEnabled(false)
    expect(audio.context.state).toBe('suspended')
    expect(vi.getTimerCount()).toBe(0)
    sound.setEnabled(true)
    sound.destroy(); sound.destroy()
    await Promise.resolve()
    expect(audio.context.close).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
    for (const source of audio.context.createBufferSource.mock.results) expect(source.value.stop).toHaveBeenCalledOnce()
    for (const node of audio.nodes) expect(node.disconnect).toHaveBeenCalled()
    sound.setEnabled(true); sound.setVisible(true)
    expect(audio.factory).toHaveBeenCalledOnce()
  })

  it('changes to muffled rain indoors and restores the outdoor filter after leaving', () => {
    vi.useFakeTimers()
    const audio = audioDouble(), sound = new TownSoundscape(() => ({ weather: 'rain', minutes: 1100 }), audio.factory)
    sound.setEnabled(true)
    const outputFilter = audio.context.createBiquadFilter.mock.results[0]!.value
    expect(outputFilter.frequency.setTargetAtTime).toHaveBeenLastCalledWith(7200, 0, .45)
    sound.setIndoor(true)
    expect(outputFilter.frequency.setTargetAtTime).toHaveBeenLastCalledWith(650, 0, .45)
    sound.setIndoor(false)
    expect(outputFilter.frequency.setTargetAtTime).toHaveBeenLastCalledWith(7200, 0, .45)
    expect(audio.context.createOscillator).not.toHaveBeenCalled()
    sound.destroy()
  })

  it('does not throw or leave a timer when Web Audio is unavailable', () => {
    vi.useFakeTimers()
    const sound = new TownSoundscape(() => ({ weather: 'clear', minutes: 720 }), () => { throw new Error('unsupported') })
    expect(() => { sound.setEnabled(true); sound.setVisible(false); sound.destroy() }).not.toThrow()
    expect(vi.getTimerCount()).toBe(0)
  })
})

describe('environment sound mix', () => {
  it('suppresses birds at night, during rain, and inside; indoor rain is quieter', () => {
    expect(soundMix({ weather: 'clear', minutes: 720 }, false).birds).toBe(true)
    for (const [weather, minutes, indoor] of [['clear', 1200, false], ['rain', 720, false], ['clear', 720, true]] as const)
      expect(soundMix({ weather, minutes }, indoor).birds).toBe(false)
    expect(soundMix({ weather: 'rain', minutes: 1000 }, true).rain).toBeLessThan(soundMix({ weather: 'rain', minutes: 1000 }, false).rain)
  })
})
