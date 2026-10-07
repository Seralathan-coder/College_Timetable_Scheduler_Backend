package com.erp.timetable.module.auth.security;

import com.erp.timetable.module.auth.entity.User;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Spring Security UserDetails wrapper around the User entity.
 */
@Getter
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String username;
    private final String password;
    private final boolean active;
    private final Long collegeId;
    private final Collection<? extends GrantedAuthority> authorities;

    public static UserPrincipal build(User user) {
        Collection<GrantedAuthority> authorities = user.getRoles().stream()
            .map(role -> new SimpleGrantedAuthority(role.getName().name()))
            .collect(Collectors.toSet());

        return new UserPrincipal(
            user.getId(),
            user.getEmail(),
            user.getUsername(),
            user.getPassword(),
            Boolean.TRUE.equals(user.getIsActive()),
            user.getCollege() != null ? user.getCollege().getId() : null,
            authorities
        );
    }

    @Override public boolean isAccountNonExpired()   { return true; }
    @Override public boolean isAccountNonLocked()    { return true; }
    @Override public boolean isCredentialsNonExpired(){ return true; }
    @Override public boolean isEnabled()             { return active; }
}
