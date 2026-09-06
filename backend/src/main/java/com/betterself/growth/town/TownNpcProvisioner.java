package com.betterself.growth.town;

import com.betterself.growth.shared.id.PublicIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Materialises the fixed 18-NPC roster ({@link TownNpcCatalog}) into {@code town_npc} rows for
 * one user's town, plus the starting {@code town_bond} graph: a low-affinity PLAYER↔NPC edge
 * for every archetype, a sparse NPC↔NPC acquaintance web, and a handful of hidden
 * {@code regard} edges among layer-2/3 NPCs (never involving PLAYER — plan §2.5 红线 1, backed
 * by {@code chk_town_bond_no_player_regard}).
 *
 * <p>Idempotent by construction: every insert is {@code insert ignore} against the tables'
 * unique keys, so a row that already exists is left completely untouched — in particular
 * {@code town_npc.settled_at}, which the nightly migration job owns from here on, is never
 * overwritten by a repeat call. Safe to call on every town load.
 *
 * <p>All randomness is seeded from the user id (see {@link #rngFor}), so a given user's
 * starting town — which pairs get an acquaintance edge, who secretly {@code regard}s whom — is
 * stable across repeated calls and across restarts, never {@code Math.random()}.
 */
@Service
public class TownNpcProvisioner {

    private static final List<String> REGARD_KINDS = List.of("CRUSH", "ADMIRE", "RIVAL", "OWE", "MISS");
    private static final double SPARSE_EDGE_PROBABILITY = 0.15;
    private static final int REGARD_EDGE_COUNT = 4;

    private static final String BOND_INSERT_SQL = """
        insert ignore into town_bond
            (public_id, town_user_id, a_kind, a_ref, b_kind, b_ref, affinity, resonance, meet_count,
             last_met_at, regard, regard_kind, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 0, null, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TownNpcProvisioner(
        JdbcTemplate jdbc,
        TransactionTemplate transactions,
        PublicIdGenerator ids,
        Clock clock,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Ensures this user's town has all 18 archetypes and a starting bond graph. Safe to call on
     * every town load — a fully-provisioned user costs one existence check plus two count
     * queries and writes nothing.
     */
    public void ensurePopulated(long userId) {
        transactions.executeWithoutResult(status -> {
            Set<String> existing = new HashSet<>(jdbc.queryForList(
                "select npc_code from town_npc where town_user_id = ?", String.class, userId
            ));
            List<TownNpcCatalog.Archetype> missing = TownNpcCatalog.all().stream()
                .filter(archetype -> !existing.contains(archetype.code()))
                .toList();
            if (!missing.isEmpty()) {
                insertNpcs(userId, missing);
            }
            if (!playerBondsAlreadySeeded(userId)) {
                ensurePlayerBonds(userId);
            }
            if (!npcWebAlreadySeeded(userId)) {
                seedNpcWeb(userId);
            }
        });
    }

    private void insertNpcs(long userId, List<TownNpcCatalog.Archetype> archetypes) {
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> batch = new ArrayList<>();
        for (TownNpcCatalog.Archetype archetype : archetypes) {
            // M7-1：节律只由这个 NPC 固定不变的档案决定（不接种子/日期），算好之后跟着这一行一起
            // 落库——HTTP 与夜间 job 都读同一份 rhythm，而不是各自重算。
            TownNpcRhythm.Rhythm rhythm = TownNpcRhythm.defaultFor(
                archetype.code(), archetype.interests(), archetype.shareDrive(), archetype.curiosity());
            batch.add(new Object[]{
                ids.next(), userId, archetype.code(), archetype.displayName(), archetype.layer(),
                archetype.sprite(), archetype.dimension(), archetype.shareDrive(), archetype.curiosity(),
                writeJson(archetype.interests()), writeJson(archetype.quirks()), writeJson(rhythm), now, now, now
            });
        }
        jdbc.batchUpdate(
            """
                insert ignore into town_npc
                    (public_id, town_user_id, npc_code, display_name, layer, sprite, dimension,
                     share_drive, curiosity, interests, quirks, rhythm, settled_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as json), cast(? as json), cast(? as json), ?, ?, ?)
                """,
            batch
        );
    }

    private boolean playerBondsAlreadySeeded(long userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and (a_kind = 'PLAYER' or b_kind = 'PLAYER')",
            Integer.class, userId
        );
        return count != null && count > 0;
    }

    /** One low-affinity, symmetric, regard-free edge PLAYER↔NPC for every archetype. */
    private void ensurePlayerBonds(long userId) {
        RandomGenerator rng = rngFor(userId, "player-bond");
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> batch = new ArrayList<>();
        for (TownNpcCatalog.Archetype archetype : TownNpcCatalog.all()) {
            double affinity = 0.05 + rng.nextDouble() * 0.10; // low starting familiarity: [0.05, 0.15)
            addSymmetricBond(batch, userId, "PLAYER", "PLAYER", "NPC", archetype.code(), affinity, 0.0, now);
        }
        jdbc.batchUpdate(BOND_INSERT_SQL, batch);
    }

    private boolean npcWebAlreadySeeded(long userId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from town_bond where town_user_id = ? and a_kind = 'NPC' and b_kind = 'NPC'",
            Integer.class, userId
        );
        return count != null && count > 0;
    }

    /** A sparse NPC↔NPC acquaintance web, plus a few hidden regard edges among layer-2/3 NPCs. */
    private void seedNpcWeb(long userId) {
        List<String> codes = TownNpcCatalog.all().stream().map(TownNpcCatalog.Archetype::code).toList();
        RandomGenerator rng = rngFor(userId, "npc-web");
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> batch = new ArrayList<>();

        for (int i = 0; i < codes.size(); i++) {
            for (int j = i + 1; j < codes.size(); j++) {
                if (rng.nextDouble() < SPARSE_EDGE_PROBABILITY) {
                    double affinity = 0.10 + rng.nextDouble() * 0.30; // acquaintance level: [0.10, 0.40)
                    addSymmetricBond(batch, userId, "NPC", codes.get(i), "NPC", codes.get(j), affinity, affinity * 0.5, now);
                }
            }
        }

        // Hidden regard: layer-2/3 only, never PLAYER (the CHECK constraint would reject that
        // anyway — this is the deliberate demonstration that it does).
        List<String> candidates = new ArrayList<>(
            TownNpcCatalog.all().stream()
                .filter(archetype -> archetype.layer() >= 2)
                .map(TownNpcCatalog.Archetype::code)
                .toList()
        );
        shuffle(candidates, rng);
        int edgeCount = Math.min(REGARD_EDGE_COUNT, candidates.size() / 2);
        for (int k = 0; k < edgeCount; k++) {
            String admirer = candidates.get(2 * k);
            String target = candidates.get(2 * k + 1);
            double regard = 0.30 + rng.nextDouble() * 0.50; // [0.30, 0.80)
            double baseAffinity = 0.05 + rng.nextDouble() * 0.25; // often unremarkable on the surface
            String regardKind = REGARD_KINDS.get(rng.nextInt(REGARD_KINDS.size()));
            addRegardEdge(batch, userId, admirer, target, baseAffinity, baseAffinity * 0.5, regard, regardKind, now);
        }
        jdbc.batchUpdate(BOND_INSERT_SQL, batch);
    }

    /** affinity/resonance are symmetric by convention, so both directions are written together. */
    private void addSymmetricBond(
        List<Object[]> batch, long userId, String aKind, String aRef, String bKind, String bRef,
        double affinity, double resonance, Timestamp now
    ) {
        batch.add(bondRow(userId, aKind, aRef, bKind, bRef, affinity, resonance, 0, null, now));
        batch.add(bondRow(userId, bKind, bRef, aKind, aRef, affinity, resonance, 0, null, now));
    }

    /** regard/regardKind are directed: only the admirer→target row carries them. */
    private void addRegardEdge(
        List<Object[]> batch, long userId, String admirerCode, String targetCode,
        double affinity, double resonance, double regard, String regardKind, Timestamp now
    ) {
        batch.add(bondRow(userId, "NPC", admirerCode, "NPC", targetCode, affinity, resonance, regard, regardKind, now));
        batch.add(bondRow(userId, "NPC", targetCode, "NPC", admirerCode, affinity, resonance, 0, null, now));
    }

    private Object[] bondRow(
        long userId, String aKind, String aRef, String bKind, String bRef,
        double affinity, double resonance, double regard, String regardKind, Timestamp now
    ) {
        return new Object[]{ids.next(), userId, aKind, aRef, bKind, bRef, affinity, resonance, regard, regardKind, now, now};
    }

    /** Deterministic per (userId, tag) so different seeding steps don't share a random stream. */
    private static RandomGenerator rngFor(long userId, String tag) {
        return new SplittableRandom(userId * 1_000_003L + tag.hashCode());
    }

    /** Fisher-Yates over our own {@link RandomGenerator} — {@link java.util.Collections#shuffle}
     * needs a {@code java.util.Random}, and we want everything on one deterministic source. */
    private static void shuffle(List<String> values, RandomGenerator rng) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            String tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize town npc catalog data", exception);
        }
    }
}
