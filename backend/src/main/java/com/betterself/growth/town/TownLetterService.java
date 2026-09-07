package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 邮递员的收发台：树洞长信 / NPC 短笺 / 活动请柬三轨的共同出口（plan §2.3 D15、§2.8）。
 *
 * <p>这张表只管"送到了什么"，不产生也不消费任何游戏数值——plan §5「明确不做」D5：信件不给经验、
 * 不给成就。读信、回信都只是叙事，落库的字段里也确实没有一个能兑换成奖励的。
 */
@Service
public class TownLetterService {

    private static final Set<String> SENDER_KINDS = Set.of("CONFIDANT", "NPC");
    private static final Set<String> LETTER_KINDS = Set.of("LONG", "NOTE", "INVITE");

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public TownLetterService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    /** 投递一封信。数据库有 CHECK 约束兜底，这里提前拦一次是为了让调用方的笔误在单测里就炸出来。 */
    public void deliver(long recipientUserId, String senderKind, String senderRef, String kind, String body,
                        LocalDateTime deliverAt) {
        if (!SENDER_KINDS.contains(senderKind)) {
            throw new IllegalArgumentException("unknown town letter sender kind: " + senderKind);
        }
        if (!LETTER_KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown town letter kind: " + kind);
        }
        if (("CONFIDANT".equals(senderKind) && !"LONG".equals(kind))
            || ("NPC".equals(senderKind) && "LONG".equals(kind))) {
            throw new IllegalArgumentException("town letter sender and track mismatch");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("town letter body must not be blank");
        }
        jdbc.update(
            """
                insert into town_letter
                    (public_id, recipient_user_id, sender_kind, sender_ref, kind, body, deliver_at, read_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, null, ?)
                """,
            ids.next(), recipientUserId, senderKind, senderRef, kind, body,
            Timestamp.from(deliverAt.atZone(clock.getZone()).toInstant()), Timestamp.from(clock.instant())
        );
    }

    /** Map badges use this projection so polling never fetches private letter bodies. */
    public UnreadCountView unreadCount(long userId) {
        Integer count = jdbc.queryForObject("""
            select count(*) from town_letter
            where recipient_user_id = ? and deliver_at <= ? and read_at is null
            """, Integer.class, userId, Timestamp.from(clock.instant()));
        return new UnreadCountView(count == null ? 0 : count);
    }

    public record UnreadCountView(int unreadCount) {}

    /** {@code GET /api/v1/town/letters}：只看已经到了投递时间的信，含未读数。 */
    public LetterInboxView inbox(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<LetterRow> rows = jdbc.query(
            """
                select public_id, sender_kind, sender_ref, kind, body, deliver_at, read_at, created_at
                from town_letter
                where recipient_user_id = ? and deliver_at <= ?
                order by deliver_at desc, id desc
                """,
            (rs, row) -> new LetterRow(
                rs.getString("public_id"), rs.getString("sender_kind"), rs.getString("sender_ref"),
                rs.getString("kind"), rs.getString("body"),
                rs.getTimestamp("deliver_at").toInstant(),
                rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
            ),
            userId, Timestamp.from(clock.instant())
        );

        Map<String, String> npcNames = npcDisplayNames(userId, rows);
        List<LetterView> views = new ArrayList<>(rows.size());
        int unread = 0;
        for (LetterRow row : rows) {
            if (row.readAt() == null) {
                unread++;
            }
            views.add(new LetterView(
                row.publicId(), row.senderKind(), row.senderRef(), senderName(row, npcNames),
                row.kind(), row.body(), row.deliverAt(), row.readAt(), row.createdAt()
            ));
        }
        return new LetterInboxView(views, unread);
    }

    /** {@code POST /api/v1/town/letters/{id}/read}：幂等——已读的信再读一次不报错也不重复计数。 */
    public void markRead(long userId, String letterPublicId) {
        Integer exists = jdbc.queryForObject(
            "select count(*) from town_letter where public_id = ? and recipient_user_id = ? and deliver_at <= ?",
            Integer.class, letterPublicId, userId, Timestamp.from(clock.instant())
        );
        if (exists == null || exists == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_LETTER_NOT_FOUND", "没有这封信");
        }
        jdbc.update(
            "update town_letter set read_at = ? where public_id = ? and recipient_user_id = ? and read_at is null",
            Timestamp.from(clock.instant()), letterPublicId, userId
        );
    }

    private String senderName(LetterRow row, Map<String, String> npcNames) {
        if ("CONFIDANT".equals(row.senderKind())) {
            return "树洞笔友";
        }
        return npcNames.getOrDefault(row.senderRef(), row.senderRef());
    }

    private Map<String, String> npcDisplayNames(long userId, List<LetterRow> rows) {
        List<String> codes = rows.stream()
            .filter(row -> "NPC".equals(row.senderKind()) && row.senderRef() != null)
            .map(LetterRow::senderRef)
            .distinct()
            .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        String placeholders = String.join(",", codes.stream().map(code -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(userId);
        args.addAll(codes);
        jdbc.query(
            "select npc_code, display_name from town_npc where town_user_id = ? and npc_code in (" + placeholders + ")",
            rs -> { names.put(rs.getString("npc_code"), rs.getString("display_name")); },
            args.toArray()
        );
        return names;
    }

    private record LetterRow(String publicId, String senderKind, String senderRef, String kind, String body,
                             Instant deliverAt, Instant readAt, Instant createdAt) {
    }

    public record LetterView(String publicId, String senderKind, String senderRef, String senderName, String kind,
                             String body, Instant deliverAt, Instant readAt, Instant createdAt) {
    }

    public record LetterInboxView(List<LetterView> letters, int unreadCount) {
    }
}
