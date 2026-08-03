package com.betterself.growth.social;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FriendMessageService {

    private static final int MAX_BODY_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final FriendService friends;

    public FriendMessageService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock, FriendService friends) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.friends = friends;
    }

    public List<ConversationView> conversations(long userId) {
        List<ConversationRow> rows = jdbc.query(
            """
                select u.id peer_user_id, u.public_id peer_public_id, u.display_name peer_display_name,
                       (select m.body from friend_message m
                        where (m.sender_user_id = ? and m.receiver_user_id = u.id)
                           or (m.sender_user_id = u.id and m.receiver_user_id = ?)
                        order by m.created_at desc, m.id desc limit 1) last_body,
                       (select m.sender_user_id from friend_message m
                        where (m.sender_user_id = ? and m.receiver_user_id = u.id)
                           or (m.sender_user_id = u.id and m.receiver_user_id = ?)
                        order by m.created_at desc, m.id desc limit 1) last_sender,
                       (select m.created_at from friend_message m
                        where (m.sender_user_id = ? and m.receiver_user_id = u.id)
                           or (m.sender_user_id = u.id and m.receiver_user_id = ?)
                        order by m.created_at desc, m.id desc limit 1) last_at,
                       (select count(*) from friend_message m
                        where m.sender_user_id = u.id and m.receiver_user_id = ? and m.read_at is null) unread
                from friend_relationship fr
                join sys_user u on u.id = case when fr.requester_user_id = ? then fr.addressee_user_id else fr.requester_user_id end
                where ? in (fr.requester_user_id, fr.addressee_user_id) and fr.status = 'ACCEPTED'
                """,
            (rs, row) -> new ConversationRow(
                rs.getLong("peer_user_id"), rs.getString("peer_public_id"), rs.getString("peer_display_name"),
                rs.getString("last_body"), rs.getLong("last_sender"), rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant(),
                rs.getInt("unread")
            ),
            userId, userId, userId, userId, userId, userId, userId, userId, userId
        );
        Map<Long, Integer> levels = levelsOf(rows.stream().map(ConversationRow::peerUserId).toList());
        List<ConversationView> result = new ArrayList<>();
        for (ConversationRow row : rows) {
            if (row.lastAt() == null) {
                continue;
            }
            result.add(new ConversationView(
                row.peerPublicId(), row.peerDisplayName(), levels.getOrDefault(row.peerUserId(), 1),
                row.lastBody(), row.lastAt(), row.lastSender() == userId, row.unread()
            ));
        }
        result.sort((a, b) -> b.lastMessageAt().compareTo(a.lastMessageAt()));
        return result;
    }

    public List<MessageView> messages(long userId, String peerPublicId, String beforePublicId, int limit) {
        long peerId = friends.requirePeerId(userId, peerPublicId);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        List<MessageRow> rows = jdbc.query(
            """
                select m.public_id, m.body, m.sender_user_id, m.created_at, m.read_at
                from friend_message m
                where ((m.sender_user_id = ? and m.receiver_user_id = ?) or (m.sender_user_id = ? and m.receiver_user_id = ?))
                  and (? is null or m.public_id < ?)
                order by m.created_at desc, m.id desc
                limit ?
                """,
            (rs, row) -> new MessageRow(
                rs.getString("public_id"), rs.getString("body"), rs.getLong("sender_user_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant()
            ),
            userId, peerId, peerId, userId, beforePublicId, beforePublicId, pageSize
        );
        Collections.reverse(rows);
        return rows.stream()
            .map(row -> new MessageView(row.publicId(), row.body(), row.senderUserId() == userId, row.createdAt(), row.readAt() != null))
            .toList();
    }

    @Transactional
    public MessageView send(long userId, String peerPublicId, String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_BODY", "消息内容不能为空");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_BODY", "消息内容最多 1000 个字符");
        }
        long peerId = friends.requirePeerId(userId, peerPublicId);
        String publicId = ids.next();
        Instant now = clock.instant();
        jdbc.update(
            """
                insert into friend_message (public_id, sender_user_id, receiver_user_id, body, created_at)
                values (?, ?, ?, ?, ?)
                """,
            publicId, userId, peerId, body.trim(), Timestamp.from(now)
        );
        return new MessageView(publicId, body.trim(), true, now, false);
    }

    @Transactional
    public void markRead(long userId, String peerPublicId) {
        long peerId = friends.requirePeerId(userId, peerPublicId);
        jdbc.update(
            """
                update friend_message set read_at = ?
                where receiver_user_id = ? and sender_user_id = ? and read_at is null
                """,
            Timestamp.from(clock.instant()), userId, peerId
        );
    }

    private Map<Long, Integer> levelsOf(List<Long> userIds) {
        Map<Long, Integer> levels = new HashMap<>();
        if (userIds.isEmpty()) {
            return levels;
        }
        String placeholders = String.join(",", userIds.stream().map(id -> "?").toList());
        jdbc.query(
            """
                select user_id, coalesce(sum(experience), 0) experience
                from user_dimension where user_id in (%s) group by user_id
                """.formatted(placeholders),
            rs -> {
                while (rs.next()) {
                    levels.put(rs.getLong("user_id"), levelFor(rs.getInt("experience")));
                }
                return null;
            },
            userIds.toArray()
        );
        for (Long userId : userIds) {
            levels.putIfAbsent(userId, 1);
        }
        return levels;
    }

    private static int levelFor(int experience) {
        return Math.max(1, Math.min(20, (int) Math.floor(Math.sqrt(Math.max(0, experience)) / 10) + 1));
    }

    private record ConversationRow(
        long peerUserId, String peerPublicId, String peerDisplayName,
        String lastBody, long lastSender, Instant lastAt, int unread
    ) {
    }

    private record MessageRow(String publicId, String body, long senderUserId, Instant createdAt, Instant readAt) {
    }

    public record ConversationView(
        String peerPublicId,
        String peerDisplayName,
        int peerLevel,
        String lastMessage,
        Instant lastMessageAt,
        boolean lastMessageFromMe,
        int unreadCount
    ) {
    }

    public record MessageView(String publicId, String body, boolean fromMe, Instant createdAt, boolean read) {
    }

    public record SendMessageCommand(String peerPublicId, String body) {
    }

    public record ReadMessagesCommand(String peerPublicId) {
    }
}
