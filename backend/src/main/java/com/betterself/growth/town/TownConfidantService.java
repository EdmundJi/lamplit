package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * M4-8 树洞笔友：写信 + 隔天回信。
 *
 * <p><b>硬隔离</b>（plan §2.7 / §3.4 第 6 条）：这个类从头到尾不查、不写 {@code town_fact} 或
 * {@code town_npc_knowledge}——{@code TownConfidantIsolationTest} 直接断言写一封树洞信前后这两张
 * 表的行数纹丝不动。树洞的内容只活在 {@code town_confidant_thread} 与它在 {@code town_letter}
 * 里的投递副本之间，两条路径都不连回小镇的传播网络。
 *
 * <p>回信只在夜间跑一次（{@link #runNightly}），且只回复「写于今天之前」的来信——这就是「隔天」：
 * 今天写的信，最早也要等到跑下一次 job（也就是明天）才会被看见。同一封信被回过之后，
 * {@link #unansweredBefore} 就再也查不到它，天然幂等，不需要额外的 run 表。
 */
@Service
public class TownConfidantService {

    static final int MAX_MESSAGE_LENGTH = 2000;

    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final TownLetterService letters;
    private final TownConfidantReplyGenerator replyGenerator;

    public TownConfidantService(JdbcTemplate jdbc, PublicIdGenerator ids, Clock clock, TownLetterService letters,
                                TownConfidantReplyGenerator replyGenerator) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.letters = letters;
        this.replyGenerator = replyGenerator;
    }

    /** {@code POST /api/v1/town/confidant}：只落一行 OUT 记录，什么都不判断、什么都不采集。 */
    public void write(long userId, String message) {
        String trimmed = message == null ? "" : message.strip();
        if (trimmed.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOWN_CONFIDANT_EMPTY", "写点什么再寄出去吧");
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_LENGTH);
        }
        jdbc.update(
            """
                insert into town_confidant_thread (public_id, user_id, direction, body, written_at, deliver_at, read_at)
                values (?, ?, 'OUT', ?, ?, null, null)
                """,
            ids.next(), userId, trimmed, Timestamp.valueOf(LocalDateTime.now(clock))
        );
    }

    /**
     * 幂等入口：把「写于今天之前、还没收到回信」的来信一次性批量回完。LLM 调用刻意放在任何事务
     * 之外——下面这段代码里没有一次 update 被包在同一个事务里，读、算、写都是独立的 JdbcTemplate
     * 调用，网关慢的时候不会有人握着写锁等它。
     */
    public void runNightly(long userId, LocalDate localDate) {
        List<PendingLetter> pending = unansweredBefore(userId, localDate);
        if (pending.isEmpty()) {
            return;
        }
        List<TownConfidantReplyGenerator.Request> requests = pending.stream()
            .map(item -> new TownConfidantReplyGenerator.Request(String.valueOf(item.id()), item.body()))
            .toList();

        Map<String, String> repliesByKey = new HashMap<>();
        for (TownConfidantReplyGenerator.Reply reply : replyGenerator.reply(requests)) {
            repliesByKey.put(reply.key(), reply.text());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        for (PendingLetter item : pending) {
            String reply = repliesByKey.get(String.valueOf(item.id()));
            if (reply == null || reply.isBlank()) {
                continue;
            }
            jdbc.update(
                """
                    insert into town_confidant_thread (public_id, user_id, direction, body, written_at, deliver_at, read_at)
                    values (?, ?, 'IN', ?, ?, ?, null)
                    """,
                ids.next(), userId, reply, Timestamp.valueOf(now), Timestamp.valueOf(now)
            );
            letters.deliver(userId, "CONFIDANT", null, "LONG", reply, now);
        }
    }

    /** 写于 {@code localDate} 当天开始之前、且此后没有任何一条回信的来信——按写信时间从早到晚处理。 */
    private List<PendingLetter> unansweredBefore(long userId, LocalDate localDate) {
        return jdbc.query(
            """
                select t.id, t.body from town_confidant_thread t
                where t.user_id = ? and t.direction = 'OUT' and t.written_at < ?
                  and not exists (
                    select 1 from town_confidant_thread r
                    where r.user_id = t.user_id and r.direction = 'IN' and r.written_at > t.written_at
                  )
                order by t.written_at
                """,
            (rs, row) -> new PendingLetter(rs.getLong("id"), rs.getString("body")),
            userId, Timestamp.valueOf(localDate.atStartOfDay())
        );
    }

    private record PendingLetter(long id, String body) {
    }
}
