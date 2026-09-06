#!/usr/bin/env python3
"""Original 75-second town-trailer score. No samples, recordings or external music.
Run: uv run --with numpy python frontend/scripts/trailer-score.py
All note sequences, sound synthesis and arrangement are authored here; deterministic seed.
"""
from pathlib import Path
import argparse
import wave
import numpy as np

RATE = 48000
SECONDS = 75.0
BPM = 96
BEAT = 60 / BPM
BAR = BEAT * 4
RNG = np.random.default_rng(27062026)
N = int(RATE * SECONDS)


def hz(midi):
    return 440 * 2 ** ((midi - 69) / 12)


def envelope(length, attack=.012, release=.12, decay=0):
    t = np.arange(length, dtype=np.float32) / RATE
    env = np.minimum(t / max(.001, attack), 1)
    env *= np.minimum(np.maximum((length / RATE - t) / max(.001, release), 0), 1)
    if decay:
        env *= np.exp(-t * decay)
    return env


def voice(midi, duration, kind='pluck'):
    t = np.arange(int(duration * RATE), dtype=np.float32) / RATE
    f = hz(midi)
    # Slightly detuned harmonics and a breathy transient avoid pure-sine beeps.
    if kind == 'pad':
        signal = sum(np.sin(2 * np.pi * f * (1 + detune) * t + phase)
                     for detune, phase in [(-.0018, .3), (.0014, 1.8), (0, .8)]) / 3
        signal += .19 * np.sin(2 * np.pi * f * 2 * t + .4)
        signal += .07 * np.sin(2 * np.pi * f * 3 * t)
        signal *= .9 + .1 * np.sin(2 * np.pi * .38 * t)
        return signal * envelope(len(t), .55, 1.0)
    if kind == 'bass':
        signal = np.sin(2 * np.pi * f * t)
        signal += .25 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t * 2)
        signal += .08 * np.sin(2 * np.pi * f * 3 * t) * np.exp(-t * 6)
        return np.tanh(signal * 1.15) * envelope(len(t), .009, .1)
    if kind == 'lead':
        signal = sum(np.sin(2 * np.pi * (f * harmonic * (1 + detune)) * t + .003 * np.sin(2 * np.pi * 4.7 * t))
                     * gain * np.exp(-t * decay)
                     for harmonic, gain, decay, detune in [(1, 1, 1.3, 0), (1, .3, 1.6, .002), (2, .26, 3, 0), (3, .1, 7, 0), (5, .045, 12, 0)])
        return signal * envelope(len(t), .018, .15)
    signal = (np.sin(2 * np.pi * f * t) * np.exp(-t * 5)
              + .32 * np.sin(2 * np.pi * f * 2 * t) * np.exp(-t * 9)
              + .14 * np.sin(2 * np.pi * f * 3 * t + .2) * np.exp(-t * 15)
              + .035 * RNG.standard_normal(len(t)) * np.exp(-t * 100))
    return signal * envelope(len(t), .004, .1)


def kick():
    t = np.arange(int(.43 * RATE), dtype=np.float32) / RATE
    phase = 2 * np.pi * (46 * t + 90 * .022 * (1 - np.exp(-t / .022)))
    body = np.sin(phase) * np.exp(-t * 11)
    click = RNG.standard_normal(len(t)) * np.exp(-t * 250) * .025
    return np.tanh(1.5 * body) * envelope(len(t), .001, .06) + click


def clap():
    t = np.arange(int(.22 * RATE), dtype=np.float32) / RATE
    noise = RNG.standard_normal(len(t)).astype(np.float32)
    # A short three-tap clap plus woody body; differentiate noise to remove rumble.
    grain = (noise - np.roll(noise, 1)) * .35
    burst = np.zeros(len(t), dtype=np.float32)
    for delay, amount in [(0, .65), (.009, .4), (.018, .75)]:
        burst += np.where(t >= delay, np.exp(-np.maximum(t-delay, 0) * 33) * amount, 0)
    return grain * burst * .55 + np.sin(2 * np.pi * 182 * t) * np.exp(-t * 35) * .15


def hat(opened=False):
    dur = .16 if opened else .055
    t = np.arange(int(dur * RATE), dtype=np.float32) / RATE
    noise = RNG.standard_normal(len(t)).astype(np.float32)
    high = noise - (np.roll(noise, 1) + np.roll(noise, 2)) / 2
    return high * np.exp(-t * (30 if opened else 85)) * envelope(len(t), .002, .02) * .35


def add(track, sound, at, gain, pan=0):
    start = int(at * RATE)
    if start >= N or start < 0:
        return
    size = min(len(sound), N - start)
    angle = (np.clip(pan, -1, 1) + 1) * np.pi / 4
    track[start:start+size, 0] += sound[:size] * gain * np.cos(angle)
    track[start:start+size, 1] += sound[:size] * gain * np.sin(angle)


def section(t):
    if t < 5: return 'intro'
    if t < 12.5: return 'opening'
    if t < 27.5: return 'build'
    if t < 43.75: return 'words'
    if t < 48.75: return 'breath'
    if t < 65: return 'lift'
    return 'home'


def score():
    bed = np.zeros((N, 2), dtype=np.float32)
    music = np.zeros_like(bed)
    rhythm = np.zeros_like(bed)
    bass = np.zeros_like(bed)
    chords = [(50, 57, 62, 65), (46, 53, 58, 62), (41, 53, 57, 60), (48, 55, 60, 64)]
    # Dm9 / Bbmaj7 / Fadd9 / Csus2 colour, resolving to F at the end.
    for bar in range(30):
        at = bar * BAR
        chord = chords[bar % 4]
        if bar >= 26:
            chord = [chords[1], chords[3], chords[2], chords[2]][bar - 26]
        name = section(at)
        bed_gain = {'intro': .030, 'opening': .041, 'build': .041, 'words': .033, 'breath': .028, 'lift': .045, 'home': .039}[name]
        # The last musical note starts at 72.5s and is allowed to die naturally.
        duration = 3.35 if bar < 29 else 2.5
        for i, note in enumerate(chord):
            add(bed, voice(note + 12, duration, 'pad'), at, bed_gain, [-.55, .35, -.15, .6][i])
        if name not in ['intro', 'breath']:
            bass_gain = .16 if name in ['build', 'lift'] else .095
            for beat in ([0, 2, 3.5] if name == 'lift' else [0, 2]):
                if at + beat * BEAT >= 72.5: continue
                add(bass, voice(chord[0] - 12, .65 if beat == 3.5 else 1.04, 'bass'), at + beat * BEAT, bass_gain)
        # Musical eighth-note arpeggio; the UI passage has half the density.
        pattern = [0, 1, 2, 1, 3, 2, 1, 2]
        ticks = range(8) if name in ['build', 'lift'] else [0, 3, 6] if name in ['opening', 'words'] else [0]
        for k in ticks:
            note_at = at + k * BEAT / 2
            if note_at >= 73: continue
            note = chord[pattern[k]] + 12
            gain = .065 if name == 'lift' else .045 if name == 'build' else .024
            add(music, voice(note, .8), note_at, gain, -.55 if k % 2 else .55)

    # Four-note motif A–C–D–F, authored rhythm; reprise and then a falling resolution.
    motif = [(0, 57, .75), (.75, 60, .5), (1.5, 62, 1.2), (3, 65, 1.25)]
    for start, intensity in [(5, .085), (15, .09), (22.5, .088), (32.5, .050), (40, .043), (48.75, .095), (56.25, .10), (61.25, .084)]:
        for beat, note, dur in motif:
            add(music, voice(note + 12, dur, 'lead'), start + beat * BEAT, intensity, -.12)
    for at, note, dur in [(66.25, 77, 1.2), (67.5, 74, 1.3), (68.75, 72, 1.4), (70, 69, 1.45), (72.5, 65, 2.45)]:
        add(music, voice(note, dur, 'lead'), at, .068, .05)

    kick_sound, clap_sound = kick(), clap()
    for b in range(int(SECONDS / BEAT)):
        at = b * BEAT
        name = section(at)
        pos = b % 4
        if name in ['build', 'lift']:
            if pos in [0, 2]: add(rhythm, kick_sound, at, .37 if name == 'lift' else .31)
            if pos in [1, 3]: add(rhythm, clap_sound, at, .135 if name == 'lift' else .11, .08)
            for eighth in [0, .5]:
                add(rhythm, hat(), at + eighth * BEAT, .075 if eighth else .048, -.38 if eighth else .42)
            if name == 'lift' and pos == 3:
                add(rhythm, kick_sound, at + .5 * BEAT, .21)
                add(rhythm, hat(True), at + .5 * BEAT, .062, .25)
        elif name == 'words':
            if pos == 0: add(rhythm, kick_sound, at, .16)
            if pos == 2: add(rhythm, clap_sound, at, .065, .1)
            if pos in [1, 3]: add(rhythm, hat(), at, .032, -.2)
        elif name == 'opening' and at >= 10 and pos == 0:
            add(rhythm, kick_sound, at, .17)
        elif name == 'home' and at < 70:
            if pos == 0: add(rhythm, kick_sound, at, .19)
            if pos == 2: add(rhythm, hat(), at, .035, -.2)

    # Gentle synthetic air transitions. No speech, licensed loops or field recordings.
    for at, length, gain in [(11.75, .75, .008), (26.7, .8, .006), (48.0, .75, .013), (64.25, .75, .007)]:
        size = int(length * RATE)
        noise = RNG.standard_normal(size).astype(np.float32)
        smooth = np.convolve(noise, np.ones(35, dtype=np.float32) / 35, mode='same')
        fade = np.linspace(0, 1, size, dtype=np.float32) ** 1.8
        add(music, smooth * fade * envelope(size, .1, .05), at, gain, -.2)

    # Cross-channel dotted-eighth echoes and a diffuse low-level room around instruments only.
    wet = np.zeros_like(music)
    for delay, gain in [(BEAT * .75, .21), (BEAT * 1.5, .10), (BEAT * 2.25, .045), (.079, .045), (.131, .035)]:
        offset = int(delay * RATE)
        wet[offset:] += music[:-offset, ::-1] * gain
    mix = bed + music + wet + rhythm + bass
    # Macro dynamics follow the editorial beats exactly, with no hard section cuts.
    points = [(0, 0), (.35, .75), (4.7, .82), (5, .96), (12.5, 1), (27.25, 1), (27.5, .78),
              (43.4, .78), (43.75, .55), (48.4, .60), (48.75, 1), (63.5, 1.04), (65, .88),
              (70, .77), (73.4, .60), (74, .48), (75, 0)]
    timeline = np.arange(N, dtype=np.float32) / RATE
    dynamics = np.interp(timeline, *zip(*points)).astype(np.float32)
    mix *= dynamics[:, None]
    mix -= np.mean(mix, axis=0)
    # Mild tape-like saturation. Peak-normalize with headroom for trailer ambience/voice.
    mix = np.tanh(mix * 1.35)
    peak = float(np.max(np.abs(mix)))
    mix *= (10 ** (-3.5 / 20)) / max(peak, 1e-9)
    mix[-int(RATE):] *= np.linspace(1, 0, RATE, dtype=np.float32)[:, None] ** .65
    return mix


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[2] / 'artifacts/product-trailer/score.wav')
    args = parser.parse_args()
    audio = score()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(audio, -1, 1) * 32767).astype('<i2')
    with wave.open(str(args.output), 'wb') as writer:
        writer.setnchannels(2); writer.setsampwidth(2); writer.setframerate(RATE); writer.writeframes(pcm.tobytes())
    rms = float(np.sqrt(np.mean(audio ** 2)))
    print(f'{args.output}: {SECONDS:.0f}s, {RATE}Hz, stereo, peak -3.5dBFS, RMS {20*np.log10(rms):.1f}dBFS')


if __name__ == '__main__':
    main()
