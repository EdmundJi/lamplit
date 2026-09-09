package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.MemoryStore;
import com.betterself.growth.town.companion.application.WorldStore;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.*;

/**
 * The world save in MySQL, minus the memories.
 *
 * <p>Since memories moved to their own files ({@link FileMemoryStore}), this class owns the seam:
 * it hydrates {@code w.memories} from the memory store on the way in, and takes them back out on
 * the way to the database, so {@code state_json} never carries a memory again. Everything between
 * those two points - all of {@code domain/**} - still sees one ordinary list on the world object and
 * did not have to change.
 *
 * <p>The consequence, named rather than hidden: rolling the world save back no longer rolls memories
 * back with it. Files are the authority for what residents remember (docs/02-modules.md), and this
 * is the price of there being exactly one authority instead of two that can disagree.
 */
@Repository
public class JdbcWorldStore implements WorldStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MemoryStore memories;
    public JdbcWorldStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,MemoryStore memories){
        this.jdbc=jdbc;this.tx=tx;this.json=json;this.memories=memories;
    }
    public CompanionWorld read(long userId){
        CompanionWorld w=jdbc.query("select state_json from town_companion_world where user_id=?",rs->rs.next()?decode(rs.getString(1)):null,userId);
        if(w!=null)hydrate(userId,w);
        return w;
    }
    public CompanionWorld update(long userId,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> operation){
        return tx.execute(status->{
            // Lock the existing user first: also serializes two simultaneous first-time joins.
            jdbc.queryForObject("select id from sys_user where id=? for update",Long.class,userId);
            CompanionWorld w=read(userId);
            if(w==null){
                if(initial==null)throw new ApiException(HttpStatus.CONFLICT,"COMPANION_NOT_JOINED","先搬进小街吧");
                w=initial.get();
                jdbc.update("insert into town_companion_world(user_id,state_json) values (?,?)",userId,persist(userId,w));
            }
            long revision=w.revision;
            w=operation.apply(w);
            if(w.revision!=revision)jdbc.update("update town_companion_world set state_json=? where user_id=?",persist(userId,w),userId);
            return w;
        });
    }
    public boolean ownsTask(long userId,String taskId){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_task where user_id=? and public_id=? and active=1)",Boolean.class,userId,taskId));}
    public String timezone(long userId){return jdbc.queryForObject("select timezone from sys_user where id=?",String.class,userId);}

    /** Fill in what each resident remembers, asking the store one owner at a time - the store has no
     * "everything in this world" read on purpose (see {@link MemoryStore}). The avatar is included
     * because it is a resident like any other; ids come from the save itself, so a hand-authored
     * neighbour is picked up without this class knowing anything about who lives here. */
    private void hydrate(long userId,CompanionWorld w){
        var owners=new LinkedHashSet<String>();
        w.residentStates.forEach(r->{if(r.id!=null)owners.add(r.id);});
        if(w.avatar!=null&&w.avatar.id()!=null)owners.add(w.avatar.id());
        var loaded=new ArrayList<CompanionWorld.Memory>();
        for(String owner:owners)loaded.addAll(memories.byOwner(userId,owner));
        w.memories=loaded;
    }
    /** Write the memories through to their files, then encode the world without them. */
    private String persist(long userId,CompanionWorld w){
        List<CompanionWorld.Memory> held=w.memories;
        memories.save(userId,held);
        w.memories=new ArrayList<>();
        try { return encode(w); } finally { w.memories=held; }
    }
    private CompanionWorld decode(String value){try{return json.readValue(value,CompanionWorld.class);}catch(Exception e){throw new IllegalStateException("Cannot decode companion save",e);}}
    private String encode(CompanionWorld w){try{return json.writeValueAsString(w);}catch(Exception e){throw new IllegalStateException("Cannot encode companion save",e);}}
}
