package com.adaptivelearning.adaptivelearningbackend.ai;

import com.adaptivelearning.adaptivelearningbackend.ai.AiScheduler.BusyException;
import com.adaptivelearning.adaptivelearningbackend.ai.AiScheduler.Lane;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.ConceptInfo;
import com.adaptivelearning.adaptivelearningbackend.ai.LearnerPrompt.Example;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.ConceptState;
import com.adaptivelearning.adaptivelearningbackend.ai.MasteryModel.Event;
import com.adaptivelearning.adaptivelearningbackend.ai.PassageRetriever.Concept;
import com.adaptivelearning.adaptivelearningbackend.ai.TierCalibration.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiInfrastructureTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");


    @Test
    void generationBurstCannotBlockAnInteractiveCall() throws Exception {
        // limit 4, 2 reserved for INTERACTIVE: at most 2 STANDARD calls may run, the rest of the capacity stays free.
        AiScheduler s = new AiScheduler(4, 1, 2, 4, 60_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runningStandard = new AtomicInteger(), maxStandard = new AtomicInteger();
        List<Future<?>> heavy = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final String user = "u" + i;
            heavy.add(pool.submit(() -> s.run(user, Lane.STANDARD, 20_000, () -> {
                int now = runningStandard.incrementAndGet();
                maxStandard.accumulateAndGet(now, Math::max);
                release.await();
                runningStandard.decrementAndGet();
                return null;
            })));
        }
        Thread.sleep(300);
        assertEquals(2, runningStandard.get(), "STANDARD lane is capped below the total limit");

        long t0 = System.currentTimeMillis();
        String lesson = s.run("student-x", Lane.INTERACTIVE, 5_000, () -> "lesson");
        assertEquals("lesson", lesson);
        assertTrue(System.currentTimeMillis() - t0 < 2_000, "interactive call must not queue behind generation");

        release.countDown();
        for (Future<?> f : heavy) f.get(10, TimeUnit.SECONDS);
        assertEquals(2, maxStandard.get());
        pool.shutdownNow();
    }

    @Test
    void oneStudentCannotHoldMoreThanTheirShareOfSlots() throws Exception {
        AiScheduler s = new AiScheduler(6, 1, 0, 2, 60_000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger(), max = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fs.add(pool.submit(() -> s.run("same-user", Lane.STANDARD, 20_000, () -> {
                max.accumulateAndGet(running.incrementAndGet(), Math::max);
                release.await();
                running.decrementAndGet();
                return null;
            })));
        }
        Thread.sleep(300);
        assertEquals(2, running.get());
        // Another student is served immediately even though the first one has 3 more calls queued.
        assertEquals("ok", s.run("other", Lane.STANDARD, 2_000, () -> "ok"));
        release.countDown();
        for (Future<?> f : fs) f.get(10, TimeUnit.SECONDS);
        assertEquals(2, max.get());
        pool.shutdownNow();
    }

    @Test
    void callerGetsBusyExceptionInsteadOfWaitingForever() throws Exception {
        AiScheduler s = new AiScheduler(1, 1, 0, 1, 60_000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> s.run("a", Lane.STANDARD, 5_000, () -> { release.await(); return null; }));
        Thread.sleep(200);
        assertThrows(BusyException.class, () -> s.run("b", Lane.STANDARD, 400, () -> "never"));
        assertEquals(1, s.stats().totalRejected());
        release.countDown();
        holder.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();
    }

    @Test
    void rateLimitHalvesTheLimitAndSuccessesGrowItBack() {
        AiScheduler s = new AiScheduler(8, 2, 0, 4, 60_000);
        assertEquals(8, s.stats().limit());
        s.onRateLimited(10);
        assertEquals(4, s.stats().limit());
        s.onRateLimited(10);
        assertEquals(2, s.stats().limit());
        s.onRateLimited(10);
        assertEquals(2, s.stats().limit(), "never below the minimum");
        for (int i = 0; i < 2; i++) s.onSuccess();
        assertEquals(3, s.stats().limit());
    }

    /** Runs the scenario: BULK waits since t=0, STANDARD arrives at t=500, then the clock moves to {@code later}. */
    private List<String> grantOrder(long later) throws Exception {
        long[] clock = {0};
        AiScheduler s = new AiScheduler(1, 1, 0, 5, 1_000, () -> clock[0]);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Future<?> holder = pool.submit(() -> s.run("holder", Lane.STANDARD, 1_000_000, () -> { release.await(); return null; }));
        Thread.sleep(150);
        Future<?> bulk = pool.submit(() -> s.run("b", Lane.BULK, 1_000_000, () -> order.add("bulk")));
        Thread.sleep(150);
        clock[0] = 500;
        Future<?> std = pool.submit(() -> s.run("s", Lane.STANDARD, 1_000_000, () -> order.add("standard")));
        Thread.sleep(150);
        clock[0] = later;
        release.countDown();
        holder.get(5, TimeUnit.SECONDS); bulk.get(5, TimeUnit.SECONDS); std.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();
        return order;
    }

    @Test
    void bulkWorkWaitsBehindStandardWorkAtFirstButIsPromotedIfItWaitsTooLong() throws Exception {
        assertEquals(List.of("standard", "bulk"), grantOrder(600), "not yet promoted: STANDARD goes first");
        assertEquals(List.of("bulk", "standard"), grantOrder(1_500), "promoted after the wait: oldest first, no starvation");
    }


    @Test
    void identicalConcurrentRequestsShareOneComputation() throws Exception {
        SingleFlight<String, String> sf = new SingleFlight<>();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(pool.submit(() -> sf.run("lesson:1", 5_000, () -> {
                calls.incrementAndGet();
                start.await();
                return "L";
            })));
        }
        Thread.sleep(300);
        start.countDown();
        for (Future<String> f : fs) assertEquals("L", f.get(5, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        assertFalse(sf.isRunning("lesson:1"));
        pool.shutdownNow();
    }


    @Test
    void rawFacilityShowsEasyAboveMediumAboveHard() {
        List<Outcome> o = new ArrayList<>();
        addMany(o, "s", 1, "Easy", 40, 0.85);
        addMany(o, "s", 1, "Medium", 40, 0.68);
        addMany(o, "s", 1, "Hard", 40, 0.50);
        TierCalibration.Report r = TierCalibration.analyse(o);
        assertTrue(r.enoughData());
        assertTrue(r.rawOrdered());
        assertEquals("WITHIN_TARGET", r.tiers().get(0).targetVerdict());
        assertEquals("WITHIN_TARGET", r.tiers().get(1).targetVerdict());
        assertEquals("WITHIN_TARGET", r.tiers().get(2).targetVerdict());
    }

    @Test
    void pairedViewSeesThroughTheFactThatStrongStudentsAreMovedToHard() {
        // Raw view is misleading: the strong students answer Hard well. Paired view shows Hard IS harder for each.
        List<Outcome> o = new ArrayList<>();
        for (int i = 0; i < 6; i++) { addMany(o, "strong" + i, 1, "Medium", 10, 0.95); addMany(o, "strong" + i, 1, "Hard", 10, 0.75); }
        for (int i = 0; i < 6; i++) addMany(o, "weak" + i, 2, "Easy", 10, 0.55);
        TierCalibration.Report r = TierCalibration.analyse(o);
        assertNotNull(r.pairedMediumToHard());
        assertEquals(-0.20, r.pairedMediumToHard(), 1e-9);
        assertEquals(6, r.pairedStudentsMH());
        assertNull(r.pairedEasyToMedium());
    }

    @Test
    void tooFewResponsesAreNotJudged() {
        List<Outcome> o = new ArrayList<>();
        addMany(o, "s", 1, "Hard", 5, 0.1);
        TierCalibration.Report r = TierCalibration.analyse(o);
        assertFalse(r.enoughData());
        assertEquals("TOO_FEW_RESPONSES", r.tiers().get(2).targetVerdict());
        assertEquals("NO_DATA", r.tiers().get(0).targetVerdict());
    }

    private static void addMany(List<Outcome> o, String who, long scope, String tier, int n, double facility) {
        int right = (int) Math.round(n * facility);
        for (int i = 0; i < n; i++) o.add(new Outcome(who, scope, tier, "MCQ", i < right ? 1.0 : 0.0, 20));
    }


    @Test
    void costGrowsWithTokensAndCacheReadsAreCheap() {
        long plain = CostModel.costMicros("claude-sonnet-4-6", 10_000, 2_000, 0, 0);
        long cached = CostModel.costMicros("claude-sonnet-4-6", 1_000, 2_000, 9_000, 0);
        assertEquals(60_000, plain);                 // 10k*3 + 2k*15 = 60,000 micro-dollars = $0.06
        assertTrue(cached < plain);
        assertTrue(CostModel.costMicros("claude-haiku-4-5-20251001", 10_000, 2_000, 0, 0) < plain);
    }

    @Test
    void budgetLevelsDegradeThenBlock() {
        assertEquals(CostModel.Level.OK, CostModel.level(100_000, 0.40, 1.00));
        assertEquals(CostModel.Level.DEGRADED, CostModel.level(450_000, 0.40, 1.00));
        assertEquals(CostModel.Level.BLOCKED, CostModel.level(1_000_000, 0.40, 1.00));
    }


    private static String longHandout() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("Paragraph ").append(i).append(" talks about filler topic number ").append(i)
              .append(" and repeats generic words many times to take up space in the handout text. ".repeat(3)).append("\n\n");
        }
        sb.append("The mitochondria is the organelle that produces most of the chemical energy a cell needs, called ATP.\n\n");
        for (int i = 40; i < 80; i++) sb.append("Another filler paragraph ").append(i).append(" about unrelated subject matter. ".repeat(6)).append("\n\n");
        return sb.toString();
    }

    @Test
    void shortHandoutsAreSentWhole() {
        String text = "Short handout about cells. ".repeat(20);
        PassageRetriever r = new PassageRetriever(text);
        PassageRetriever.Result res = r.retrieve(List.of(new Concept("cell", "Cell", "basic unit")), text, 1800, 2, 2400);
        assertTrue(res.wholeHandout());
        assertEquals(text, res.text());
    }

    @Test
    void longHandoutsAreReducedToThePassagesAboutTheWantedConcept() {
        String text = longHandout();
        PassageRetriever r = new PassageRetriever(text);
        assertTrue(PassageRetriever.wordCount(text) > 1800);
        PassageRetriever.Result res = r.retrieve(
                List.of(new Concept("mitochondria", "Mitochondria", "organelle that produces ATP energy")), text, 1800, 2, 600);
        assertFalse(res.wholeHandout());
        assertTrue(res.text().contains("produces most of the chemical energy"));
        assertTrue(PassageRetriever.wordCount(res.text()) < PassageRetriever.wordCount(text) / 3);
        assertFalse(res.passagesByConcept().get("mitochondria").isEmpty());
    }


    private static Event ev(String key, double credit, int daysAgo) {
        return new Event(key, credit, "MCQ", "Easy", 20, NOW.minus(daysAgo, ChronoUnit.DAYS));
    }

    private static final List<ConceptInfo> INFO = List.of(
            new ConceptInfo("a", "Alpha", "first letter"), new ConceptInfo("b", "Beta", "second letter"),
            new ConceptInfo("c", "Gamma", "third letter"), new ConceptInfo("d", "Delta", "fourth letter"));

    private static Map<String, ConceptState> eval(List<Event> events) {
        return MasteryModel.evaluate(List.of("a", "b", "c", "d"), events, NOW);
    }

    @Test
    void promptSeparatesOpenWeaknessesSlipsAndLearnedConcepts() {
        List<Event> e = new ArrayList<>();
        e.add(ev("a", 0, 6)); e.add(ev("a", 0, 5)); e.add(ev("a", 0, 4));
        e.add(ev("b", 0, 9)); e.add(ev("b", 1, 6)); e.add(ev("b", 1, 2));
        e.add(ev("c", 1, 9)); e.add(ev("c", 1, 8)); e.add(ev("c", 1, 7)); e.add(ev("c", 0, 1));
        Map<String, List<Example>> ex = Map.of("a", List.of(new Example("What is alpha?", "omega", "first letter", "", "", "", NOW)));
        LearnerPrompt.Rendered r = LearnerPrompt.render(INFO, eval(e), ex, 15, NOW);
        assertEquals(1, r.weak());
        assertEquals(1, r.slips());
        assertEquals(1, r.recovered());
        assertEquals(1, r.unseen());
        assertTrue(r.text().contains("OPEN WEAKNESSES"));
        assertTrue(r.text().contains("mistake: Q: What is alpha?"));
        int weakSection = r.text().indexOf("OPEN WEAKNESSES"), slipSection = r.text().indexOf("ONE-OFF SLIPS");
        assertTrue(r.text().substring(weakSection, slipSection).contains("Alpha"));
        assertFalse(r.text().substring(weakSection, slipSection).contains("Beta"), "recovered concept must not read as a weakness");
        assertTrue(r.text().contains("NOT YET ASKED: Delta"));
    }

    @Test
    void lessonFingerprintIgnoresRoutineChangesButCatchesRealOnes() {
        List<Event> base = new ArrayList<>();
        base.add(ev("a", 0, 6)); base.add(ev("a", 0, 5));
        String h1 = LearnerPrompt.stateHash(eval(base));

        List<Event> unrelated = new ArrayList<>(base);
        unrelated.add(ev("d", 1, 1));                          // first correct answer on an unrelated concept
        assertEquals(h1, LearnerPrompt.stateHash(eval(unrelated)), "an unrelated correct answer must not bust the cached lesson");

        List<Event> worse = new ArrayList<>(base);
        worse.add(ev("a", 0, 1));                               // same concept, another miss: same open weakness
        assertEquals(h1, LearnerPrompt.stateHash(eval(worse)));

        List<Event> newWeakness = new ArrayList<>(base);
        newWeakness.add(ev("c", 0, 2)); newWeakness.add(ev("c", 0, 1));
        assertNotEquals(h1, LearnerPrompt.stateHash(eval(newWeakness)), "a new weak concept changes what the lesson should say");

        List<Event> resolved = new ArrayList<>(base);
        resolved.add(ev("a", 1, 3)); resolved.add(ev("a", 1, 2)); resolved.add(ev("a", 1, 1)); // learned it
        assertNotEquals(h1, LearnerPrompt.stateHash(eval(resolved)), "resolving the weakness must invalidate the old lesson");
    }

    @Test
    void promptRespectsTheEntryBudget() {
        List<ConceptInfo> many = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<Event> e = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String k = "k" + i;
            many.add(new ConceptInfo(k, "Term" + i, "def"));
            keys.add(k);
            e.add(ev(k, 0, 5)); e.add(ev(k, 0, 4));
        }
        LearnerPrompt.Rendered r = LearnerPrompt.render(many, MasteryModel.evaluate(keys, e, NOW), Map.of(),
                AiPolicy.LESSON_MAX_CONCEPT_ENTRIES, NOW);
        long entries = r.text().lines().filter(l -> l.startsWith("  - ")).count();
        assertEquals(AiPolicy.LESSON_MAX_CONCEPT_ENTRIES, entries);
    }


    @Test
    void quizSizeFollowsHandoutLengthWithinTheGlobalBounds() {
        assertEquals(15, AiPolicy.quizSizeForWords(500));
        assertEquals(20, AiPolicy.quizSizeForWords(3000));
        assertEquals(25, AiPolicy.quizSizeForWords(9000));
        for (int w : new int[]{0, 1, 1499, 1500, 4000, 4001, 100000}) {
            int n = AiPolicy.quizSizeForWords(w);
            assertTrue(n >= AiPolicy.QUIZ_MIN_QUESTIONS && n <= AiPolicy.QUIZ_MAX_QUESTIONS);
        }
    }

    @Test
    void conceptKeysAreStableAndUnique() {
        assertEquals("chief-information-officer-cio", ConceptKey.of("Chief Information Officer (CIO)"));
        assertEquals("cafe", ConceptKey.of("Café"));
        assertEquals(List.of("tcp", "tcp-2", "udp"), ConceptKey.uniqueKeys(List.of("TCP", "tcp", "UDP")));
        assertEquals(1.0, ConceptKey.overlap(List.of("a", "b"), List.of("a", "b", "c")), 1e-9);
        assertEquals(0.0, ConceptKey.overlap(List.of("a"), List.of("z")), 1e-9);
    }
}
