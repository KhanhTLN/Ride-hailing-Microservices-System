package com.threecats.userservice.security;

import com.threecats.userservice.user.entity.User;
import com.threecats.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetails loadUserByID(String id) throws UsernameNotFoundException {
        User user = userRepository.findById(UUID.fromString(id))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));

        if (user.getStatus()!=com.threecats.userservice.enums.AccountStatus.ACTIVE) throw new UsernameNotFoundException("User account is inactive");
        String roleName = user.getRole() != null ? user.getRole().name() : "PASSENGER";
        return new org.springframework.security.core.userdetails.User(
                user.getId().toString(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + roleName))
        );
    }

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String normalized=identifier.contains("@") ? identifier.trim().toLowerCase(java.util.Locale.ROOT) : identifier.trim();
        User user = userRepository.findByEmailIgnoreCase(normalized)
                .or(() -> userRepository.findByUsername(normalized))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with identifier: " + identifier));

        if (user.getStatus()!=com.threecats.userservice.enums.AccountStatus.ACTIVE) throw new UsernameNotFoundException("User account is inactive");
        String roleName = user.getRole() != null ? user.getRole().name() : "PASSENGER";
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + roleName))
        );
    }
}
