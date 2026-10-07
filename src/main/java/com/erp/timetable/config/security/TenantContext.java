package com.erp.timetable.config.security;

import com.erp.timetable.module.auth.entity.User;
import com.erp.timetable.module.auth.repository.UserRepository;
import com.erp.timetable.module.auth.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the currently authenticated {@link User} from the DB (never from
 * client-supplied data) so services can apply college/department scoping.
 * The DB-loaded user — including its college — is the single authority for
 * tenant isolation.
 */
@Component
@RequiredArgsConstructor
public class TenantContext {

    private final UserRepository userRepository;

    public User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        return userRepository.findById(principal.getId()).orElse(null);
    }

    public Long currentCollegeId() {
        User user = currentUser();
        return user != null && user.getCollege() != null ? user.getCollege().getId() : null;
    }
}