package com.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Profession-level dedupe. The cases below are real titles the pipeline produced —
 * the bail-bondsman pair is what word-overlap dedupe let through.
 */
class StoryTellerToolsTest {

    @Test
    void extractsRoleFromPovTitle() {
        assertEquals(Set.of("bail", "bondsman"),
                StoryTellerTools.professionWords(
                        "POV: You're a Bail Bondsman and Your First Client is Your Own Brother"));
        assertEquals(Set.of("repo"),
                StoryTellerTools.professionWords(
                        "POV: You're a Repo Man and Tonight It's Your Old House"));
        assertEquals(Set.of("armored", "car", "guard"),
                StoryTellerTools.professionWords(
                        "POV: You Are an Armored Car Guard and the System Is the Real Threat"));
    }

    @Test
    void extractsRoleFromDescriptiveTitle() {
        assertEquals(Set.of("bail", "bondsman"),
                StoryTellerTools.professionWords("The life of a Bail Bondsman"));
    }

    @Test
    void sameProfessionCollidesEvenWhenHooksDiffer() {
        // The miss that motivated this: only "bail" is shared, so word-overlap scored low.
        Set<String> a = StoryTellerTools.professionWords("The life of a Bail Bondsman");
        Set<String> b = StoryTellerTools.professionWords(
                "POV: You're a Bail Enforcement Agent and You Hunt Your Old Partner");
        assertTrue(b.stream().anyMatch(a::contains), "should share a distinctive role word");
    }

    @Test
    void genericRoleWordsDoNotCollide() {
        // "owner"/"business" are too generic to make two jobs the same.
        Set<String> shopOwner = StoryTellerTools.professionWords(
                "POV: You're a Small Business Owner and the Police Seize Your Life Savings");
        Set<String> barOwner = StoryTellerTools.professionWords(
                "POV: You're a Nightclub Owner and the Fire Marshal Wants a Bribe");
        assertFalse(shopOwner.isEmpty());
        assertFalse(barOwner.stream().anyMatch(shopOwner::contains),
                "generic role words must not force a collision");
    }

    @Test
    void differentProfessionsDoNotCollide() {
        Set<String> a = StoryTellerTools.professionWords(
                "POV: You're a Bail Bondsman and Your First Client is Your Own Brother");
        Set<String> b = StoryTellerTools.professionWords(
                "POV: You're a Night Shift Nurse and the Charts Don't Add Up");
        assertFalse(b.stream().anyMatch(a::contains));
    }

    @Test
    void noRoleStatedYieldsEmpty() {
        assertTrue(StoryTellerTools.professionWords("The Cost of Extreme Debt").isEmpty());
    }
}
