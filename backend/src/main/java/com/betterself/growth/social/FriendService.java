package com.betterself.growth.social;

import com.betterself.growth.career.RoleProgressionService;
import com.betterself.growth.execution.TaskExecutionService;
import com.betterself.growth.insight.InsightService;
import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class FriendService {

    private static final Map<String, String> SPECIES_NAMES = Map.of(
        "CAT", "猫", "DOG", "狗", "HAMSTER", "仓鼠", "SNAKE", "蛇",
        "RABBIT", "兔子", "BIRD", "小鸟", "TURTLE", "乌龟", "FOX", "狐狸"
    );

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final InsightService insights;
    private final RoleProgressionService roleProgress;
    private final TaskExecutionService taskExecution;

    public FriendService(
        JdbcTemplate jdbc,
        PublicIdGenerator ids,
        Clock clock,
        InsightService insights,
        RoleProgressionService roleProgress,
        TaskExecutionService taskExecution
    ) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.insights = insights;
        this.roleProgress = roleProgress;
        this.taskExecution = taskExecution;
    }

    public FriendListView list(long userId) {
        List<RelationRow> rows = jdbc.query(
            """
                select f.id, f.requester_user_id, f.addressee_user_id, f.status, f.created_at,
                       u.id peer_user_id, u.public_id peer_public_id, u.display_name peer_display_name,
                       u.created_at peer_created_at
                from friend_relationship f
                join sys_user u on u.id = case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end
                where ? in (f.requester_user_id, f.addressee_user_id)
                order by f.updated_at desc
                """,
            (rs, row) -> new RelationRow(
                rs.getLong("id"), rs.getLong("requester_user_id"), rs.getLong("addressee_user_id"),
                rs.getLong("peer_user_id"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("peer_public_id"),
                rs.getString("peer_display_name"), rs.getDate("peer_created_at").toLocalDate()
            ),
            userId, userId
        );
        Map<Long, Integer> levels = levelsOf(rows.stream().map(RelationRow::peerUserId).toList());
        List<FriendItem> friends = new ArrayList<>();
        List<FriendItem> incoming = new ArrayList<>();
        List<FriendItem> outgoing = new ArrayList<>();
        for (RelationRow row : rows) {
            FriendItem item = new FriendItem(
                row.peerPublicId(), row.peerDisplayName(), levels.getOrDefault(row.peerUserId(), 1),
                row.peerCreatedAt(), row.status(), row.requesterUserId() == userId ? "OUTGOING" : "INCOMING",
                row.createdAt().atZone(ZoneId.systemDefault()).toLocalDate()
            );
            if ("ACCEPTED".equals(row.status())) {
                friends.add(item);
            } else if (row.addresseeUserId() == userId) {
                incoming.add(item);
            } else {
                outgoing.add(item);
            }
        }
        return new FriendListView(friends, incoming, outgoing);
    }

    @Transactional
    public FriendItem request(long userId, String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FRIEND_EMAIL", "请输入好友的注册邮箱");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        UserRow target = jdbc.query(
            """
                select id, public_id, display_name, created_at
                from sys_user where email_normalized = ? and status = 'ACTIVE' and deleted_at is null
                """,
            rs -> rs.next()
                ? new UserRow(rs.getLong("id"), rs.getString("public_id"), rs.getString("display_name"), rs.getDate("created_at").toLocalDate())
                : null,
            normalized
        );
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FRIEND_USER_NOT_FOUND", "没有找到该邮箱对应的用户");
        }
        if (target.id() == userId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FRIEND_SELF_REQUEST", "不能添加自己为好友");
        }
        RelationRow existing = relation(userId, target.id());
        if (existing != null) {
            if ("ACCEPTED".equals(existing.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "FRIENDS_ALREADY", "你们已经是好友了");
            }
            if (existing.addresseeUserId() == userId) {
                jdbc.update(
                    "update friend_relationship set status = 'ACCEPTED', updated_at = ? where requester_user_id = ? and addressee_user_id = ?",
                    Timestamp.from(clock.instant()), target.id(), userId
                );
                return new FriendItem(target.publicId(), target.displayName(), levelOf(target.id()),
                    target.createdAt(), "ACCEPTED", "OUTGOING", LocalDate.now(clock));
            }
            throw new ApiException(HttpStatus.CONFLICT, "FRIEND_REQUEST_EXISTS", "好友申请已发送，等待对方接受");
        }
        String publicId = ids.next();
        try {
            jdbc.update(
                """
                    insert into friend_relationship (public_id, pair_key, requester_user_id, addressee_user_id, status)
                    values (?, ?, ?, ?, 'PENDING')
                    """,
                publicId, pairKey(userId, target.id()), userId, target.id()
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "FRIEND_REQUEST_EXISTS", "好友申请已发送，等待对方接受");
        }
        return new FriendItem(target.publicId(), target.displayName(), levelOf(target.id()),
            target.createdAt(), "PENDING", "OUTGOING", LocalDate.now(clock));
    }

    @Transactional
    public FriendItem accept(long userId, String peerPublicId) {
        RelationRow row = relationWithPeer(userId, peerPublicId);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FRIEND_REQUEST_NOT_FOUND", "没有找到该好友申请");
        }
        if ("ACCEPTED".equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "FRIENDS_ALREADY", "你们已经是好友了");
        }
        if (row.addresseeUserId() != userId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FRIEND_REQUEST_NOT_YOURS", "该申请不是发送给你的");
        }
        jdbc.update(
            "update friend_relationship set status = 'ACCEPTED', updated_at = ? where id = ?",
            Timestamp.from(clock.instant()), row.id()
        );
        return new FriendItem(row.peerPublicId(), row.peerDisplayName(), levelOf(row.peerUserId()),
            row.peerCreatedAt(), "ACCEPTED", "INCOMING", LocalDate.now(clock));
    }

    @Transactional
    public void reject(long userId, String peerPublicId) {
        RelationRow row = relationWithPeer(userId, peerPublicId);
        if (row == null || !"PENDING".equals(row.status()) || row.addresseeUserId() != userId) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FRIEND_REQUEST_NOT_FOUND", "没有找到该好友申请");
        }
        jdbc.update("delete from friend_relationship where id = ?", row.id());
    }

    @Transactional
    public void remove(long userId, String peerPublicId) {
        RelationRow row = relationWithPeer(userId, peerPublicId);
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", "该好友关系不存在");
        }
        jdbc.update("delete from friend_relationship where id = ?", row.id());
    }

    public FriendProfileView detail(long userId, String peerPublicId) {
        RelationRow row = relationWithPeer(userId, peerPublicId);
        if (row == null || !"ACCEPTED".equals(row.status())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FRIENDSHIP_NOT_FOUND", "只能查看好友的资料");
        }
        long peerId = row.peerUserId();
        InsightService.AttributesOverview attributes = insights.attributes(peerId);
        InsightService.Overview overview = insights.overview(peerId);
        List<RoleView> roles = roleProgress.list(peerId).stream()
            .map(role -> new RoleView(role.roleCode(), role.roleName(), role.level()))
            .toList();
        PetView pet = selectedPet(peerId);
        ZoneId zone = userZone(peerId);
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        List<TaskView> todayTasks = taskExecution.schedules(peerId, today).stream()
            .map(schedule -> new TaskView(
                schedule.publicId(), schedule.taskTitle(), schedule.status(), schedule.roleName(),
                schedule.plannedStartAt()
            ))
            .toList();
        List<AttributeView> attributeViews = attributes.attributes().stream()
            .map(item -> new AttributeView(
                item.code(), item.name(), item.dimensionName(), item.experience(), item.level(), item.radarScore()
            ))
            .toList();
        return new FriendProfileView(
            row.peerPublicId(), row.peerDisplayName(), row.peerCreatedAt(),
            attributes.overallLevel(), attributes.totalExperience(),
            new OverviewView(
                overview.effectiveActions(), overview.fulfillmentRate(),
                overview.recoveryCount(), overview.totalExperience()
            ),
            longestActionStreak(peerId), roles, pet, attributeViews, todayTasks
        );
    }

    private int longestActionStreak(long userId) {
        List<LocalDate> activeDates = jdbc.queryForList(
            """
                select distinct s.local_date from task_event e join task_schedule s on s.id = e.schedule_id
                where e.user_id = ? and e.event_type in ('COMPLETED','PARTIAL')
                  and not exists (select 1 from task_event r where r.reverses_event_id = e.id)
                order by s.local_date
                """,
            LocalDate.class, userId
        );
        int longest = 0;
        int current = 0;
        LocalDate previous = null;
        for (LocalDate date : activeDates) {
            if (previous != null && ChronoUnit.DAYS.between(previous, date) == 1) {
                current += 1;
            } else {
                current = 1;
            }
            longest = Math.max(longest, current);
            previous = date;
        }
        return longest;
    }

    private PetView selectedPet(long userId) {
        return jdbc.query(
            """
                select species_code, name, breed, fur_color, level, affection
                from partner_pet where user_id = ? order by selected desc, id limit 1
                """,
            rs -> rs.next() ? new PetView(
                rs.getString("species_code"), speciesName(rs.getString("species_code")),
                rs.getString("name"), rs.getString("breed"), rs.getString("fur_color"),
                rs.getInt("level"), rs.getInt("affection"), requirement(rs.getInt("level"))
            ) : null,
            userId
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

    private int levelOf(long userId) {
        Integer experience = jdbc.queryForObject(
            "select coalesce(sum(experience), 0) from user_dimension where user_id = ?",
            Integer.class, userId
        );
        return levelFor(experience == null ? 0 : experience);
    }

    private static int levelFor(int experience) {
        return Math.max(1, Math.min(20, (int) Math.floor(Math.sqrt(Math.max(0, experience)) / 10) + 1));
    }

    private int requirement(int level) {
        return level >= 20 ? 999 : level * 10;
    }

    private String speciesName(String code) {
        return SPECIES_NAMES.getOrDefault(code, code);
    }

    private RelationRow relation(long userId, long peerUserId) {
        return jdbc.query(
            """
                select f.id, f.requester_user_id, f.addressee_user_id, f.status, f.created_at,
                       ? peer_user_id, u.public_id peer_public_id, u.display_name peer_display_name, u.created_at peer_created_at
                from friend_relationship f
                join sys_user u on u.id = ?
                where f.pair_key = ?
                """,
            rs -> rs.next() ? new RelationRow(
                rs.getLong("id"), rs.getLong("requester_user_id"), rs.getLong("addressee_user_id"),
                rs.getLong("peer_user_id"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("peer_public_id"),
                rs.getString("peer_display_name"), rs.getDate("peer_created_at").toLocalDate()
            ) : null,
            peerUserId, peerUserId, pairKey(userId, peerUserId)
        );
    }

    private RelationRow relationWithPeer(long userId, String peerPublicId) {
        return jdbc.query(
            """
                select f.id, f.requester_user_id, f.addressee_user_id, f.status, f.created_at,
                       u.id peer_user_id, u.public_id peer_public_id, u.display_name peer_display_name,
                       u.created_at peer_created_at
                from friend_relationship f
                join sys_user u on u.id = case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end
                where ? in (f.requester_user_id, f.addressee_user_id) and u.public_id = ?
                """,
            rs -> rs.next() ? new RelationRow(
                rs.getLong("id"), rs.getLong("requester_user_id"), rs.getLong("addressee_user_id"),
                rs.getLong("peer_user_id"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("peer_public_id"),
                rs.getString("peer_display_name"), rs.getDate("peer_created_at").toLocalDate()
            ) : null,
            userId, userId, peerPublicId
        );
    }

    private ZoneId userZone(long userId) {
        String timezone = jdbc.queryForObject(
            "select timezone from sys_user where id = ?",
            String.class, userId
        );
        return ZoneId.of(timezone);
    }

    private static String pairKey(long first, long second) {
        return Math.min(first, second) + ":" + Math.max(first, second);
    }

    private record RelationRow(
        long id,
        long requesterUserId,
        long addresseeUserId,
        long peerUserId,
        String status,
        Instant createdAt,
        String peerPublicId,
        String peerDisplayName,
        LocalDate peerCreatedAt
    ) {
    }

    private record UserRow(long id, String publicId, String displayName, LocalDate createdAt) {
    }

    public record FriendListView(List<FriendItem> friends, List<FriendItem> incoming, List<FriendItem> outgoing) {
    }

    public record RequestCommand(String email) {
    }

    public record FriendItem(
        String publicId,
        String displayName,
        int overallLevel,
        LocalDate memberSince,
        String status,
        String direction,
        LocalDate createdAt
    ) {
    }

    public record FriendProfileView(
        String publicId,
        String displayName,
        LocalDate memberSince,
        int overallLevel,
        int totalExperience,
        OverviewView overview,
        int longestStreak,
        List<RoleView> roles,
        PetView pet,
        List<AttributeView> attributes,
        List<TaskView> todayTasks
    ) {
    }

    public record OverviewView(int effectiveActions, BigDecimal fulfillmentRate, int recoveryCount, int totalExperience) {
    }

    public record RoleView(String roleCode, String roleName, int level) {
    }

    public record PetView(
        String speciesCode,
        String speciesName,
        String name,
        String breed,
        String furColor,
        int level,
        int affection,
        int nextLevelAffection
    ) {
    }

    public record AttributeView(
        String code,
        String name,
        String dimensionName,
        int experience,
        int level,
        int radarScore
    ) {
    }

    public record TaskView(String publicId, String title, String status, String roleName, Instant plannedStartAt) {
    }
}
