package com.betterself.growth.town.companion.interfaces;

import com.betterself.growth.town.companion.application.CompanionService;
import com.betterself.growth.goal.QuickTaskService;
import com.betterself.growth.shared.api.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"app.ai.provider=mock"})
@AutoConfigureMockMvc
class CompanionIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access",()->true);
        r.add("app.security.jwt-secret",()->"test-only-secret-at-least-thirty-two-bytes");
        r.add("app.security.mfa-encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));r.add("app.security.secure-cookies",()->false);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanionService service;
    @Autowired QuickTaskService tasks;
    @Test void independentPersistentWorldsConcurrentIdempotencyAndPrivateTasks() throws Exception {
        Session a=register("companion-a@example.test"),b=register("companion-b@example.test");
        mvc.perform(get("/api/v1/town/companion")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/town/companion").cookie(a.cookie)).andExpect(jsonPath("$.data.joined").value(false));
        try(var pool=Executors.newFixedThreadPool(4)){
            List<Future<String>> joins=new ArrayList<>();
            for(int i=0;i<4;i++)joins.add(pool.submit(()->service.join(a.id,new CompanionService.Join("小我","Asia/Shanghai")).world().id));
            Set<String> ids=new HashSet<>();for(var join:joins)ids.add(join.get(10,TimeUnit.SECONDS));assertThat(ids).hasSize(1);
        }
        service.join(b.id,new CompanionService.Join("邻居","Asia/Shanghai"));
        assertThat(service.get(a.id).world().id).isNotEqualTo(service.get(b.id).world().id);
        String task=tasks.create(a.id,"companion-task-001",new QuickTaskService.Command("私密任务不要传播",LocalDate.now())).publicId();
        var command=new CompanionService.Command("focus-command-001","focus","explicit",task,1);
        assertThatThrownBy(()->service.submit(b.id,command)).isInstanceOf(ApiException.class);
        try(var pool=Executors.newFixedThreadPool(4)){
            List<Future<?>> calls=new ArrayList<>();for(int i=0;i<4;i++)calls.add(pool.submit(()->service.submit(a.id,command)));
            for(var call:calls)call.get(10,TimeUnit.SECONDS);
        }
        var world=service.get(a.id).world();assertThat(world.intents).hasSize(1);assertThat(world.focus.taskId()).isEqualTo(task);
        assertThat(world.memories).noneMatch(m->m.text().contains("私密"));
        assertThat(service.get(b.id).world().focus).isNull();
        service.cancel(a.id,command.id());service.submit(a.id,command);
        assertThat(service.get(a.id).world().focus).isNull();assertThat(service.get(a.id).world().intents).hasSize(1);
        assertThatThrownBy(()->service.submit(a.id,new CompanionService.Command(command.id(),"rest","explicit",null,1))).isInstanceOf(ApiException.class);
        service.submit(a.id,new CompanionService.Command("focus-command-002","focus","explicit",task,1));
        jdbc.update("update town_companion_world set state_json=json_set(state_json,'$.focus.endsAt',?) where user_id=?",java.time.Instant.now().minusSeconds(1).toString(),a.id);
        service.advance(a.id);
        assertThat(service.get(a.id).world().focus).isNull();
        assertThat(service.get(a.id).world().intents.get(1).status).isEqualTo("done");
        assertThat(jdbc.queryForObject("select count(*) from task_event where user_id=?",Integer.class,a.id)).isZero();
        assertThat(jdbc.queryForObject("select status from task_schedule where user_id=?",String.class,a.id)).isEqualTo("PLANNED");
        mvc.perform(get("/api/v1/town/companion").cookie(a.cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.world.residents.length()").value(4));
    }
    private Session register(String email)throws Exception{
        var response=mvc.perform(post("/api/v1/auth/register").contentType("application/json").content("""
        {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Companion","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
        """.formatted(email))).andExpect(status().isCreated()).andReturn();
        return new Session(jdbc.queryForObject("select id from sys_user where email_normalized=?",Long.class,email),response.getResponse().getCookie("access_token"));
    }
    record Session(long id,Cookie cookie){}
}
