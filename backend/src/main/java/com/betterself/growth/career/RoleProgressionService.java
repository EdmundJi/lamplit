package com.betterself.growth.career;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class RoleProgressionService {

    private static final int[] LEVEL_REQUIREMENTS = {10, 15, 25, 40, 65, 105, 170, 275, 445};
    private static final int[] LEVEL_START_EXPERIENCE = {0, 10, 25, 50, 90, 155, 260, 430, 705, 1150};
    public static final int MAX_LEVEL_EXPERIENCE = 999;
    public static final int MAX_TOTAL_EXPERIENCE = 2149;

    private final JdbcTemplate jdbc;

    public RoleProgressionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initialize(long userId) {
        for (CareerRole role : CareerRole.values()) {
            jdbc.update(
                "insert ignore into user_role_progress (user_id, role_code) values (?, ?)",
                userId, role.name()
            );
        }
    }

    public List<RoleProgressView> list(long userId) {
        initialize(userId);
        return Arrays.stream(CareerRole.values()).map(role -> view(userId, role)).toList();
    }

    @Transactional
    public ProgressChange apply(long userId, CareerRole role, int requestedDelta) {
        initialize(userId);
        ProgressRow current = jdbc.queryForObject(
            "select experience, level from user_role_progress where user_id = ? and role_code = ? for update",
            (rs, row) -> new ProgressRow(rs.getInt("experience"), rs.getInt("level")),
            userId, role.name()
        );
        int totalExperience = Math.max(0, Math.min(MAX_TOTAL_EXPERIENCE, current.experience() + requestedDelta));
        int actualDelta = totalExperience - current.experience();
        int level = levelFor(totalExperience);
        jdbc.update(
            "update user_role_progress set experience = ?, level = ?, updated_at = UTC_TIMESTAMP(3) where user_id = ? and role_code = ?",
            totalExperience, level, userId, role.name()
        );
        return new ProgressChange(actualDelta, toView(role, totalExperience, level));
    }

    public RoleProgressView view(long userId, CareerRole role) {
        initialize(userId);
        ProgressRow row = jdbc.queryForObject(
            "select experience, level from user_role_progress where user_id = ? and role_code = ?",
            (rs, index) -> new ProgressRow(rs.getInt("experience"), rs.getInt("level")),
            userId, role.name()
        );
        return toView(role, row.experience(), row.level());
    }

    public static int levelFor(int totalExperience) {
        int bounded = Math.max(0, Math.min(MAX_TOTAL_EXPERIENCE, totalExperience));
        int level = 1;
        for (int index = 1; index < LEVEL_START_EXPERIENCE.length; index++) {
            if (bounded < LEVEL_START_EXPERIENCE[index]) break;
            level = index + 1;
        }
        return level;
    }

    public static int requirementForLevel(int level) {
        if (level < 1 || level > 10) throw new IllegalArgumentException("Level must be between 1 and 10");
        return level == 10 ? MAX_LEVEL_EXPERIENCE : LEVEL_REQUIREMENTS[level - 1];
    }

    private RoleProgressView toView(CareerRole role, int totalExperience, int level) {
        int currentThreshold = LEVEL_START_EXPERIENCE[level - 1];
        Integer nextThreshold = level == 10 ? null : LEVEL_START_EXPERIENCE[level];
        int experience = totalExperience - currentThreshold;
        int maxExperience = requirementForLevel(level);
        int progressPercent = (int) Math.round(experience * 100.0 / maxExperience);
        return new RoleProgressView(
            role.name(), role.displayName(), level, experience, maxExperience, totalExperience,
            currentThreshold, nextThreshold, nextThreshold == null ? 0 : maxExperience - experience,
            Math.max(0, Math.min(100, progressPercent))
        );
    }

    public record RoleProgressView(
        String roleCode,
        String roleName,
        int level,
        int experience,
        int maxExperience,
        int totalExperience,
        int currentLevelExperience,
        Integer nextLevelExperience,
        int experienceToNextLevel,
        int levelProgressPercent
    ) {
    }

    public record ProgressChange(int experienceDelta, RoleProgressView progress) {
    }

    private record ProgressRow(int experience, int level) {
    }
}
