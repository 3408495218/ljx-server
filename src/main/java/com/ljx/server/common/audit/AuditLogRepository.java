package com.ljx.server.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByAccountIdOrderByIdDesc(Long accountId);

    List<AuditLog> findByAccountIdAndActionOrderByIdDesc(Long accountId, AuditAction action);
}