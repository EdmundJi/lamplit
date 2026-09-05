package com.betterself.growth.town;

import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * 小镇社会模拟的装配层：把「测到了什么」变成「谁知道什么、从谁听来、说成了什么样」。
 *
 * <p>这个类本身不做任何判断——概率、衰减、亲密度全在 {@link TownSocialSim} 那个纯函数类里，
 * 日程在 {@link TownNpcSchedules} 里，走样文本在 {@link TownRetellGenerator} 里。留在这儿的只有
 * SQL 和顺序。这样"模拟跑得对不对"可以完全脱离数据库来验证。
 *
 * <p>隐私边界（plan §3.4）在本文件里体现为一条铁律：玩家的任何事实在写进 {@code town_fact} 之前
 * 必须先过 {@link TownNpcPerception}，落库的 payload 只有模糊化之后的那句话，原始数字一律留在
 * {@link TownFacts} 里，不出这个方法。
 */
@Service
public class TownSocietyService {

    private static final Logger log = LoggerFactory.getLogger(TownSocietyService.class);

    /** 全知但不八卦的那一个：拿得到全部事实，但每一条都标 no_relay。 */
    static final String GUIDE = "GUIDE";

    /** 护栏 A：全镇每天主动找玩家的总次数（plan §2.4）。 */
    static final int DAILY_INITIATIVE_LIMIT = 3;

    private static final int MAX_TALKING_POINTS = 3;
    private static final double WITNESS_SALIENCE = 1.0;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TownNpcProvisioner provisioner;
    private final TownRetellGenerator retell;
    private final PublicIdGenerator ids;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TownSocietyService(JdbcTemplate jdbc, TransactionTemplate tx, TownNpcProvisioner provisioner,
                              TownRetellGenerator retell, PublicIdGenerator ids, ObjectMapper mapper,
                              Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.provisioner = provisioner;
        this.retell = retell;
        this.ids = ids;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 夜间流水线

    /** plan §3.2 的七步（本轮实现 1~5；活动/信件/迁徙是 M4）。整晚一个事务，重跑幂等。 */
    public void runNightly(long userId, LocalDate localDate) {
        provisioner.ensurePopulated(userId);
        tx.executeWithoutResult(status -> {
            List<NpcRow> npcs = npcs(userId);
            if (npcs.isEmpty()) {
                return;
            }
            ZoneId zone = zoneOf(userId);
            Map<String, List<TownNpcSchedules.Slot>> schedules = schedules(npcs, localDate);

            decaySalience(userId);
            List<Long> factIds = collectFacts(userId, zone, localDate, npcs, schedules);
            witness(userId, localDate, npcs, schedules, factIds);
            List<TownSocialSim.Encounter> encounters = TownNpcSchedules.encounters(schedules);
            propagate(userId, localDate, npcs, encounters);
            updateBonds(userId, localDate, npcs, encounters);
            generateRetellText(userId, npcs);
        });
    }

    // ---------------------------------------------------------------- 1. 采集事实

    /**
     * 玩家事件 → 模糊化 → {@code town_fact}；NPC 自己的事 → 由日程直接生成。
     * 返回今天新写入或已存在的 fact id，供目击判定用。
     */
    private List<Long> collectFacts(long userId, ZoneId zone, LocalDate localDate, List<NpcRow> npcs,
                                    Map<String, List<TownNpcSchedules.Slot>> schedules) {
        List<Long> ids = new ArrayList<>();
        TownFacts facts = TownFacts.collect(jdbc, userId, zone, clock);

        // ↓↓↓ 这四行是隐私边界本身：出现在 payload 里的永远是右边那句话，不是左边那个数。
        ids.add(upsertFact(userId, "PLAYER", "PLAYER", "LEVEL_BUCKET", null, localDate,
            "在镇上" + TownNpcPerception.levelBucket(facts.level())));
        ids.add(upsertFact(userId, "PLAYER", "PLAYER", "RHYTHM", null, localDate,
            TownNpcPerception.rhythmLine(facts.completedLast7Days())));
        ids.add(upsertFact(userId, "PLAYER", "PLAYER", "DIMENSION_FOCUS", facts.dominantDimension(), localDate,
            TownNpcPerception.dimensionFocusLine(facts.dominantDimension())));
        ids.add(upsertFact(userId, "PLAYER", "PLAYER", "STREAK_HINT", null, localDate,
            TownNpcPerception.streakHintLine(facts.longestStreak())));

        // NPC 自己的事：去了哪儿、心情如何。这些是"人人目击得到"之外的、真正会传的料。
        for (NpcRow npc : npcs) {
            if (GUIDE.equals(npc.npcCode())) {
                continue;
            }
            List<TownNpcSchedules.Slot> schedule = schedules.get(npc.npcCode());
            TownNpcSchedules.Slot afternoon = TownNpcSchedules.slotAt(schedule, 15);
            if (afternoon == null || TownNpcSchedules.HOME.equals(afternoon.place())) {
                continue;
            }
            ids.add(upsertFact(userId, "NPC", npc.npcCode(), "NPC_ACTIVITY", null, localDate,
                npc.displayName() + "今天" + placeCopy(afternoon.place()) + activityCopy(afternoon.activity())));
        }

        ids.addAll(regardGuesses(userId, localDate, npcs, schedules));
        ids.removeIf(java.util.Objects::isNull);
        return ids;
    }

    /**
     * plan §2.5 那条咬合：regard 本人绝不说，只能靠第三方看见「A 在某处待得比谁都久，而 B 恰好也在」
     * 然后自己猜。写进网络的是这条**猜测**，不是事实——所以它天生就该走样。
     */
    private List<Long> regardGuesses(long userId, LocalDate localDate, List<NpcRow> npcs,
                                     Map<String, List<TownNpcSchedules.Slot>> schedules) {
        Map<String, NpcRow> byCode = new HashMap<>();
        npcs.forEach(npc -> byCode.put(npc.npcCode(), npc));
        List<Long> ids = new ArrayList<>();
        for (RegardRow regard : regards(userId)) {
            NpcRow admirer = byCode.get(regard.aRef());
            NpcRow target = byCode.get(regard.bRef());
            if (admirer == null || target == null || regard.regard() < 0.45) {
                continue;
            }
            String shared = sharedPlace(schedules.get(admirer.npcCode()), schedules.get(target.npcCode()));
            if (shared == null) {
                continue;
            }
            ids.add(upsertFact(userId, "NPC", admirer.npcCode(), "REGARD_GUESS", null, localDate,
                admirer.displayName() + "好像老在" + placeName(shared) + "多待一会儿，" + target.displayName() + "那会儿也在"));
        }
        return ids;
    }

    private String sharedPlace(List<TownNpcSchedules.Slot> a, List<TownNpcSchedules.Slot> b) {
        if (a == null || b == null) {
            return null;
        }
        for (int i = 0; i < a.size() && i < b.size(); i++) {
            String place = a.get(i).place();
            if (place.equals(b.get(i).place()) && !TownNpcSchedules.HOME.equals(place)) {
                return place;
            }
        }
        return null;
    }

    /** 同一天同一主体同一 kind 只留一条（靠唯一键），所以夜间 job 重跑不会翻倍。 */
    private Long upsertFact(long userId, String subjectKind, String subjectRef, String kind, String dimension,
                            LocalDate localDate, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("text", text);
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update(
            """
                insert into town_fact
                    (public_id, town_user_id, subject_kind, subject_ref, kind, dimension, payload,
                     occurred_on, occurred_at, no_relay, created_at)
                values (?, ?, ?, ?, ?, ?, cast(? as json), ?, ?, 0, ?)
                on duplicate key update payload = values(payload), occurred_at = values(occurred_at)
                """,
            ids.next(), userId, subjectKind, subjectRef, kind, dimension, payload.toString(),
            Date.valueOf(localDate), Timestamp.valueOf(now), Timestamp.valueOf(now)
        );
        return jdbc.queryForObject(
            """
                select id from town_fact
                where town_user_id = ? and subject_kind = ? and subject_ref = ? and kind = ? and occurred_on = ?
                """,
            Long.class, userId, subjectKind, subjectRef, kind, Date.valueOf(localDate)
        );
    }

    // ---------------------------------------------------------------- 2. 目击判定

    /**
     * 目击 = 物理在场（plan §3.2 步骤 2）。
     *
     * <p>能拿到的在场信息只有 {@code town_presence} 的**最后一次**上报（该表主键就是 user_id，
     * 没有轨迹历史），所以这里按"玩家最后一次出现的时间与场景"去和 NPC 日程求交集。够不够细是另
     * 一回事，但它保证了那条最要紧的性质：<b>玩家半夜一个人完成任务时，全镇没有一个人在场</b>，
     * 于是只有小助知道，而小助的每一条 knowledge 都是 no_relay。
     */
    private void witness(long userId, LocalDate localDate, List<NpcRow> npcs,
                         Map<String, List<TownNpcSchedules.Slot>> schedules, List<Long> factIds) {
        if (factIds.isEmpty()) {
            return;
        }
        Presence presence = presence(userId, localDate);
        Set<Long> playerFacts = new HashSet<>(playerFactIds(userId, localDate));

        for (NpcRow npc : npcs) {
            boolean omniscient = GUIDE.equals(npc.npcCode());
            for (Long factId : factIds) {
                boolean playerFact = playerFacts.contains(factId);
                String from;
                if (omniscient) {
                    from = "OMNISCIENT";
                } else if (playerFact) {
                    if (presence == null || !sawPlayer(schedules.get(npc.npcCode()), presence)) {
                        continue;
                    }
                    from = "WITNESS";
                } else {
                    // NPC 自己的事：当事人本来就知道，旁人得靠传播。
                    if (!ownFact(userId, factId, npc.npcCode())) {
                        continue;
                    }
                    from = "WITNESS";
                }
                // 小助全知，但每一条都不外传——它是私人秘书，不是消息源。
                insertKnowledge(userId, npc.npcCode(), factId, localDate, from, 0, WITNESS_SALIENCE, omniscient);
            }
        }
    }

    private boolean sawPlayer(List<TownNpcSchedules.Slot> schedule, Presence presence) {
        TownNpcSchedules.Slot slot = TownNpcSchedules.slotAt(schedule, presence.hour());
        if (slot == null || TownNpcSchedules.HOME.equals(slot.place())) {
            return false;
        }
        return slot.place().equals(scenePlace(presence.scene()));
    }

    /** 玩家上报的 scene 映射到 NPC 日程用的地点码；认不出来就当作在街上。 */
    private String scenePlace(String scene) {
        if (scene == null) {
            return TownNpcSchedules.STREET;
        }
        return switch (scene) {
            case "academy", "interior:academy-study" -> TownNpcSchedules.ACADEMY;
            case "gym", "interior:public-gym" -> TownNpcSchedules.GYM;
            case "home", "interior:home-living-room" -> TownNpcSchedules.HOME;
            default -> TownNpcSchedules.STREET;
        };
    }

    // ---------------------------------------------------------------- 3. 传播模拟

    private void propagate(long userId, LocalDate localDate, List<NpcRow> npcs,
                           List<TownSocialSim.Encounter> encounters) {
        Map<String, TownSocialSim.Persona> personas = new LinkedHashMap<>();
        npcs.forEach(npc -> personas.put(npc.npcCode(), persona(npc)));

        Map<String, TownSocialSim.Bond> bonds = bondIndex(userId);
        Map<String, List<TownSocialSim.RelayCandidate>> known = candidatesByNpc(userId);

        List<TownSocialSim.RelayResult> results = TownSocialSim.simulate(
            encounters,
            personas,
            (a, b) -> bonds.getOrDefault(bondKey(a, b), new TownSocialSim.Bond(0.15, 0.0, 0, null)),
            code -> known.getOrDefault(code, List.of()),
            new SplittableRandom(seedFor(userId, localDate))
        );

        for (TownSocialSim.RelayResult result : results) {
            insertKnowledge(userId, result.listener(), result.factId(), localDate,
                result.speaker(), result.hops(), result.salience(), false);
        }
    }

    /** 只有 no_relay = 0 的才进传播网络——小助知道的一切在这里被拦住。 */
    private Map<String, List<TownSocialSim.RelayCandidate>> candidatesByNpc(long userId) {
        Map<String, List<TownSocialSim.RelayCandidate>> byNpc = new HashMap<>();
        jdbc.query(
            """
                select k.npc_code, k.fact_id, k.hops, k.salience, k.retold_text,
                       f.dimension, json_unquote(json_extract(f.payload, '$.text')) as text
                from town_npc_knowledge k join town_fact f on f.id = k.fact_id
                where k.town_user_id = ? and k.no_relay = 0 and f.no_relay = 0 and k.salience > 0.05
                """,
            rs -> {
                String npc = rs.getString("npc_code");
                String previous = rs.getString("retold_text");
                byNpc.computeIfAbsent(npc, key -> new ArrayList<>()).add(new TownSocialSim.RelayCandidate(
                    npc, null, rs.getLong("fact_id"), rs.getString("dimension"),
                    rs.getInt("hops"), rs.getDouble("salience"),
                    previous != null ? previous : rs.getString("text")
                ));
            },
            userId
        );
        return byNpc;
    }

    // ---------------------------------------------------------------- 4. 转述

    /** 把还没有走样文本的那些 knowledge 一次性交给生成器（批量，成本可预测）。 */
    private void generateRetellText(long userId, List<NpcRow> npcs) {
        Map<String, NpcRow> byCode = new HashMap<>();
        npcs.forEach(npc -> byCode.put(npc.npcCode(), npc));

        List<PendingRetell> pending = jdbc.query(
            """
                select k.id, k.npc_code, k.hops, k.retold_text,
                       json_unquote(json_extract(f.payload, '$.text')) as source_text,
                       (select k2.retold_text from town_npc_knowledge k2
                        where k2.town_user_id = k.town_user_id and k2.fact_id = k.fact_id
                          and k2.npc_code = k.learned_from) as upstream_text
                from town_npc_knowledge k join town_fact f on f.id = k.fact_id
                where k.town_user_id = ? and k.retold_text is null and k.no_relay = 0
                order by k.hops, k.id
                """,
            (rs, row) -> new PendingRetell(rs.getLong("id"), rs.getString("npc_code"), rs.getInt("hops"),
                rs.getString("upstream_text") != null ? rs.getString("upstream_text") : rs.getString("source_text")),
            userId
        );
        if (pending.isEmpty()) {
            return;
        }

        List<TownRetellGenerator.Request> requests = new ArrayList<>(pending.size());
        for (PendingRetell item : pending) {
            NpcRow npc = byCode.get(item.npcCode());
            if (npc == null) {
                continue;
            }
            requests.add(new TownRetellGenerator.Request(
                String.valueOf(item.id()), npc.displayName(), personaSketch(npc),
                List.copyOf(readQuirks(npc.quirks())), item.hops(), item.previousText()
            ));
        }

        for (TownRetellGenerator.Retold retold : retell.retell(requests)) {
            jdbc.update("update town_npc_knowledge set retold_text = ? where id = ?",
                retold.text(), Long.valueOf(retold.key()));
        }
    }

    // ---------------------------------------------------------------- 5. 亲密度

    private void updateBonds(long userId, LocalDate localDate, List<NpcRow> npcs,
                             List<TownSocialSim.Encounter> encounters) {
        Map<String, TownSocialSim.Persona> personas = new LinkedHashMap<>();
        npcs.forEach(npc -> personas.put(npc.npcCode(), persona(npc)));
        Map<String, TownSocialSim.Bond> bonds = bondIndex(userId);
        RandomGenerator rng = new SplittableRandom(seedFor(userId, localDate) ^ 0x5DEECE66DL);

        Set<String> touched = new HashSet<>();
        for (TownSocialSim.Encounter encounter : encounters) {
            String key = bondKey(encounter.a(), encounter.b());
            if (!touched.add(key + "@" + encounter.slotHour())) {
                continue;
            }
            TownSocialSim.Persona a = personas.get(encounter.a());
            TownSocialSim.Persona b = personas.get(encounter.b());
            if (a == null || b == null) {
                continue;
            }
            TownSocialSim.Bond current = bonds.getOrDefault(key, new TownSocialSim.Bond(0.15, 0.0, 0, null));
            TownSocialSim.Bond next = TownSocialSim.afterMeeting(current, a, b, localDate, rng);
            bonds.put(key, next);
            writeBond(userId, "NPC", encounter.a(), "NPC", encounter.b(), next, localDate);
            writeBond(userId, "NPC", encounter.b(), "NPC", encounter.a(), next, localDate);
        }
    }

    /** affinity 语义对称，所以两个方向一起写；regard 是有向的，这里一个字都不碰。 */
    private void writeBond(long userId, String aKind, String aRef, String bKind, String bRef,
                           TownSocialSim.Bond bond, LocalDate localDate) {
        jdbc.update(
            """
                insert into town_bond
                    (public_id, town_user_id, a_kind, a_ref, b_kind, b_ref, affinity, resonance, meet_count,
                     last_met_at, regard, regard_kind, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, null, ?, ?)
                on duplicate key update affinity = values(affinity), resonance = values(resonance),
                    meet_count = values(meet_count), last_met_at = values(last_met_at),
                    updated_at = values(updated_at)
                """,
            ids.next(), userId, aKind, aRef, bKind, bRef, bond.affinity(), bond.resonance(), bond.meetCount(),
            Timestamp.valueOf(localDate.atTime(12, 0)),
            Timestamp.valueOf(LocalDateTime.now(clock)), Timestamp.valueOf(LocalDateTime.now(clock))
        );
    }

    private void decaySalience(long userId) {
        jdbc.update(
            """
                update town_npc_knowledge
                set salience = greatest(0, salience * case when hops >= 3 then 0.55
                                                          when hops = 2 then 0.7
                                                          when hops = 1 then 0.82
                                                          else 0.9 end)
                where town_user_id = ?
                """,
            userId
        );
    }

    // ---------------------------------------------------------------- 查询（HTTP 用）

    /** {@code GET /api/v1/town/npcs} 的数据源。 */
    public RosterView roster(long userId) {
        provisioner.ensurePopulated(userId);
        List<NpcRow> npcs = npcs(userId);
        LocalDate today = LocalDate.now(zoneOf(userId));
        Map<String, Double> affinity = playerAffinity(userId);
        Map<String, List<TalkingPoint>> points = talkingPointIndex(userId);
        Map<String, MoodRow> moods = moods(userId, today);

        List<NpcView> views = new ArrayList<>(npcs.size());
        for (NpcRow npc : npcs) {
            Map<String, Double> interests = readInterests(npc.interests());
            List<TownNpcSchedules.Slot> schedule = TownNpcSchedules.forNpc(
                npc.npcCode(), npc.layer(), interests, today);
            MoodRow mood = moods.getOrDefault(npc.npcCode(), new MoodRow(0.0, 0.5));
            views.add(new NpcView(
                npc.npcCode(), npc.displayName(), npc.layer(), npc.sprite(), npc.dimension(), interests,
                affinity.getOrDefault(npc.npcCode(), 0.15),
                new MoodView(mood.valence(), mood.energy()),
                schedule.stream().map(slot -> new ScheduleView(
                    slot.startHour(), slot.endHour(), slot.place(), slot.activity())).toList(),
                points.getOrDefault(npc.npcCode(), List.of())
            ));
        }
        return new RosterView(views, new InitiativeBudgetView(DAILY_INITIATIVE_LIMIT, 0));
    }

    /** {@code GET /api/v1/town/npc/{code}/talking-points} 的数据源。 */
    public TalkingPointsView talkingPoints(long userId, String npcCode) {
        provisioner.ensurePopulated(userId);
        NpcRow npc = npc(userId, npcCode);
        if (npc == null) {
            return null;
        }
        return new TalkingPointsView(npc.npcCode(), npc.displayName(),
            talkingPointIndex(userId).getOrDefault(npcCode, List.of()));
    }

    /**
     * 限知在读取侧的最后一道闸：只取这个 NPC 自己 knowledge 里 no_relay = 0 的条目。
     * 小助的每一条都是 no_relay = 1，所以它的 points 恒为空——它对你说话，但不对外传话。
     */
    private Map<String, List<TalkingPoint>> talkingPointIndex(long userId) {
        Map<String, List<TalkingPoint>> byNpc = new HashMap<>();
        jdbc.query(
            """
                select k.npc_code, f.public_id, k.hops, k.salience, k.retold_text,
                       json_unquote(json_extract(f.payload, '$.text')) as source_text
                from town_npc_knowledge k join town_fact f on f.id = k.fact_id
                where k.town_user_id = ? and k.no_relay = 0 and f.no_relay = 0 and k.salience > 0.05
                order by k.salience desc
                """,
            rs -> {
                String text = rs.getString("retold_text");
                if (text == null || text.isBlank()) {
                    text = rs.getString("source_text");
                }
                if (text == null || text.isBlank()) {
                    return;
                }
                List<TalkingPoint> list = byNpc.computeIfAbsent(rs.getString("npc_code"), key -> new ArrayList<>());
                if (list.size() < MAX_TALKING_POINTS) {
                    list.add(new TalkingPoint(rs.getString("public_id"), text,
                        rs.getInt("hops"), rs.getDouble("salience")));
                }
            },
            userId
        );
        return byNpc;
    }

    // ---------------------------------------------------------------- SQL 小工具

    private List<NpcRow> npcs(long userId) {
        return jdbc.query(
            """
                select npc_code, display_name, layer, sprite, dimension, share_drive, curiosity, interests, quirks
                from town_npc where town_user_id = ? order by layer, npc_code
                """,
            (rs, row) -> new NpcRow(rs.getString("npc_code"), rs.getString("display_name"), rs.getInt("layer"),
                rs.getString("sprite"), rs.getString("dimension"), rs.getDouble("share_drive"),
                rs.getDouble("curiosity"), rs.getString("interests"), rs.getString("quirks")),
            userId
        );
    }

    private NpcRow npc(long userId, String npcCode) {
        return npcs(userId).stream().filter(row -> row.npcCode().equals(npcCode)).findFirst().orElse(null);
    }

    private List<Long> playerFactIds(long userId, LocalDate localDate) {
        return jdbc.queryForList(
            "select id from town_fact where town_user_id = ? and subject_kind = 'PLAYER' and occurred_on = ?",
            Long.class, userId, Date.valueOf(localDate));
    }

    private boolean ownFact(long userId, long factId, String npcCode) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_fact where id = ? and town_user_id = ? and subject_ref = ?",
            Integer.class, factId, userId, npcCode);
        return count != null && count > 0;
    }

    private void insertKnowledge(long userId, String npcCode, long factId, LocalDate localDate,
                                 String learnedFrom, int hops, double salience, boolean noRelay) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update(
            """
                insert into town_npc_knowledge
                    (public_id, town_user_id, npc_code, fact_id, learned_on, learned_at, learned_from, hops,
                     salience, retold_text, no_relay)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?)
                on duplicate key update salience = greatest(town_npc_knowledge.salience, values(salience))
                """,
            ids.next(), userId, npcCode, factId, Date.valueOf(localDate), Timestamp.valueOf(now), learnedFrom,
            hops, salience, noRelay ? 1 : 0
        );
    }

    private Map<String, TownSocialSim.Bond> bondIndex(long userId) {
        Map<String, TownSocialSim.Bond> bonds = new HashMap<>();
        jdbc.query(
            """
                select a_ref, b_ref, affinity, resonance, meet_count, last_met_at
                from town_bond where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC'
                """,
            rs -> {
                Timestamp met = rs.getTimestamp("last_met_at");
                bonds.put(bondKey(rs.getString("a_ref"), rs.getString("b_ref")), new TownSocialSim.Bond(
                    rs.getDouble("affinity"), rs.getDouble("resonance"), rs.getInt("meet_count"),
                    met == null ? null : met.toLocalDateTime().toLocalDate()));
                
            },
            userId
        );
        return bonds;
    }

    private Map<String, Double> playerAffinity(long userId) {
        Map<String, Double> affinity = new HashMap<>();
        jdbc.query(
            """
                select b_ref, affinity from town_bond
                where town_user_id = ? and a_kind = 'PLAYER' and b_kind = 'NPC'
                """,
            rs -> {
                affinity.put(rs.getString("b_ref"), rs.getDouble("affinity"));
            },
            userId
        );
        return affinity;
    }

    private List<RegardRow> regards(long userId) {
        return jdbc.query(
            """
                select a_ref, b_ref, regard, regard_kind from town_bond
                where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' and regard > 0
                """,
            (rs, row) -> new RegardRow(rs.getString("a_ref"), rs.getString("b_ref"),
                rs.getDouble("regard"), rs.getString("regard_kind")),
            userId
        );
    }

    private Map<String, MoodRow> moods(long userId, LocalDate localDate) {
        Map<String, MoodRow> moods = new HashMap<>();
        jdbc.query(
            "select npc_code, valence, energy from town_npc_mood where town_user_id = ? and local_date = ?",
            rs -> {
                moods.put(rs.getString("npc_code"), new MoodRow(rs.getDouble("valence"), rs.getDouble("energy")));
            },
            userId, Date.valueOf(localDate)
        );
        return moods;
    }

    private Presence presence(long userId, LocalDate localDate) {
        List<Presence> rows = jdbc.query(
            "select scene, updated_at from town_presence where user_id = ?",
            (rs, row) -> new Presence(rs.getString("scene"), rs.getTimestamp("updated_at").toLocalDateTime()),
            userId
        );
        if (rows.isEmpty()) {
            return null;
        }
        Presence presence = rows.get(0);
        // 只认当天的在场：昨天站过的位置不能成为今天的目击证据。
        return presence.at().toLocalDate().equals(localDate) ? presence : null;
    }

    private ZoneId zoneOf(long userId) {
        String zone = jdbc.query("select timezone from sys_user where id = ?",
            rs -> rs.next() ? rs.getString(1) : null, userId);
        try {
            return zone == null ? clock.getZone() : ZoneId.of(zone);
        } catch (RuntimeException ex) {
            return clock.getZone();
        }
    }

    private Map<String, List<TownNpcSchedules.Slot>> schedules(List<NpcRow> npcs, LocalDate localDate) {
        Map<String, List<TownNpcSchedules.Slot>> schedules = new LinkedHashMap<>();
        for (NpcRow npc : npcs) {
            schedules.put(npc.npcCode(),
                TownNpcSchedules.forNpc(npc.npcCode(), npc.layer(), readInterests(npc.interests()), localDate));
        }
        return schedules;
    }

    private TownSocialSim.Persona persona(NpcRow npc) {
        return new TownSocialSim.Persona(npc.npcCode(), npc.layer(), npc.shareDrive(), npc.curiosity(),
            readInterests(npc.interests()), readQuirks(npc.quirks()));
    }

    private Map<String, Double> readInterests(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Double> parsed = new LinkedHashMap<>();
            mapper.readTree(json).fields().forEachRemaining(
                entry -> parsed.put(entry.getKey(), entry.getValue().asDouble()));
            return parsed;
        } catch (Exception ex) {
            log.warn("town npc interests unreadable, treating as empty", ex);
            return Map.of();
        }
    }

    private Set<String> readQuirks(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> quirks = new HashSet<>();
            mapper.readTree(json).forEach(node -> quirks.add(node.asText()));
            return quirks;
        } catch (Exception ex) {
            log.warn("town npc quirks unreadable, treating as empty", ex);
            return Set.of();
        }
    }

    /**
     * 交给转述生成器的人设。一二层的正式人设写在 {@link TownPersonas} 里；三层背景居民没有、也不该有
     * 一份手写人设，所以按性格维度现拼一句——够生成器把握语气就行。
     */
    private String personaSketch(NpcRow npc) {
        String authored = TownPersonas.persona(npc.npcCode());
        if (authored != null) {
            return authored;
        }
        StringBuilder sketch = new StringBuilder("你是成长小镇的居民「").append(npc.displayName()).append("」。");
        sketch.append(npc.shareDrive() >= 0.6 ? "话多、藏不住事，" : "话不多、说半句留半句，");
        sketch.append(npc.curiosity() >= 0.6 ? "对别人的事很好奇。" : "不太打听别人的事。");
        return sketch.toString();
    }

    private static String bondKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static long seedFor(long userId, LocalDate localDate) {
        return userId * 1_000_003L + localDate.toEpochDay();
    }

    private static String placeCopy(String place) {
        return switch (place) {
            case TownNpcSchedules.ACADEMY -> "在学院";
            case TownNpcSchedules.GYM -> "在健身房";
            case TownNpcSchedules.CAFE -> "在咖啡馆";
            case TownNpcSchedules.PARK -> "在公园";
            case TownNpcSchedules.PLAZA -> "在广场";
            default -> "在街上";
        };
    }

    private static String placeName(String place) {
        return placeCopy(place).substring(1);
    }

    private static String activityCopy(String activity) {
        return switch (activity) {
            case "reading" -> "看了很久的书";
            case "sit" -> "坐了一下午";
            case "phone" -> "一直在打电话";
            case "walking" -> "来来回回走";
            case "fishing" -> "钓鱼";
            case "watering" -> "浇花";
            case "chopping" -> "劈柴";
            case "harvesting" -> "收东西";
            case "digging" -> "挖土";
            default -> "待着";
        };
    }

    // ---------------------------------------------------------------- 视图与行

    public record RosterView(List<NpcView> npcs, InitiativeBudgetView initiativeBudget) {
    }

    public record NpcView(String code, String displayName, int layer, String sprite, String dimension,
                          Map<String, Double> interests, double affinityToPlayer, MoodView mood,
                          List<ScheduleView> schedule, List<TalkingPoint> talkingPoints) {
    }

    public record ScheduleView(int startHour, int endHour, String place, String activity) {
    }

    public record MoodView(double valence, double energy) {
    }

    public record TalkingPoint(String factId, String text, int hops, double salience) {
    }

    public record InitiativeBudgetView(int limit, int used) {
    }

    public record TalkingPointsView(String code, String displayName, List<TalkingPoint> points) {
    }

    private record NpcRow(String npcCode, String displayName, int layer, String sprite, String dimension,
                          double shareDrive, double curiosity, String interests, String quirks) {
    }

    private record RegardRow(String aRef, String bRef, double regard, String regardKind) {
    }

    private record MoodRow(double valence, double energy) {
    }

    private record Presence(String scene, LocalDateTime at) {
        int hour() {
            return at.getHour();
        }
    }

    private record PendingRetell(long id, String npcCode, int hops, String previousText) {
    }
}
