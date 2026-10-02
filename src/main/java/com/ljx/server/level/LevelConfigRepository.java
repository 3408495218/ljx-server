package com.ljx.server.level;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LevelConfigRepository extends JpaRepository<LevelConfig, Integer> {

    List<LevelConfig> findAllByOrderByLevelAsc();
}
