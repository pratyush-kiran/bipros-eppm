package com.bipros.security.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.ProfileRepository;
import com.bipros.security.domain.repository.RoleRepository;
import com.bipros.security.domain.repository.UserRepository;
import com.bipros.security.domain.repository.UserRoleRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceSetPasswordTest {

    @Mock UserRepository userRepository;
    @Mock UserRoleRepository userRoleRepository;
    @Mock RoleRepository roleRepository;
    @Mock ProfileRepository profileRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuditService auditService;
    @Mock CurrentUserService currentUserService;
    @InjectMocks UserService userService;

    @Test
    void encodesWithBcryptAndSavesWhenUserFound() {
        User u = new User("illayaraja", "illayaraja@bipros.test", "old-hash");
        when(userRepository.findByUsername("illayaraja")).thenReturn(Optional.of(u));
        when(passwordEncoder.encode("NewPass@123")).thenReturn("bcrypt-hash");

        userService.setPasswordByUsername("illayaraja", "NewPass@123");

        assertThat(u.getPasswordHash()).isEqualTo("bcrypt-hash");
        verify(userRepository).save(u);
    }

    @Test
    void throwsNotFoundAndDoesNotSaveWhenUserMissing() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.setPasswordByUsername("ghost", "NewPass@123"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).save(any());
    }
}
