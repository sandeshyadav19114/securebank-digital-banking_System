package com.securebank.security;

import com.securebank.config.JwtProperties;
import com.securebank.customer.Customer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {

    public record IssuedToken(String value, long expiresInSeconds) {}

    private final JwtEncoder encoder;
    private final JwtProperties props;
    private final Clock clock;

    public IssuedToken issue(Customer customer) {
        Instant now = clock.instant();
        Duration ttl = Duration.ofMinutes(props.accessTokenMinutes());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(String.valueOf(customer.getId()))
                .id(UUID.randomUUID().toString())
                .claim("email", customer.getEmail())
                .claim("roles", List.of(customer.getRole().name()))
                .build();
        String token = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }
}
