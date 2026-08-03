package com.betterself.growth.auth;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class MfaService {

    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final DefaultCodeVerifier verifier;

    public MfaService(Clock clock) {
        verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), () -> clock.instant().getEpochSecond());
        verifier.setAllowedTimePeriodDiscrepancy(1);
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public boolean verify(String secret, String code) {
        return secret != null && code != null && verifier.isValidCode(secret, code);
    }
}
