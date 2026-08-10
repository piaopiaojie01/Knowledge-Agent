package com.ka.repository;

import com.ka.entity.McpServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {
    List<McpServer> findByEnabledTrue();
}
