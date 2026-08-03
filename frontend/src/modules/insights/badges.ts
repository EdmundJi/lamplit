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
  Sparkles,
  Sprout,
  Star,
  Target,
  Trophy,
} from 'lucide-vue-next'

export type BadgeMetrics = {
  effectiveActions: number
  fulfillmentRate: number
  recoveryCount: number
  totalExperience: number
  longestStreak: number
  roles: { roleCode: string; level: number }[]
}

export type Badge = {
  title: string
  trigger: string
  body: string
  earned: boolean
  icon: unknown
  tone: string
}

export function computeBadges(metrics: BadgeMetrics): Badge[] {
  const roleCountLevel2 = metrics.roles.filter(role => role.level >= 2).length
  const roleCountLevel5 = metrics.roles.filter(role => role.level >= 5).length
  const bestRoleLevel = Math.max(0, ...metrics.roles.map(role => role.level))
  return [
    { title: '第一步行动', trigger: '本周有效行动 ≥ 1', body: '迈出第一步，系统才开始有你的真实节奏。', earned: metrics.effectiveActions >= 1, icon: Footprints, tone: 'green' },
    { title: '三次推进', trigger: '本周有效行动 ≥ 3', body: '一周内完成三次有效推进。', earned: metrics.effectiveActions >= 3, icon: ListChecks, tone: 'green' },
    { title: '五点成线', trigger: '本周有效行动 ≥ 5', body: '把零散行动连成更稳定的周节奏。', earned: metrics.effectiveActions >= 5, icon: CheckCircle2, tone: 'green' },
    { title: '兑现过半', trigger: '本周兑现率 ≥ 50%', body: '计划已经不只是写下来。', earned: metrics.fulfillmentRate >= .5, icon: CircleDot, tone: 'blue' },
    { title: '稳定一周', trigger: '本周兑现率 ≥ 75%', body: '本周兑现率达到稳定区间。', earned: metrics.fulfillmentRate >= .75, icon: CalendarCheck, tone: 'blue' },
    { title: '高度贴合', trigger: '本周兑现率 ≥ 90%', body: '计划规模和真实生活匹配得很好。', earned: metrics.fulfillmentRate >= .9, icon: Target, tone: 'blue' },
    { title: '温和恢复', trigger: '恢复次数 ≥ 1', body: '中断之后重新回到计划。', earned: metrics.recoveryCount >= 1, icon: Leaf, tone: 'amber' },
    { title: '恢复熟练', trigger: '恢复次数 ≥ 3', body: '你已经在练习不责备地回来。', earned: metrics.recoveryCount >= 3, icon: Repeat2, tone: 'amber' },
    { title: '小火苗', trigger: '连续行动天数 ≥ 2', body: '连续两天留下行动痕迹。', earned: metrics.longestStreak >= 2, icon: Flame, tone: 'amber' },
    { title: '一周足迹', trigger: '连续行动天数 ≥ 7', body: '连续一周保持可见行动。', earned: metrics.longestStreak >= 7, icon: CalendarDays, tone: 'amber' },
    { title: '十点经验', trigger: '累计经验 ≥ 10', body: '经验来自行动记录，不代表人格或能力评价。', earned: metrics.totalExperience >= 10, icon: Star, tone: 'violet' },
    { title: '五十经验', trigger: '累计经验 ≥ 50', body: '积累开始变得可见。', earned: metrics.totalExperience >= 50, icon: Sparkles, tone: 'violet' },
    { title: '百点经验', trigger: '累计经验 ≥ 100', body: '长期行动留下了更厚的轨迹。', earned: metrics.totalExperience >= 100, icon: Trophy, tone: 'violet' },
    { title: '学习起步', trigger: '学生等级 ≥ 2', body: '学习角色完成第一次升级。', earned: metrics.roles.some(role => role.roleCode === 'STUDENT' && role.level >= 2), icon: BookOpenCheck, tone: 'green' },
    { title: '身体照顾', trigger: '健身用户等级 ≥ 2', body: '身体照顾角色完成第一次升级。', earned: metrics.roles.some(role => role.roleCode === 'FITNESS_USER' && role.level >= 2), icon: Sprout, tone: 'green' },
    { title: '职场推进', trigger: '打工人等级 ≥ 2', body: '职场角色完成第一次升级。', earned: metrics.roles.some(role => role.roleCode === 'WORKER' && role.level >= 2), icon: Rocket, tone: 'blue' },
    { title: '情绪支持', trigger: '情绪支持等级 ≥ 2', body: '情绪支持角色完成第一次升级。', earned: metrics.roles.some(role => role.roleCode === 'EMOTIONAL_SUPPORT_USER' && role.level >= 2), icon: HeartHandshake, tone: 'violet' },
    { title: '多角色探索', trigger: '至少 2 个角色达到 LV.2', body: '你开始在多个生活场景里留下行动。', earned: roleCountLevel2 >= 2, icon: Compass, tone: 'blue' },
    { title: '角色进阶', trigger: '至少 2 个角色达到 LV.5', body: '多个角色进入更稳定的成长阶段。', earned: roleCountLevel5 >= 2, icon: Mountain, tone: 'violet' },
    { title: '满级职业', trigger: '任一角色达到 LV.10', body: '至少一个职业等级已满级。', earned: bestRoleLevel >= 10, icon: Medal, tone: 'amber' },
  ]
}
