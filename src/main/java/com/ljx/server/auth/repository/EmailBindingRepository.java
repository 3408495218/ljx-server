package com.ljx.server.auth.repository;

import com.ljx.server.auth.entity.EmailBinding;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailBindingRepository extends JpaRepository<EmailBinding, Long> {
}
