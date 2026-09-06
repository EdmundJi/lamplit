package com.betterself.growth.town;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TownMigrationUpgradeIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void shippedV23ChecksumIsPreservedAndV24UpgradesExistingRowsWithoutDataLoss() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("23").load().migrate();
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("select checksum from flyway_schema_history where version='23'",Integer.class))
            .isEqualTo(-455111432);
        jdbc.update("""
            insert into sys_user(public_id,email,email_normalized,password_hash,display_name,birth_date,timezone)
            values ('01JUPGRADEOWNER00000000001','upgrade@example.test','upgrade@example.test','fixture','Owner','1990-01-01','Asia/Shanghai')
            """);
        long user=jdbc.queryForObject("select id from sys_user where email='upgrade@example.test'",Long.class);
        jdbc.update("""
            insert into town_presence_sample(user_id,sampled_at,scene,x,y) values
            (?,'2026-09-06 01:10:05','cafe',1,1),
            (?,'2026-09-06 01:10:25','cafe',2,2),
            (?,'2026-09-06 01:11:05','cafe',3,3)
            """,user,user,user);
        jdbc.update("""
            insert into town_migration(public_id,npc_code,from_user_id,to_user_id,paired_with_npc_code,moved_at)
            values ('01JUPGRADEMOVE000000000001','TOWNIE_01',?,?,'TOWNIE_02','2026-09-06 03:00:00')
            """,user,user);
        jdbc.update("""
            insert into town_confidant_thread(public_id,user_id,direction,body,written_at)
            values ('01JUPGRADELETTER000000001',?,'OUT','preserve private letter','2026-09-05 01:00:00')
            """,user);
        var latest=Flyway.configure().dataSource(source).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("select count(*) from town_presence_sample",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(sample_minute) from town_presence_sample",Integer.class)).isEqualTo(2);
        long bucket=jdbc.queryForObject("select min(sample_minute) from town_presence_sample",Long.class);
        assertThat(jdbc.update("insert ignore into town_presence_sample(user_id,sampled_at,sample_minute,scene,x,y) values (?,'2026-09-06 01:10:50',?,'cafe',4,4)",user,bucket)).isZero();
        assertThat(jdbc.queryForObject("select local_date=DATE(moved_at) from town_migration",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select body from town_confidant_thread",String.class)).isEqualTo("preserve private letter");
        assertThat(jdbc.queryForObject("select checksum from flyway_schema_history where version='23'",Integer.class)).isEqualTo(-455111432);
    }
}
