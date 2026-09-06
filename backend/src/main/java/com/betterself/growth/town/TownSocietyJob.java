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
    private final TownNoteService notes;
    private final TownDailyProduction daily;

    public TownSocietyJob(JdbcTemplate jdbc, TownSocietyService society, TownEventService events,
                          TownConfidantService confidant, TownMigrationService migration, Clock clock,
                          TownNoteService notes, TownDailyProduction daily) {
        this.jdbc = jdbc;
        this.society = society;
        this.events = events;
        this.confidant = confidant;
        this.migration = migration;
        this.clock = clock;
        this.notes = notes;
        this.daily = daily;
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
        for (long userId : candidates) {
            try { migration.runNightly(userId, localDateFor(userId)); }
            catch (RuntimeException ex) { log.warn("town migration failed for user {}", userId, ex); }
        }
        log.info("town society job finished: {}/{} towns simulated", done, candidates.size());
    }

    /** Prepare today's immutable mood/event inputs, simulate the completed local day, then
     * produce notes and private replies. Cross-town migrations run in a separate final pass. */
    void runTown(long userId, LocalDate localDate) {
        events.runNightly(userId, localDate);
        // Only a completed local day has all accepted presence samples available.
        society.runNightly(userId, localDate.minusDays(1));
        notes.runNightly(userId, localDate);
        confidant.runNightly(userId, localDate);

    }

    private LocalDate localDateFor(long userId) {
        return daily.today(userId);
    }

    private List<Long> activeUsers() {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(ACTIVE_WINDOW_DAYS);
        return jdbc.queryForList(
            """
                select distinct u.id from sys_user u where u.status='ACTIVE' and u.deleted_at is null and (
                  exists(select 1 from task_event e where e.user_id=u.id and e.occurred_at>=?)
                  or exists(select 1 from town_presence p where p.user_id=u.id and p.updated_at>=?)
                  or exists(select 1 from town_npc n where n.town_user_id=u.id and n.created_at>=?)
                  or exists(select 1 from town_confidant_thread c where c.user_id=u.id and c.direction='OUT' and c.answered_at is null)
                ) order by u.id
                """, Long.class, Timestamp.valueOf(since),Timestamp.valueOf(since),Timestamp.valueOf(since)
        );
    }
}
