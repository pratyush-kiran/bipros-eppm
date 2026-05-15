package com.bipros.security.infrastructure.security;

import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    // TODO: remove these aliases after Phase 3 controller sweep completes
    // (legacy @PreAuthorize strings will be replaced with hasPermission(...))
    // Keys and values are role NAMES (without the "ROLE_" prefix); the prefix
    // is added uniformly when authorities are emitted below.
    private static final Map<String, List<String>> ROLE_ALIASES = Map.of(
            // Bidirectional renames (both spellings may appear in @PreAuthorize)
            "QC_MANAGER", List.of("QA_QC_ENGINEER"),
            "QA_QC_ENGINEER", List.of("QC_MANAGER"),
            "HSE_OFFICER", List.of("SAFETY_OFFICER"),
            "SAFETY_OFFICER", List.of("HSE_OFFICER"),
            // One-way legacy strings (canonical role -> legacy authority only)
            "SUPERVISOR", List.of("SITE_SUPERVISOR"),
            "FINANCE", List.of("COST_ENGINEER"),
            "STORE_MANAGER", List.of("STORE_KEEPER")
    );

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("User not found: {}", username);
                    return new UsernameNotFoundException("User not found with username: " + username);
                });

        LinkedHashSet<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (var userRole : user.getRoles()) {
            String roleName = userRole.getRole().getName();
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
            for (String alias : ROLE_ALIASES.getOrDefault(roleName, List.of())) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + alias));
            }
        }
        Collection<GrantedAuthority> finalAuthorities = authorities;

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(finalAuthorities)
                .accountExpired(false)
                .accountLocked(user.isAccountLocked())
                .credentialsExpired(false)
                .disabled(!user.isEnabled())
                .build();
    }
}
