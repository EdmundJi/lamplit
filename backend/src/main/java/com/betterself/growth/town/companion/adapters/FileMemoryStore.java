package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.MemoryStore;
import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Memories as files on disk, with a SQLite index beside them.
 *
 * <p><b>Files are the authority.</b> {@code <root>/u<userId>/<residentId>/<memoryId>.md} holds one
 * remembered fact, as a short header plus the text the resident actually wrote. That is what
 * 02-modules asked for: physical separation so nobody's memories bleed into anyone else's, and a
 * format a person can open, read and edit. The world save in MySQL no longer carries memories at
 * all.
 *
 * <p><b>SQLite is an index, not a second truth.</b> {@code <root>/index.db} mirrors each file's
 * metadata so a later batch can ask questions files alone answer slowly - "which of this resident's
 * beliefs are about 知夏", "what did they conclude across the last three days". It is rebuilt from
 * the files whenever it is missing or empty, so losing it costs a rescan and nothing else. Being
 * honest about the current state: at six residents the in-memory cache below is what actually
 * serves reads; the index earns its place when the memory/reflection work lands on top of it, and
 * it is here now so that work does not have to migrate storage a second time.
 *
 * <p>This is not openclaw's unforgeable-provenance model, and should not be described as one. There
 * the risk is a model writing its own memory files; here every write goes through the simulation's
 * own Java code and a model only ever supplies validated text, so the index buys queryability, not
 * a security boundary.
 */
@Repository
public class FileMemoryStore implements MemoryStore {
    /** Ids that are safe as a single path segment. Anything else is a bug upstream, not a filename to
     * escape: memory ids are minted as "m-<n>" and owner ids are resident ids. */
    private static final String SAFE = "[A-Za-z0-9_-]{1,64}";
    private final Path root;
    private final Map<Long, Map<String, Map<String, Memory>>> cache = new ConcurrentHashMap<>();

    /** Blank means "nobody told us where to put these", which is the local-development and test case:
     * fall back to the build directory rather than to a system path we may not be allowed to create.
     * Every container sets COMPANION_MEMORY_ROOT explicitly and mounts a volume there, so a real
     * deployment never takes this branch - see deploy/compose.dev.yaml and Dockerfile.backend. */
    @org.springframework.beans.factory.annotation.Autowired
    public FileMemoryStore(@Value("${app.town.companion-memory-root:}") String root) {
        this(root == null || root.isBlank()
            ? Path.of(System.getProperty("user.dir"), "target", "companion-memory")
            : Path.of(root));
    }
    /** Direct-path constructor for tests and tools. Spring uses the annotated one above; without that
     * annotation two public constructors leave it with no candidate at all and it falls back to
     * looking for a no-arg one, which is how this class first failed to start. */
    public FileMemoryStore(Path root) {
        this.root = root;
        try { Files.createDirectories(root); } catch (IOException e) { throw new UncheckedIOException(e); }
        initIndex();
    }

    @Override public List<Memory> byOwner(long userId, String ownerId) {
        if (ownerId == null || !ownerId.matches(SAFE)) return List.of();
        return ownerCache(userId, ownerId).values().stream()
            .sorted(Comparator.comparing(Memory::at, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Memory::id))
            .toList();
    }

    @Override public void save(long userId, List<Memory> memories) {
        if (memories == null || memories.isEmpty()) return;
        for (Memory m : memories) {
            if (m == null || m.id() == null || m.ownerId() == null) continue;
            if (!m.id().matches(SAFE) || !m.ownerId().matches(SAFE)) continue;
            Memory existing = ownerCache(userId, m.ownerId()).get(m.id());
            if (m.equals(existing)) continue;                       // unchanged: no file churn
            writeFile(userId, m);
            ownerCache(userId, m.ownerId()).put(m.id(), m);
            indexUpsert(userId, m);
        }
    }

    @Override public void deleteUser(long userId) {
        cache.remove(userId);
        Path dir = userDir(userId);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("delete from memory where user_id=?")) {
            ps.setLong(1, userId); ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Cannot clear memory index for user " + userId, e); }
    }

    // ---- files ---------------------------------------------------------------------------------

    private Path userDir(long userId) { return root.resolve("u" + userId); }
    private Path fileOf(long userId, String ownerId, String id) { return userDir(userId).resolve(ownerId).resolve(id + ".md"); }

    private void writeFile(long userId, Memory m) {
        Path file = fileOf(userId, m.ownerId(), m.id());
        String body = """
            ---
            id: %s
            owner: %s
            source: %s
            type: %s
            at: %s
            topic: %s
            importance: %d
            evidence: %s
            supersedes: %s
            superseded: %s
            ---
            %s
            """.formatted(m.id(), m.ownerId(), nullToEmpty(m.sourceId()), nullToEmpty(m.sourceType()),
                m.at() == null ? "" : m.at().toString(), nullToEmpty(m.topicId()), m.importance(),
                m.evidenceIds() == null ? "" : String.join(",", m.evidenceIds()),
                nullToEmpty(m.supersedesKey()), m.superseded() ? "true" : "false", nullToEmpty(m.text()));
        try {
            Files.createDirectories(file.getParent());
            // Write beside the target and move into place, so a crash mid-write never leaves a
            // half-written memory that would then be parsed back as a real one.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new UncheckedIOException("Cannot write memory " + m.id(), e); }
    }

    private Map<String, Memory> ownerCache(long userId, String ownerId) {
        return cache.computeIfAbsent(userId, u -> new ConcurrentHashMap<>())
            .computeIfAbsent(ownerId, o -> readOwnerFromDisk(userId, o));
    }

    private Map<String, Memory> readOwnerFromDisk(long userId, String ownerId) {
        Map<String, Memory> out = new ConcurrentHashMap<>();
        Path dir = userDir(userId).resolve(ownerId);
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md")).forEach(p -> {
                Memory m = parse(p);
                if (m != null) out.put(m.id(), m);
            });
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return out;
    }

    /** Reads one memory file back. A file we cannot make sense of is skipped rather than thrown on:
     * these are hand-editable by design, and one bad file must not take the whole town down. */
    private static Memory parse(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !"---".equals(lines.getFirst().strip())) return null;
            Map<String, String> head = new LinkedHashMap<>();
            int i = 1;
            for (; i < lines.size() && !"---".equals(lines.get(i).strip()); i++) {
                int colon = lines.get(i).indexOf(':');
                if (colon > 0) head.put(lines.get(i).substring(0, colon).strip(), lines.get(i).substring(colon + 1).strip());
            }
            String text = String.join("\n", lines.subList(Math.min(i + 1, lines.size()), lines.size())).strip();
            String id = head.get("id"), owner = head.get("owner");
            if (id == null || owner == null) return null;
            List<String> evidence = head.getOrDefault("evidence", "").isBlank() ? List.of()
                : List.of(head.get("evidence").split(","));
            String at = head.getOrDefault("at", "");
            return new Memory(id, owner, emptyToNull(head.get("source")), emptyToNull(head.get("type")),
                at.isBlank() ? null : Instant.parse(at), text, emptyToNull(head.get("topic")),
                evidence, parseInt(head.get("importance")),
                // Absent in every file written before these two lines existed, and absent is exactly
                // what those files mean: no key, not superseded - the same values the shorter Memory
                // constructor used to hand back. So an old memory directory still reads correctly.
                emptyToNull(head.get("supersedes")), "true".equals(head.getOrDefault("superseded", "").strip()));
        } catch (Exception e) { return null; }
    }
    private static int parseInt(String v) { try { return v == null ? 5 : Integer.parseInt(v.strip()); } catch (NumberFormatException e) { return 5; } }
    private static String nullToEmpty(String v) { return v == null ? "" : v; }
    private static String emptyToNull(String v) { return v == null || v.isBlank() ? null : v; }

    // ---- index ---------------------------------------------------------------------------------

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("index.db"));
    }
    private void initIndex() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                create table if not exists memory(
                  user_id integer not null, owner_id text not null, id text not null,
                  source_id text, source_type text, at text, topic_id text,
                  importance integer, evidence text,
                  primary key(user_id, owner_id, id))""");
            s.executeUpdate("create index if not exists memory_owner_at on memory(user_id, owner_id, at)");
        } catch (SQLException e) { throw new IllegalStateException("Cannot open memory index at " + root, e); }
    }
    private void indexUpsert(long userId, Memory m) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("""
                 insert into memory(user_id,owner_id,id,source_id,source_type,at,topic_id,importance,evidence)
                 values (?,?,?,?,?,?,?,?,?)
                 on conflict(user_id,owner_id,id) do update set
                   source_id=excluded.source_id, source_type=excluded.source_type, at=excluded.at,
                   topic_id=excluded.topic_id, importance=excluded.importance, evidence=excluded.evidence""")) {
            ps.setLong(1, userId); ps.setString(2, m.ownerId()); ps.setString(3, m.id());
            ps.setString(4, m.sourceId()); ps.setString(5, m.sourceType());
            ps.setString(6, m.at() == null ? null : m.at().toString()); ps.setString(7, m.topicId());
            ps.setInt(8, m.importance());
            ps.setString(9, m.evidenceIds() == null ? "" : String.join(",", m.evidenceIds()));
            ps.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Cannot index memory " + m.id(), e); }
    }
}
