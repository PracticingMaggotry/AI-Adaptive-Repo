package com.adaptivelearning.adaptivelearningbackend;

import com.adaptivelearning.adaptivelearningbackend.ScopeService.ConceptDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LegacyAttemptImporterTest {

    private static final List<ConceptDef> CONCEPTS = List.of(
            new ConceptDef("chief-information-officer", "Chief Information Officer", "executive responsible for IT strategy"),
            new ConceptDef("firewall", "Firewall", "network security device that filters traffic"),
            new ConceptDef("bandwidth", "Bandwidth", "maximum rate of data transfer"));

    @Test
    void questionIsMatchedToTheConceptWhoseTermItMentions() {
        assertEquals("firewall", LegacyAttemptImporter.match("Which device filters traffic using a firewall policy?", CONCEPTS));
        assertEquals("chief-information-officer",
                LegacyAttemptImporter.match("Who does the Chief Information Officer report to?", CONCEPTS));
    }

    @Test
    void definitionWordsAloneNeverCreateAMatch() {
        assertNull(LegacyAttemptImporter.match("Which executive handles strategy and traffic?", CONCEPTS));
    }

    @Test
    void unrelatedQuestionsStayUnmapped() {
        assertNull(LegacyAttemptImporter.match("What colour is the sky?", CONCEPTS));
        assertNull(LegacyAttemptImporter.match("", CONCEPTS));
    }
}
