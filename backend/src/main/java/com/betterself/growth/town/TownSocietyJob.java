package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 每晚离线跑完整条传播链（plan §2.2 D12）。
 *
 * <p>为什么是离线批量而不是相遇时现算：现算意味着每次两个 NPC 擦肩都要等一次 LLM，成本和延迟都
 * 不可控，而且同一条八卦每次被问到都可能长得不一样。夜里一次算完、把每一手的走样文本写进表里，
 * 白天前端就只是"把已经写好的那句取出来播"——快、便宜、并且可复现。
 *
 * <p>作息与 {@link TownReflectionJob} 一致：按用户时区算当天日期，逐个用户跑，单个用户失败不影响
 * 其他人。默认排在反思任务（03:15）之后，免得两个 job 同时压数据库。
 */
@Component
public class TownSocietyJob {

    private static final Logger log = LoggerFactory.getLogger(TownSocietyJob.class);

    /** 只跑最近还在用的人的小镇——空镇没有事实可采，跑了也是白跑。 */
    private static final int ACTIVE_WINDOW_DAYS = 3;

    private final JdbcTemplate jdbc;
    private final TownSocietyService society;
    private final Clock clock;

    public TownSocietyJob(JdbcTemplate jdbc, TownSocietyService society, Clock clock) {
        this.jdbc = jdbc;
        this.society = society;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.town.society-cron:0 45 3 * * *}")
    public void run() {
        List<Long> candidates = activeUsers();
        // 开工也记一笔：这个 job 要调 LLM，慢起来能跑很久，只在结尾记日志的话"还在跑"和
        // "根本没触发"在日志里长得一模一样。
        log.info("town society job starting for {} town(s)", candidates.size());
        int done = 0;
        for (long userId : candidates) {
            try {
                society.runNightly(userId, localDateFor(userId));
                done++;
            } catch (ApiException ex) {
                // 今天已经跑过（或被限流）——安静跳过，和 TownReflectionJob 一个口径。
                log.debug("town society skipped for user {}: {}", userId, ex.getMessage());
            } catch (RuntimeException ex) {
                log.warn("town society failed for user {}", userId, ex);
            }
        }
        log.info("town society job finished: {}/{} towns simulated", done, candidates.size());
    }

    private LocalDate localDateFor(long userId) {
        String zone = jdbc.query("select timezone from sys_user where id = ?",
            rs -> rs.next() ? rs.getString(1) : null, userId);
        try {
            return LocalDate.now(zone == null ? clock.getZone() : ZoneId.of(zone));
        } catch (RuntimeException ex) {
            return LocalDate.now(clock.getZone());
        }
    }

    private List<Long> activeUsers() {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(ACTIVE_WINDOW_DAYS);
        return jdbc.queryForList(
            "select distinct user_id from task_event where occurred_at >= ?",
            Long.class, Timestamp.valueOf(since)
        );
    }
}
