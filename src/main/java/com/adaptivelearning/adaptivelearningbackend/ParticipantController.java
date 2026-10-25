package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/** Admin-only enrolment of study participants and management of the code-to-identity key. */
@RestController
@RequestMapping("/api/admin/participants")
public class ParticipantController {

    @Autowired private ParticipantCodeRepository repo;
    @Autowired private ConsentRecordRepository consentRepo;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminActivityLogRepository logRepo;

    @Autowired private PasswordEncoder passwordEncoder;
    private final SecureRandom rng = new SecureRandom();
    private static final String PW_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Body: { "count": 10 }. Creates study accounts, enrols them, returns credentials ONCE (passwords are not recoverable). */
    @PostMapping("/create-accounts")
    public ResponseEntity<Map<String, Object>> createAccounts(@RequestBody Map<String, Integer> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        int count = (body == null || body.get("count") == null) ? 0 : body.get("count");
        if (count < 1 || count > 50) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "count must be 1-50."));
        }
        int next = repo.findAll().stream().map(ParticipantCode::getCode)
                .filter(c -> c.matches("P\\d+")).mapToInt(c -> Integer.parseInt(c.substring(1)))
                .max().orElse(0) + 1;

        List<Map<String, String>> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String code = String.format("P%03d", next++);
            String email = code.toLowerCase(Locale.ROOT) + "@study.invalid";
            if (userRepository.findByEmailIgnoreCase(email).isPresent()) continue;
            StringBuilder pw = new StringBuilder();
            for (int k = 0; k < 10; k++) pw.append(PW_CHARS.charAt(rng.nextInt(PW_CHARS.length())));
            userRepository.save(new User(email, passwordEncoder.encode(pw.toString()), "Participant " + code));
            repo.save(new ParticipantCode(email, code));
            Map<String, String> a = new LinkedHashMap<>();
            a.put("code", code); a.put("email", email); a.put("password", pw.toString());
            accounts.add(a);
        }
        logRepo.save(new AdminActivityLog("participants-enrolled",
                "Created " + accounts.size() + " study account(s)", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true, "accounts", accounts));
    }

    private boolean isAdmin(HttpSession s) { return Boolean.TRUE.equals(s.getAttribute("isAdmin")); }

    /** email (lower-case) -> code. Used by the exports. */
    public Map<String, String> codeByEmail() {
        Map<String, String> m = new HashMap<>();
        for (ParticipantCode p : repo.findAll()) m.put(p.getEmail().toLowerCase(Locale.ROOT), p.getCode());
        return m;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        List<Map<String, Object>> items = repo.findAll().stream()
                .sorted(Comparator.comparing(ParticipantCode::getCode))
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", p.getCode());
                    m.put("email", p.getEmail());
                    m.put("consented", consentRepo.existsByParticipantCodeAndVersion(p.getCode(), ConsentService.VERSION));
                    return m;
                }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("success", true, "participants", items));
    }

    /** Body: { "emails": ["a@x.com", ...] }. Assigns the next free code to each registered, non-admin, not-yet-enrolled email. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> enroll(@RequestBody Map<String, List<String>> body, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        List<String> emails = body == null ? null : body.get("emails");
        if (emails == null || emails.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "No emails given."));
        }

        Set<String> enrolled = codeByEmail().keySet();
        int next = repo.findAll().stream()
                .map(ParticipantCode::getCode)
                .filter(c -> c.matches("P\\d+"))
                .mapToInt(c -> Integer.parseInt(c.substring(1)))
                .max().orElse(0) + 1;

        List<String> assigned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String raw : new LinkedHashSet<>(emails)) {
            String e = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            Optional<User> u = userRepository.findByEmailIgnoreCase(e);
            if (u.isEmpty()) { skipped.add(e + " (not registered)"); continue; }
            if (u.get().isAdmin()) { skipped.add(e + " (admin)"); continue; }
            String canonical = u.get().getEmail().toLowerCase(Locale.ROOT);
            if (enrolled.contains(canonical)) { skipped.add(e + " (already enrolled)"); continue; }
            String code = String.format("P%03d", next++);
            repo.save(new ParticipantCode(canonical, code));
            assigned.add(code);
        }

        logRepo.save(new AdminActivityLog("participants-enrolled",
                "Enrolled " + assigned.size() + " study participant(s)", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true, "assigned", assigned.size(), "skipped", skipped));
    }

    /**
     * Clears a participant's consent so they are shown the consent notice again. Consent is checked on every
     * guarded request, so this also takes effect for a participant who is already logged in. Participant data
     * is untouched; only the consent record is removed (the reset itself is written to the activity log).
     */
    @PostMapping("/{code}/reset-consent")
    public ResponseEntity<Map<String, Object>> resetConsent(@PathVariable String code, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        boolean enrolled = repo.findAll().stream().anyMatch(p -> p.getCode().equals(c));
        if (!enrolled) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "No participant with that code."));
        }
        long n = consentRepo.deleteByParticipantCode(c);
        logRepo.save(new AdminActivityLog("consent-reset",
                "Reset consent for participant " + c, n + " record(s) removed",
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true, "deleted", n));
    }

    /** Body: { "confirm": "RESET" }. Clears consent for every enrolled participant. */
    @PostMapping("/reset-consent")
    public ResponseEntity<Map<String, Object>> resetAllConsent(@RequestBody(required = false) Map<String, String> body,
                                                               HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        if (body == null || !"RESET".equals(body.get("confirm"))) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Type RESET to confirm."));
        }
        long n = 0;
        for (ParticipantCode p : repo.findAll()) n += consentRepo.deleteByParticipantCode(p.getCode());
        logRepo.save(new AdminActivityLog("consent-reset",
                "Reset consent for all enrolled participants", n + " record(s) removed",
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true, "deleted", n));
    }

    /** The identity key. Store it offline, separate from the datasets. */
    @GetMapping(value = "/mapping.csv")
    public ResponseEntity<byte[]> mapping(HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).build();
        StringBuilder sb = new StringBuilder("participant_code,email\r\n");
        repo.findAll().stream().sorted(Comparator.comparing(ParticipantCode::getCode))
                .forEach(p -> sb.append(p.getCode()).append(',').append(p.getEmail()).append("\r\n"));
        logRepo.save(new AdminActivityLog("export", "Exported participant identity key", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"participant_key.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Destroys the identity key. Export mapping.csv first if you need it. */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> wipe(@RequestParam String confirm, HttpSession session) {
        if (!isAdmin(session)) return ResponseEntity.status(403).body(Map.of("success", false));
        if (!"DELETE".equals(confirm)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Type DELETE to confirm."));
        }
        long n = repo.count();
        repo.deleteAll();
        logRepo.save(new AdminActivityLog("participants-key-deleted",
                "Deleted participant identity key (" + n + " rows)", null,
                (String) session.getAttribute("loggedInUserEmail")));
        return ResponseEntity.ok(Map.of("success", true, "deleted", n));
    }
}
