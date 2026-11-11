package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ai.AiPolicy;
import com.adaptivelearning.adaptivelearningbackend.ai.AiScheduler;
import com.adaptivelearning.adaptivelearningbackend.ai.AiScheduler.Lane;
import com.adaptivelearning.adaptivelearningbackend.ai.CostModel;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Single door to the Anthropic API: admission control (fair, lane-aware, self-tuning), per-student budget, and usage
 * accounting. ClaudeService sends every HTTP call through {@link #execute}.
 */
@Component
public class AiGateway {

    /** Thrown instead of calling the API when the student's daily AI budget is used up. */
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) { super(message); }
    }

    /** Which lane each task runs in. Unknown tasks run as STANDARD. */
    private static final Map<String, Lane> LANES = Map.ofEntries(
            Map.entry("lesson", Lane.INTERACTIVE),
            Map.entry("essay-grading", Lane.INTERACTIVE),
            Map.entry("practical-verification", Lane.INTERACTIVE),
            Map.entry("question-generation", Lane.STANDARD),
            Map.entry("knowledge-extraction", Lane.BULK),
            Map.entry("summarize-material", Lane.BULK),
            Map.entry("categorize-material", Lane.BULK),
            Map.entry("categorize-questions", Lane.BULK),
            Map.entry("consolidate-subcategories", Lane.BULK));

    private final AiScheduler scheduler = AiScheduler.withDefaults();
    private final AiUsageRepository usageRepository;
    private final AdminAccounts adminAccounts;

    public AiGateway(AiUsageRepository usageRepository, AdminAccounts adminAccounts) {
        this.usageRepository = usageRepository;
        this.adminAccounts = adminAccounts;
    }

    public static Lane laneFor(String task) {
        return LANES.getOrDefault(task, Lane.STANDARD);
    }

    public <T> T execute(String task, Callable<T> call) throws Exception {
        Lane lane = laneFor(task);
        String student = AiCallContext.student();
        if (lane != Lane.INTERACTIVE && level(student) == CostModel.Level.BLOCKED) {
            throw new BudgetExceededException("Daily AI allowance reached for this account. It resets at midnight Philippine time.");
        }
        return scheduler.run(student, lane, AiPolicy.SCHED_MAX_WAIT_MS, call);
    }

    public CostModel.Level level(String studentId) {
        if (adminAccounts.isAdmin(studentId)) return CostModel.Level.OK; // admins have no daily AI budget
        try {
            long micros = usageRepository.sumCostMicros(studentId, today());
            return CostModel.level(micros, AiPolicy.BUDGET_SOFT_USD, AiPolicy.BUDGET_HARD_USD);
        } catch (Exception e) {
            return CostModel.Level.OK; // accounting must never take the product down
        }
    }

    public CostModel.Level currentLevel() { return level(AiCallContext.student()); }

    public void onRateLimited(long retryAfterMs) { scheduler.onRateLimited(retryAfterMs); }

    public void onSuccess() { scheduler.onSuccess(); }

    public AiScheduler.Stats stats() { return scheduler.stats(); }

    /** Adds one call to today's totals for the current student. Best effort: failures are swallowed. */
    public synchronized void recordUsage(String task, String model, long in, long out, long cacheRead, long cacheWrite) {
        try {
            String student = AiCallContext.student();
            AiUsage row = usageRepository.findByStudentIdAndDayAndTaskAndModel(student, today(), task, model)
                    .orElseGet(() -> new AiUsage(student, today(), task, model));
            row.add(in, out, cacheRead, cacheWrite, CostModel.costMicros(model, in, out, cacheRead, cacheWrite));
            usageRepository.save(row);
        } catch (Exception ignored) {
        }
    }

    private static LocalDate today() { return AppTime.today(); }
}
