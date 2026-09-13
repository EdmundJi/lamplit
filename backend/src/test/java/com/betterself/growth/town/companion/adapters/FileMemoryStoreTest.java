package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.domain.CompanionWorld.Memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FileMemoryStoreTest {
    private static final Instant AT = Instant.parse("2026-09-09T02:00:00Z");
    private static Memory memory(String id, String owner, String text) {
        return new Memory(id, owner, "owner", "observed", AT, text, "service", List.of("m-1"), 6);
    }

    @Test void aMemorySurvivesARestartBecauseItIsAFileNotAFieldInTheSave(@TempDir Path root) {
        new FileMemoryStore(root).save(1L, List.of(memory("m-1", "student", "阿禾端来了一杯。")));
        // A second store instance shares nothing but the directory - this is the actual claim.
        assertThat(new FileMemoryStore(root).byOwner(1L, "student"))
            .singleElement().satisfies(m -> {
                assertThat(m.text()).isEqualTo("阿禾端来了一杯。");
                assertThat(m.sourceType()).isEqualTo("observed");
                assertThat(m.importance()).isEqualTo(6);
                assertThat(m.evidenceIds()).containsExactly("m-1");
            });
    }

    /** "他曾经这么想过"永远查得到（docs/01「记忆分了层」）——那条保证的全部实现是 supersedesKey /
     * superseded 两个字段，而文件才是权威（docs/02「记忆存在哪」）。这两个字段以前根本没写进文件头：
     * 同一个进程里靠 {@code cache} 握着活对象看不出来，一旦重启、缓存冷了从盘上读回来，每一次
     * 改主意都被抹平成"他一直就是这么想的"。这条测试就是那道缺口的守门人。 */
    @Test void changingYourMindSurvivesARestartToo(@TempDir Path root) {
        Memory earlier = new Memory("m-1", "artist", "artist", "belief", AT, "窗边那个位子该留给小川。",
            null, List.of("m-0"), 9, "artist:seat:owner", true);
        Memory now = new Memory("m-2", "artist", "artist", "belief", AT, "谁先坐下就是谁的。",
            null, List.of("m-0"), 9, "artist:seat:owner", false);
        new FileMemoryStore(root).save(1L, List.of(earlier, now));
        // A second instance shares nothing but the directory - the files have to carry it themselves.
        assertThat(new FileMemoryStore(root).byOwner(1L, "artist"))
            .extracting(Memory::id, Memory::supersedesKey, Memory::superseded)
            .containsExactly(tuple("m-1", "artist:seat:owner", true),
                             tuple("m-2", "artist:seat:owner", false));
    }

    /** A memory directory written before those two header lines existed still reads back, and reads
     * back as what it actually meant: no key, not superseded. */
    @Test void aFileFromBeforeSupersessionExistedStillReadsBack(@TempDir Path root) throws Exception {
        Path file = root.resolve("u1/student/m-9.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            ---
            id: m-9
            owner: student
            source: student
            type: observed
            at: 2026-09-09T02:00:00Z
            topic: service
            importance: 5
            evidence:
            ---
            旧存档里的一条。
            """);
        assertThat(new FileMemoryStore(root).byOwner(1L, "student")).singleElement().satisfies(m -> {
            assertThat(m.supersedesKey()).isNull();
            assertThat(m.superseded()).isFalse();
            assertThat(m.text()).isEqualTo("旧存档里的一条。");
        });
    }

    @Test void oneResidentsMemoriesAreNeverReturnedToAnother(@TempDir Path root) {
        FileMemoryStore store = new FileMemoryStore(root);
        store.save(1L, List.of(memory("m-1", "student", "只有小川知道的事。"), memory("m-2", "artist", "知夏那一版。")));
        assertThat(store.byOwner(1L, "student")).extracting(Memory::text).containsExactly("只有小川知道的事。");
        assertThat(store.byOwner(1L, "artist")).extracting(Memory::text).containsExactly("知夏那一版。");
        // Isolation is physical, not a filter: each resident's memories are literally a separate directory.
        assertThat(root.resolve("u1/student/m-1.md")).exists();
        assertThat(root.resolve("u1/artist/m-1.md")).doesNotExist();
    }

    @Test void twoUsersNeverSeeEachOthersTown(@TempDir Path root) {
        FileMemoryStore store = new FileMemoryStore(root);
        store.save(1L, List.of(memory("m-1", "student", "第一个用户的小镇。")));
        store.save(2L, List.of(memory("m-1", "student", "第二个用户的小镇。")));
        assertThat(store.byOwner(1L, "student")).extracting(Memory::text).containsExactly("第一个用户的小镇。");
        assertThat(store.byOwner(2L, "student")).extracting(Memory::text).containsExactly("第二个用户的小镇。");
    }

    @Test void closingAnAccountTakesTheMemoryDirectoryWithIt(@TempDir Path root) throws Exception {
        FileMemoryStore store = new FileMemoryStore(root);
        store.save(1L, List.of(memory("m-1", "student", "该被删掉的事。")));
        store.save(2L, List.of(memory("m-1", "student", "别人的事，不该受影响。")));
        store.deleteUser(1L);
        assertThat(root.resolve("u1")).doesNotExist();
        assertThat(store.byOwner(1L, "student")).isEmpty();
        assertThat(new FileMemoryStore(root).byOwner(1L, "student")).isEmpty();
        assertThat(store.byOwner(2L, "student")).hasSize(1);
        // The index has to lose the rows too, or account closure leaves the metadata behind. Ask the
        // database, not the bytes: SQLite does not zero freed pages, so deleted text stays readable
        // in the file for a while and "the bytes no longer contain it" tests nothing.
        assertThat(indexedRows(root, 1L)).isZero();
        assertThat(indexedRows(root, 2L)).isEqualTo(1);
    }

    private static int indexedRows(Path root, long userId) throws Exception {
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("index.db"));
             var ps = c.prepareStatement("select count(*) from memory where user_id=?")) {
            ps.setLong(1, userId);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    @Test void aFileEditedByHandIsWhatTheResidentThenRemembers(@TempDir Path root) throws Exception {
        new FileMemoryStore(root).save(1L, List.of(memory("m-1", "student", "原来的说法。")));
        Path file = root.resolve("u1/student/m-1.md");
        Files.writeString(file, Files.readString(file).replace("原来的说法。", "我自己改过的说法。"));
        // 02-modules asks for memories a person can read, edit and delete. That only means anything
        // if the edit is what comes back.
        assertThat(new FileMemoryStore(root).byOwner(1L, "student"))
            .singleElement().extracting(Memory::text).isEqualTo("我自己改过的说法。");
    }

    @Test void aFileThatMakesNoSenseIsSkippedRatherThanTakingTheTownDown(@TempDir Path root) throws Exception {
        new FileMemoryStore(root).save(1L, List.of(memory("m-1", "student", "好的那一条。")));
        Files.writeString(root.resolve("u1/student/broken.md"), "这不是一条记忆，没有头部。");
        assertThat(new FileMemoryStore(root).byOwner(1L, "student"))
            .extracting(Memory::text).containsExactly("好的那一条。");
    }
}
