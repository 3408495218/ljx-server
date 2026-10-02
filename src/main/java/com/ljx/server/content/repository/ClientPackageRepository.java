package com.ljx.server.content.repository;

import com.ljx.server.content.entity.ClientPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientPackageRepository extends JpaRepository<ClientPackage, Long> {

    Optional<ClientPackage> findByRoomId(Long roomId);

    void deleteByRoomId(Long roomId);
}