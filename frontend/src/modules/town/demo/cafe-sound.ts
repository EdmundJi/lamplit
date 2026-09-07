/** Quiet, opt-in procedural foley. The audio context is created only by the user's gesture. */
export function createCafeSound() {
  let context: AudioContext | undefined
  let master: GainNode | undefined
  let windSource: AudioBufferSourceNode | undefined
  let windGain: GainNode | undefined
  let enabled = false
  let paused = false
  let previousMs: number | undefined
  let lastFootstep = -1
  let birdBucket = -1
  let destroyed = false
  const active = new Set<AudioScheduledSourceNode>()
  const connectVoice = (source: AudioScheduledSourceNode, gain: GainNode) => {
    source.connect(gain); gain.connect(master!)
    active.add(source)
    source.onended = () => { active.delete(source); source.disconnect(); gain.disconnect() }
  }
  const silenceVoices = () => {
    for (const source of active) { try { source.stop() } catch { /* Already ended. */ } }
    active.clear()
  }
  function tone(frequency: number, delay: number, duration: number, volume: number, endFrequency = frequency) {
    if (!context || !master) return
    const now = context.currentTime + delay
    const oscillator = context.createOscillator(), envelope = context.createGain()
    oscillator.type = 'sine'
    oscillator.frequency.setValueAtTime(frequency, now)
    oscillator.frequency.exponentialRampToValueAtTime(endFrequency, now + duration)
    envelope.gain.setValueAtTime(0, now)
    envelope.gain.linearRampToValueAtTime(volume, now + .006)
    envelope.gain.exponentialRampToValueAtTime(.0001, now + duration)
    connectVoice(oscillator, envelope)
    oscillator.start(now); oscillator.stop(now + duration + .015)
  }
  function cup() {
    tone(1820, 0, .22, .042)
    tone(2860, .008, .12, .018)
    tone(1130, .024, .08, .011)
  }
  function footstep(step: number) {
    tone(step % 2 ? 128 : 113, 0, .055, .042, 57)
  }
  function bird() {
    tone(2350, 0, .13, .022, 3350)
    tone(3300, .16, .17, .016, 2500)
  }
  function adjustVolume() {
    if (!context || !master) return
    master.gain.cancelScheduledValues(context.currentTime)
    master.gain.setTargetAtTime(enabled && !paused ? .38 : 0, context.currentTime, .025)
  }
  async function setEnabled(value: boolean) {
    if (destroyed) return
    enabled = value
    if (enabled && !context) {
      context = new AudioContext()
      master = context.createGain()
      master.gain.value = 0
      master.connect(context.destination)
      // Brownish noise, heavily filtered; no continuous hiss competing with the small actions.
      const noise = context.createBuffer(1, context.sampleRate * 4, context.sampleRate)
      const samples = noise.getChannelData(0)
      let previous = 0, seed = 72431
      for (let i = 0; i < samples.length; i++) {
        seed = (seed * 1664525 + 1013904223) >>> 0
        previous = (previous + (seed / 4294967296 * 2 - 1) * .025) / 1.018
        samples[i] = previous * 2
      }
      const filter = context.createBiquadFilter()
      filter.type = 'lowpass'; filter.frequency.value = 420; filter.Q.value = .3
      windGain = context.createGain(); windGain.gain.value = .06
      windSource = context.createBufferSource(); windSource.buffer = noise; windSource.loop = true
      windSource.connect(filter); filter.connect(windGain); windGain.connect(master); windSource.start()
    }
    if (enabled && context?.state === 'suspended') await context.resume()
    if (!enabled) silenceVoices()
    adjustVolume()
  }
  function update(elapsedMs: number, isPaused: boolean) {
    const previous = previousMs
    previousMs = elapsedMs
    const pauseChanged = paused !== isPaused
    paused = isPaused
    if (pauseChanged) { if (paused) silenceVoices(); adjustVolume() }
    if (!enabled || paused || !context || context.state !== 'running') return
    // Absolute-time seeks and replay reset the cursor without firing missed cues.
    if (previous === undefined || elapsedMs <= previous || elapsedMs - previous > 200) {
      if (previous !== undefined && elapsedMs !== previous) silenceVoices()
      lastFootstep = Math.floor(elapsedMs / 570)
      birdBucket = Math.floor((elapsedMs + 2600) / 11000)
      return
    }
    if (pauseChanged) return
    if ([8050, 8850, 12150, 20500].some(cue => previous < cue && elapsedMs >= cue)) cup()
    const t = elapsedMs / 1000
    const walking = (t > 1.5 && t < 7) || (t > 7 && t < 13) || (t > 16 && t < 28.5)
    const step = Math.floor(elapsedMs / 570)
    if (walking && step !== lastFootstep) footstep(step)
    lastFootstep = step
    const bucket = Math.floor((elapsedMs + 2600) / 11000)
    if (bucket !== birdBucket) bird()
    birdBucket = bucket
    windGain?.gain.setTargetAtTime(.055 + Math.sin(t * .28) * .014, context.currentTime, .3)
  }
  function destroy() {
    destroyed = true; enabled = false; silenceVoices()
    windSource?.stop(); windSource?.disconnect(); windGain?.disconnect(); master?.disconnect()
    void context?.close()
    context = undefined
  }
  return { setEnabled, update, destroy, resetCursor: () => { previousMs = undefined; silenceVoices() } }
}
