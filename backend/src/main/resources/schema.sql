-- FlowMind 建表脚本（由统筹维护，agent 不得擅自修改）
-- 执行：mysql -uroot -p < schema.sql

CREATE DATABASE IF NOT EXISTS flowmind CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE flowmind;

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    email         VARCHAR(100) NOT NULL DEFAULT '',
    role          VARCHAR(20)  NOT NULL DEFAULT 'user',
    status        TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB COMMENT='用户表';

-- 工作流表
CREATE TABLE IF NOT EXISTS workflows (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status      VARCHAR(20)  NOT NULL DEFAULT 'draft',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='工作流表';

-- 节点表
CREATE TABLE IF NOT EXISTS workflow_nodes (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    workflow_id BIGINT       NOT NULL,
    node_key    VARCHAR(50)  NOT NULL,
    type        VARCHAR(20)  NOT NULL COMMENT 'input/llm/condition/http',
    name        VARCHAR(100) NOT NULL DEFAULT '',
    config      TEXT         NOT NULL COMMENT 'JSON 配置',
    position_x  DOUBLE       NOT NULL DEFAULT 0,
    position_y  DOUBLE       NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_workflow_node (workflow_id, node_key),
    KEY idx_workflow_id (workflow_id)
) ENGINE=InnoDB COMMENT='工作流节点表';

-- 边表
CREATE TABLE IF NOT EXISTS workflow_edges (
    id               BIGINT      PRIMARY KEY AUTO_INCREMENT,
    workflow_id      BIGINT      NOT NULL,
    source_node_key  VARCHAR(50) NOT NULL,
    target_node_key  VARCHAR(50) NOT NULL,
    source_handle    VARCHAR(50) NULL COMMENT '条件分支名，普通边为 NULL',
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_workflow_id (workflow_id)
) ENGINE=InnoDB COMMENT='工作流边表';

-- 执行记录表
CREATE TABLE IF NOT EXISTS executions (
    id            BIGINT        PRIMARY KEY AUTO_INCREMENT,
    workflow_id   BIGINT        NOT NULL,
    user_id       BIGINT        NOT NULL,
    status        VARCHAR(20)   NOT NULL DEFAULT 'running' COMMENT 'running/success/failed/cancelled',
    input         TEXT          NULL COMMENT '入参 JSON',
    output        TEXT          NULL COMMENT '输出 JSON',
    total_tokens  INT           NOT NULL DEFAULT 0,
    total_cost    DECIMAL(10,6) NOT NULL DEFAULT 0,
    duration_ms   BIGINT        NOT NULL DEFAULT 0,
    error_message TEXT          NULL,
    started_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at   DATETIME      NULL,
    KEY idx_workflow_id (workflow_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='执行记录表';

-- 节点执行日志表
CREATE TABLE IF NOT EXISTS execution_node_logs (
    id            BIGINT      PRIMARY KEY AUTO_INCREMENT,
    execution_id  BIGINT      NOT NULL,
    node_key      VARCHAR(50) NOT NULL,
    node_name     VARCHAR(100) NOT NULL DEFAULT '',
    node_type     VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/success/failed/skipped',
    input         TEXT        NULL,
    output        TEXT        NULL,
    tokens        INT         NOT NULL DEFAULT 0,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    error_message TEXT        NULL,
    started_at    DATETIME    NULL,
    finished_at   DATETIME    NULL,
    KEY idx_execution_id (execution_id)
) ENGINE=InnoDB COMMENT='节点执行日志表';

-- 工具表
CREATE TABLE IF NOT EXISTS tools (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name          VARCHAR(50)  NOT NULL COMMENT '工具标识，如 weather_query',
    display_name  VARCHAR(100) NOT NULL,
    description   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '给 LLM 看的功能描述',
    method        VARCHAR(10)  NOT NULL DEFAULT 'GET',
    url_template  VARCHAR(500) NOT NULL COMMENT '支持 {{param}} 占位符',
    headers       TEXT         NULL COMMENT 'JSON',
    body_template TEXT         NULL,
    params_schema TEXT         NULL COMMENT 'JSON: [{key,label,type,required}]',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB COMMENT='工具表';

-- 知识库表（阶梯 5 RAG，SQL 来自 docs/阶梯5-RAG技术方案.md §2，统筹定稿）
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) DEFAULT '',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id)
) ENGINE=InnoDB COMMENT='知识库表';

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    kb_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    chunk_count INT DEFAULT 0,
    file_size INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb (kb_id)
) ENGINE=InnoDB COMMENT='知识库文档表';

-- 预置演示工具
INSERT INTO tools (name, display_name, description, method, url_template, params_schema) VALUES
('weather_query', '天气查询', '查询指定城市的实时天气情况', 'GET',
 'https://wttr.in/{{city}}?format=j1',
 '[{"key":"city","label":"城市","type":"text","required":true}]'),
('hitokoto', '一言', '随机获取一句名人名言或语录', 'GET',
 'https://v1.hitokoto.cn/',
 '[]')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
