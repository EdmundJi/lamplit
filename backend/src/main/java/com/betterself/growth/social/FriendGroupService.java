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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class FriendGroupService {

    private static final int MAX_BODY_LENGTH = 1000;
    private static final int MAX_MEMBERS = 10;

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final FriendService friends;

    public FriendGroupService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock, FriendService friends) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.friends = friends;
    }

    @Transactional
    public GroupView create(long userId, String name, List<String> memberPublicIds) {
        if (name == null || name.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_NAME", "群聊名称不能为空");
        }
        if (name.trim().length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_NAME", "群聊名称最多 80 个字符");
        }
        List<String> members = memberPublicIds == null ? List.of() : memberPublicIds.stream()
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        if (members.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GROUP_MEMBERS", "至少选择一位好友");
        }
        if (members.size() > MAX_MEMBERS - 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GROUP_MEMBER_LIMIT", "群聊最多 " + MAX_MEMBERS + " 人（含自己）");
        }
        List<Long> peerIds = new ArrayList<>();
        for (String memberPublicId : members) {
            peerIds.add(friends.requirePeerId(userId, memberPublicId));
        }
        String publicId = ids.next();
        Instant now = clock.instant();
        jdbc.update(
            "insert into friend_group (public_id, name, owner_user_id, created_at, updated_at) values (?, ?, ?, ?, ?)",
            publicId, name.trim(), userId, Timestamp.from(now), Timestamp.from(now)
        );
        long groupId = jdbc.queryForObject(
            "select id from friend_group where public_id = ?", Long.class, publicId
        );
        jdbc.update(
            "insert into friend_group_member (group_id, user_id, last_read_at) values (?, ?, ?)",
            groupId, userId, Timestamp.from(now)
        );
        for (Long peerId : peerIds) {
            jdbc.update(
                "insert into friend_group_member (group_id, user_id, last_read_at) values (?, ?, ?)",
                groupId, peerId, Timestamp.from(now)
            );
        }
        return group(userId, publicId);
    }

    public List<GroupConversationView> list(long userId) {
        return jdbc.query(
            """
                select g.id, g.public_id, g.name,
                       (select m.body from friend_group_message m
                        where m.group_id = g.id order by m.created_at desc, m.id desc limit 1) last_body,
                       (select m.sender_user_id from friend_group_message m
                        where m.group_id = g.id order by m.created_at desc, m.id desc limit 1) last_sender,
                       (select m.created_at from friend_group_message m
                        where m.group_id = g.id order by m.created_at desc, m.id desc limit 1) last_at,
                       (select count(*) from friend_group_message m
                        where m.group_id = g.id and m.sender_user_id != ? and m.created_at > coalesce(mem.last_read_at, ?)) unread,
                       (select count(*) from friend_group_member cm where cm.group_id = g.id) member_count
                from friend_group_member mem
                join friend_group g on g.id = mem.group_id
                where mem.user_id = ?
                order by g.updated_at desc
                """,
            (rs, row) -> new GroupConversationView(
                rs.getString("public_id"), rs.getString("name"),
                rs.getString("last_body"), rs.getLong("last_sender"), rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant(),
                rs.getInt("unread"), rs.getInt("member_count")
            ),
            userId, Timestamp.from(Instant.EPOCH), userId
        );
    }

    public GroupView group(long userId, String publicId) {
        GroupRow row = jdbc.query(
            """
                select g.id, g.public_id, g.name, g.owner_user_id, g.created_at
                from friend_group g
                join friend_group_member mem on mem.group_id = g.id
                where g.public_id = ? and mem.user_id = ?
                """,
            rs -> rs.next() ? new GroupRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("name"),
                rs.getLong("owner_user_id"), rs.getTimestamp("created_at").toInstant()
            ) : null,
            publicId, userId
        );
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群聊不存在或你不在群内");
        }
        List<MemberRow> members = jdbc.query(
            """
                select u.id, u.public_id, u.display_name, mem.joined_at
                from friend_group_member mem
                join sys_user u on u.id = mem.user_id
                where mem.group_id = ?
                order by mem.joined_at
                """,
            (rs, item) -> new MemberRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("display_name"),
                rs.getTimestamp("joined_at").toInstant()
            ),
            row.id()
        );
        Map<Long, Integer> levels = levelsOf(members.stream().map(MemberRow::userId).toList());
        List<GroupMemberView> memberViews = members.stream()
            .map(member -> new GroupMemberView(
                member.publicId(), member.displayName(), levels.getOrDefault(member.userId(), 1),
                member.userId() == row.ownerUserId(), member.joinedAt()
            ))
            .toList();
        return new GroupView(row.publicId(), row.name(), memberViews);
    }

    public List<GroupMessageView> messages(long userId, String groupPublicId, String beforePublicId, int limit) {
        long groupId = requireMember(userId, groupPublicId);
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 100));
        List<GroupMessageRow> rows = jdbc.query(
            """
                select m.public_id, m.body, m.sender_user_id, m.created_at, u.display_name sender_name
                from friend_group_message m
                join sys_user u on u.id = m.sender_user_id
                where m.group_id = ? and (? is null or m.public_id < ?)
                order by m.created_at desc, m.id desc
                limit ?
                """,
            (rs, item) -> new GroupMessageRow(
                rs.getString("public_id"), rs.getString("body"), rs.getLong("sender_user_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("sender_name")
            ),
            groupId, beforePublicId, beforePublicId, pageSize
        );
        Collections.reverse(rows);
        return rows.stream()
            .map(row -> new GroupMessageView(
                row.publicId(), row.body(), row.senderUserId() == userId, row.senderName(), row.createdAt()
            ))
            .toList();
    }

    @Transactional
    public GroupMessageView send(long userId, String groupPublicId, String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_BODY", "消息内容不能为空");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE_BODY", "消息内容最多 1000 个字符");
        }
        long groupId = requireMember(userId, groupPublicId);
        String publicId = ids.next();
        Instant now = clock.instant();
        String senderName = jdbc.queryForObject(
            "select display_name from sys_user where id = ?", String.class, userId
        );
        jdbc.update(
            "insert into friend_group_message (public_id, group_id, sender_user_id, body, created_at) values (?, ?, ?, ?, ?)",
            publicId, groupId, userId, body.trim(), Timestamp.from(now)
        );
        jdbc.update(
            "update friend_group set updated_at = ? where id = ?", Timestamp.from(now), groupId
        );
        return new GroupMessageView(publicId, body.trim(), true, senderName, now);
    }

    @Transactional
    public void markRead(long userId, String groupPublicId) {
        long groupId = requireMember(userId, groupPublicId);
        jdbc.update(
            "update friend_group_member set last_read_at = ? where group_id = ? and user_id = ?",
            Timestamp.from(clock.instant()), groupId, userId
        );
    }

    public UnreadSummaryView unreadSummary(long userId) {
        Integer single = jdbc.queryForObject(
            """
                select count(*) from friend_message
                where receiver_user_id = ? and read_at is null
                """,
            Integer.class, userId
        );
        Integer grouped = jdbc.queryForObject(
            """
                select count(*) from friend_group_message m
                join friend_group_member mem on mem.group_id = m.group_id and mem.user_id = ?
                where m.sender_user_id != ? and m.created_at > coalesce(mem.last_read_at, ?)
                """,
            Integer.class, userId, userId, Timestamp.from(Instant.EPOCH)
        );
        int total = (single == null ? 0 : single) + (grouped == null ? 0 : grouped);
        LatestUnreadRow latest = latestUnread(userId);
        if (latest == null) {
            return new UnreadSummaryView(total, null, null, null);
        }
        return new UnreadSummaryView(total, latest.kind(), latest.publicId(), latest.displayName());
    }

    private LatestUnreadRow latestUnread(long userId) {
        LatestUnreadRow single = jdbc.query(
            """
                select 'single' kind, u.public_id, u.display_name, m.created_at
                from friend_message m
                join sys_user u on u.id = m.sender_user_id
                where m.receiver_user_id = ? and m.read_at is null
                order by m.created_at desc, m.id desc limit 1
                """,
            rs -> rs.next() ? new LatestUnreadRow(
                rs.getString("kind"), rs.getString("public_id"), rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant()
            ) : null,
            userId
        );
        LatestUnreadRow grouped = jdbc.query(
            """
                select 'group' kind, g.public_id, g.name display_name, m.created_at
                from friend_group_message m
                join friend_group g on g.id = m.group_id
                join friend_group_member mem on mem.group_id = m.group_id and mem.user_id = ?
                where m.sender_user_id != ? and m.created_at > coalesce(mem.last_read_at, ?)
                order by m.created_at desc, m.id desc limit 1
                """,
            rs -> rs.next() ? new LatestUnreadRow(
                rs.getString("kind"), rs.getString("public_id"), rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant()
            ) : null,
            userId, userId, Timestamp.from(Instant.EPOCH)
        );
        if (single == null) {
            return grouped;
        }
        if (grouped == null) {
            return single;
        }
        return grouped.createdAt().isAfter(single.createdAt()) ? grouped : single;
    }

    private long requireMember(long userId, String groupPublicId) {
        Long groupId = jdbc.query(
            """
                select mem.group_id from friend_group_member mem
                join friend_group g on g.id = mem.group_id
                where g.public_id = ? and mem.user_id = ?
                """,
            rs -> rs.next() ? rs.getLong(1) : null,
            groupPublicId, userId
        );
        if (groupId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群聊不存在或你不在群内");
        }
        return groupId;
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

    private record GroupRow(long id, String publicId, String name, long ownerUserId, Instant createdAt) {
    }

    private record LatestUnreadRow(String kind, String publicId, String displayName, Instant createdAt) {
    }

    private record MemberRow(long userId, String publicId, String displayName, Instant joinedAt) {
    }

    private record GroupMessageRow(String publicId, String body, long senderUserId, Instant createdAt, String senderName) {
    }

    public record GroupView(String publicId, String name, List<GroupMemberView> members) {
    }

    public record GroupMemberView(String publicId, String displayName, int level, boolean owner, Instant joinedAt) {
    }

    public record GroupConversationView(
        String publicId,
        String name,
        String lastMessage,
        long lastSenderId,
        Instant lastMessageAt,
        int unreadCount,
        int memberCount
    ) {
    }

    public record GroupMessageView(String publicId, String body, boolean fromMe, String senderName, Instant createdAt) {
    }

    public record UnreadSummaryView(int totalUnread, String kind, String publicId, String displayName) {
    }

    public record CreateGroupCommand(String name, List<String> memberPublicIds) {
    }

    public record SendGroupMessageCommand(String body) {
    }
}
