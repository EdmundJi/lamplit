package com.betterself.growth.goal;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class GoalService {

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public GoalService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    public List<DimensionView> dimensions(long userId) {
        return jdbc.query(
            """
                select public_id, code, name, description, is_system, archived_at
                from growth_dimension
                where owner_user_id is null or owner_user_id = ?
                order by is_system desc, id
                """,
            (rs, row) -> new DimensionView(
                rs.getString("public_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("is_system"), rs.getTimestamp("archived_at") != null
            ),
            userId
        );
    }

    @Transactional
    public DimensionView createDimension(long userId, DimensionCommand command) {
        String code = normalizeCode(command.code());
        requireText(command.name(), "INVALID_DIMENSION_NAME", "Dimension name is required");
        String publicId = ids.next();
        try {
            jdbc.update(
                "insert into growth_dimension (public_id, owner_user_id, code, name, description) values (?, ?, ?, ?, ?)",
                publicId, userId, code, command.name().trim(), command.description()
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "DIMENSION_CODE_EXISTS", "Dimension code already exists");
        }
        Long dimensionId = jdbc.queryForObject(
            "select id from growth_dimension where public_id = ? and owner_user_id = ?",
            Long.class, publicId, userId
        );
        jdbc.update("insert into user_dimension (user_id, dimension_id) values (?, ?)", userId, dimensionId);
        return dimension(userId, publicId);
    }

    @Transactional
    public DimensionView updateDimension(long userId, String publicId, DimensionCommand command) {
        requireText(command.name(), "INVALID_DIMENSION_NAME", "Dimension name is required");
        int changed = jdbc.update(
            "update growth_dimension set name = ?, description = ? where public_id = ? and owner_user_id = ? and archived_at is null",
            command.name().trim(), command.description(), publicId, userId
        );
        if (changed == 0) {
            throw notFound("DIMENSION_NOT_FOUND");
        }
        return dimension(userId, publicId);
    }

    @Transactional
    public void deleteDimension(long userId, String publicId) {
        Long dimensionId = jdbc.query(
            "select id from growth_dimension where public_id = ? and owner_user_id = ?",
            rs -> rs.next() ? rs.getLong(1) : null,
            publicId, userId
        );
        if (dimensionId == null) {
            throw notFound("DIMENSION_NOT_FOUND");
        }
        Integer used = jdbc.queryForObject(
            "select count(*) from growth_goal where user_id = ? and dimension_id = ?",
            Integer.class, userId, dimensionId
        );
        if (used != null && used > 0) {
            jdbc.update("update growth_dimension set archived_at = ? where id = ?", Timestamp.from(clock.instant()), dimensionId);
            jdbc.update("update user_dimension set active = 0, updated_at = ? where user_id = ? and dimension_id = ?",
                Timestamp.from(clock.instant()), userId, dimensionId);
            return;
        }
        jdbc.update("delete from user_dimension where user_id = ? and dimension_id = ?", userId, dimensionId);
        jdbc.update("delete from growth_dimension where id = ?", dimensionId);
    }

    @Transactional
    public GoalView createGoal(long userId, GoalCommand command) {
        LocalDate start = command.startDate() == null ? LocalDate.now(clock) : command.startDate();
        LocalDate end = command.endDate() == null ? start.plusDays(27) : command.endDate();
        if (!GoalPolicy.hasValidDuration(start, end)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GOAL_DURATION", "Goal duration must be 14 to 84 days");
        }
        requireText(command.title(), "INVALID_GOAL_TITLE", "Goal title is required");
        Long dimensionId = jdbc.query(
            """
                select id from growth_dimension
                where public_id = ? and archived_at is null and (owner_user_id is null or owner_user_id = ?)
                """,
            rs -> rs.next() ? rs.getLong(1) : null,
            command.dimensionPublicId(), userId
        );
        if (dimensionId == null) {
            throw notFound("DIMENSION_NOT_FOUND");
        }
        lockUser(userId);
        Integer active = jdbc.queryForObject(
            "select count(*) from growth_goal where user_id = ? and status = 'ACTIVE'",
            Integer.class, userId
        );
        if (!GoalPolicy.canCreateActiveGoal(active == null ? 0 : active)) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_GOAL_LIMIT", "At most three active goals are allowed");
        }
        String publicId = ids.next();
        jdbc.update(
            """
                insert into growth_goal (public_id, user_id, dimension_id, title, description, start_date, end_date, status)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """,
            publicId, userId, dimensionId, command.title().trim(), command.description(), Date.valueOf(start), Date.valueOf(end)
        );
        return goal(userId, publicId);
    }

    public List<GoalView> goals(long userId, String status) {
        String normalized = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        return jdbc.query(
            """
                select g.public_id, d.public_id dimension_public_id, g.title, g.description,
                       g.start_date, g.end_date, g.status
                from growth_goal g join growth_dimension d on d.id = g.dimension_id
                where g.user_id = ? and (? is null or g.status = ?)
                order by g.created_at desc
                """,
            (rs, row) -> goalView(rs),
            userId, normalized, normalized
        );
    }

    public GoalView goal(long userId, String publicId) {
        GoalView view = jdbc.query(
            """
                select g.public_id, d.public_id dimension_public_id, g.title, g.description,
                       g.start_date, g.end_date, g.status
                from growth_goal g join growth_dimension d on d.id = g.dimension_id
                where g.user_id = ? and g.public_id = ?
                """,
            rs -> rs.next() ? goalView(rs) : null,
            userId, publicId
        );
        if (view == null) {
            throw notFound("GOAL_NOT_FOUND");
        }
        return view;
    }

    @Transactional
    public GoalView updateGoal(long userId, String publicId, GoalCommand command) {
        GoalView current = goal(userId, publicId);
        LocalDate start = command.startDate() == null ? current.startDate() : command.startDate();
        LocalDate end = command.endDate() == null ? current.endDate() : command.endDate();
        if (!GoalPolicy.hasValidDuration(start, end)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GOAL_DURATION", "Goal duration must be 14 to 84 days");
        }
        String title = command.title() == null ? current.title() : command.title().trim();
        String description = command.description() == null ? current.description() : command.description();
        jdbc.update(
            "update growth_goal set title = ?, description = ?, start_date = ?, end_date = ?, updated_at = ? where user_id = ? and public_id = ?",
            title, description, Date.valueOf(start), Date.valueOf(end), Timestamp.from(clock.instant()), userId, publicId
        );
        return goal(userId, publicId);
    }

    @Transactional
    public GoalView changeStatus(long userId, String publicId, String status) {
        lockUser(userId);
        GoalView current = goal(userId, publicId);
        if (!validTransition(current.status(), status)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_GOAL_TRANSITION", "Goal status transition is not allowed");
        }
        if ("ACTIVE".equals(status) && !"ACTIVE".equals(current.status())) {
            Integer active = jdbc.queryForObject(
                "select count(*) from growth_goal where user_id = ? and status = 'ACTIVE'",
                Integer.class, userId
            );
            if (!GoalPolicy.canCreateActiveGoal(active == null ? 0 : active)) {
                throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_GOAL_LIMIT", "At most three active goals are allowed");
            }
        }
        int changed = jdbc.update(
            "update growth_goal set status = ?, updated_at = ? where user_id = ? and public_id = ?",
            status, Timestamp.from(clock.instant()), userId, publicId
        );
        return goal(userId, publicId);
    }

    private void lockUser(long userId) {
        jdbc.queryForObject("select id from sys_user where id = ? for update", Long.class, userId);
    }

    private boolean validTransition(String current, String next) {
        if (current.equals(next)) {
            return true;
        }
        return switch (current) {
            case "ACTIVE" -> next.equals("PAUSED") || next.equals("COMPLETED") || next.equals("CANCELLED");
            case "PAUSED" -> next.equals("ACTIVE") || next.equals("COMPLETED") || next.equals("CANCELLED");
            default -> false;
        };
    }

    private DimensionView dimension(long userId, String publicId) {
        return dimensions(userId).stream()
            .filter(item -> item.publicId().equals(publicId))
            .findFirst()
            .orElseThrow(() -> notFound("DIMENSION_NOT_FOUND"));
    }

    private GoalView goalView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GoalView(
            rs.getString("public_id"), rs.getString("dimension_public_id"), rs.getString("title"),
            rs.getString("description"), rs.getDate("start_date").toLocalDate(),
            rs.getDate("end_date").toLocalDate(), rs.getString("status")
        );
    }

    private String normalizeCode(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9_]{2,39}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIMENSION_CODE", "Dimension code is invalid");
        }
        return code;
    }

    private void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
    }

    private ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Resource not found");
    }

    public record DimensionCommand(String code, String name, String description) {
    }

    public record DimensionView(String publicId, String code, String name, String description, boolean system, boolean archived) {
    }

    public record GoalCommand(
        String dimensionPublicId,
        String title,
        String description,
        LocalDate startDate,
        LocalDate endDate
    ) {
    }

    public record GoalView(
        String publicId,
        String dimensionPublicId,
        String title,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        String status
    ) {
    }
}
