package net.hwyz.iov.cloud.edd.dict.service.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EDD-DICT-DSN-CR-001 §10.2 / §10.3：Flyway 迁移集成验证（真实 MySQL 临时 schema）。
 */
class FlywayMigrationTest extends FlywayItSupport {

    private String schema;

    @BeforeEach
    void setUp() throws SQLException {
        assumeMysqlAvailable();
        schema = createSchema("dict_it_cr001");
    }

    @AfterEach
    void tearDown() {
        dropSchema(schema);
    }

    @Test
    @DisplayName("TC-01 全新 schema：V1 创建三张业务表与迁移历史")
    void freshSchemaMigratesV1() throws SQLException {
        MigrateResult result = flyway(schema, true).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.migrations).singleElement()
                .satisfies(migration -> assertThat(migration.version).isEqualTo("1"));

        List<String> tables = tableNames(schema);
        assertThat(tables)
                .contains("tb_dictionary_category", "tb_dictionary_column", "tb_dictionary")
                .contains("flyway_schema_history");

        List<Map<String, Object>> history = query(schema,
                "SELECT version, type FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("version")).isEqualTo("1");
        assertThat(history.get(0).get("type")).isEqualTo("SQL");
        assertThat(count(schema, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-02 V1 结构与更名前手工初始化脚本一致（字段/默认值/注释/PK/UK/引擎/字符集）")
    void v1StructureEqualsLegacySchema() throws SQLException {
        String legacySchema = createSchema("dict_it_cr001_legacy");
        try {
            execute(legacySchema, LEGACY_INIT_SQL);
            flyway(schema, true).migrate();

            Map<String, List<String>> migrated = structureSnapshot(schema);
            Map<String, List<String>> legacy = structureSnapshot(legacySchema);

            assertThat(migrated.get("tables")).isEqualTo(legacy.get("tables")).hasSize(3);
            assertThat(migrated.get("columns")).isEqualTo(legacy.get("columns"));
            assertThat(migrated.get("indexes")).isEqualTo(legacy.get("indexes"));

            Map<String, Object> jsonColumns = query(schema,
                    "SELECT COUNT(*) AS json_columns FROM information_schema.columns"
                            + " WHERE table_schema = ? AND table_name = 'tb_dictionary'"
                            + " AND column_name IN ('select_column', 'where_condition') AND data_type = 'json'", schema)
                    .get(0);
            assertThat(((Number) jsonColumns.get("json_columns")).intValue()).isEqualTo(2);
        } finally {
            dropSchema(legacySchema);
        }
    }

    @Test
    @DisplayName("TC-03 重复迁移：不重复执行 V1 且业务数据保持")
    void repeatedMigrateIsIdempotent() throws SQLException {
        flyway(schema, true).migrate();
        executeUpdate(schema,
                "INSERT INTO tb_dictionary_category (name, code, enable, sort, create_time, modify_time)"
                        + " VALUES ('识别分类', 'identify', 1, 1, NOW(), NOW())");

        MigrateResult second = flyway(schema, true).migrate();

        assertThat(second.migrationsExecuted).isZero();
        assertThat(count(schema, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1'")).isEqualTo(1);
        assertThat(count(schema, "SELECT COUNT(*) FROM tb_dictionary_category WHERE code = 'identify'")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-04 既有 schema 无历史表：建立基线，业务表与数据不被重建/清空")
    void existingSchemaIsBaselinedAndDataPreserved() throws SQLException {
        execute(schema, LEGACY_INIT_SQL);
        executeUpdate(schema,
                "INSERT INTO tb_dictionary_category (name, code, enable, sort, create_time, modify_time)"
                        + " VALUES ('既有分类', 'legacy', 1, 1, NOW(), NOW())");

        MigrateResult result = flyway(schema, true).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();

        List<Map<String, Object>> baselines = query(schema,
                "SELECT version, type FROM flyway_schema_history WHERE type = 'BASELINE'");
        assertThat(baselines).singleElement()
                .satisfies(row -> assertThat(String.valueOf(row.get("version"))).isNotBlank());
        assertThat(count(schema,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND type = 'SQL'")).isZero();
        assertThat(count(schema, "SELECT COUNT(*) FROM tb_dictionary_category WHERE code = 'legacy'")).isEqualTo(1);
        assertThat(count(schema, "SELECT COUNT(*) FROM tb_dictionary_category")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-05 已有基线 0 的历史：V1 以 IF NOT EXISTS 执行且不删除既有数据")
    void historyWithBaselineZeroExecutesV1Safely() throws SQLException {
        execute(schema, LEGACY_INIT_SQL);
        execute(schema, CREATE_HISTORY_TABLE);
        executeUpdate(schema, baselineRow("0"));
        executeUpdate(schema,
                "INSERT INTO tb_dictionary_category (name, code, enable, sort, create_time, modify_time)"
                        + " VALUES ('既有分类', 'legacy', 1, 1, NOW(), NOW())");

        MigrateResult result = flyway(schema, true).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(count(schema, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND type = 'SQL'"))
                .isEqualTo(1);
        assertThat(count(schema, "SELECT COUNT(*) FROM tb_dictionary_category WHERE code = 'legacy'")).isEqualTo(1);
        assertThat(tableNames(schema)).contains("tb_dictionary_category", "tb_dictionary_column", "tb_dictionary");
    }

    @Test
    @DisplayName("TC-06 历史校验不一致：validate 失败且不自动 repair")
    void checksumMismatchFailsValidation() throws SQLException {
        flyway(schema, true).migrate();
        executeUpdate(schema,
                "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");

        Flyway flyway = flyway(schema, true);
        assertThatThrownBy(flyway::validate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");

        List<Map<String, Object>> history = query(schema,
                "SELECT type FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("type")).isEqualTo("SQL");
        assertThat(count(schema, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-07 迁移脚本静态校验：仅 IF NOT EXISTS 建表，无 DROP/TRUNCATE/schema 前缀/数据变更")
    void migrationScriptIsNonDestructive() throws Exception {
        String script = initScript();
        StringBuilder ddl = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.stripLeading().startsWith("--")) {
                ddl.append(line).append('\n');
            }
        }
        String normalized = ddl.toString().toLowerCase();

        assertThat(normalized).contains("create table if not exists `tb_dictionary_category`");
        assertThat(normalized).contains("create table if not exists `tb_dictionary_column`");
        assertThat(normalized).contains("create table if not exists `tb_dictionary`");

        assertThat(normalized).doesNotContain("drop table", "drop database", "truncate", "alter table");
        assertThat(normalized).doesNotContain("db_dictionary.");
        assertThat(normalized).doesNotContain("insert into", "update ", "delete from", "use ");
    }

    private static List<String> tableNames(String schema) throws SQLException {
        return query(schema,
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name", schema)
                .stream()
                .map(row -> String.valueOf(row.get("table_name")))
                .toList();
    }
}
