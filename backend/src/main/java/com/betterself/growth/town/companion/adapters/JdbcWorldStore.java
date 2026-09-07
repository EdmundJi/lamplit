package com.betterself.growth.town.companion.adapters;

import com.betterself.growth.town.companion.application.WorldStore;
import com.betterself.growth.town.companion.domain.CompanionWorld;
import com.betterself.growth.shared.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.*;

@Repository
public class JdbcWorldStore implements WorldStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public JdbcWorldStore(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json){this.jdbc=jdbc;this.tx=tx;this.json=json;}
    public CompanionWorld read(long userId){return jdbc.query("select state_json from town_companion_world where user_id=?",rs->rs.next()?decode(rs.getString(1)):null,userId);}
    public CompanionWorld update(long userId,Supplier<CompanionWorld> initial,UnaryOperator<CompanionWorld> operation){
        return tx.execute(status->{
            // Lock the existing user first: also serializes two simultaneous first-time joins.
            jdbc.queryForObject("select id from sys_user where id=? for update",Long.class,userId);
            CompanionWorld w=read(userId);
            if(w==null){
                if(initial==null)throw new ApiException(HttpStatus.CONFLICT,"COMPANION_NOT_JOINED","先搬进小街吧");
                w=initial.get();
                jdbc.update("insert into town_companion_world(user_id,state_json) values (?,?)",userId,encode(w));
            }
            long revision=w.revision;
            w=operation.apply(w);
            if(w.revision!=revision)jdbc.update("update town_companion_world set state_json=? where user_id=?",encode(w),userId);
            return w;
        });
    }
    public boolean ownsTask(long userId,String taskId){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from user_task where user_id=? and public_id=? and active=1)",Boolean.class,userId,taskId));}
    public String timezone(long userId){return jdbc.queryForObject("select timezone from sys_user where id=?",String.class,userId);}
    private CompanionWorld decode(String value){try{return json.readValue(value,CompanionWorld.class);}catch(Exception e){throw new IllegalStateException("Cannot decode companion save",e);}}
    private String encode(CompanionWorld w){try{return json.writeValueAsString(w);}catch(Exception e){throw new IllegalStateException("Cannot encode companion save",e);}}
}
