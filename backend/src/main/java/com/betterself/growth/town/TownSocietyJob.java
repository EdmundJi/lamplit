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
    private final TownEventService events;
    private final TownConfidantService confidant;
    private final TownMigrationService migration;
    private final Clock clock;

    public TownSocietyJob(JdbcTemplate jdbc, TownSocietyService society, TownEventService events,
                          TownConfidantService confidant, TownMigrationService migration, Clock clock) {
        this.jdbc = jdbc;
        this.society = society;
        this.events = events;
        this.confidant = confidant;
        this.migration = migration;
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
                runTown(userId, localDateFor(userId));
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

    /**
     * 一个小镇的一晚，四步。顺序不是随便排的：
     *
     * <ol>
     *   <li><b>活动</b>要排在最前面。它今晚建的 {@code town_event} 起始时间就在今天，而当天行程
     *       （M7-3）要把这场活动插成一条高优先级安排。晚于社会模拟执行的话，夜里推出的相遇序列
     *       里没有这场活动、白天 {@code roster()} 却算得出有——前后端两份日程对不上，plan §3.5
     *       那条「两边算出来的必须是同一份」的地基就塌了。代价只是请柬排序用的是昨晚的亲密度，
     *       而亲密度本来就是按天缓慢变化的，肉眼无差。</li>
     *   <li><b>社会模拟</b>：采集事实 → 目击 → 传播 → 转述 → 亲密度。</li>
     *   <li><b>树洞回信</b>：与前两步没有依赖，且按 plan §2.7 与传播网络完全隔离。</li>
     *   <li><b>迁徙</b>排在最后：它会改动名册，放最后就不会影响当晚其余步骤读到的「今天的人」。
     *       它同时会写进对方的小镇，但那一镇当晚不会让新来的人入场（见
     *       {@code TownSocietyService.simulationRoster}），所以两个用户谁先跑都算得出同一份结果。</li>
     * </ol>
     *
     * <p>每一步各自幂等（社会模拟靠 {@code town_society_run}，其余三步靠自己的产出表做存在性判断），
     * 所以某一步抛异常时前面已完成的步骤不会在重跑时被重复执行。
     */
    private void runTown(long userId, LocalDate localDate) {
        events.runNightly(userId, localDate);
        society.runNightly(userId, localDate);
        confidant.runNightly(userId, localDate);
        migration.runNightly(userId, localDate);
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
