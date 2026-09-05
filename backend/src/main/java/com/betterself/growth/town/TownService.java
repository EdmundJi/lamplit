package com.betterself.growth.town;

import com.betterself.growth.achievement.TitleService;
import com.betterself.growth.execution.TaskExecutionService;
import com.betterself.growth.insight.InsightService;
import com.betterself.growth.social.FriendGroupService;
import com.betterself.growth.social.FriendService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Read model for 成长小镇: the caller plus accepted, non-solo friends, each with the
 * numbers that shape their building and today's schedule that drives their avatar.
 */
@Service
public class TownService {

    static final Set<String> DIMENSIONS = Set.of("KNOWLEDGE", "HEALTH", "CAREER", "RELATIONSHIP", "WELLBEING");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InsightService insights;
    private final FriendService friends;
    private final FriendGroupService groups;
    private final TitleService titles;
    private final TaskExecutionService taskExecution;
    private final TownPresenceService presence;

    public TownService(
        JdbcTemplate jdbc,
        Clock clock,
        InsightService insights,
        FriendService friends,
        FriendGroupService groups,
        TitleService titles,
        TaskExecutionService taskExecution,
        TownPresenceService presence
    ) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.insights = insights;
        this.friends = friends;
        this.groups = groups;
        this.titles = titles;
        this.taskExecution = taskExecution;
        this.presence = presence;
    }

    public TownView town(long userId) {
        UserRow self = user(userId);
        ZoneId zone = ZoneId.of(self.timezone());
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        List<Resident> residents = new ArrayList<>();
        TitleService.TitleView title = titles.equipped(userId);
        residents.add(resident(self, true, title == null ? null : title.name()));
        if (!self.soloGrowth()) {
            for (UserRow friend : acceptedFriends(userId)) {
                residents.add(resident(friend, false, null));
            }
        }
        int unread = groups.unreadSummary(userId).totalUnread();
        return new TownView(today, clock.instant(), unread, self.soloGrowth(), residents);
    }

    /** Today's schedule for one user, in that user's own timezone. */
    public List<ScheduleItem> todaySchedules(long userId, ZoneId zone) {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        return taskExecution.schedules(userId, today).stream()
            .map(item -> new ScheduleItem(
                item.publicId(), item.taskTitle(), item.status(), item.roleCode(), item.roleName(),
                item.plannedStartAt(), item.plannedEndAt(), item.estimatedMinutes(), item.difficulty()
            ))
            .toList();
    }

    /** How many friend-group messages this user hasn't read yet — the postman's one legible signal. */
    public int unreadCount(long userId) {
        return groups.unreadSummary(userId).totalUnread();
    }

    public UserRow user(long userId) {
        return jdbc.queryForObject(
            """
                select u.id, u.public_id, u.display_name, u.timezone, up.solo_growth
                from sys_user u join user_preference up on up.user_id = u.id
                where u.id = ?
                """,
            (rs, row) -> new UserRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("display_name"),
                rs.getString("timezone"), rs.getBoolean("solo_growth")
            ),
            userId
        );
    }

    private List<UserRow> acceptedFriends(long userId) {
        return jdbc.query(
            """
                select u.id, u.public_id, u.display_name, u.timezone, up.solo_growth
                from friend_relationship f
                join sys_user u on u.id = case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end
                join user_preference up on up.user_id = u.id
                where ? in (f.requester_user_id, f.addressee_user_id)
                  and f.status = 'ACCEPTED' and up.solo_growth = 0
                  and u.status = 'ACTIVE' and u.deleted_at is null
                order by f.updated_at
                """,
            (rs, row) -> new UserRow(
                rs.getLong("id"), rs.getString("public_id"), rs.getString("display_name"),
                rs.getString("timezone"), rs.getBoolean("solo_growth")
            ),
            userId, userId
        );
    }

    private Resident resident(UserRow user, boolean self, String title) {
        InsightService.AttributesOverview attributes = insights.attributes(user.id());
        String dominant = attributes.attributes().stream()
            .filter(item -> DIMENSIONS.contains(item.code()) && item.experience() > 0)
            .max(Comparator.comparingInt(InsightService.AttributeView::experience))
            .map(InsightService.AttributeView::code)
            .orElse(null);
        return new Resident(
            user.publicId(), user.displayName(), attributes.overallLevel(), attributes.totalExperience(),
            dominant, friends.longestActionStreak(user.id()), title, self, user.timezone(),
            todaySchedules(user.id(), ZoneId.of(user.timezone())), presence.latest(user.id())
        );
    }

    public record UserRow(long id, String publicId, String displayName, String timezone, boolean soloGrowth) {
    }

    public record TownView(LocalDate localDate, Instant serverTime, int unread, boolean soloGrowth, List<Resident> residents) {
    }

    public record Resident(
        String publicId,
        String displayName,
        int level,
        int totalExperience,
        String dominantDimension,
        int longestStreak,
        String title,
        boolean self,
        String timezone,
        List<ScheduleItem> schedules,
        TownPresenceService.PresenceView presence
    ) {
    }

    public record ScheduleItem(
        String publicId,
        String title,
        String status,
        String roleCode,
        String roleName,
        Instant plannedStartAt,
        Instant plannedEndAt,
        int estimatedMinutes,
        int difficulty
    ) {
    }
}
