-- EDD-DICT-DSN-CR-001：数据字典初始结构（V1）
-- 约定：
--   1. 非破坏性：仅 CREATE TABLE IF NOT EXISTS，不含 DROP/TRUNCATE/业务数据变更；
--   2. 不绑定 schema：表名不带 schema 前缀，目标 schema 由当前数据源决定；
--   3. 结构与原 doc/sql/init.sql 定义保持一致（字段顺序、类型、NULL、默认值、注释、PK、UK、引擎、字符集）。

CREATE TABLE IF NOT EXISTS `tb_dictionary_category`
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

CREATE TABLE IF NOT EXISTS `tb_dictionary_column`
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

CREATE TABLE IF NOT EXISTS `tb_dictionary`
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
