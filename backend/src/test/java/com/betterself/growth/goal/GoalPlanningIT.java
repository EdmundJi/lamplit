package com.betterself.growth.goal;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class GoalPlanningIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        registry.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        registry.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.security.secure-cookies", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void enforcesGoalAndDimensionPoliciesAndMaterializesSchedulesIdempotently() throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(registrationJson("planning@example.test")))
            .andExpect(status().isCreated())
            .andReturn();
        Cookie access = registration.getResponse().getCookie("access_token");
        Cookie csrf = registration.getResponse().getCookie("csrf_token");

        mvc.perform(post("/api/v1/dimensions")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"code\":\"deep_work\",\"name\":\"Deep Work\",\"description\":\"Focused practice\"}"))
            .andExpect(status().isCreated());
        String customDimensionId = jdbc.queryForObject(
            "select public_id from growth_dimension where code = 'deep_work'",
            String.class
        );
        String systemDimensionId = jdbc.queryForObject(
            "select public_id from growth_dimension where is_system = 1 order by id limit 1",
            String.class
        );
        String systemDimensionCode = jdbc.queryForObject(
            "select code from growth_dimension where is_system = 1 order by id limit 1",
            String.class
        );

        createGoal(access, csrf, customDimensionId, "Primary goal", "2026-08-03", "2026-08-30", 201);
        createGoal(access, csrf, systemDimensionId, "Second goal", "2026-08-03", "2026-08-30", 201);
        createGoal(access, csrf, systemDimensionId, "Third goal", "2026-08-03", "2026-08-30", 201);
        createGoal(access, csrf, systemDimensionId, "Fourth goal", "2026-08-03", "2026-08-30", 409);
        createGoal(access, csrf, systemDimensionId, "Too short", "2026-08-03", "2026-08-10", 422);

        String goalId = jdbc.queryForObject(
            "select public_id from growth_goal where title = 'Primary goal'",
            String.class
        );
        changeGoal(access, csrf, goalId, "pause", 200);
        createGoal(access, csrf, systemDimensionId, "Replacement goal", "2026-08-03", "2026-08-30", 201);
        changeGoal(access, csrf, goalId, "resume", 409);
        String replacementId = jdbc.queryForObject(
            "select public_id from growth_goal where title = 'Replacement goal'",
            String.class
        );
        mvc.perform(delete("/api/v1/goals/{id}", replacementId)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue()))
            .andExpect(status().isOk());
        changeGoal(access, csrf, goalId, "resume", 200);

        mvc.perform(delete("/api/v1/dimensions/{id}", customDimensionId)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue()))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
            "select archived_at is not null from growth_dimension where public_id = ?",
            Boolean.class,
            customDimensionId
        )).isTrue();

        mvc.perform(post("/api/v1/plans/weekly")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("""
                    {"goalPublicId":"%s","weekStartDate":"2026-08-03","timezone":"Asia/Shanghai"}
                    """.formatted(goalId)))
            .andExpect(status().isCreated());
        String planId = jdbc.queryForObject("select public_id from weekly_plan", String.class);

        mvc.perform(post("/api/v1/tasks")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("""
                    {
                      "weeklyPlanPublicId":"%s",
                      "title":"Review data structures",
                      "estimatedMinutes":30,
                      "difficulty":2,
                      "rrule":"FREQ=WEEKLY;BYDAY=MO,WE,FR",
                      "dimensionWeights":{"%s":10},
                      "plannedLocalTime":"07:30",
                      "activeFrom":"2026-08-03",
                      "activeUntil":"2026-08-09"
                    }
                    """.formatted(planId, systemDimensionCode)))
            .andExpect(status().isCreated());
        String taskId = jdbc.queryForObject("select public_id from user_task", String.class);

        mvc.perform(patch("/api/v1/tasks/{id}", taskId)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"title\":\"Review graphs\",\"estimatedMinutes\":45}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Review graphs"))
            .andExpect(jsonPath("$.data.estimatedMinutes").value(45));

        mvc.perform(patch("/api/v1/plans/weekly/{id}", planId)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("{\"status\":\"CONFIRMED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        materialize(access, csrf, planId);
        materialize(access, csrf, planId);
        Integer schedules = jdbc.queryForObject("select count(*) from task_schedule", Integer.class);
        Integer uniqueSchedules = jdbc.queryForObject(
            "select count(distinct concat(task_id, ':', planned_start_at)) from task_schedule",
            Integer.class
        );
        assertThat(schedules).isEqualTo(3);
        assertThat(uniqueSchedules).isEqualTo(schedules);

        MvcResult otherRegistration = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content(registrationJson("planning-other@example.test")))
            .andExpect(status().isCreated())
            .andReturn();
        Cookie otherAccess = otherRegistration.getResponse().getCookie("access_token");
        mvc.perform(get("/api/v1/tasks/{id}", taskId).cookie(otherAccess))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("TASK_NOT_FOUND"));

        mvc.perform(get("/api/v1/does-not-exist").cookie(access))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.data.code").value("RESOURCE_NOT_FOUND"));
    }

    private void createGoal(
        Cookie access,
        Cookie csrf,
        String dimensionId,
        String title,
        String start,
        String end,
        int expectedStatus
    ) throws Exception {
        mvc.perform(post("/api/v1/goals")
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue())
                .contentType("application/json")
                .content("""
                    {
                      "dimensionPublicId":"%s",
                      "title":"%s",
                      "description":"Observable completion criteria",
                      "startDate":"%s",
                      "endDate":"%s"
                    }
                    """.formatted(dimensionId, title, start, end)))
            .andExpect(status().is(expectedStatus));
    }

    private void materialize(Cookie access, Cookie csrf, String planId) throws Exception {
        mvc.perform(post("/api/v1/plans/weekly/{id}/materialize", planId)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue()))
            .andExpect(status().isOk());
    }

    private void changeGoal(Cookie access, Cookie csrf, String goalId, String action, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/goals/{id}/{action}", goalId, action)
                .cookie(access, csrf)
                .header("X-CSRF-Token", csrf.getValue()))
            .andExpect(status().is(expectedStatus));
    }

    private String registrationJson(String email) {
        return """
            {
              "email":"%s",
              "password":"Correct-Horse-Battery-2026!",
              "displayName":"Planning User",
              "birthDate":"1990-01-01",
              "timezone":"Asia/Shanghai",
              "consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}
            }
            """.formatted(email);
    }
}
