package com.betterself.growth.achievement;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class TitleService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TitleService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TitleView> list(long userId) {
        return jdbc.query(
            """
                select t.code, t.name, t.description, t.graphic_type, t.graphic_key, t.frame_style,
                       case when ut.title_code is null then 0 else 1 end held,
                       coalesce(ut.equipped, 0) equipped, ut.acquired_at
                from title_def t
                left join user_title ut on ut.title_code = t.code and ut.user_id = ?
                where t.is_active = 1
                order by t.sort_order, t.id
                """,
            (rs, row) -> titleView(rs),
            userId
        );
    }

    /** 已佩戴的称号，未佩戴时返回 null。 */
    @Transactional(readOnly = true)
    public TitleView equipped(long userId) {
        return DataAccessUtils.singleResult(jdbc.query(
            """
                select t.code, t.name, t.description, t.graphic_type, t.graphic_key, t.frame_style,
                       1 held, 1 equipped, ut.acquired_at
                from user_title ut join title_def t on t.code = ut.title_code
                where ut.user_id = ? and ut.equipped = 1 and t.is_active = 1
                """,
            (rs, row) -> titleView(rs),
            userId
        ));
    }

    /** 成就奖励发放称号，已持有则幂等忽略。 */
    @Transactional
    public void acquire(long userId, String titleCode) {
        jdbc.update(
            """
                insert into user_title (user_id, title_code, equipped, acquired_at)
                select ?, code, 0, ? from title_def where code = ? and is_active = 1
                on duplicate key update title_code = values(title_code)
                """,
            userId,
            Timestamp.from(clock.instant()),
            titleCode
        );
    }

    @Transactional
    public List<TitleView> equip(long userId, String titleCode) {
        if (titleCode == null || titleCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TITLE_CODE", "称号代码不能为空");
        }
        lockUser(userId);
        Integer held = jdbc.queryForObject(
            """
                select count(*) from user_title ut
                join title_def t on t.code = ut.title_code and t.is_active = 1
                where ut.user_id = ? and ut.title_code = ?
                """,
            Integer.class,
            userId,
            titleCode
        );
        if (held == null || held == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TITLE_NOT_HELD", "尚未获得该称号");
        }
        jdbc.update("update user_title set equipped = 0 where user_id = ?", userId);
        jdbc.update(
            "update user_title set equipped = 1 where user_id = ? and title_code = ?",
            userId,
            titleCode
        );
        return list(userId);
    }

    @Transactional
    public List<TitleView> unequip(long userId) {
        lockUser(userId);
        jdbc.update("update user_title set equipped = 0 where user_id = ?", userId);
        return list(userId);
    }

    private void lockUser(long userId) {
        jdbc.queryForObject("select id from sys_user where id = ? for update", Long.class, userId);
    }

    private TitleView titleView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp acquiredAt = rs.getTimestamp("acquired_at");
        return new TitleView(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("graphic_type"),
            rs.getString("graphic_key"),
            rs.getString("frame_style"),
            rs.getInt("held") == 1,
            rs.getInt("equipped") == 1,
            acquiredAt == null ? null : acquiredAt.toInstant()
        );
    }

    public record TitleView(
        String code,
        String name,
        String description,
        String graphicType,
        String graphicKey,
        String frameStyle,
        boolean held,
        boolean equipped,
        Instant acquiredAt
    ) {
    }
}
