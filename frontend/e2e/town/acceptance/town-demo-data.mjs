/** Explicit synthetic input for filming the production town. Never reads credentials or real account data. */
export async function installTownDemoData(page) {
  const playerId = 'local-demo-player'
  const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
  const me = { publicId: playerId, displayName: '小镇体验者', email: 'demo@local.invalid', timezone: 'Asia/Shanghai', role: 'USER' }
  const profile = { ...me, overallLevel: 3, totalExperience: 600, effectiveActions: 0, longestStreak: 0, soloGrowth: true, equippedTitle: null, wallet: { coinBalance: 0, lifetimeCoins: 0 }, selectedPet: null, petCount: 0 }
  const resident = { publicId: playerId, displayName: me.displayName, self: true, level: 3, totalExperience: 600, dominantDimension: 'WELLBEING', longestStreak: 0, title: null, timezone: me.timezone, schedules: [], presence: null }
  const names = ['林知', '安禾', '陆夏', '沈牧', '许宁', '柯云', '王芳', '李明', '赵晨', '周雨', '陈晓', '苏叶', '江川', '吴桐', '方晴', '唐果']
  // Two cafe neighbours are free for local errands; everyone else keeps a normal off-stage place.
  // Positions, movement, occupancy, invitation and actions are entirely owned by production code.
  const npcs = names.map((displayName, index) => {
    const code = `DEMO_RESIDENT_${index}`
    const place = index < 2 ? 'cafe' : index < 6 ? 'home' : 'park'
    const activity = index === 0 ? 'reading' : 'idle'
    return {
      code, displayName, layer: index < 6 ? 2 : 3, sprite: `c${String(index + 1).padStart(2, '0')}`,
      dimension: index < 6 ? ['KNOWLEDGE', 'WELLBEING', 'HEALTH', 'CAREER', 'RELATIONSHIP', 'KNOWLEDGE'][index] : null,
      interests: {}, affinityToPlayer: .4, mood: { valence: .45, energy: .5 },
      schedule: [{ startHour: 0, endHour: 24, place, activity }],
      dayPlan: { date: localDate, errands: [{ startMinute: 0, endMinute: 1440, place, activity, priority: 1, origin: 'RHYTHM' }], legs: [] },
      talkingPoints: [],
    }
  })
  const roster = [
    { ...npcs[0], code: 'GUIDE', displayName: '小助', layer: 1, sprite: 'npc_scout' },
    { ...npcs[0], code: 'POSTMAN', displayName: '邮递员', layer: 1, sprite: 'npc_postman' },
    ...npcs,
  ]
  let presence = null
  await page.addInitScript(id => {
    localStorage.setItem(`better-self:welcome:${id}`, 'dismissed')
    localStorage.setItem(`better-self:town-onboarding:${id}:completed`, 'true')
  }, playerId)
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data = []
    if (path === '/me') data = me
    else if (path === '/me/profile') data = profile
    else if (path === '/town') data = { localDate, serverTime: new Date().toISOString(), unread: 0, soloGrowth: true, residents: [{ ...resident, presence }] }
    else if (path === '/town/npcs') data = { npcs: roster, initiativeBudget: { limit: 3, used: 3 } }
    else if (path === '/town/presence') { presence = { ...route.request().postDataJSON(), updatedAt: new Date().toISOString() }; data = presence }
    else if (path.endsWith('/talking-points')) data = { points: [] }
    else if (path === '/town/reflection/latest' || path === '/town/confidant' || path === '/daily-status') data = null
    else if (path === '/town/letters') data = { letters: [], unreadCount: 0 }
    else if (path === '/town/letters/unread') data = { unreadCount: 0 }
    else if (path === '/friends/unread-summary') data = { totalUnread: 0 }
    else if (path === '/partners/profile') data = { selectedPet: null, pets: [] }
    else if (path === '/insights/attributes') data = { overallLevel: 3, totalExperience: 600, attributes: [] }
    await route.fulfill({ json: { data, requestId: 'explicit-local-demo-data', timestamp: new Date().toISOString() } })
  })
  return { playerId, dataSource: '本地演示数据', residentCount: roster.length, notes: 'API inputs are synthetic; scene, clock, movement, actions, storage and audio run normally.' }
}
