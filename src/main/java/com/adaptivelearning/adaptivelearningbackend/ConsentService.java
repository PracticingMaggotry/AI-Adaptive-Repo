package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ConsentService {

    /** Bump this when the notice text changes; everyone is asked again. */
    public static final String VERSION = "v1";

    @Autowired private ParticipantCodeRepository codes;
    @Autowired private ConsentRecordRepository records;

    public boolean isParticipant(String email) {
        return email != null && codes.findByEmailIgnoreCase(email).isPresent();
    }

    /** True for non-participants (no consent needed) and participants who accepted the current version. */
    public boolean hasConsented(String email) {
        if (email == null) return true;
        Optional<ParticipantCode> p = codes.findByEmailIgnoreCase(email);
        return p.isEmpty() || records.existsByParticipantCodeAndVersion(p.get().getCode(), VERSION);
    }

    public void accept(String email) {
        codes.findByEmailIgnoreCase(email).ifPresent(p -> {
            if (!records.existsByParticipantCodeAndVersion(p.getCode(), VERSION)) {
                try { records.save(new ConsentRecord(p.getCode(), VERSION)); }
                catch (org.springframework.dao.DataIntegrityViolationException ignored) {}
            }
        });
    }
}
