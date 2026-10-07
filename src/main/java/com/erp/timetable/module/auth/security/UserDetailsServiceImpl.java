package com.erp.timetable.module.auth.security;

import com.erp.timetable.module.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads user-specific data for Spring Security authentication.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail)
            throws UsernameNotFoundException {
        // Kept for UserDetailsService interface compliance; the login flow
        // resolves accounts through AuthService (college-aware). Usernames are
        // only unique per college, so when several accounts match, the FIRST is
        // returned deterministically by id.
        var user = userRepository.findAllByUsernameOrEmail(usernameOrEmail).stream()
            .min(java.util.Comparator.comparingLong(u -> u.getId() == null ? 0L : u.getId()))
            .orElseThrow(() -> new UsernameNotFoundException(
                "User not found with username or email: " + usernameOrEmail));

        return UserPrincipal.build(user);
    }

    /**
     * Loads the EXACT account referenced by a JWT (token carries a userId
     * claim). Usernames are only unique per college, so a token must never be
     * resolved back through a username — the userId claim pins the account.
     */
    @Transactional(readOnly = true)
    public UserDetails loadUserById(Long id) {
        var user = userRepository.findById(id)
            .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));
        return UserPrincipal.build(user);
    }
}
