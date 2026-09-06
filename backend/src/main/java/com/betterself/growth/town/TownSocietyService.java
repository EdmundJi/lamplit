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

    /** {@code town_event.ends_at} 理论上不为空，真遇到空值时按这个时长补，免得算出 0 分钟的行程。 */
    private static final int EVENT_FALLBACK_MINUTES = 120;

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
    private final TownEventService events;

    public TownSocietyService(JdbcTemplate jdbc, TransactionTemplate tx, TownNpcProvisioner provisioner,
                              TownRetellGenerator retell, PublicIdGenerator ids, ObjectMapper mapper,
                              Clock clock, TownEventService events) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.provisioner = provisioner;
        this.retell = retell;
        this.ids = ids;
        this.mapper = mapper;
        this.clock = clock;
        this.events = events;
    }

    // ---------------------------------------------------------------- 夜间流水线

    /**
     * plan §3.2 的七步（本轮实现 1~5；活动/信件/迁徙是 M4）。
     *
     * <p>一天只跑一次，靠 {@code town_society_run} 的唯一键挡住重复触发。事实本身有唯一键、
     * 重写不翻倍，但衰减、传播、亲密度这三步都是累积的：再跑一次不等于"这一天又发生了一遍"，
     * 而是凭空多出一轮。所以幂等必须落在整晚这一层，不能只落在 town_fact 上。
     *
     * <p>转述特意留在事务外面：它要调 LLM，慢的时候能占满几十秒的网关预算，握着写锁等它是在
     * 拿数据库的行锁换一个外部服务的响应时间。
     */
    public void runNightly(long userId, LocalDate localDate) {
        events.runNightly(userId, localDate);
        tx.executeWithoutResult(status -> {
            if (!claimRun(userId, localDate)) return;
            List<NpcRow> npcs = simulationRoster(userId, localDate);
            Map<String, TownDayPlan.DayPlan> plans = dayPlans(userId, npcs, localDate);
            decaySalience(userId);
            List<Long> factIds = collectFacts(userId, zoneOf(userId), localDate, npcs, plans);
            Set<Long> playerFacts = new HashSet<>(playerFactIds(userId, localDate));
            witness(userId, localDate, npcs, plans, factIds, playerFacts);
            List<TownSocialSim.Encounter> encounters = TownDayPlan.encounters(plans);
            propagate(userId, localDate, npcs, encounters, playerFacts);
            updateBonds(userId, localDate, npcs, encounters);
        });
        // Retell can be retried after a failed gateway without repeating cumulative simulation writes.
        generateRetellText(userId, npcs(userId));
    }

    /**
     * 今晚参与模拟的人：名册里去掉「今天才搬来的」。
     *
     * <p>迁徙是跨小镇的一次写：A 镇的迁徙会直接把人塞进 B 镇。如果新来的人当晚就参与 B 镇的
     * 相遇与传播，B 镇那一晚算出什么就取决于两个用户谁先被 job 扫到——而整个限知模型的地基是
     * 「同一天可以重算出同一条链」（plan §3.5，M1-6 的验收）。把当天到岸的人推迟一晚再入场，
     * 跨镇的执行顺序就再也影响不到任何一镇的传播链了。
     *
     * <p>只影响模拟，不影响 {@code roster()}——新面孔当天就该在街上看得见，只是今晚还没跟谁
     * 说上话。这也正好对上 plan §2.6 想要的那种「他刚来」的生疏感。
     *
     * <p>判断依据是 {@code town_migration} 而不是 {@code town_npc.settled_at}：后者建号时就写上了，
     * 拿它当条件会把开镇第一天的 18 个人整个滤空。路过的旅人也不在此列——他由本镇自己的迁徙
     * 步骤生成，不存在"另一个用户先跑还是后跑"的分叉。
     */
    private List<NpcRow> simulationRoster(long userId, LocalDate localDate) {
        Set<String> arrivedToday = new HashSet<>(jdbc.queryForList(
            "select paired_with_npc_code from town_migration where to_user_id = ? and (local_date = ? or (local_date is null and moved_at >= ? and moved_at < ?))",
            String.class, userId, Date.valueOf(localDate),
            Timestamp.from(localDate.atStartOfDay(zoneOf(userId)).toInstant()), Timestamp.from(localDate.plusDays(1).atStartOfDay(zoneOf(userId)).toInstant())
        ));
        if (arrivedToday.isEmpty()) {
            return npcs(userId);
        }
        return npcs(userId).stream().filter(npc -> !arrivedToday.contains(npc.npcCode())).toList();
    }

    /** Claim shares the simulation transaction: rollback permits a real retry after SQL failure. */
    private boolean claimRun(long userId, LocalDate localDate) {
        return jdbc.update(
            "insert ignore into town_society_run (town_user_id, local_date, ran_at) values (?, ?, ?)",
            userId, Date.valueOf(localDate), Timestamp.valueOf(LocalDateTime.now(clock))
        ) > 0;
    }

    // ---------------------------------------------------------------- 1. 采集事实

    /**
     * 玩家事件 → 模糊化 → {@code town_fact}；NPC 自己的事 → 由日程直接生成。
     * 返回今天新写入或已存在的 fact id，供目击判定用。
     */
    private List<Long> collectFacts(long userId, ZoneId zone, LocalDate localDate, List<NpcRow> npcs,
                                    Map<String, TownDayPlan.DayPlan> plans) {
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
            var afternoon = TownDayPlan.positionAt(plans.get(npc.npcCode()), 15 * 60);
            if (!"AT".equals(afternoon.kind()) || TownNpcSchedules.HOME.equals(afternoon.place())) continue;
            ids.add(upsertFact(userId, "NPC", npc.npcCode(), "NPC_ACTIVITY", null, localDate,
                npc.displayName() + "今天" + placeCopy(afternoon.place()) + activityCopy(afternoon.activity())));
        }

        // A physical sample authorizes only the visible visit, never private metrics or task details.
        for (Presence sample : presenceSamples(userId, localDate)) {
            String place = scenePlace(sample.scene());
            if (place == null || TownNpcSchedules.HOME.equals(place)) continue;
            Long id = upsertFact(userId, "PLAYER", "PLAYER", "PUBLIC_VISIT_" + place.toUpperCase(), null,
                localDate, "最近" + placeCopy(place) + "露过面");
            if (id != null) {
                ids.add(id);
                for (NpcRow npc : npcs) {
                    if (npc.layer() <= 2 && sawPlayer(plans.get(npc.npcCode()), sample))
                        insertKnowledge(userId, npc.npcCode(), id, localDate, "WITNESS", 0, 1, GUIDE.equals(npc.npcCode()));
                }
            }
        }
        ids.addAll(regardGuesses(userId, localDate, npcs, plans));
        ids.removeIf(java.util.Objects::isNull);
        return ids;
    }

    /**
     * plan §2.5 那条咬合：regard 本人绝不说，只能靠第三方看见「A 在某处待得比谁都久，而 B 恰好也在」
     * 然后自己猜。写进网络的是这条**猜测**，不是事实——所以它天生就该走样。
     */
    private List<Long> regardGuesses(long userId, LocalDate localDate, List<NpcRow> npcs,
                                     Map<String, TownDayPlan.DayPlan> plans) {
        Map<String, NpcRow> byCode = new HashMap<>();
        npcs.forEach(npc -> byCode.put(npc.npcCode(), npc));
        List<Long> facts = new ArrayList<>();
        Set<String> observed = new HashSet<>();
        for (RegardRow regard : regards(userId)) {
            NpcRow admirer = byCode.get(regard.aRef());
            NpcRow target = byCode.get(regard.bRef());
            if (observed.contains(regard.aRef()) || admirer == null || target == null || regard.regard() < 0.45 || "MISS".equals(regard.regardKind())) continue;
            for (int minute = 0; minute < 1440; minute += 5) {
                var a = TownDayPlan.positionAt(plans.get(admirer.npcCode()), minute);
                var b = TownDayPlan.positionAt(plans.get(target.npcCode()), minute);
                if (!"AT".equals(a.kind()) || !"AT".equals(b.kind()) || TownNpcSchedules.HOME.equals(a.place())
                    || !a.place().equals(b.place())) continue;
                final int observedMinute = minute;
                var observer = npcs.stream().filter(n -> !GUIDE.equals(n.npcCode())
                    && !n.npcCode().equals(admirer.npcCode()) && !n.npcCode().equals(target.npcCode()))
                    .filter(n -> {
                        var at = TownDayPlan.positionAt(plans.get(n.npcCode()), observedMinute);
                        return "AT".equals(at.kind()) && a.place().equals(at.place());
                    }).findFirst();
                if (observer.isEmpty()) continue;
                Long fact = upsertFact(userId, "NPC", admirer.npcCode(), "REGARD_GUESS", null, localDate,
                    admirer.displayName() + "好像在" + placeName(a.place()) + "等人，" + target.displayName() + "也在，也许只是凑巧");
                if (fact != null) {
                    insertKnowledge(userId, observer.get().npcCode(), fact, localDate, "WITNESS", 0, 0.7, false);
                    facts.add(fact);
                    observed.add(admirer.npcCode());
                }
                break;
            }
        }
        return facts;
    }

    /**
     * 落库前的最后一道闸。上游每一句都来自 {@link TownNpcPerception} 的固定文案，本来就不该带
     * 数字——但整条隐私边界不能只靠"上游都很自觉"。这里硬拦一次：带阿拉伯数字的文本一律不写，
     * 并记一条 warn，好让新增的事实类型如果漏了模糊化，是在日志里炸出来而不是悄悄进了传播网络。
     */
    private static final java.util.regex.Pattern FACT_DIGITS = java.util.regex.Pattern.compile("[0-9０-９]");

    /** 同一天同一主体同一 kind 只留一条（靠唯一键），所以夜间 job 重跑不会翻倍。 */
    private Long upsertFact(long userId, String subjectKind, String subjectRef, String kind, String dimension,
                            LocalDate localDate, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (FACT_DIGITS.matcher(text).find()) {
            log.warn("refusing to store a town fact that still carries a raw number: kind={} subject={}",
                kind, subjectRef);
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
                on duplicate key update id = town_fact.id
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

    /** Only GUIDE learns aggregate player facts automatically. Actual public visits and
     * third-party guesses were seeded from their own physical evidence during collection. */
    private void witness(long userId, LocalDate localDate, List<NpcRow> npcs,
                         Map<String, TownDayPlan.DayPlan> plans, List<Long> factIds,
                         Set<Long> playerFacts) {
        if (factIds.isEmpty()) {
            return;
        }

        for (NpcRow npc : npcs) {
            boolean omniscient = GUIDE.equals(npc.npcCode());
            for (Long factId : factIds) {
                boolean playerFact = playerFacts.contains(factId);
                String from;
                if (omniscient) {
                    from = "OMNISCIENT";
                } else if (playerFact) {
                    // Aggregate player measurements are private. Public visits were witnessed above;
                    // explicit tell() consent has its own narrowly scoped knowledge write.
                    continue;
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

    private boolean sawPlayer(TownDayPlan.DayPlan plan, Presence presence) {
        if (plan == null) return false;
        String place = scenePlace(presence.scene());
        if (place == null || TownNpcSchedules.HOME.equals(place)) return false;
        var at = TownDayPlan.positionAt(plan, presence.at().toLocalTime().toSecondOfDay() / 60);
        return "AT".equals(at.kind()) && place.equals(at.place());
    }

    /** A whole outdoor street does not establish proximity. Unknown scenes fail closed. */
    private String scenePlace(String scene) {
        if (scene == null) return null;
        return switch (scene) {
            case "academy", "interior:academy-study", "town:academy" -> TownNpcSchedules.ACADEMY;
            case "gym", "interior:public-gym", "town:gym" -> TownNpcSchedules.GYM;
            case "cafe", "interior:public-cafe", "interior:cafe-interior", "town:cafe" -> TownNpcSchedules.CAFE;
            case "town:park" -> TownNpcSchedules.PARK;
            case "town:plaza" -> TownNpcSchedules.PLAZA;
            case "home", "interior:home-living-room" -> TownNpcSchedules.HOME;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 3. 传播模拟

    private void propagate(long userId, LocalDate localDate, List<NpcRow> npcs,
                           List<TownSocialSim.Encounter> encounters, Set<Long> playerFacts) {
        // 小助整个退出传播网络——不只是"它已知的那些不外传"，而是它连听都不参与。
        // 只过滤它已有的 knowledge 是不够的：它照样会在相遇里听到一条新的，然后成为下一手的
        // 消息源，于是"全知但不八卦"就破了。它是私人秘书，不是镇上的一张嘴。
        List<TownSocialSim.Encounter> gossipable = encounters.stream()
            .filter(encounter -> !GUIDE.equals(encounter.a()) && !GUIDE.equals(encounter.b()))
            .toList();
        Map<String, TownSocialSim.Persona> personas = new LinkedHashMap<>();
        npcs.stream()
            .filter(npc -> !GUIDE.equals(npc.npcCode()))
            .forEach(npc -> personas.put(npc.npcCode(), persona(npc)));

        Map<String, Integer> layerByCode = new HashMap<>();
        npcs.forEach(npc -> layerByCode.put(npc.npcCode(), npc.layer()));
        Map<String, TownSocialSim.Bond> bonds = bondIndex(userId);
        Map<String, List<TownSocialSim.RelayCandidate>> known = candidatesByNpc(userId, localDate);

        Map<Long, String> privateSubjects = new HashMap<>();
        jdbc.query("select id,subject_ref from town_fact where town_user_id=? and kind='REGARD_GUESS'",
            rs -> { privateSubjects.put(rs.getLong(1),rs.getString(2)); },userId);
        known.replaceAll((code, candidates) -> candidates.stream()
            .filter(c -> !code.equals(privateSubjects.get(c.factId()))).toList());
        List<TownSocialSim.RelayResult> results = TownSocialSim.simulate(
            gossipable,
            personas,
            (a, b) -> bonds.getOrDefault(bondKey(a, b), new TownSocialSim.Bond(0.15, 0.0, 0, null)),
            code -> known.getOrDefault(code, List.of()),
            new SplittableRandom(seedFor(userId, localDate)),
            (listener, fact) -> (!playerFacts.contains(fact) || layerByCode.getOrDefault(listener, 3) < 3)
                && !listener.equals(privateSubjects.get(fact))
        );

        for (TownSocialSim.RelayResult result : results) {
            // 目击那一侧已经挡住了三层居民，但传闻还是能顺着链条传到他们耳朵里。§3.4 第 5 条
            // 说的是"不持有"，所以听来的也一样要挡。
            if (playerFacts.contains(result.factId())
                && layerByCode.getOrDefault(result.listener(), 3) >= 3) {
                continue;
            }
            insertKnowledge(userId, result.listener(), result.factId(), localDate,
                result.speaker(), result.hops(), result.salience(), false);
        }
    }

    /** 只有 no_relay = 0 的才进传播网络——小助知道的一切在这里被拦住。 */
    private Map<String, List<TownSocialSim.RelayCandidate>> candidatesByNpc(long userId, LocalDate localDate) {
        Map<String, List<TownSocialSim.RelayCandidate>> byNpc = new HashMap<>();
        jdbc.query(
            """
                select k.npc_code, k.fact_id, k.hops, k.salience, k.retold_text,
                       f.dimension, json_unquote(json_extract(f.payload, '$.text')) as text
                from town_npc_knowledge k join town_fact f on f.id = k.fact_id
                where k.town_user_id = ? and f.town_user_id=k.town_user_id
                  and k.npc_code <> 'GUIDE'
                  and not (f.kind='REGARD_GUESS' and f.subject_ref=k.npc_code)
                  and not (f.subject_kind='PLAYER' and exists (select 1 from town_npc n
                    where n.town_user_id=k.town_user_id and n.npc_code=k.npc_code and n.layer=3))
                  and (f.origin_town_user_id is null or f.occurred_on >= ?)
                  and (f.origin_town_user_id is null or exists (
                    select 1 from friend_relationship fr
                    join sys_user origin on origin.id=f.origin_town_user_id
                    join user_preference op on op.user_id=origin.id
                    join user_preference tp on tp.user_id=k.town_user_id
                    where fr.status='ACCEPTED' and origin.status='ACTIVE' and origin.deleted_at is null
                    and op.solo_growth=0 and tp.solo_growth=0
                    and ((fr.requester_user_id=k.town_user_id and fr.addressee_user_id=f.origin_town_user_id)
                    or (fr.addressee_user_id=k.town_user_id and fr.requester_user_id=f.origin_town_user_id))))
                  and k.no_relay = 0 and f.no_relay = 0 and k.salience > 0.05
                order by k.npc_code, k.fact_id
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
            userId, Date.valueOf(localDate.minusDays(14))
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
                where k.town_user_id = ? and f.town_user_id=k.town_user_id and f.no_relay=0
                  and k.npc_code<>'GUIDE' and k.retold_text is null and k.no_relay = 0
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

        Map<String, TownRetellGenerator.Request> allowed = new HashMap<>();
        requests.forEach(request -> allowed.put(request.key(), request));
        for (TownRetellGenerator.Retold retold : retell.retell(requests)) {
            if (!allowed.containsKey(retold.key())) continue;
            String text = retold.text();
            if (text == null || text.isBlank() || FACT_DIGITS.matcher(text).find())
                text = new TemplateRetellGenerator().generate(allowed.get(retold.key()));
            jdbc.update("update town_npc_knowledge set retold_text = ? where id = ? and town_user_id=? and retold_text is null",
                text, Long.valueOf(retold.key()), userId);
        }
    }

    // ---------------------------------------------------------------- 5. 亲密度

    /**
     * 亲密度更新（plan §3.2 步骤 5）。
     *
     * <p>三件事，缺一不可：
     * <ol>
     *   <li>今天见过面的一对 —— 每天只算<b>一次</b>。同一对人一天里可能在七个时段都撞见，
     *       按时段逐次累加会让 meet_count 和 affinity 以"被触发的次数"而不是"过了多少天"增长；</li>
     *   <li>今天没见到的一对 —— 按半衰期衰减。这就是 M1-5 说的「久不见面衰减」，
     *       靠 {@link TownSocialSim#decayedAffinity} 那个纯函数，而不是另写一份；</li>
     *   <li><b>玩家</b>与看见过他的 NPC —— 玩家也是图上的节点。不更新这条边，
     *       affinityToPlayer 会永远停在初始的 0.05~0.15，于是按亲密度分档的气泡和主动搭话
     *       全都够不着阈值，整层行为等于没接。</li>
     * </ol>
     */
    private void updateBonds(long userId, LocalDate localDate, List<NpcRow> npcs,
                             List<TownSocialSim.Encounter> encounters) {
        Map<String, TownSocialSim.Persona> personas = new LinkedHashMap<>();
        npcs.forEach(npc -> personas.put(npc.npcCode(), persona(npc)));
        Map<String, TownSocialSim.Bond> bonds = bondIndex(userId);
        RandomGenerator rng = new SplittableRandom(seedFor(userId, localDate) ^ 0x5DEECE66DL);

        Set<String> metToday = new HashSet<>();
        for (TownSocialSim.Encounter encounter : encounters) {
            String key = bondKey(encounter.a(), encounter.b());
            if (!metToday.add(key)) {
                continue;
            }
            TownSocialSim.Persona a = personas.get(encounter.a());
            TownSocialSim.Persona b = personas.get(encounter.b());
            if (a == null || b == null) {
                continue;
            }
            TownSocialSim.Bond current = bonds.getOrDefault(key, new TownSocialSim.Bond(0.15, 0.0, 0, null));
            TownSocialSim.Bond next = TownSocialSim.afterMeeting(current, a, b, localDate, rng);
            writeBond(userId, "NPC", encounter.a(), "NPC", encounter.b(), next, localDate);
            writeBond(userId, "NPC", encounter.b(), "NPC", encounter.a(), next, localDate);
        }

        decayUnmetBonds(userId, localDate, metToday);
        updatePlayerBonds(userId, localDate, npcs, personas, rng);
    }

    /** 今天没碰上的那些边按半衰期往下走——这条以前只有单测，生产里没人调。 */
    private void decayUnmetBonds(long userId, LocalDate localDate, Set<String> metToday) {
        for (BondRow row : npcBondRows(userId)) {
            if (metToday.contains(bondKey(row.aRef(), row.bRef()))) {
                continue;
            }
            double decayed = TownSocialSim.decayedAffinity(row.affinity(), row.lastMetOn(), localDate);
            if (Math.abs(decayed - row.affinity()) < 1e-6) {
                continue;
            }
            jdbc.update(
                "update town_bond set affinity = ?, updated_at = ? where id = ?",
                decayed, Timestamp.valueOf(LocalDateTime.now(clock)), row.id()
            );
        }
    }

    /**
     * 玩家这条边。看见过他的一二层 NPC 今天算见过一面；其余的按时间衰减。
     * 玩家没有性格档案，用一份中性人设参与同一个公式，免得再写第二套算法。
     */
    private void updatePlayerBonds(long userId, LocalDate localDate, List<NpcRow> npcs,
                                   Map<String, TownSocialSim.Persona> personas, RandomGenerator rng) {
        TownSocialSim.Persona player = new TownSocialSim.Persona(
            "PLAYER", 1, 0.5, 0.5,
            Map.of("KNOWLEDGE", 0.2, "HEALTH", 0.2, "CAREER", 0.2, "RELATIONSHIP", 0.2, "WELLBEING", 0.2),
            Set.of()
        );
        var plans = dayPlans(userId, npcs, localDate);
        var samples = presenceSamples(userId, localDate);
        for (BondRow row : playerBondRows(userId)) {
            NpcRow npc = npcs.stream().filter(item -> item.npcCode().equals(row.bRef())).findFirst().orElse(null);
            TownSocialSim.Persona npcPersona = personas.get(row.bRef());
            if (npc == null || npcPersona == null) {
                continue;
            }
            // 小助天天见你（它就站在学院门口），其余人要真的在场才算。三层不参与——他们
            // 本来就不持有你的事实，也不该因为"路过"就和你熟起来。
            boolean met = GUIDE.equals(npc.npcCode())
                || (npc.layer() <= 2 && samples.stream().anyMatch(sample -> sawPlayer(plans.get(npc.npcCode()), sample)));

            TownSocialSim.Bond current = new TownSocialSim.Bond(
                row.affinity(), row.resonance(), row.meetCount(), row.lastMetOn());
            TownSocialSim.Bond next = met
                ? TownSocialSim.afterMeeting(current, player, npcPersona, localDate, rng)
                : new TownSocialSim.Bond(
                    TownSocialSim.decayedAffinity(row.affinity(), row.lastMetOn(), localDate),
                    row.resonance(), row.meetCount(), row.lastMetOn());
            writeBond(userId, "PLAYER", "PLAYER", "NPC", npc.npcCode(), next, met ? localDate : row.lastMetOn());
            writeBond(userId, "NPC", npc.npcCode(), "PLAYER", "PLAYER", next, met ? localDate : row.lastMetOn());
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
            localDate == null ? null : Timestamp.valueOf(localDate.atTime(12, 0)),
            Timestamp.valueOf(LocalDateTime.now(clock)), Timestamp.valueOf(LocalDateTime.now(clock))
        );
    }

    private void decaySalience(long userId) {
        jdbc.update(
            """
                update town_npc_knowledge
                -- 和 TownSocialSim.dailySalienceDecay 是同一个公式：rate = min(1, 0.10 + hops*0.05)。
                -- 之前这里另写了一组常数，于是"衰减"在单测里和在生产里是两回事。
                set salience = greatest(0, salience * (1 - least(1, 0.10 + hops * 0.05)))
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
        LocalDate today = LocalDate.now(clock.withZone(zoneOf(userId)));
        events.runNightly(userId, today);
        Map<String, Double> affinity = playerAffinity(userId);
        Map<String, MoodRow> moods = moods(userId, today);
        Map<String, TownDayPlan.DayPlan> dayPlans = dayPlans(userId, npcs, today);
        bootstrapPublicDays(userId, today, npcs, dayPlans);
        Map<String, Map<String, Double>> peerAffinity = new HashMap<>();
        jdbc.query("select a_ref,b_ref,affinity from town_bond where town_user_id=? and a_kind='NPC' and b_kind='NPC'",
            rs -> { peerAffinity.computeIfAbsent(rs.getString(1), ignored -> new HashMap<>()).put(rs.getString(2), rs.getDouble(3)); }, userId);
        Map<String, List<TalkingPoint>> points = talkingPointIndex(userId);

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
                toDayPlanView(dayPlans.get(npc.npcCode())),
                points.getOrDefault(npc.npcCode(), List.of()),
                Map.copyOf(peerAffinity.getOrDefault(npc.npcCode(), Map.of()))
            ));
        }
        return new RosterView(views, new InitiativeBudgetView(DAILY_INITIATIVE_LIMIT, initiativeUsed(userId, today)));
    }

    /** A new town has no overnight gossip yet. Each layer-two resident may describe their
     * own saved plans immediately; this does not witness player facts or broadcast to peers.
     * The daily claim and all six fact/knowledge pairs commit together, without any LLM call. */
    private void bootstrapPublicDays(long userId, LocalDate date, List<NpcRow> residents,
                                     Map<String, TownDayPlan.DayPlan> plans) {
        tx.executeWithoutResult(status -> {
            if (jdbc.update("insert ignore into town_daily_production (town_user_id,local_date,stage) values (?,?,'NPC_DAY_PLAN')",
                userId, Date.valueOf(date)) == 0) return;
            for (NpcRow npc : residents) {
                if (npc.layer() != 2) continue;
                String text = TownNpcPublicDay.line(npc.displayName(), npc.dimension(), readInterests(npc.interests()), plans.get(npc.npcCode()));
                Long factId = upsertFact(userId, "NPC", npc.npcCode(), "NPC_DAY_PLAN", npc.dimension(), date, text);
                if (factId == null) continue;
                jdbc.update("""
                    insert ignore into town_npc_knowledge
                      (public_id,town_user_id,npc_code,fact_id,learned_on,learned_at,learned_from,hops,salience,retold_text,no_relay)
                    values (?,?,?,?,?,?,'SELF',0,0.6,?,0)
                    """, ids.next(), userId, npc.npcCode(), factId, Date.valueOf(date), Timestamp.from(clock.instant()), text);
            }
        });
    }

    /** M7-6：{@code TownDayPlan.DayPlan} 内部形状 → CONTRACT-M7.md §1 的 {@code dayPlan} JSON。 */
    private DayPlanView toDayPlanView(TownDayPlan.DayPlan plan) {
        List<ErrandView> errands = plan.errands().stream()
            .map(e -> new ErrandView(e.place(), e.activity(), e.startMinute(), e.endMinute(), e.priority(),
                e.origin()))
            .toList();
        List<LegView> legs = plan.legs().stream()
            .map(l -> new LegView(l.fromPlace(), l.toPlace(), l.departMinute(), l.arriveMinute()))
            .toList();
        return new DayPlanView(plan.date(), errands, legs);
    }

    // ---------------------------------------------------------------- 护栏 A 持久化

    /**
     * 护栏 A 的每日预算消费。之前 {@code used} 只在前端内存里，刷新即重置——现在按
     * (user_id, local_date) 落库，谁调这个接口谁 +1，顶到 {@link #DAILY_INITIATIVE_LIMIT}
     * 就不再往上涨（超额调用返回 {@code used == limit}，是幂等的饱和状态，不是报错）。
     */
    public InitiativeBudgetView consumeInitiative(long userId) {
        LocalDate today = LocalDate.now(clock.withZone(zoneOf(userId)));
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(clock));
        jdbc.update(
            """
                insert into town_initiative_budget (user_id, local_date, used, created_at, updated_at)
                values (?, ?, 1, ?, ?)
                on duplicate key update
                    used = least(town_initiative_budget.used + 1, ?),
                    updated_at = values(updated_at)
                """,
            userId, Date.valueOf(today), now, now, DAILY_INITIATIVE_LIMIT
        );
        return new InitiativeBudgetView(DAILY_INITIATIVE_LIMIT, initiativeUsed(userId, today));
    }

    private int initiativeUsed(long userId, LocalDate localDate) {
        Integer used = jdbc.query(
            "select used from town_initiative_budget where user_id = ? and local_date = ?",
            rs -> rs.next() ? rs.getInt(1) : 0, userId, Date.valueOf(localDate)
        );
        return used == null ? 0 : used;
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
                where k.town_user_id = ? and f.town_user_id=k.town_user_id
                  and k.npc_code <> 'GUIDE'
                  and not (f.kind='REGARD_GUESS' and f.subject_ref=k.npc_code)
                  and not (f.subject_kind='PLAYER' and exists (select 1 from town_npc n
                    where n.town_user_id=k.town_user_id and n.npc_code=k.npc_code and n.layer=3))
                  and (f.origin_town_user_id is null or f.occurred_on >= ?)
                  and (f.origin_town_user_id is null or exists (
                    select 1 from friend_relationship fr
                    join sys_user origin on origin.id=f.origin_town_user_id
                    join user_preference op on op.user_id=origin.id
                    join user_preference tp on tp.user_id=k.town_user_id
                    where fr.status='ACCEPTED' and origin.status='ACTIVE' and origin.deleted_at is null
                    and op.solo_growth=0 and tp.solo_growth=0
                    and ((fr.requester_user_id=k.town_user_id and fr.addressee_user_id=f.origin_town_user_id)
                    or (fr.addressee_user_id=k.town_user_id and fr.requester_user_id=f.origin_town_user_id))))
                  and k.no_relay = 0 and f.no_relay = 0 and k.salience > 0.05
                order by k.salience desc
                """,
            rs -> {
                String text = rs.getString("retold_text");
                if (text == null || text.isBlank()) {
                    text = rs.getString("source_text");
                }
                if (text == null || text.isBlank() || FACT_DIGITS.matcher(text).find()) {
                    return;
                }
                List<TalkingPoint> list = byNpc.computeIfAbsent(rs.getString("npc_code"), key -> new ArrayList<>());
                if (list.size() < MAX_TALKING_POINTS) {
                    list.add(new TalkingPoint(rs.getString("public_id"), text,
                        rs.getInt("hops"), rs.getDouble("salience")));
                }
            },
            userId, Date.valueOf(LocalDate.now(clock.withZone(zoneOf(userId))).minusDays(14))
        );
        return byNpc;
    }

    // ---------------------------------------------------------------- SQL 小工具

    private record BondRow(long id, String aRef, String bRef, double affinity, double resonance,
                           int meetCount, LocalDate lastMetOn) {
    }

    private List<BondRow> npcBondRows(long userId) {
        return jdbc.query(
            "select id, a_ref, b_ref, affinity, resonance, meet_count, last_met_at from town_bond "
                + "where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' order by id",
            (rs, row) -> toBondRow(rs), userId);
    }

    private List<BondRow> playerBondRows(long userId) {
        return jdbc.query(
            "select id, a_ref, b_ref, affinity, resonance, meet_count, last_met_at from town_bond "
                + "where town_user_id = ? and a_kind = 'PLAYER' and b_kind = 'NPC' order by id",
            (rs, row) -> toBondRow(rs), userId);
    }

    private static BondRow toBondRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp met = rs.getTimestamp("last_met_at");
        return new BondRow(rs.getLong("id"), rs.getString("a_ref"), rs.getString("b_ref"),
            rs.getDouble("affinity"), rs.getDouble("resonance"), rs.getInt("meet_count"),
            met == null ? null : met.toLocalDateTime().toLocalDate());
    }

    private List<NpcRow> npcs(long userId) {
        return jdbc.query(
            """
                select npc_code, display_name, layer, sprite, dimension, share_drive, curiosity, interests,
                       quirks, rhythm
                from town_npc where town_user_id = ? order by layer, npc_code
                """,
            (rs, row) -> new NpcRow(rs.getString("npc_code"), rs.getString("display_name"), rs.getInt("layer"),
                rs.getString("sprite"), rs.getString("dimension"), rs.getDouble("share_drive"),
                rs.getDouble("curiosity"), rs.getString("interests"), rs.getString("quirks"),
                rs.getString("rhythm")),
            userId
        );
    }

    private NpcRow npc(long userId, String npcCode) {
        return npcs(userId).stream().filter(row -> row.npcCode().equals(npcCode)).findFirst().orElse(null);
    }

    private List<Long> playerFactIds(long userId, LocalDate localDate) {
        return jdbc.queryForList(
            "select id from town_fact where town_user_id = ? and subject_kind = 'PLAYER'",
            Long.class, userId);
    }

    private boolean ownFact(long userId, long factId, String npcCode) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_fact where id = ? and town_user_id = ? and subject_ref = ? and kind <> 'REGARD_GUESS'",
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

    private List<Presence> presenceSamples(long userId, LocalDate localDate) {
        ZoneId zone = zoneOf(userId);
        return jdbc.query("select scene,sampled_at from town_presence_sample where user_id=? and sampled_at>=? and sampled_at<? order by sampled_at",
            (rs, row) -> new Presence(rs.getString(1), rs.getTimestamp(2).toInstant().atZone(zone).toLocalDateTime()), userId,
            Timestamp.from(localDate.atStartOfDay(zone).toInstant()), Timestamp.from(localDate.plusDays(1).atStartOfDay(zone).toInstant()));
    }

    /** Explicit consent to share ONE server-generated coarse impression; never accepts free text. */
    public void tell(long userId, String npcCode, String kind) {
        provisioner.ensurePopulated(userId);
        NpcRow npc = npc(userId, npcCode);
        if (npc == null || npc.layer() > 2) throw new com.betterself.growth.shared.api.ApiException(
            org.springframework.http.HttpStatus.NOT_FOUND, "TOWN_NPC_NOT_FOUND", "这个居民暂不能搭话");
        if (kind == null || !Set.of("RHYTHM", "DIMENSION_FOCUS", "STREAK_HINT", "LEVEL_BUCKET").contains(kind))
            throw new com.betterself.growth.shared.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "TOWN_TELL_KIND_INVALID", "请选择要分享的近况");
        LocalDate date = LocalDate.now(clock.withZone(zoneOf(userId)));
        TownFacts facts = TownFacts.collect(jdbc, userId, zoneOf(userId), clock);
        String text = switch (kind) {
            case "RHYTHM" -> TownNpcPerception.rhythmLine(facts.completedLast7Days());
            case "DIMENSION_FOCUS" -> TownNpcPerception.dimensionFocusLine(facts.dominantDimension());
            case "STREAK_HINT" -> TownNpcPerception.streakHintLine(facts.longestStreak());
            default -> "在镇上" + TownNpcPerception.levelBucket(facts.level());
        };
        tx.executeWithoutResult(status -> {
            Long factId = upsertFact(userId, "PLAYER", "PLAYER", kind, null, date, text);
            if (factId != null) insertKnowledge(userId, npcCode, factId, date, "TOLD", 0, 1, GUIDE.equals(npcCode));
        });
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

    /** Immutable daily output, shared by the API, fact witnesses and encounter simulation.
     * Events and moods must be prepared first. A retry reads the saved plan even when today's
     * affinity or a migrating resident's rhythm has since changed. */
    private Map<String, TownDayPlan.DayPlan> dayPlans(long userId, List<NpcRow> npcs, LocalDate localDate) {
        Map<String, MoodRow> moods = moods(userId, localDate);
        Map<String, Double> affinity = new HashMap<>();
        jdbc.query("select npc_code,affinity_to_player from town_npc_mood where town_user_id=? and local_date=?",
            rs -> { affinity.put(rs.getString(1), rs.getDouble(2)); }, userId, Date.valueOf(localDate));
        Map<String, List<TownDayPlan.EventSlot>> events = eventSlots(userId, localDate);
        boolean rainy = isRainy(localDate);

        Map<String, TownDayPlan.DayPlan> plans = new LinkedHashMap<>();
        for (NpcRow npc : npcs) {
            TownNpcRhythm.Rhythm rhythm = readRhythm(npc.rhythm());
            MoodRow mood = moods.getOrDefault(npc.npcCode(), new MoodRow(0.0, 0.5));
            double npcAffinity = affinity.getOrDefault(npc.npcCode(), 0.15);
            TownDayPlan.DayPlanContext context = new TownDayPlan.DayPlanContext(
                mood.valence(), rainy, npcAffinity, events.getOrDefault(npc.npcCode(), List.of()));
            var generated = TownDayPlan.generate(npc.npcCode(), npc.layer(), rhythm, context, localDate);
            try {
                jdbc.update("update town_npc_mood set day_plan=cast(? as json) where town_user_id=? and npc_code=? and local_date=? and day_plan is null",
                    mapper.writeValueAsString(generated), userId, npc.npcCode(), Date.valueOf(localDate));
                String saved = jdbc.queryForObject("select day_plan from town_npc_mood where town_user_id=? and npc_code=? and local_date=?",
                    String.class, userId, npc.npcCode(), Date.valueOf(localDate));
                plans.put(npc.npcCode(), mapper.readValue(saved, TownDayPlan.DayPlan.class));
            } catch (java.io.IOException ex) { throw new IllegalStateException("invalid persisted town day plan", ex); }
        }
        return plans;
    }

    /**
     * 今天有活动的人，当天行程里必须多出一条去场地的高优先级安排（M7-3 验收：「活动日必定插入
     * 对应行程」）。到场的是主办人加所有收到请柬的 NPC——玩家那张请柬不在这里，玩家不走日程。
     *
     * <p>这也是 {@link TownEventService} 必须排在整晚流水线最前面的原因：它今晚建的 event
     * 起始时间就在今天，如果晚于这里执行，夜间推出的相遇序列里就没有这场活动，而白天
     * {@code roster()} 又会算出有——前后端两份日程对不上，限知模型的地基（plan §3.5）就塌了。
     */
    private Map<String, List<TownDayPlan.EventSlot>> eventSlots(long userId, LocalDate localDate) {
        Map<String, List<TownDayPlan.EventSlot>> byNpc = new LinkedHashMap<>();
        jdbc.query(
            """
                select e.venue, e.starts_at, e.ends_at, e.host_npc_code, i.recipient_ref
                from town_event e
                left join town_invitation i on i.event_id = e.id and i.recipient_kind = 'NPC'
                where e.town_user_id = ? and e.starts_at >= ? and e.starts_at < ?
                """,
            rs -> {
                LocalDateTime startsAt = rs.getTimestamp("starts_at").toLocalDateTime();
                Timestamp endsAt = rs.getTimestamp("ends_at");
                int startMinute = startsAt.toLocalTime().toSecondOfDay() / 60;
                int duration = endsAt == null
                    ? EVENT_FALLBACK_MINUTES
                    : (int) java.time.Duration.between(startsAt, endsAt.toLocalDateTime()).toMinutes();
                TownDayPlan.EventSlot slot = new TownDayPlan.EventSlot(
                    rs.getString("venue"), "sit", startMinute, Math.max(1, duration));
                List<TownDayPlan.EventSlot> hostSlots = byNpc.computeIfAbsent(rs.getString("host_npc_code"), key -> new ArrayList<>());
                if (!hostSlots.contains(slot)) hostSlots.add(slot);
                String guest = rs.getString("recipient_ref");
                if (guest != null) {
                    byNpc.computeIfAbsent(guest, key -> new ArrayList<>()).add(slot);
                }
            },
            userId, Timestamp.valueOf(localDate.atStartOfDay()), Timestamp.valueOf(localDate.plusDays(1).atStartOfDay())
        );
        return byNpc;
    }

    private TownNpcRhythm.Rhythm readRhythm(String json) {
        try {
            return mapper.readValue(json, TownNpcRhythm.Rhythm.class);
        } catch (Exception ex) {
            // 建号早于 M7 的账号理论上不该存在（迁移已经回填了所有旧行），但读坏一份不该拖垮
            // 整晚的流水线——退回到"没有常态行程"，当天就只剩早晚在家。
            log.warn("town npc rhythm unreadable, falling back to an empty rhythm", ex);
            return new TownNpcRhythm.Rhythm(420, 1320, List.of());
        }
    }

    /**
     * 占位天气：没有真实气象数据源，按日期哈希模拟一个全镇统一的晴/雨，只为了让
     * "雨天缩短户外行程"这条偏离规则有输入可用。TODO：接入真实天气后替换这里。
     */
    private static boolean isRainy(LocalDate localDate) {
        long hashed = localDate.toEpochDay() * 2654435761L;
        return Math.floorMod(hashed, 5) == 0;
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
                          List<ScheduleView> schedule, DayPlanView dayPlan, List<TalkingPoint> talkingPoints,
                          Map<String, Double> affinityToNpcs) {
    }

    public record ScheduleView(int startHour, int endHour, String place, String activity) {
    }

    /** CONTRACT-M7.md §1：{@code schedule} 之外新增的字段，{@code schedule} 本身原样保留。 */
    public record DayPlanView(LocalDate date, List<ErrandView> errands, List<LegView> legs) {
    }

    public record ErrandView(String place, String activity, int startMinute, int endMinute, int priority,
                             String origin) {
    }

    public record LegView(String fromPlace, String toPlace, int departMinute, int arriveMinute) {
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
                          double shareDrive, double curiosity, String interests, String quirks, String rhythm) {
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
