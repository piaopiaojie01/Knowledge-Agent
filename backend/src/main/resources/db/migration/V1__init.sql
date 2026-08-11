-- Knowledge Agent 基线表结构（与 JPA 实体对齐，取自生产库 mysqldump）
-- 已有库走 Flyway baseline（版本 1 跳过本脚本）；全新库按本脚本建表

CREATE TABLE IF NOT EXISTS `users` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `password_hash` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
    `display_name` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `role` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    `is_active` tinyint(1) NOT NULL DEFAULT '1',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `storage_used` bigint NOT NULL DEFAULT '0' COMMENT '已用存储(字节)',
    `storage_limit` bigint NOT NULL DEFAULT '5368709120' COMMENT '存储上限(字节,默认5GB)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `username` (`username`),
    KEY `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `knowledge_bases` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `name` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL,
    `description` text COLLATE utf8mb4_unicode_ci,
    `created_by` bigint NOT NULL,
    `is_public` tinyint(1) NOT NULL DEFAULT '0',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_created_by` (`created_by`),
    CONSTRAINT `fk_kb_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `documents` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `kb_id` bigint NOT NULL,
    `title` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
    `content` longtext COLLATE utf8mb4_unicode_ci,
    `file_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT 'text' COMMENT 'text / pdf / markdown',
    `doc_status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED / DELETED',
    `chunk_count` int DEFAULT '0' COMMENT '分块数量',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `archived` bit(1) NOT NULL,
    `version` int NOT NULL,
    `content_hash` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `file_size` bigint DEFAULT NULL,
    `ingest_message` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `ingest_progress` int DEFAULT NULL,
    `uploaded_by` bigint DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_kb_id` (`kb_id`),
    FULLTEXT KEY `ft_content` (`title`,`content`),
    CONSTRAINT `fk_doc_kb` FOREIGN KEY (`kb_id`) REFERENCES `knowledge_bases` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `permissions` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `user_id` bigint NOT NULL,
    `kb_id` bigint NOT NULL,
    `permission_type` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'READ / WRITE / ADMIN',
    `granted_by` bigint DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_kb` (`user_id`,`kb_id`),
    KEY `idx_kb_id` (`kb_id`),
    CONSTRAINT `fk_perm_kb` FOREIGN KEY (`kb_id`) REFERENCES `knowledge_bases` (`id`),
    CONSTRAINT `fk_perm_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `feedback` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `answer` text COLLATE utf8mb4_unicode_ci,
    `comment` text COLLATE utf8mb4_unicode_ci,
    `created_at` datetime(6) NOT NULL,
    `question` text COLLATE utf8mb4_unicode_ci,
    `rating` int NOT NULL,
    `session_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `user_id` bigint NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `audit_logs` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `action` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `detail` text COLLATE utf8mb4_unicode_ci,
    `ip` varchar(45) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `target` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `user_id` bigint DEFAULT NULL,
    `username` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `conversations` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `content` text COLLATE utf8mb4_unicode_ci NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
    `session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `user_id` bigint NOT NULL,
    `input_tokens` int NOT NULL,
    `output_tokens` int NOT NULL,
    `title` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_conv_session` (`session_id`),
    KEY `idx_conv_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `model_configs` (
    `id` bigint NOT NULL,
    `api_key` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `base_url` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `created_at` datetime(6) DEFAULT NULL,
    `enabled` bit(1) DEFAULT NULL,
    `max_tokens` int DEFAULT NULL,
    `model_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `temperature` double DEFAULT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `skills` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `builtin` bit(1) DEFAULT NULL,
    `config_json` text COLLATE utf8mb4_unicode_ci,
    `created_at` datetime(6) DEFAULT NULL,
    `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `enabled` bit(1) DEFAULT NULL,
    `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_85woe63nu9klkk9fa73vf0jd0` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `mcp_servers` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) DEFAULT NULL,
    `description` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `enabled` bit(1) DEFAULT NULL,
    `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
    `updated_at` datetime(6) DEFAULT NULL,
    `url` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_tdan0087ys2dsfvl2ebugl227` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notifications` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `created_at` datetime(6) NOT NULL,
    `is_read` bit(1) NOT NULL,
    `message` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL,
    `type` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
    `username` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
