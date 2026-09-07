package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Authored side stories, private to the player. They never imply remembered model chat,
 * publish facts, grant rewards, or change the resident's already-frozen daily schedule. */
@Service
public class TownStoryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final TownNpcProvisioner provisioner;

    public TownStoryService(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, TownNpcProvisioner provisioner) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.provisioner = provisioner;
    }

    public StoryView detail(long userId, String npcCode) {
        requireResident(userId, npcCode);
        return view(npcCode, read(userId, npcCode, false));
    }

    /** Both stage and revision are required. A retry with an old token returns the persisted
     * result, so double clicks and two tabs can never skip an unseen story passage. */
    public StoryView advance(long userId, String npcCode, Command command) {
        requireResident(userId, npcCode);
        if (command == null || command.expectedStage() == null || command.expectedRevision() == null
            || command.expectedStage() < 0 || command.expectedStage() > 4 || command.expectedRevision() < 0
            || command.action() == null || !List.of("BEGIN", "CONTINUE", "PARTICIPATE", "OBSERVE", "PAUSE", "RESUME").contains(command.action())) {
            throw invalid("这一步没有保存，请重新打开故事后再试");
        }
        return tx.execute(status -> {
            jdbc.update("""
                insert ignore into town_story_progress (town_user_id,npc_code,updated_at) values (?,?,?)
                """, userId, npcCode, Timestamp.from(clock.instant()));
            Progress old = read(userId, npcCode, true);
            if (old.stage() != command.expectedStage() || old.revision() != command.expectedRevision()) {
                return view(npcCode, old);
            }
            String action = command.action();
            if (!allowed(old).stream().anyMatch(a -> a.code().equals(action))) {
                throw invalid("请先读完当前这一段，或稍后再继续");
            }
            boolean paused = "PAUSE".equals(action) || (old.paused() && !"RESUME".equals(action));
            int stage = old.stage() + (List.of("BEGIN", "CONTINUE", "PARTICIPATE", "OBSERVE").contains(action) ? 1 : 0);
            String participation = "PARTICIPATE".equals(action) ? "JOINED"
                : "OBSERVE".equals(action) ? "OBSERVED" : old.participation();
            Instant now = clock.instant();
            jdbc.update("""
                update town_story_progress set stage=?,revision=revision+1,paused=?,participation=?,
                    started_at=coalesce(started_at,?),updated_at=?,completed_at=?
                where town_user_id=? and npc_code=?
                """, stage, paused, participation, Timestamp.from(now), Timestamp.from(now),
                stage == 4 ? Timestamp.from(now) : null, userId, npcCode);
            return view(npcCode, read(userId, npcCode, false));
        });
    }

    private void requireResident(long userId, String code) {
        if (!List.of(TownNpcCatalog.GUIDE, TownNpcCatalog.POSTMAN).contains(code == null ? "" : code)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_STORY_NOT_FOUND", "这位邻居暂时没有新的故事");
        }
        provisioner.ensurePopulated(userId);
        Integer count = jdbc.queryForObject("select count(*) from town_npc where town_user_id=? and npc_code=?", Integer.class, userId, code);
        if (count == null || count == 0) throw new ApiException(HttpStatus.NOT_FOUND, "TOWN_STORY_NOT_FOUND", "这位邻居还没有入住");
    }

    private Progress read(long userId, String code, boolean lock) {
        return jdbc.query("select stage,revision,paused,participation,completed_at from town_story_progress where town_user_id=? and npc_code=?"
            + (lock ? " for update" : ""), (rs, row) -> new Progress(rs.getInt("stage"), rs.getInt("revision"),
                rs.getBoolean("paused"), rs.getString("participation"),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()), userId, code)
            .stream().findFirst().orElse(new Progress(0, 0, false, "UNDECIDED", null));
    }

    private StoryView view(String code, Progress progress) {
        boolean guide = TownNpcCatalog.GUIDE.equals(code);
        String title = guide ? "窗边的一小格书架" : "邮包上的蓝色补丁";
        String[] headings = {"一个小念头", "准备起来", "留一点自己的选择", "事情有了变化", "留在小镇的回忆"};
        String[] guideText = {
            "小助在整理自己的书架，想把窗边空着的一格留给读起来不费劲的书。这个小计划，愿意听听吗？",
            "小助把厚重的工具书放回原位，挑出两本短篇集。他想让这小小的一格，看起来像一个随时能停下来的地方。",
            "书已经选好了，旁边还缺一句说明。你可以帮他选一句‘翻开一页也很好’，也可以让他自己想想。",
            "JOINED".equals(progress.participation())
                ? "小助把你选的‘翻开一页也很好’写在纸卡上，夹进了书架。这一格终于有了自己的语气。"
                : "小助自己写了一张‘读到喜欢的地方，就停一会儿’的小纸卡。没有人催，他也慢慢把这一格整理好了。",
            "JOINED".equals(progress.participation())
                ? "小助的窗边书架留着你选的那句话：翻开一页也很好。这是你们一起做完的一件小事。"
                : "小助把窗边的一格书架整理好了。你听过它从一个念头变成一处阅读角的经过。"
        };
        String[] postmanText = {
            "邮递员发现常背的邮包磨出了一个小洞。他舍不得换掉它，正打算找块布补一补。要听听这个小计划吗？",
            "邮递员从抽屉里翻出一块蓝色旧布，试了几次位置。补丁有些显眼，不过他觉得，陪自己走过许多路的邮包可以有一点不同。",
            "补丁快缝好了，还剩最后几针。你可以替他选一个小小的星形收尾，也可以坐着听他讲完。",
            "JOINED".equals(progress.participation())
                ? "邮递员照着你选的星形缝好最后几针。他把邮包提起来看了看：这个小星星，倒像是在陪他认路。"
                : "邮递员用一排整齐的针脚收了尾。他把邮包提起来试了试，觉得它还能陪自己走很久。",
            "JOINED".equals(progress.participation())
                ? "那块蓝色补丁的角上，有你选的小星星。你们一起给旧邮包留下了一个新细节。"
                : "邮递员修好了旧邮包。你听过那块蓝色补丁的来历，也知道他为什么愿意留下它。"
        };
        return new StoryView(code, TownNpcCatalog.byCode(code).displayName(), title, progress.stage(), progress.revision(),
            progress.paused(), progress.participation(), headings[progress.stage()],
            (guide ? guideText : postmanText)[progress.stage()], allowed(progress), progress.completedAt());
    }

    private List<Action> allowed(Progress progress) {
        if (progress.stage() == 4) return List.of();
        if (progress.paused()) return List.of(new Action("RESUME", "接着上次这一段"));
        if (progress.stage() == 0) return List.of(new Action("BEGIN", "听听这个小计划"));
        if (progress.stage() == 2) return List.of(new Action("PARTICIPATE", "帮他选一个细节"),
            new Action("OBSERVE", "听他自己做完"), new Action("PAUSE", "今天先到这里"));
        return List.of(new Action("CONTINUE", progress.stage() == 3 ? "收下这段回忆" : "听听接下来的打算"),
            new Action("PAUSE", "今天先到这里"));
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "TOWN_STORY_ACTION_INVALID", message);
    }

    private record Progress(int stage, int revision, boolean paused, String participation, Instant completedAt) {}
    public record Command(String action, Integer expectedStage, Integer expectedRevision) {}
    public record Action(String code, String label) {}
    public record StoryView(String npcCode, String displayName, String title, int stage, int revision, boolean paused,
                            String participation, String heading, String body, List<Action> actions, Instant completedAt) {}
}
