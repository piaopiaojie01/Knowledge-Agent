-- Knowledge Agent 数据库初始化
-- Phase 1: 只读知识库

CREATE DATABASE IF NOT EXISTS knowledge_agent
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE knowledge_agent;

-- 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(256) NOT NULL,
    display_name VARCHAR(128),
    role VARCHAR(32) NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    storage_used BIGINT NOT NULL DEFAULT 0 COMMENT '已用存储（字节）',
    storage_limit BIGINT NOT NULL DEFAULT 5368709120 COMMENT '存储上限（字节），默认 5GB',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    description TEXT,
    created_by BIGINT NOT NULL,
    is_public TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_created_by (created_by),
    CONSTRAINT fk_kb_creator FOREIGN KEY (created_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 文档表
CREATE TABLE IF NOT EXISTS documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    kb_id BIGINT NOT NULL,
    title VARCHAR(512) NOT NULL,
    content LONGTEXT,
    file_type VARCHAR(32) DEFAULT 'text' COMMENT 'text / pdf / markdown / image',
    doc_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / FAILED / ARCHIVED / DELETED',
    chunk_count INT DEFAULT 0 COMMENT '分块数量',
    file_size BIGINT COMMENT '原始文件大小（字节）',
    content_hash VARCHAR(64) COMMENT '文件内容 SHA-256，同 KB 下去重',
    uploaded_by BIGINT COMMENT '上传者用户 ID（删除时据此回收配额，旧数据可空）',
    embed_model VARCHAR(128) COMMENT '向量化使用的嵌入模型',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_kb_id (kb_id),
    INDEX idx_content_hash (content_hash),
    FULLTEXT INDEX ft_content (title, content),
    CONSTRAINT fk_doc_kb FOREIGN KEY (kb_id) REFERENCES knowledge_bases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 权限表
CREATE TABLE IF NOT EXISTS permissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL,
    permission_type VARCHAR(16) NOT NULL COMMENT 'READ / WRITE / ADMIN',
    granted_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_kb (user_id, kb_id),
    INDEX idx_kb_id (kb_id),
    CONSTRAINT fk_perm_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_perm_kb FOREIGN KEY (kb_id) REFERENCES knowledge_bases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 回答反馈表
CREATE TABLE IF NOT EXISTS feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64),
    question TEXT,
    answer TEXT,
    rating INT NOT NULL DEFAULT 0 COMMENT '评分',
    comment TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 种子数据 ====================

-- 管理员用户 密码: admin123 (BCrypt)
INSERT IGNORE INTO users (username, password_hash, display_name, role)
VALUES ('admin', '$2b$10$Ym0T0v8/BdfIEeHVrtXhgO/JXdqPHL7z9SnLFSoKw4K4EDN6Qn/PK', '系统管理员', 'ADMIN');

-- 只读用户 密码: reader123 (BCrypt)
INSERT IGNORE INTO users (username, password_hash, display_name, role)
VALUES ('reader', '$2b$10$mg9e/l20lSINxp2WUj4ys.QHHL6YsqNYWkD7DJActlmxIDnUuEO0u', '只读用户', 'USER');

-- 示例知识库
INSERT IGNORE INTO knowledge_bases (id, name, description, created_by, is_public)
VALUES (1, '技术文档库', '公司内部技术文档与架构设计', 1, 1);

INSERT IGNORE INTO knowledge_bases (id, name, description, created_by, is_public)
VALUES (2, '产品手册', '产品使用手册与FAQ', 1, 1);

-- 示例文档
INSERT IGNORE INTO documents (id, kb_id, title, content, file_type, doc_status)
VALUES (1, 1, '系统架构设计', '本系统采用微服务架构，包含API网关、用户服务、知识库服务等模块。各服务之间通过HTTP RESTful API进行通信，使用Redis作为缓存层，MySQL作为持久化存储。', 'text', 'ACTIVE');

INSERT IGNORE INTO documents (id, kb_id, title, content, file_type, doc_status)
VALUES (2, 1, '数据库设计规范', '所有表必须使用InnoDB引擎，字符集统一使用utf8mb4。表名使用小写字母和下划线命名，主键统一使用自增BIGINT类型。索引命名规范：主键 pk_表名，唯一索引 uk_字段名，普通索引 idx_字段名。', 'text', 'ACTIVE');

INSERT IGNORE INTO documents (id, kb_id, title, content, file_type, doc_status)
VALUES (3, 2, '产品快速入门', '欢迎使用我们的产品。首先请完成账号注册，然后创建您的第一个项目。在项目设置中配置相关参数后即可开始使用核心功能。', 'text', 'ACTIVE');

-- 权限分配
INSERT IGNORE INTO permissions (user_id, kb_id, permission_type, granted_by)
VALUES (1, 1, 'ADMIN', 1);

INSERT IGNORE INTO permissions (user_id, kb_id, permission_type, granted_by)
VALUES (1, 2, 'ADMIN', 1);

INSERT IGNORE INTO permissions (user_id, kb_id, permission_type, granted_by)
VALUES (2, 1, 'READ', 1);

INSERT IGNORE INTO permissions (user_id, kb_id, permission_type, granted_by)
VALUES (2, 2, 'READ', 1);
