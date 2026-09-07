package com.betterself.growth.town;

import com.betterself.growth.shared.id.PublicIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * M4-1（NPC 自主办活动）与 M4-4（请柬）。
 *
 * <p>只有第二层的 6 个 NPC 会办活动——plan §2.4：「有性格、兴趣、关系图、会办活动」是第二层的定义，
 * 第三层只有日程，第一层永不做这种事。谁办、办不办由 {@link #hostProbability} 这个纯函数决定，
 * 单测直接钉住"不会连续一周冷场，也不会天天办"。
 *
 * <p>每日 EVENT 决定（包括不办）与活动、请柬和投递共用短事务及唯一日期键。
 */
@Service
public class TownEventService {

    private static final Logger log = LoggerFactory.getLogger(TownEventService.class);

    /** 判定"算不算朋友"的门槛——只统计走得近的关系，免得把点头之交也算进热闹程度里。 */
    static final double FRIEND_AFFINITY_THRESHOLD = 0.30;

    /** 除了玩家之外，最多再邀这么多个 NPC——party 不是全镇大会。 */
    private static final int MAX_NPC_INVITEES = 5;

    private static final LocalTime EVENT_START_HOUR = LocalTime.of(18, 0);
    private static final int EVENT_DURATION_HOURS = 2;
    private static final int INVITE_STAGGER_MINUTES = 2;
    private static final String EVENT_KIND = "GATHERING";

    private static final List<String> VENUES = List.of(
        TownNpcSchedules.PLAZA, TownNpcSchedules.PARK, TownNpcSchedules.CAFE
    );

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PublicIdGenerator ids;
    private final TownLetterService letters;
    private final Clock clock;
    private final TownMoodService moods;
    private final TownDailyProduction daily;

    public TownEventService(JdbcTemplate jdbc, TransactionTemplate tx, PublicIdGenerator ids,
                            TownLetterService letters, Clock clock, TownMoodService moods, TownDailyProduction daily) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ids = ids;
        this.letters = letters;
        this.clock = clock;
        this.moods = moods;
        this.daily = daily;
    }

    /** 幂等入口：一天最多一场活动，重跑不产生第二场、不重发第二轮请柬。 */
    public void runNightly(long userId, LocalDate localDate) {
        moods.ensureDay(userId, localDate);
        tx.executeWithoutResult(status -> {
            if (!daily.claim(userId, localDate, "EVENT")) return;
            if (alreadyHostedToday(userId, localDate)) return;
            // Never create a retroactive party (or mail an invitation to yesterday's party).
            if (localDate.isBefore(daily.today(userId))) return;
            List<HostCandidate> candidates = hostCandidates(userId, localDate);
            RandomGenerator rng = new SplittableRandom(seedFor(userId, localDate));
            pickHost(candidates, rng).ifPresent(code -> {
                HostCandidate host = candidates.stream().filter(c -> c.npcCode().equals(code)).findFirst().orElseThrow();
                hostEvent(userId, localDate, host, pickVenue(host.dimension(), rng));
            });
        });
    }

    /** Authenticated owner's local calendar day; no bonds, invitee identities or private state. */
    public List<EventView> today(long userId) {
        LocalDate date = daily.today(userId);
        runNightly(userId, date);
        var zone = daily.zone(userId);
        return jdbc.query("""
            select e.public_id,e.kind,e.venue,n.display_name,e.starts_at,e.ends_at,e.dimension
            from town_event e join town_npc n on n.town_user_id=e.town_user_id and n.npc_code=e.host_npc_code
            where e.town_user_id=? and e.starts_at>=? and e.starts_at<? order by e.starts_at,e.public_id
            """, (rs, row) -> new EventView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getTimestamp(5).toLocalDateTime().atZone(zone).toOffsetDateTime(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime().atZone(zone).toOffsetDateTime(),
                rs.getString(7)), userId, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }

    public record EventView(String publicId, String kind, String venue, String hostName,
                            java.time.OffsetDateTime startsAt, java.time.OffsetDateTime endsAt, String dimension) {}

    private void hostEvent(long userId, LocalDate localDate, HostCandidate host, String venue) {
        LocalDateTime startsAt = localDate.atTime(EVENT_START_HOUR);
        LocalDateTime endsAt = startsAt.plusHours(EVENT_DURATION_HOURS);
        String eventPublicId = ids.next();
        jdbc.update(
            """
                insert into town_event
                    (public_id, town_user_id, host_npc_code, kind, venue, dimension, starts_at, ends_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            eventPublicId, userId, host.npcCode(), EVENT_KIND, venue, host.dimension(),
            Timestamp.valueOf(startsAt), Timestamp.valueOf(endsAt), Timestamp.valueOf(LocalDateTime.now(clock))
        );
        long eventId = jdbc.queryForObject(
            "select id from town_event where public_id = ?", Long.class, eventPublicId
        );

        List<Recipient> recipients = rankRecipients(recipientCandidates(userId, host.npcCode()));
        LocalDateTime deliverBase = LocalDateTime.now(clock);
        int rank = 0;
        for (Recipient recipient : recipients) {
            LocalDateTime deliveredAt = deliverBase.plusMinutes((long) rank * INVITE_STAGGER_MINUTES);
            jdbc.update(
                """
                    insert into town_invitation
                        (public_id, town_user_id, event_id, recipient_kind, recipient_ref, delivered_at, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                ids.next(), userId, eventId, recipient.kind(), recipient.ref(),
                Timestamp.valueOf(deliveredAt), Timestamp.valueOf(LocalDateTime.now(clock))
            );
            if ("PLAYER".equals(recipient.kind())) {
                letters.deliver(userId, "NPC", host.npcCode(), "INVITE",
                    inviteBody(host.displayName(), venue), deliveredAt);
            }
            rank++;
        }
    }

    private String inviteBody(String hostName, String venue) {
        return "「" + hostName + "」在" + venueLabel(venue) + "张罗了一场聚会，要不要一起来？";
    }

    private String venueLabel(String venue) {
        return switch (venue) {
            case TownNpcSchedules.PLAZA -> "广场";
            case TownNpcSchedules.PARK -> "公园";
            case TownNpcSchedules.CAFE -> "咖啡馆";
            default -> "镇上";
        };
    }

    // ---------------------------------------------------------------- 纯函数（可脱离数据库单测）

    /**
     * 办活动的意愿：分享欲越高、朋友越多、心情越好，今天想张罗一场的概率越高——但任何一项拉满也
     * 不会把概率顶到 1，纯背景式的"天天办"和"永远不办"都不该出现。
     */
    static double hostProbability(double shareDrive, int friendCount, double moodValence) {
        double friendFactor = Math.min(1.0, Math.max(0, friendCount) / 5.0);
        double moodFactor = (clamp01Signed(moodValence) + 1.0) / 2.0;
        double score = 0.01 + 0.09 * clamp01(shareDrive) + 0.04 * friendFactor + 0.02 * moodFactor;
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 谁来办：先让每个候选人各自掷一次骰子决定"今天想不想张罗"，想张罗的人里再随机挑一个真正
     * 办起来——避免同一天冒出好几场活动，也避免概率被候选人数量稀释到形同虚设。
     */
    static Optional<String> pickHost(List<HostCandidate> candidates, RandomGenerator rng) {
        List<String> willing = new ArrayList<>();
        for (HostCandidate candidate : candidates) {
            double probability = hostProbability(candidate.shareDrive(), candidate.friendCount(), candidate.moodValence());
            if (rng.nextDouble() < probability) {
                willing.add(candidate.npcCode());
            }
        }
        if (willing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(willing.get(rng.nextInt(willing.size())));
    }

    /** 按亲密度降序排；同分时按 ref 稳定排序，好让"先到先得"的顺序可复现。 */
    static List<Recipient> rankRecipients(List<Recipient> candidates) {
        List<Recipient> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(Recipient::affinity).reversed()
            .thenComparing(Recipient::ref));
        return sorted;
    }

    private String pickVenue(String hostDimension, RandomGenerator rng) {
        String mapped = switch (hostDimension == null ? "" : hostDimension) {
            case "RELATIONSHIP" -> TownNpcSchedules.CAFE;
            case "WELLBEING" -> TownNpcSchedules.PARK;
            default -> TownNpcSchedules.PLAZA;
        };
        // 七成照人设去处走，三成随手换一个地方——活动不该场场都在同一个地方。
        return rng.nextDouble() < 0.7 ? mapped : VENUES.get(rng.nextInt(VENUES.size()));
    }

    // ---------------------------------------------------------------- SQL

    private boolean alreadyHostedToday(long userId, LocalDate localDate) {
        LocalDateTime dayStart = localDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        Integer count = jdbc.queryForObject(
            "select count(*) from town_event where town_user_id = ? and starts_at >= ? and starts_at < ?",
            Integer.class, userId, Timestamp.valueOf(dayStart), Timestamp.valueOf(dayEnd)
        );
        return count != null && count > 0;
    }

    /** 只有第二层的 6 个 NPC 会主动张罗——plan §2.4。 */
    private List<HostCandidate> hostCandidates(long userId, LocalDate localDate) {
        List<HostCandidate> candidates = new ArrayList<>();
        List<NpcSnapshot> layerTwo = jdbc.query(
            "select npc_code, display_name, dimension, share_drive from town_npc "
                + "where town_user_id = ? and layer = 2 order by npc_code",
            (rs, row) -> new NpcSnapshot(rs.getString("npc_code"), rs.getString("display_name"),
                rs.getString("dimension"), rs.getDouble("share_drive")),
            userId
        );
        Map<String, Integer> friendCounts = friendCounts(userId);
        Map<String, Double> valences = moodValences(userId, localDate);
        for (NpcSnapshot npc : layerTwo) {
            candidates.add(new HostCandidate(npc.npcCode(), npc.displayName(), npc.dimension(), npc.shareDrive(),
                friendCounts.getOrDefault(npc.npcCode(), 0), valences.getOrDefault(npc.npcCode(), 0.0)));
        }
        return candidates;
    }

    private Map<String, Integer> friendCounts(long userId) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query(
            """
                select a_ref, count(*) as cnt from town_bond
                where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC' and affinity >= ?
                group by a_ref
                """,
            rs -> { counts.put(rs.getString("a_ref"), rs.getInt("cnt")); },
            userId, FRIEND_AFFINITY_THRESHOLD
        );
        return counts;
    }

    private Map<String, Double> moodValences(long userId, LocalDate localDate) {
        Map<String, Double> valences = new HashMap<>();
        jdbc.query(
            "select npc_code, valence from town_npc_mood where town_user_id = ? and local_date = ?",
            rs -> { valences.put(rs.getString("npc_code"), rs.getDouble("valence")); },
            userId, java.sql.Date.valueOf(localDate)
        );
        return valences;
    }

    /** 玩家 + 镇上其他（非一层、非主办人）NPC 对主办人的亲密度，用来排请柬顺序。 */
    private List<Recipient> recipientCandidates(long userId, String hostCode) {
        List<Recipient> recipients = new ArrayList<>();
        Double playerAffinity = jdbc.query(
            "select affinity from town_bond where town_user_id = ? and a_kind = 'PLAYER' and b_kind = 'NPC' and b_ref = ?",
            rs -> rs.next() ? rs.getDouble(1) : null, userId, hostCode
        );
        recipients.add(new Recipient("PLAYER", "PLAYER", playerAffinity == null ? 0.0 : playerAffinity));

        List<Recipient> npcAffinities = jdbc.query(
            """
                select n.npc_code as ref, coalesce(b.affinity, 0) as affinity
                from town_npc n
                left join town_bond b on b.town_user_id = n.town_user_id and b.a_kind = 'NPC' and b.a_ref = ?
                    and b.b_kind = 'NPC' and b.b_ref = n.npc_code
                where n.town_user_id = ? and n.layer >= 2 and n.npc_code <> ?
                """,
            (rs, row) -> new Recipient("NPC", rs.getString("ref"), rs.getDouble("affinity")),
            hostCode, userId, hostCode
        );
        List<Recipient> topNpcs = rankRecipients(npcAffinities).stream().limit(MAX_NPC_INVITEES).toList();
        recipients.addAll(topNpcs);
        return recipients;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clamp01Signed(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static long seedFor(long userId, LocalDate localDate) {
        return userId * 1_000_033L + localDate.toEpochDay() + 7;
    }

    // ---------------------------------------------------------------- 行

    record HostCandidate(String npcCode, String displayName, String dimension, double shareDrive,
                         int friendCount, double moodValence) {
    }

    record Recipient(String kind, String ref, double affinity) {
    }

    private record NpcSnapshot(String npcCode, String displayName, String dimension, double shareDrive) {
    }
}
