import {
  Award,
  Bot,
  CalendarCheck,
  MessageCircle,
  PawPrint,
  Radar,
  Settings,
  Target,
  TrendingUp,
  UserRound,
} from 'lucide-vue-next'
import type { WorldPanelDef } from '../panel.types'

/**
 * 小镇里能打开的全部功能面板。外壳（ImmersiveTown.vue）按这份清单渲染 dock 和自动开窗逻辑，
 * 面板实现放在同目录下，各自 lazy-load。
 */
export const worldPanels: WorldPanelDef[] = [
  {
    key: 'today',
    title: '今天',
    subtitle: '先看看今天要做的这一件事',
    icon: CalendarCheck,
    loader: () => import('./TodayPanel.vue'),
    size: 'compact',
    anchor: 'home',
    fullPage: '/today',
  },
  {
    key: 'mementos',
    title: '家的纪念墙',
    subtitle: '留在这里的，是你走过的日子',
    icon: Award,
    loader: () => import('./MementosPanel.vue'),
    size: 'wide',
    anchor: 'home',
    fullPage: '/insights',
  },
  {
    key: 'goals',
    title: '目标',
    subtitle: '正在进行的方向，一眼看清',
    icon: Target,
    loader: () => import('./GoalsPanel.vue'),
    size: 'compact',
    // 咖啡馆：定方向这件事更像找人聊聊、理一理，不是在健身房或学院里做的事。
    anchor: 'cafe',
    fullPage: '/goals',
  },
  {
    key: 'ai',
    title: 'AI 助手',
    subtitle: '把还没理清的想法说给它听',
    icon: Bot,
    loader: () => import('./AiPanel.vue'),
    size: 'wide',
    anchor: 'npc:assistant',
    fullPage: '/ai',
  },
  {
    key: 'friends',
    title: '信箱与好友',
    subtitle: '收长信、短笺和请柬，也和好友说说话',
    icon: MessageCircle,
    loader: () => import('./FriendsPanel.vue'),
    size: 'wide',
    anchor: 'npc:postman',
    fullPage: '/friends/chat',
  },
  {
    key: 'insights',
    title: '洞察',
    subtitle: '这段时间的成长，留下了哪些痕迹',
    icon: TrendingUp,
    loader: () => import('./InsightsPanel.vue'),
    size: 'compact',
    anchor: 'academy',
    fullPage: '/insights',
  },
  {
    key: 'attributes',
    title: '属性',
    subtitle: '五项能力各自长到了哪里',
    icon: Radar,
    loader: () => import('./AttributesPanel.vue'),
    size: 'compact',
    // 健身房：五维度里最直观能对上号的地点，走到这儿看一眼自己的雷达图很自然。
    anchor: 'gym',
    fullPage: '/attributes',
  },
  {
    key: 'partners',
    title: '伙伴',
    subtitle: '陪伴你的小家伙，今天状态如何',
    icon: PawPrint,
    loader: () => import('./PartnersPanel.vue'),
    size: 'compact',
    // 公园：遛宠物本来就该在这儿，比绑回自己家更像「伙伴陪着你出门」。
    anchor: 'park',
    fullPage: '/partners',
  },
  {
    key: 'profile',
    title: '个人',
    subtitle: '你的等级、称号与这一路的积累',
    icon: UserRound,
    loader: () => import('./ProfilePanel.vue'),
    size: 'compact',
    // 广场：等级、称号是公开给全镇看的荣誉，挂在镇中心的告示牌逻辑上说得通。
    anchor: 'plaza',
    fullPage: '/profile',
  },
  {
    key: 'settings',
    title: '设置',
    subtitle: '调整外观、通知与小镇里的节奏',
    icon: Settings,
    loader: () => import('./SettingsPanel.vue'),
    size: 'compact',
    // 街道：调的是外观/通知/节奏这些贯穿全镇的东西，不属于任何一栋建筑，落在路上最中性。
    anchor: 'street',
    fullPage: '/settings',
  },
]
