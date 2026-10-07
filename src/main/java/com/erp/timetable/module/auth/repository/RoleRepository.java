package com.erp.timetable.module.auth.repository;

import com.erp.timetable.module.auth.entity.Role;
import com.erp.timetable.module.auth.entity.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);
}
