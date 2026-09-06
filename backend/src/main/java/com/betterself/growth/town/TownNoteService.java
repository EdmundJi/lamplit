package com.betterself.growth.town;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Passive mailbox notes about the sender's own life, never a dump of player facts or gossip. */
@Service
public class TownNoteService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TownDailyProduction daily;
    private final TownLetterService letters;
    private final Clock clock;
    public TownNoteService(JdbcTemplate jdbc, TransactionTemplate tx, TownDailyProduction daily,
                           TownLetterService letters, Clock clock) {
        this.jdbc=jdbc; this.tx=tx; this.daily=daily; this.letters=letters; this.clock=clock;
    }
    public void runNightly(long userId, LocalDate date) {
        tx.executeWithoutResult(status -> {
            if (!daily.claim(userId, date, "NOTE")) return;
            if (Math.floorMod(date.toEpochDay() + userId, 3) != 0) return;
            var candidates = jdbc.query("""
                select n.npc_code,n.dimension,m.valence from town_npc n
                join town_npc_mood m on m.town_user_id=n.town_user_id and m.npc_code=n.npc_code and m.local_date=?
                where n.town_user_id=? and n.layer=2 order by m.affinity_to_player desc,n.npc_code
                """, (rs,row) -> new Sender(rs.getString(1),rs.getString(2),rs.getDouble(3)), java.sql.Date.valueOf(date),userId);
            if (candidates.isEmpty()) return;
            var sender = candidates.get(0);
            String body = sender.valence() < -0.2 ? "今天想慢一点，给自己留些安静的空隙。写张小笺问候你，不用回信。"
                : switch (sender.dimension() == null ? "" : sender.dimension()) {
                    case "KNOWLEDGE" -> "翻到一页有趣的书，折了个小小的书角。给你捎个问候，不用回信。";
                    case "HEALTH" -> "出门舒展了一下肩膀，风吹过来很舒服。给你捎个问候，不用回信。";
                    case "CAREER" -> "收拾桌面时找到了旧便签，忍不住笑了一下。给你捎个问候，不用回信。";
                    case "RELATIONSHIP" -> "想起镇上的熟面孔，便写了这张小笺。愿你有个舒心的片刻，不用回信。";
                    default -> "窗边的光落在纸上很好看，就想写张小笺问候你。不用回信。";
                };
            letters.deliver(userId,"NPC",sender.code(),"NOTE",body,LocalDateTime.now(clock));
        });
    }
    private record Sender(String code,String dimension,double valence) {}
}
