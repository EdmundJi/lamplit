-- The legacy town/ module (43 main-source classes) was removed in an earlier batch.
-- These tables were built by V17-V28 for that module and no Java code reads or writes
-- them anymore (verified by grepping backend/src/main/java table-name by table-name).
-- town_companion_world (V29) and town_companion_model_usage (V30) are intentionally
-- kept: they back the current companion module (JdbcWorldStore / JdbcModelUsage).
--
-- Drop order respects the two inter-town foreign keys (town_npc_knowledge -> town_fact,
-- town_invitation -> town_event): the referencing table is dropped before the table it
-- references.

-- Foreign keys are disabled for the duration: every table below is going away, so
-- their inter-dependencies (town_npc_knowledge -> town_fact, town_invitation ->
-- town_event) are irrelevant, and relying on hand-sorted drop order turned out to be
-- fragile -- a mid-edit version of this file with the wrong order reached the shared
-- dev server, failed, and left Flyway refusing to start the app at all.
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS town_npc_message;
DROP TABLE IF EXISTS town_npc_memory;
DROP TABLE IF EXISTS town_quota;
DROP TABLE IF EXISTS town_reflection;
DROP TABLE IF EXISTS town_npc_promise;
DROP TABLE IF EXISTS town_presence;
DROP TABLE IF EXISTS town_npc_knowledge;
DROP TABLE IF EXISTS town_fact;
DROP TABLE IF EXISTS town_bond;
DROP TABLE IF EXISTS town_npc_mood;
DROP TABLE IF EXISTS town_invitation;
DROP TABLE IF EXISTS town_event;
DROP TABLE IF EXISTS town_migration;
DROP TABLE IF EXISTS town_letter;
DROP TABLE IF EXISTS town_confidant_thread;
DROP TABLE IF EXISTS town_npc;
DROP TABLE IF EXISTS town_society_run;
DROP TABLE IF EXISTS town_initiative_budget;
DROP TABLE IF EXISTS town_daily_production;
DROP TABLE IF EXISTS town_presence_sample;
DROP TABLE IF EXISTS town_story_progress;
DROP TABLE IF EXISTS town_visit_profile;
DROP TABLE IF EXISTS town_visit_memento;
DROP TABLE IF EXISTS town_visit_postcard;

SET FOREIGN_KEY_CHECKS = 1;
