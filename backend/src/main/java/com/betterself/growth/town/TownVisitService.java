package com.betterself.growth.town;

import com.betterself.growth.shared.api.ApiException;
import com.betterself.growth.shared.id.PublicIdGenerator;
import com.betterself.growth.social.FriendService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A deliberately curated asynchronous portrait. Never exposes live presence or private task data. */
@Service
public class TownVisitService {
    private final JdbcTemplate jdbc;
    private final FriendService friends;
    private final PublicIdGenerator ids;
    private final Clock clock;
    public TownVisitService(JdbcTemplate jdbc, FriendService friends, PublicIdGenerator ids, Clock clock) {
        this.jdbc = jdbc; this.friends = friends; this.ids = ids; this.clock = clock;
    }
    public record Memento(String code, String name, String triggerText, Instant earnedAt) {}
    public record Profile(String publicId, String displayName, boolean enabled, String style, List<Memento> mementos) {}
    public record DirectoryEntry(String publicId, String displayName) {}
    public record SaveProfile(boolean enabled, String style, List<String> mementoCodes) {}
    public record SendPostcard(String body, String requestKey) {}
    public record Postcard(String publicId, String senderName, String body, Instant createdAt) {}
    public Profile mine(long user) { return profile(user); }
    @Transactional
    public Profile save(long user, SaveProfile command) {
        if (command == null || command.style() == null || !Set.of("original", "meadow", "dusk").contains(command.style())
            || command.mementoCodes() == null || command.mementoCodes().size() > 4 || command.mementoCodes().stream().anyMatch(c -> c == null || c.length() > 64))
            throw bad("请选择一种配色和最多四件已获得的纪念");
        for (String code : command.mementoCodes()) {
            Integer count = jdbc.queryForObject("select count(*) from user_achievement where user_id = ? and achievement_code = ?", Integer.class, user, code);
            if (count == null || count == 0) throw bad("只能分享自己已获得的纪念");
        }
        jdbc.update("insert into town_visit_profile(user_id, enabled, style, updated_at) values(?,?,?,?) on duplicate key update enabled=values(enabled), style=values(style), updated_at=values(updated_at)", user, command.enabled(), command.style(), Timestamp.from(clock.instant()));
        jdbc.update("delete from town_visit_memento where user_id = ?", user);
        command.mementoCodes().stream().distinct().forEach(code -> jdbc.update("insert into town_visit_memento(user_id, achievement_code) values(?,?)", user, code));
        return profile(user);
    }
    public List<DirectoryEntry> directory(long user) {
        return jdbc.query("""
            select u.public_id, u.display_name from friend_relationship f
            join sys_user u on u.id = case when f.requester_user_id = ? then f.addressee_user_id else f.requester_user_id end
            join town_visit_profile p on p.user_id=u.id and p.enabled=true
            where ? in (f.requester_user_id,f.addressee_user_id) and f.status='ACCEPTED' and u.status='ACTIVE'
            order by u.display_name, u.public_id
            """, (rs,n) -> new DirectoryEntry(rs.getString(1),rs.getString(2)), user,user);
    }
    @Transactional(readOnly = true)
    public Profile visit(long user, String owner) { return profile(requireVisit(user, owner)); }
    private long requireVisit(long user, String owner) {
        long peer = friends.requirePeerId(user, owner);
        Boolean enabled = jdbc.query("select p.enabled from town_visit_profile p join sys_user u on u.id=p.user_id where p.user_id=? and u.status='ACTIVE' for share", rs -> rs.next() && rs.getBoolean(1), peer);
        if (!Boolean.TRUE.equals(enabled)) throw missing();
        return peer;
    }
    private Profile profile(long owner) {
        List<Memento> mementos = jdbc.query("""
            select a.code,a.name,a.trigger_text,ua.earned_at from town_visit_memento m
            join user_achievement ua on ua.user_id=m.user_id and ua.achievement_code=m.achievement_code
            join achievement a on a.code=m.achievement_code
            where m.user_id=? order by ua.earned_at desc,a.code limit 4
            """, (rs,n) -> new Memento(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()), owner);
        return jdbc.queryForObject("select u.public_id,u.display_name,coalesce(p.enabled,false),coalesce(p.style,'original') from sys_user u left join town_visit_profile p on p.user_id=u.id where u.id=?",
            (rs,n) -> new Profile(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getString(4),mementos), owner);
    }
    @Transactional
    public Postcard send(long user, String owner, SendPostcard command) {
        if (command == null || command.body() == null || command.body().trim().isEmpty() || command.body().length()>300
            || command.requestKey() == null || !command.requestKey().matches("[a-zA-Z0-9_-]{8,64}")) throw bad("请填写 1–300 字明信片");
        // Serialize retries for this sender; the unique key also protects independent requests.
        jdbc.queryForObject("select id from sys_user where id=? for update", Long.class, user);
        long peer = requireVisit(user, owner);
        var prior = jdbc.query("select owner_user_id,body,public_id from town_visit_postcard where sender_user_id=? and request_key=?",
            (rs,n) -> new String[]{Long.toString(rs.getLong(1)),rs.getString(2),rs.getString(3)}, user, command.requestKey());
        if (!prior.isEmpty()) {
            var row = prior.getFirst();
            if (!row[0].equals(Long.toString(peer)) || !row[1].equals(command.body().trim())) throw new ApiException(HttpStatus.CONFLICT,"POSTCARD_RETRY_CHANGED","这次重试的内容已改变，请重新发送");
            return postcard(row[2]);
        }
        String id = ids.next();
        jdbc.update("insert into town_visit_postcard(public_id,owner_user_id,sender_user_id,request_key,body,created_at) values(?,?,?,?,?,?)", id,peer,user,command.requestKey(),command.body().trim(),Timestamp.from(clock.instant()));
        return postcard(id);
    }
    public List<Postcard> received(long user) {
        return jdbc.query("select p.public_id,u.display_name,p.body,p.created_at from town_visit_postcard p join sys_user u on u.id=p.sender_user_id where p.owner_user_id=? and p.owner_deleted=false and p.sender_deleted=false order by p.created_at desc,p.id desc limit 100",
            (rs,n) -> new Postcard(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()), user);
    }
    @Transactional
    public void remove(long user, String publicId) {
        int changed = jdbc.update("update town_visit_postcard set owner_deleted=true where public_id=? and owner_user_id=?",publicId,user);
        if (changed == 0) changed = jdbc.update("update town_visit_postcard set sender_deleted=true where public_id=? and sender_user_id=?",publicId,user);
        if (changed == 0) throw missing();
    }
    private Postcard postcard(String id) {
        return jdbc.queryForObject("select p.public_id,u.display_name,p.body,p.created_at from town_visit_postcard p join sys_user u on u.id=p.sender_user_id where p.public_id=?",
            (rs,n) -> new Postcard(rs.getString(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()), id);
    }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"VISIT_UNAVAILABLE","这处分享暂不可访问"); }
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_VISIT",message); }
}
