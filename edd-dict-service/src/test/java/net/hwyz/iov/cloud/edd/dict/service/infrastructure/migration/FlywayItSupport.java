package net.hwyz.iov.cloud.edd.dict.service.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EDD-DICT-DSN-CR-001 §10.2/§10.3 集成验证支撑：在真实 MySQL 上以临时 schema 验证 Flyway 迁移行为。
 *
 * <p>该支撑类仅存在于测试源码，不引入任何生产 Flyway Bean；连接信息通过系统属性或环境变量提供，
 * 缺失时集成用例以“跳过并说明原因”结束，避免把未执行场景描述为通过。</p>
 */
abstract class FlywayItSupport {

    static final String MIGRATION_LOCATION = "classpath:db/migration";
    static final String INIT_SCRIPT = "/db/migration/V1__init_dictionary_schema.sql";

    static final String[] BUSINESS_TABLES = {
            "tb_dictionary_category", "tb_dictionary_column", "tb_dictionary"
    };

    private static final String HOST = systemPropertyOrEnv("dict.it.mysql.host", "DICT_IT_MYSQL_HOST", "127.0.0.1");
    private static final String PORT = systemPropertyOrEnv("dict.it.mysql.port", "DICT_IT_MYSQL_PORT", "3306");
    private static final String USERNAME = systemPropertyOrEnv("dict.it.mysql.username", "DICT_IT_MYSQL_USERNAME", "root");
    private static final String PASSWORD = systemPropertyOrEnv("dict.it.mysql.password", "DICT_IT_MYSQL_PASSWORD", "");

    private static final String URL_PARAMS = "?useUnicode=true&characterEncoding=utf8&allowMultiQueries=true"
            + "&allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC"
            + "&forceConnectionTimeZoneToSession=true&preserveInstants=true";

    private static Boolean available;

    private static String systemPropertyOrEnv(String property, String env, String defaultValue) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** 无 schema 的连接串，用于建库/删库。 */
    static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + URL_PARAMS;
    }

    static String schemaUrl(String schema) {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + schema + URL_PARAMS;
    }

    /** MySQL 不可用时中止当前用例并给出去重后的原因，不计为通过。 */
    static void assumeMysqlAvailable() {
        if (available == null) {
            try (Connection ignored = DriverManager.getConnection(serverUrl(), USERNAME, PASSWORD)) {
                available = Boolean.TRUE;
            } catch (SQLException e) {
                available = Boolean.FALSE;
                Assumptions.abort("MySQL 集成测试未执行：无法连接 " + serverUrl() + "（" + e.getMessage() + "）");
            }
        }
        Assumptions.assumeTrue(available, "MySQL 集成测试未执行：数据源不可用");
    }

    /** 创建唯一临时 schema。 */
    static String createSchema(String prefix) throws SQLException {
        String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(serverUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` DEFAULT CHARSET utf8mb4");
        }
        return schema;
    }

    /** 删除临时 schema（仅限本测试自建对象，不触碰既有业务库）。 */
    static void dropSchema(String schema) {
        if (schema == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        } catch (SQLException ignored) {
            // 清理失败不影响验收结论，临时 schema 名称带随机后缀便于人工识别
        }
    }

    static Flyway flyway(String schema, boolean baselineOnMigrate) {
        return Flyway.configure()
                .dataSource(schemaUrl(schema), USERNAME, PASSWORD)
                .locations(MIGRATION_LOCATION)
                .encoding(StandardCharsets.UTF_8)
                .baselineOnMigrate(baselineOnMigrate)
                .cleanDisabled(true)
                .load();
    }

    static Connection connection(String schema) throws SQLException {
        return DriverManager.getConnection(schemaUrl(schema), USERNAME, PASSWORD);
    }

    /** 执行多语句脚本（依赖 allowMultiQueries）。 */
    static void execute(String schema, String sql) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static void executeUpdate(String schema, String... sqls) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            for (String sql : sqls) {
                statement.execute(sql);
            }
        }
    }

    static List<Map<String, Object>> query(String schema, String sql, Object... args) throws SQLException {
        try (Connection connection = connection(schema);
             java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int columns = resultSet.getMetaData().getColumnCount();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columns; i++) {
                        row.put(resultSet.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT),
                                resultSet.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    static long count(String schema, String sql, Object... args) throws SQLException {
        return ((Number) query(schema, sql, args).get(0).values().iterator().next()).longValue();
    }

    static String initScript() throws IOException {
        try (InputStream inputStream = FlywayItSupport.class.getResourceAsStream(INIT_SCRIPT)) {
            if (inputStream == null) {
                throw new IOException("迁移脚本不在测试 classpath 上：" + INIT_SCRIPT);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 更名前的手工初始化定义（原 doc/sql/init.sql，去掉 schema 前缀）。
     * 用于证明 V1 与既有结构语义一致。
     */
    static final String LEGACY_INIT_SQL = """
            CREATE TABLE `tb_dictionary_category`
            (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `pid`         BIGINT                DEFAULT NULL COMMENT '上级分类ID',
                `name`        VARCHAR(255) NOT NULL COMMENT '分类名称',
                `code`        VARCHAR(255) NOT NULL COMMENT '分类代码',
                `type`        VARCHAR(255)          DEFAULT NULL COMMENT '分类类型',
                `enable`      TINYINT      NOT NULL COMMENT '是否启用',
                `sort`        INT          NOT NULL COMMENT '排序',
                `description` VARCHAR(255)          DEFAULT NULL COMMENT '备注',
                `create_time` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                `create_by`   BIGINT                DEFAULT NULL COMMENT '创建者',
                `modify_time` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
                `modify_by`   BIGINT                DEFAULT NULL COMMENT '修改者',
                `row_version` INT                   DEFAULT NULL COMMENT '记录版本',
                `row_valid`   TINYINT               DEFAULT NULL COMMENT '是否有效',
                PRIMARY KEY (`id`),
                UNIQUE KEY (`code`)
            ) ENGINE = InnoDB
              DEFAULT CHARSET = utf8mb4 COMMENT ='数据字典分类';
            CREATE TABLE `tb_dictionary_column`
            (
                `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `category_id` BIGINT       NOT NULL COMMENT '分类ID',
                `name`        VARCHAR(255) NOT NULL COMMENT '列名称',
                `code`        VARCHAR(255) NOT NULL COMMENT '列代码',
                `type`        VARCHAR(255) NOT NULL COMMENT '列类型',
                `len`         INT          NOT NULL COMMENT '列长度',
                `nullable`    TINYINT      NOT NULL COMMENT '列是否可为空',
                `uniq`        TINYINT      NOT NULL COMMENT '列是否唯一',
                `value_type`  SMALLINT     NOT NULL COMMENT '值类型：0-无限制，1-表关联',
                `value_range` VARCHAR(255)          DEFAULT NULL COMMENT '值范围',
                `sort`        INT          NOT NULL COMMENT '排序',
                `description` VARCHAR(255)          DEFAULT NULL COMMENT '备注',
                `create_time` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                `create_by`   BIGINT                DEFAULT NULL COMMENT '创建者',
                `modify_time` TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
                `modify_by`   BIGINT                DEFAULT NULL COMMENT '修改者',
                `row_version` INT                   DEFAULT NULL COMMENT '记录版本',
                `row_valid`   TINYINT               DEFAULT NULL COMMENT '是否有效',
                PRIMARY KEY (`id`)
            ) ENGINE = InnoDB
              DEFAULT CHARSET = utf8mb4 COMMENT ='数据字典结构';
            CREATE TABLE `tb_dictionary`
            (
                `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
                `name`            VARCHAR(255) NOT NULL COMMENT '字典名称',
                `code`            VARCHAR(255) NOT NULL COMMENT '字典代码',
                `category_code`   VARCHAR(255) NOT NULL COMMENT '字典分类代码',
                `select_column`   JSON         NOT NULL COMMENT '所选列',
                `where_condition` JSON         NOT NULL COMMENT '指定条件',
                `enable`          TINYINT      NOT NULL COMMENT '是否启用',
                `sort`            INT          NOT NULL COMMENT '排序',
                `description`     VARCHAR(255)          DEFAULT NULL COMMENT '备注',
                `create_time`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                `create_by`       BIGINT                DEFAULT NULL COMMENT '创建者',
                `modify_time`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
                `modify_by`       BIGINT                DEFAULT NULL COMMENT '修改者',
                `row_version`     INT                   DEFAULT NULL COMMENT '记录版本',
                `row_valid`       TINYINT               DEFAULT NULL COMMENT '是否有效',
                PRIMARY KEY (`id`),
                UNIQUE KEY (`code`)
            ) ENGINE = InnoDB
              DEFAULT CHARSET = utf8mb4 COMMENT ='数据字典';
            """;

    /** Flyway 9 的 MySQL 历史表定义，用于构造“已有基线”场景。 */
    static final String CREATE_HISTORY_TABLE = """
            CREATE TABLE `flyway_schema_history` (
                `installed_rank` INT NOT NULL,
                `version` VARCHAR(50),
                `description` VARCHAR(200) NOT NULL,
                `type` VARCHAR(20) NOT NULL,
                `script` VARCHAR(1000) NOT NULL,
                `checksum` INT,
                `installed_by` VARCHAR(100) NOT NULL,
                `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `execution_time` INT NOT NULL,
                `success` BOOL NOT NULL,
                PRIMARY KEY (`installed_rank`),
                INDEX `flyway_schema_history_s_idx` (`success`)
            );
            """;

    static String baselineRow(String version) {
        return "INSERT INTO `flyway_schema_history` VALUES (1, '" + version
                + "', '<< Flyway Baseline >>', 'BASELINE', '<< Flyway Baseline >>',"
                + " NULL, 'root', NOW(), 0, 1)";
    }

    /** 结构快照：列定义 + 索引 + 表选项，用于对比 V1 与既有定义。 */
    static Map<String, List<String>> structureSnapshot(String schema) throws SQLException {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        snapshot.put("tables", flatten(query(schema,
                "SELECT table_name, engine, table_collation, table_comment"
                        + " FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_name LIKE 'tb_%'"
                        + " ORDER BY table_name", schema)));
        snapshot.put("columns", flatten(query(schema,
                "SELECT table_name, ordinal_position, column_name, column_type, is_nullable,"
                        + " IFNULL(column_default, '<null>') AS column_default, extra, column_comment, collation_name"
                        + " FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name LIKE 'tb_%'"
                        + " ORDER BY table_name, ordinal_position", schema)));
        snapshot.put("indexes", flatten(query(schema,
                "SELECT table_name, index_name, non_unique, seq_in_index, column_name"
                        + " FROM information_schema.statistics"
                        + " WHERE table_schema = ? AND table_name LIKE 'tb_%'"
                        + " ORDER BY table_name, index_name, seq_in_index", schema)));
        return snapshot;
    }

    private static List<String> flatten(List<Map<String, Object>> rows) {
        List<String> flattened = new ArrayList<>(rows.size());
        rows.forEach(row -> flattened.add(String.valueOf(row.values())));
        return flattened;
    }
}
