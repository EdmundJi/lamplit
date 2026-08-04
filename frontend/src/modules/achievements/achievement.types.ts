import type { Component } from 'vue'
import {
  BookOpenCheck,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  CircleDot,
  Compass,
  Flame,
  Footprints,
  HeartHandshake,
  Leaf,
  ListChecks,
  Medal,
  Mountain,
  Repeat2,
  Rocket,
  Route,
  Sparkles,
  Sprout,
  Star,
  Target,
  Trophy,
} from 'lucide-vue-next'

export type Achievement = {
  code: string
  name: string
  body: string
  triggerText: string
  category: 'MILESTONE' | 'ACTION' | 'STREAK' | 'ROLE'
  iconKey: string
  tone: 'green' | 'blue' | 'amber' | 'violet'
  earned: boolean
  earnedAt: string | null
}

export type Title = {
  code: string
  name: string
  description: string
  graphicType: 'LUCIDE' | 'EMOJI' | 'RIVE'
  graphicKey: string
  frameStyle: string
  held: boolean
  equipped: boolean
  acquiredAt: string | null
}

const icons: Record<string, Component> = {
  BookOpenCheck,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  CircleDot,
  Compass,
  Flame,
  Footprints,
  HeartHandshake,
  Leaf,
  ListChecks,
  Medal,
  Mountain,
  Repeat2,
  Rocket,
  Route,
  Sparkles,
  Sprout,
  Star,
  Target,
  Trophy,
}

export function growthIcon(key: string): Component {
  return icons[key] ?? Sparkles
}
