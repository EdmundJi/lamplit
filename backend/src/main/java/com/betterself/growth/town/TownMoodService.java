package com.betterself.growth.town;

import com.betterself.growth.shared.id.PublicIdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Date;
import java.time.LocalDate;
import java.util.SplittableRandom;

/** One immutable daily mood per resident; no user facts, private letters or regard enter it. */
@Service
public class TownMoodService {
    private final JdbcTemplate jdbc;
    private final PublicIdGenerator ids;
    private final TownNpcProvisioner provisioner;
    public TownMoodService(JdbcTemplate jdbc, PublicIdGenerator ids, TownNpcProvisioner provisioner) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.provisioner = provisioner;
    }
    public void ensureDay(long userId, LocalDate date) {
        provisioner.ensurePopulated(userId);
        var residents = jdbc.query("""
            select n.npc_code, n.share_drive, n.curiosity, coalesce(b.affinity, 0.15) affinity
            from town_npc n left join town_bond b on b.town_user_id=n.town_user_id
              and b.a_kind='PLAYER' and b.b_kind='NPC' and b.b_ref=n.npc_code
            where n.town_user_id=? order by n.npc_code
            """, (rs, row) -> new Resident(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)), userId);
        for (var resident : residents) {
            var mood = generate(userId, resident.code(), date, resident.share(), resident.curiosity());
            jdbc.update("""
                insert ignore into town_npc_mood
                  (public_id,town_user_id,npc_code,local_date,valence,energy,affinity_to_player)
                values (?,?,?,?,?,?,?)
                """, ids.next(), userId, resident.code(), Date.valueOf(date), mood.valence(), mood.energy(), resident.affinity());
        }
    }
    static Mood generate(long userId, String code, LocalDate date, double share, double curiosity) {
        var rng = new SplittableRandom(userId * 1_000_003L ^ code.hashCode() * 31L ^ date.toEpochDay());
        return new Mood(Math.max(-1, Math.min(1, rng.nextDouble(-0.85, 0.85) + (share - 0.5) * 0.2)),
            Math.max(0, Math.min(1, rng.nextDouble(0.2, 0.9) + (curiosity - 0.5) * 0.1)));
    }
    record Mood(double valence, double energy) {}
    private record Resident(String code, double share, double curiosity, double affinity) {}
}
