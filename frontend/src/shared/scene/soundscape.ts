/** Mirrors atmosphere.ts's WeatherKind without depending on the Phaser-heavy module. */
export type WeatherKind = 'clear' | 'rain' | 'snow'
export type SoundSpace = 'outdoor' | 'home' | 'cafe'

export type SoundEnvironment = { weather: WeatherKind; minutes: number }
/** Doorway attenuation: outdoor rain loses both volume and high frequencies. */
export function soundMix(environment: SoundEnvironment, location: boolean | SoundSpace) {
  const space: SoundSpace = typeof location === 'boolean' ? (location ? 'home' : 'outdoor') : location
  const indoor = space !== 'outdoor'
  return {
    wind: indoor ? .004 : environment.weather === 'snow' ? .025 : .017,
    rain: environment.weather === 'rain' ? (indoor ? .028 : .075) : 0,
    cutoff: indoor ? 650 : 7200,
    birds: environment.minutes >= 360 && environment.minutes < 1140 && environment.weather === 'clear' && !indoor,
    // The recording already sits around -20 dBFS. This gain, followed by the .42 master,
    // keeps its speech-shaped murmur near -54 dBFS so it reads as room tone while studying.
    cafe: space === 'cafe' ? .05 : 0,
  }
}

/** Its own timer survives the sleeping street indoors. No context/timer before opt-in. */
export class TownSoundscape {
  private enabled = false
  private visible = true
  private space: SoundSpace = 'outdoor'
  private cafeOpen = true
  private destroyed = false
  private context: AudioContext | null = null
  private master: GainNode | null = null
  private wind: GainNode | null = null
  private rain: GainNode | null = null
  private cafe: GainNode | null = null
  private filter: BiquadFilterNode | null = null
  private sources = new Set<AudioScheduledSourceNode>()
  private nodes = new Set<AudioNode>()
  private timer: ReturnType<typeof setInterval> | null = null
  private nextBird = 0

  constructor(
    private readonly environment: () => SoundEnvironment,
    private readonly createContext: () => AudioContext = () => new AudioContext(),
    private readonly loadCafeLoop: (context: AudioContext) => Promise<AudioBuffer> = async context => {
      const response = await fetch('/assets/audio/cafe-roomtone.ogg')
      if (!response.ok) throw new Error(`Cafe room tone unavailable (${response.status})`)
      return context.decodeAudioData(await response.arrayBuffer())
    },
  ) {}
  private own<T extends AudioNode>(node: T): T { this.nodes.add(node); return node }

  private initialize() {
    if (this.context) return
    const context = this.context = this.createContext()
    this.master = this.own(context.createGain())
    this.master.gain.value = 0
    this.master.connect(context.destination)
    this.filter = this.own(context.createBiquadFilter())
    this.filter.type = 'lowpass'
    this.filter.connect(this.master)
    this.cafe = this.own(context.createGain())
    this.cafe.gain.value = 0
    // The cafe recording is already band-limited; bypass the doorway filter so indoor
    // weather can stay muffled without turning the room murmur into a bassy drone.
    this.cafe.connect(this.master)
    const buffer = context.createBuffer(1, Math.ceil(context.sampleRate * 3), context.sampleRate)
    const samples = buffer.getChannelData(0)
    for (let i = 0; i < samples.length; i++) samples[i] = Math.random() * 2 - 1
    for (const kind of ['wind', 'rain'] as const) {
      const source = this.own(context.createBufferSource()), filter = this.own(context.createBiquadFilter()), gain = this.own(context.createGain())
      filter.type = kind === 'wind' ? 'lowpass' : 'highpass'
      filter.frequency.value = kind === 'wind' ? 360 : 1100
      gain.gain.value = 0
      source.buffer = buffer; source.loop = true
      source.connect(filter); filter.connect(gain); gain.connect(this.filter)
      this[kind] = gain
      this.sources.add(source); source.start()
    }
    void this.initializeCafe(context)
  }

  private async initializeCafe(context: AudioContext) {
    try {
      const buffer = await this.loadCafeLoop(context)
      if (this.destroyed || this.context !== context || !this.cafe) return
      const source = this.own(context.createBufferSource())
      source.buffer = buffer
      source.loop = true
      source.connect(this.cafe)
      this.sources.add(source)
      source.start()
    } catch { /* Weather remains available when the optional recording cannot load. */ }
  }

  setEnabled(enabled: boolean) { if (!this.destroyed) { this.enabled = enabled; this.sync() } }
  setVisible(visible: boolean) { if (!this.destroyed) { this.visible = visible; this.sync() } }
  setIndoor(indoor: boolean) { this.setSpace(indoor ? 'home' : 'outdoor') }
  setSpace(space: SoundSpace) { if (!this.destroyed) { this.space = space; this.refresh() } }
  /** Set from authoritative world state; callers must not infer opening hours from the local clock. */
  setCafeOpen(open: boolean) { if (!this.destroyed) { this.cafeOpen = open; this.refresh() } }

  private sync() {
    if (!this.enabled || !this.visible) {
      this.clearTimer()
      if (this.context) {
        this.master?.gain.setTargetAtTime(0, this.context.currentTime, .04)
        void this.context.suspend().catch(() => {})
      }
      return
    }
    try {
      this.initialize()
      // Resume within the gesture; the continuation must not reactivate a hidden/destroyed scene.
      void this.context!.resume().then(() => {
        if (!this.destroyed && this.enabled && this.visible) this.refresh()
      }).catch(() => {})
      this.refresh()
      if (!this.timer) this.timer = setInterval(() => this.refresh(), 1500)
    } catch { this.releaseAudio() }
  }

  refresh() {
    const context = this.context
    if (!context || this.destroyed || !this.enabled || !this.visible) return
    const mix = soundMix(this.environment(), this.space)
    this.master?.gain.setTargetAtTime(.42, context.currentTime, .35)
    this.wind?.gain.setTargetAtTime(mix.wind, context.currentTime, .7)
    this.rain?.gain.setTargetAtTime(mix.rain, context.currentTime, .7)
    this.cafe?.gain.setTargetAtTime(this.cafeOpen ? mix.cafe : 0, context.currentTime, .8)
    this.filter?.frequency.setTargetAtTime(mix.cutoff, context.currentTime, .45)
    if (mix.birds && context.state === 'running' && context.currentTime >= this.nextBird) {
      this.nextBird = context.currentTime + 14 + Math.random() * 16
      this.chirp(context)
    }
  }

  private chirp(context: AudioContext) {
    const voice = this.own(context.createOscillator()), envelope = this.own(context.createGain())
    const start = context.currentTime + .1
    voice.type = 'sine'
    voice.frequency.setValueAtTime(1900, start)
    voice.frequency.exponentialRampToValueAtTime(3100, start + .09)
    voice.frequency.exponentialRampToValueAtTime(2300, start + .2)
    envelope.gain.setValueAtTime(0, start)
    envelope.gain.linearRampToValueAtTime(.018, start + .03)
    envelope.gain.exponentialRampToValueAtTime(.0001, start + .23)
    voice.connect(envelope); envelope.connect(this.filter!)
    this.sources.add(voice)
    voice.onended = () => {
      voice.disconnect(); envelope.disconnect()
      this.sources.delete(voice); this.nodes.delete(voice); this.nodes.delete(envelope)
    }
    voice.start(start); voice.stop(start + .25)
  }

  private clearTimer() { if (this.timer) clearInterval(this.timer); this.timer = null }
  private releaseAudio() {
    this.clearTimer()
    for (const source of this.sources) { try { source.stop() } catch { /* Already ended. */ } }
    for (const node of this.nodes) node.disconnect()
    this.sources.clear(); this.nodes.clear()
    if (this.context) void this.context.close().catch(() => {})
    this.context = null; this.master = this.wind = this.rain = this.cafe = null; this.filter = null
  }
  destroy() { if (!this.destroyed) { this.destroyed = true; this.releaseAudio() } }
}
