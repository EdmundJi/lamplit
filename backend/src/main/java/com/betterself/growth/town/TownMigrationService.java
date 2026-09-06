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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * M4-6（迁徙）与 M4-7（路过的旅人）。
 *
 * <p>迁徙是「交换制」：两个互为好友的小镇各出一个第三层居民，原地互换。表结构没有给"一个 NPC
 * 实例可以换镇子"的空间——每个用户从一开始就拥有全部 18 个 {@code npc_code}（{@code TownNpcProvisioner}
 * 建镇时全量铺开），所以同一个 code 在两个镇里天然都已经有人。真正搬家的不是"code"，而是**这个槽位
 * 当下住的是谁**：把 A 镇 X 槽位的人设（姓名/sprite/兴趣/癖好）和 B 镇 Y 槽位的互换，槽位对应的
 * {@code npc_code} 在各自镇里永远不变。这样搬家不需要新增列、也不会撞 {@code uk_town_npc_town_code}。
 *
 * <p>「对原小镇的 bond 转为 MISS」因此也要按槽位理解：谁在换人之前对着这个槽位有好感，就转成
 * 「怀念」；换人之后这个槽位的相处度清零重来——新来的人不该白捡走前任攒了多年的交情。
 *
 * <p>「带着 knowledge」实现为：从起点镇挑一条已经模糊化过的、关于起点镇玩家的「节奏印象」
 * （{@code TownFacts}/{@code TownNpcPerception} 早就产出好的那句话），当作这个人自己的一段往事，
 * 用 {@code hops} 故意调高的方式写进目标镇——复用 {@link TownSocialSim#dailySalienceDecay} 现成的
 * 衰减公式，"越传越淡"不用另写一套，调大 hops 就自然跑得更快。
 */
@Service
public class TownMigrationService {

    private static final Logger log = LoggerFactory.getLogger(TownMigrationService.class);

    /** 搬完至少驻留这么多天才能再搬——plan §2.6「冷却」。 */
    static final int MIGRATION_COOLDOWN_DAYS = 21;
    /** 每天尝试一次迁徙的概率，期望频率落在 plan 说的「1~2 周一次」附近。 */
    static final double DAILY_MIGRATION_PROBABILITY = 0.10;

    /** 跨镇传闻用更陡的衰减率——直接借用 hops 的语义，不用另写一套衰减公式。 */
    private static final int CROSS_TOWN_HOPS = 3;
    private static final double CROSS_TOWN_INITIAL_SALIENCE = 0.6;

    private static final double MISS_REGARD = 0.5;
    private static final double FRESH_AFFINITY = 0.12;

    private static final int TRAVELER_STAY_DAYS = 4;
    private static final String TRAVELER_CODE = "TRAVELER";
    private static final List<String> TRAVELER_SPRITES = List.of("c17", "c18", "c19", "c20");
    private static final List<String> TRAVELER_NAMES = List.of("阿远", "小舟", "阿棠", "随风");
    private static final List<String> TRAVELER_TALES = List.of(
        "我路过的上一个镇子，有户人家门口种了整墙的爬山虎，主人每天准时浇水。",
        "我从前待的地方，广场上总有个小摊准时出摊，风雨无阻。",
        "我来的那边，有人特别爱翻新旧家具，到他手里总能变个样子。",
        "上一个镇的咖啡馆老板记得住每个常客的口味，从不用问。"
    );

    private static final Pattern DIGIT = Pattern.compile("[0-9０-９]");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PublicIdGenerator ids;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TownDailyProduction daily;

    public TownMigrationService(JdbcTemplate jdbc, TransactionTemplate tx, PublicIdGenerator ids,
                                ObjectMapper mapper, Clock clock, TownDailyProduction daily) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ids = ids;
        this.mapper = mapper;
        this.clock = clock;
        this.daily = daily;
    }

    /** 按当地日期记录尝试与旅人进出；交换双方的 MIGRATED 唯一键和交换写入共用事务。 */
    public void runNightly(long userId, LocalDate localDate) {
        tx.executeWithoutResult(status -> {
            if (daily.claim(userId, localDate, "MIGRATION_ATTEMPT")) migrateIfDue(userId, localDate);
        });
        tx.executeWithoutResult(status -> {
            if (daily.claim(userId, localDate, "TRAVELER")) manageTraveler(userId, localDate);
        });
    }

    // ---------------------------------------------------------------- M4-6 迁徙

    private void migrateIfDue(long userId, LocalDate localDate) {
        if (alreadyMigratedToday(userId, localDate)) {
            return;
        }
        List<Long> friends = mutualFriendUserIds(userId);
        if (friends.isEmpty()) {
            return;
        }
        List<EligibleNpc> mine = eligibleLayer3(userId, localDate);
        if (mine.isEmpty()) {
            return;
        }
        RandomGenerator rng = new SplittableRandom(seedFor(userId, localDate));
        if (!rollsMigration(rng, DAILY_MIGRATION_PROBABILITY)) {
            return;
        }

        long friendId = friends.get(rng.nextInt(friends.size()));
        LocalDate peerDate = daily.dateForPeer(userId,friendId,localDate);
        if (alreadyMigratedToday(friendId, peerDate)) {
            // 对方今晚已经和别人换过了——稀疏触发本来就不追求"今天一定要换成"。
            return;
        }
        List<EligibleNpc> theirs = eligibleLayer3(friendId, peerDate);
        if (theirs.isEmpty()) {
            return;
        }

        String mineCode = mine.get(rng.nextInt(mine.size())).npcCode();
        String theirCode = theirs.get(rng.nextInt(theirs.size())).npcCode();
        long friendIdFinal = friendId;
        tx.executeWithoutResult(status -> swap(userId, mineCode, friendIdFinal, theirCode, localDate, peerDate));
        log.info("town migration: {}#{} <-> {}#{}", userId, mineCode, friendIdFinal, theirCode);
    }

    private void swap(long userIdA, String codeA, long userIdB, String codeB, LocalDate localDate, LocalDate peerDate) {
        // Stable lock order; recheck friendship, cooldown and both daily claims under the locks.
        // Lock resident rows, not the parent sys_user rows: daily claims already hold FK
        // shared parent locks and upgrading those would deadlock concurrent A/B swaps.
        for (long townId : new long[]{Math.min(userIdA,userIdB),Math.max(userIdA,userIdB)}) {
            jdbc.queryForList("select id from town_npc where town_user_id=? and layer=3 order by npc_code for update",Long.class,townId);
        }
        if (!mutualFriendUserIds(userIdA,true).contains(userIdB) || !mutualFriendUserIds(userIdB,true).contains(userIdA)) return;
        if (alreadyMigratedToday(userIdA, localDate) || alreadyMigratedToday(userIdB, peerDate)) return;
        if (eligibleLayer3(userIdA,localDate,true).stream().noneMatch(n -> n.npcCode().equals(codeA))
            || eligibleLayer3(userIdB,peerDate,true).stream().noneMatch(n -> n.npcCode().equals(codeB))) return;
        // Locking read sees a concurrent exchange even under MySQL REPEATABLE READ.
        if (!jdbc.queryForList("""
            select town_user_id from town_daily_production where stage='MIGRATED'
            and ((town_user_id=? and local_date=?) or (town_user_id=? and local_date=?)) for update
            """,Long.class,userIdA,Date.valueOf(localDate),userIdB,Date.valueOf(peerDate)).isEmpty()) return;
        daily.claim(userIdA,localDate,"MIGRATED");
        daily.claim(userIdB,peerDate,"MIGRATED");
        LocalDateTime now = LocalDateTime.now(clock);
        String taleA = carriedLine(userIdA,codeA);
        String taleB = carriedLine(userIdB,codeB);
        NpcContent contentA = npcContent(userIdA, codeA);
        NpcContent contentB = npcContent(userIdB, codeB);
        if (contentA == null || contentB == null) {
            log.warn("town migration aborted: missing npc content for {}#{} or {}#{}", userIdA, codeA, userIdB, codeB);
            return;
        }

        prepareDeparture(userIdA, codeA, now);
        prepareDeparture(userIdB, codeB, now);

        applyContent(userIdA, codeA, contentB, now);
        applyContent(userIdB, codeB, contentA, now);

        // 原 A 镇住户搬去 B，带着 A 镇的传闻；原 B 镇住户搬去 A，带着 B 镇的传闻。
        carryTale(userIdB, codeB, userIdA, peerDate, now, taleA);
        carryTale(userIdA, codeA, userIdB, localDate, now, taleB);

        recordMigration(codeA, userIdA, userIdB, codeB, now, peerDate);
        recordMigration(codeB, userIdB, userIdA, codeA, now, localDate);
    }

    /** 换人之前：先把"大家怀念他"记下来（用的是即将清空的旧数据），再清空这个槽位的相处记录。 */
    private void prepareDeparture(long userId, String code, LocalDateTime now) {
        jdbc.update("delete from town_npc_knowledge where town_user_id=? and npc_code=?", userId,code);
        jdbc.update(
            """
                update town_bond set regard = ?, regard_kind = 'MISS', updated_at = ?
                where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' and b_ref = ? and regard_kind is null
                """,
            MISS_REGARD, Timestamp.from(now.atZone(clock.getZone()).toInstant()), userId, code
        );
        // 这个槽位过去对别人怀有的心思（比如暗恋）跟着这个人一起走，不该留给接手的新人背锅。
        jdbc.update(
            """
                update town_bond set regard = 0, regard_kind = null, updated_at = ?
                where town_user_id = ? and a_kind = 'NPC' and a_ref = ? and b_kind = 'NPC'
                """,
            Timestamp.from(now.atZone(clock.getZone()).toInstant()), userId, code
        );
        jdbc.update(
            """
                update town_bond set affinity = ?, resonance = ?, meet_count = 0, last_met_at = null, updated_at = ?
                where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' and (a_ref = ? or b_ref = ?)
                """,
            FRESH_AFFINITY, FRESH_AFFINITY * 0.5, Timestamp.from(now.atZone(clock.getZone()).toInstant()), userId, code, code
        );
        jdbc.update(
            """
                update town_bond set affinity = ?, resonance = ?, meet_count = 0, last_met_at = null, updated_at = ?
                where town_user_id = ? and ((a_kind = 'PLAYER' and b_kind = 'NPC' and b_ref = ?)
                                          or (a_kind = 'NPC' and a_ref = ? and b_kind = 'PLAYER'))
                """,
            FRESH_AFFINITY, FRESH_AFFINITY * 0.5, Timestamp.from(now.atZone(clock.getZone()).toInstant()), userId, code, code
        );
    }

    private NpcContent npcContent(long userId, String code) {
        return jdbc.query(
            "select display_name, sprite, dimension, share_drive, curiosity, interests, quirks, rhythm "
                + "from town_npc where town_user_id = ? and npc_code = ?",
            rs -> rs.next() ? new NpcContent(rs.getString("display_name"), rs.getString("sprite"),
                rs.getString("dimension"), rs.getDouble("share_drive"), rs.getDouble("curiosity"),
                rs.getString("interests"), rs.getString("quirks"), rs.getString("rhythm")) : null,
            userId, code
        );
    }

    private void applyContent(long userId, String code, NpcContent content, LocalDateTime now) {
        jdbc.update(
            """
                update town_npc set display_name = ?, sprite = ?, dimension = ?, share_drive = ?, curiosity = ?,
                    interests = cast(? as json), quirks = cast(? as json), rhythm = cast(? as json), settled_at = ?, updated_at = ?
                where town_user_id = ? and npc_code = ?
                """,
            content.displayName(), content.sprite(), content.dimension(), content.shareDrive(), content.curiosity(),
            content.interestsJson(), content.quirksJson(), content.rhythmJson(), Timestamp.from(now.atZone(clock.getZone()).toInstant()), Timestamp.from(now.atZone(clock.getZone()).toInstant()),
            userId, code
        );
    }

    /** 搬家者只携带自己已经听过的模糊传闻。没有合格知识就空手出发。 */
    private void carryTale(long destinationUserId, String destinationCode, long originUserId, LocalDate localDate,
                           LocalDateTime now, String line) {
        if (line == null || line.isBlank() || DIGIT.matcher(line).find()) return;
        String text = "以前那边听说，" + line;
        long factId = insertNpcFact(destinationUserId, destinationCode, "CROSS_TOWN_TALE", localDate, now, text, originUserId);
        insertOwnKnowledge(destinationUserId, destinationCode, factId, localDate, now, CROSS_TOWN_HOPS,
            CROSS_TOWN_INITIAL_SALIENCE, text);
    }

    /** Carry only this departing resident's already-retold, relayable local knowledge.
     * Never query private player aggregates, GUIDE memory, or re-export a third town's tale. */
    private String carriedLine(long userId, String code) {
        return jdbc.query("""
            select k.retold_text from town_npc_knowledge k join town_fact f on f.id=k.fact_id
            where k.town_user_id=? and k.npc_code=? and f.town_user_id=k.town_user_id
              and k.no_relay=0 and f.no_relay=0 and k.hops>0 and k.salience>0.05
              and k.retold_text is not null and f.origin_town_user_id is null
              and f.subject_kind='NPC' and f.kind in ('NPC_ACTIVITY','REGARD_GUESS')
            order by k.salience desc,k.id desc limit 1
            """, rs -> rs.next() ? rs.getString(1) : null,userId,code);
    }

    private void recordMigration(String npcCode, long fromUserId, long toUserId, String pairedWithCode,
                                 LocalDateTime now, LocalDate localDate) {
        jdbc.update(
            """
                insert into town_migration
                    (public_id, npc_code, from_user_id, to_user_id, paired_with_npc_code, moved_at, created_at, local_date)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            ids.next(), npcCode, fromUserId, toUserId, pairedWithCode, Timestamp.from(now.atZone(clock.getZone()).toInstant()), Timestamp.from(now.atZone(clock.getZone()).toInstant()), Date.valueOf(localDate)
        );
    }

    private boolean alreadyMigratedToday(long userId, LocalDate localDate) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_migration where to_user_id = ? "
                + "and local_date = ?",
            Integer.class, userId, Date.valueOf(localDate)
        );
        return count != null && count > 0;
    }

    /** 只在互为好友之间——plan §2.6 唯一的安全阀。镜像 {@code TownService.acceptedFriends}。 */
    private List<Long> mutualFriendUserIds(long userId) {
        return mutualFriendUserIds(userId,false);
    }

    private List<Long> mutualFriendUserIds(long userId, boolean lock) {
        return jdbc.query(
            """
                select case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end as fid
                from friend_relationship f
                join sys_user u on u.id = case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end
                join user_preference up on up.user_id = u.id
                where ? in (f.requester_user_id, f.addressee_user_id) and f.status = 'ACCEPTED'
                  and up.solo_growth = 0 and u.status = 'ACTIVE' and u.deleted_at is null
                order by fid
                """ + (lock ? " for share" : ""),
            (rs, row) -> rs.getLong("fid"), userId, userId, userId
        );
    }

    /** 只有第三层、且属于固定名册（不是路过的旅人）、且已经过了冷却期的才能搬家。 */
    private List<EligibleNpc> eligibleLayer3(long userId, LocalDate localDate) {
        return eligibleLayer3(userId,localDate,false);
    }

    private List<EligibleNpc> eligibleLayer3(long userId, LocalDate localDate, boolean lock) {
        var zone = daily.zone(userId);
        List<EligibleNpc> all = jdbc.query(
            "select npc_code, settled_at from town_npc where town_user_id = ? and layer = 3 order by npc_code" + (lock ? " for update" : ""),
            (rs, row) -> new EligibleNpc(rs.getString("npc_code"),
                rs.getTimestamp("settled_at").toInstant().atZone(zone).toLocalDate()),
            userId
        );
        return all.stream()
            .filter(npc -> TownNpcCatalog.byCode(npc.npcCode()) != null)
            .filter(npc -> offCooldown(npc.settledAt(), localDate, MIGRATION_COOLDOWN_DAYS))
            .toList();
    }

    // ---------------------------------------------------------------- M4-7 路过的旅人

    private void manageTraveler(long userId, LocalDate localDate) {
        Optional<TravelerRow> current = currentTraveler(userId);
        if (current.isPresent()) {
            if (ChronoUnit.DAYS.between(current.get().settledAt(), localDate) >= TRAVELER_STAY_DAYS) {
                departTraveler(userId, current.get().npcCode());
            }
            return;
        }
        if (!mutualFriendUserIds(userId).isEmpty()) {
            return; // 有朋友的人有真正的迁徙可看，不需要旅人替代。
        }
        spawnTraveler(userId, localDate);
    }

    private Optional<TravelerRow> currentTraveler(long userId) {
        var zone = daily.zone(userId);
        TravelerRow row = jdbc.query(
            "select npc_code, settled_at from town_npc where town_user_id = ? and npc_code = ?",
            rs -> rs.next() ? new TravelerRow(rs.getString("npc_code"),
                rs.getTimestamp("settled_at").toInstant().atZone(zone).toLocalDate()) : null,
            userId, TRAVELER_CODE
        );
        return Optional.ofNullable(row);
    }

    private void spawnTraveler(long userId, LocalDate localDate) {
        RandomGenerator rng = new SplittableRandom(seedFor(userId, localDate) ^ 0x9E3779B97F4A7C15L);
        String name = TRAVELER_NAMES.get(rng.nextInt(TRAVELER_NAMES.size()));
        String sprite = TRAVELER_SPRITES.get(rng.nextInt(TRAVELER_SPRITES.size()));
        String tale = TRAVELER_TALES.get(rng.nextInt(TRAVELER_TALES.size()));
        LocalDateTime now = LocalDateTime.now(clock);

        tx.executeWithoutResult(status -> {
            jdbc.update(
                """
                    insert ignore into town_npc
                        (public_id, town_user_id, npc_code, display_name, layer, sprite, dimension,
                         share_drive, curiosity, interests, quirks, rhythm, settled_at, created_at, updated_at)
                    values (?, ?, ?, ?, 3, ?, null, 0.75, 0.5,
                        cast('{"KNOWLEDGE":0.2,"HEALTH":0.2,"CAREER":0.2,"RELATIONSHIP":0.2,"WELLBEING":0.2}' as json),
                        cast('["NOSTALGIC"]' as json),
                        cast('{"wakeMinute":420,"sleepMinute":1260,"errands":[{"place":"plaza","startMinute":600,"durationMinutes":90},{"place":"park","startMinute":960,"durationMinutes":90}]}' as json), ?, ?, ?)
                    """,
                ids.next(), userId, TRAVELER_CODE, name, sprite, Timestamp.from(now.atZone(clock.getZone()).toInstant()), Timestamp.from(now.atZone(clock.getZone()).toInstant()),
                Timestamp.from(now.atZone(clock.getZone()).toInstant())
            );
            long factId = insertNpcFact(userId, TRAVELER_CODE, "TRAVELER_TALE", localDate, now, tale, null);
            insertOwnKnowledge(userId, TRAVELER_CODE, factId, localDate, now, 0, 0.8, tale);
        });
        log.info("town traveler {} arrived for user {}", name, userId);
    }

    private void departTraveler(long userId, String code) {
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from town_npc_knowledge where town_user_id = ? and npc_code = ?", userId, code);
            jdbc.update("delete from town_fact where town_user_id = ? and subject_kind = 'NPC' and subject_ref = ?",
                userId, code);
            jdbc.update("delete from town_bond where town_user_id = ? and (a_ref = ? or b_ref = ?)",
                userId, code, code);
            jdbc.update("delete from town_npc_mood where town_user_id = ? and npc_code = ?", userId, code);
            jdbc.update("delete from town_npc where town_user_id = ? and npc_code = ?", userId, code);
        });
        log.info("town traveler {} left user {}", code, userId);
    }

    // ---------------------------------------------------------------- 共用的 SQL 小工具

    private long insertNpcFact(long userId, String subjectRef, String kind, LocalDate localDate, LocalDateTime now,
                               String text, Long originUserId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("text", text);
        jdbc.update(
            """
                insert into town_fact
                    (public_id, town_user_id, subject_kind, subject_ref, kind, dimension, payload,
                     occurred_on, occurred_at, origin_town_user_id, no_relay, created_at)
                values (?, ?, 'NPC', ?, ?, null, cast(? as json), ?, ?, ?, 0, ?)
                on duplicate key update payload = values(payload), occurred_at = values(occurred_at)
                """,
            ids.next(), userId, subjectRef, kind, payload.toString(), Date.valueOf(localDate), Timestamp.from(now.atZone(clock.getZone()).toInstant()),
            originUserId, Timestamp.from(now.atZone(clock.getZone()).toInstant())
        );
        return jdbc.queryForObject(
            """
                select id from town_fact
                where town_user_id = ? and subject_kind = 'NPC' and subject_ref = ? and kind = ? and occurred_on = ?
                """,
            Long.class, userId, subjectRef, kind, Date.valueOf(localDate)
        );
    }

    private void insertOwnKnowledge(long userId, String npcCode, long factId, LocalDate localDate,
                                    LocalDateTime now, int hops, double salience, String retoldText) {
        jdbc.update(
            """
                insert ignore into town_npc_knowledge
                    (public_id, town_user_id, npc_code, fact_id, learned_on, learned_at, learned_from, hops,
                     salience, retold_text, no_relay)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
            ids.next(), userId, npcCode, factId, Date.valueOf(localDate), Timestamp.from(now.atZone(clock.getZone()).toInstant()), npcCode, hops,
            salience, retoldText
        );
    }

    // ---------------------------------------------------------------- 纯函数（可脱离数据库单测）

    static boolean offCooldown(LocalDate settledAt, LocalDate today, int cooldownDays) {
        return !today.isBefore(settledAt.plusDays(cooldownDays));
    }

    static boolean rollsMigration(RandomGenerator rng, double dailyProbability) {
        return rng.nextDouble() < dailyProbability;
    }

    private static long seedFor(long userId, LocalDate localDate) {
        return userId * 1_000_037L + localDate.toEpochDay() + 11;
    }

    // ---------------------------------------------------------------- 行

    private record NpcContent(String displayName, String sprite, String dimension, double shareDrive,
                              double curiosity, String interestsJson, String quirksJson, String rhythmJson) {
    }

    private record EligibleNpc(String npcCode, LocalDate settledAt) {
    }

    private record TravelerRow(String npcCode, LocalDate settledAt) {
    }
}
