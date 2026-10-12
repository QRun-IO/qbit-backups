/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.backups.prune;


import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.kingsrook.qbits.backups.prune.PruneCalculator.Decision;
import com.kingsrook.qbits.backups.prune.PruneCalculator.Guard;
import com.kingsrook.qbits.backups.prune.PruneCalculator.Policy;
import com.kingsrook.qbits.backups.prune.PruneCalculator.RetentionClass;
import com.kingsrook.qbits.backups.prune.PruneCalculator.RunInfo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** GFS keep-set fixtures with dates written out, so each expectation can be read
 ** off a calendar.
 *******************************************************************************/
class PruneCalculatorTest
{
   private static final ZoneId UTC = ZoneId.of("UTC");



   /*******************************************************************************
    **
    *******************************************************************************/
   private static RunInfo run(int id, String instant)
   {
      return (new RunInfo(id, Instant.parse(instant), false, false));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Set<Integer> kept(List<Decision> decisions)
   {
      return (decisions.stream().filter(Decision::keep).map(Decision::id).collect(Collectors.toSet()));
   }



   /*******************************************************************************
    ** One run a night for ten nights, keep 7 daily: the seven newest stay.
    *******************************************************************************/
   @Test
   void testDailyKeepsTheNewestDays()
   {
      List<RunInfo> runs = new ArrayList<>();
      for(int day = 1; day <= 10; day++)
      {
         runs.add(run(day, String.format("2026-10-%02dT02:00:00Z", day)));
      }
      List<Decision> decisions = PruneCalculator.calculate(runs, new Policy(7, 0, 0, 0, 0), UTC);
      assertThat(kept(decisions)).containsExactlyInAnyOrder(4, 5, 6, 7, 8, 9, 10);
      assertThat(decisions.stream().filter(Decision::keep).map(Decision::retentionClass).distinct()).containsExactly(RetentionClass.DAILY);
   }



   /*******************************************************************************
    ** Two runs in one calendar day count as one day; the newest of the day is the one kept.
    *******************************************************************************/
   @Test
   void testOnePerCalendarDay()
   {
      List<RunInfo> runs = List.of(
         run(1, "2026-10-10T01:00:00Z"), run(2, "2026-10-10T13:00:00Z"),
         run(3, "2026-10-11T01:00:00Z"), run(4, "2026-10-11T23:59:00Z"),
         run(5, "2026-10-12T02:00:00Z"));
      assertThat(kept(PruneCalculator.calculate(runs, new Policy(2, 0, 0, 0, 0), UTC))).containsExactlyInAnyOrder(4, 5);
      assertThat(kept(PruneCalculator.calculate(runs, new Policy(3, 0, 0, 0, 0), UTC))).containsExactlyInAnyOrder(2, 4, 5);
   }



   /*******************************************************************************
    ** The calendar day is the plan time zone day, not the UTC day.
    *******************************************************************************/
   @Test
   void testDayBoundaryFollowsTheTimeZone()
   {
      List<RunInfo> runs = List.of(run(1, "2026-10-11T23:30:00Z"), run(2, "2026-10-12T00:30:00Z"));
      assertThat(kept(PruneCalculator.calculate(runs, new Policy(1, 0, 0, 0, 0), UTC))).containsExactly(2);
      ///////////////////////////////////////////////////////////////////////////////////////////
      // in Chicago (UTC-5 in October) both are the evening of the 11th: one day, newest wins   //
      ///////////////////////////////////////////////////////////////////////////////////////////
      assertThat(kept(PruneCalculator.calculate(runs, new Policy(1, 0, 0, 0, 0), ZoneId.of("America/Chicago")))).containsExactly(2);
      List<RunInfo> three = List.of(run(1, "2026-10-11T04:30:00Z"), run(2, "2026-10-11T06:30:00Z"), run(3, "2026-10-12T02:00:00Z"));
      assertThat(kept(PruneCalculator.calculate(three, new Policy(2, 0, 0, 0, 0), ZoneId.of("America/Chicago")))).as("1 is the 10th; 2 and 3 are the 11th, where 3 is newest").containsExactlyInAnyOrder(1, 3);
      assertThat(kept(PruneCalculator.calculate(three, new Policy(2, 0, 0, 0, 0), UTC))).containsExactlyInAnyOrder(2, 3);
   }



   /*******************************************************************************
    ** Weekly, monthly and yearly keep the newest of the last N periods that have a
    ** run, and the label is the longest lived rule that keeps the run.
    *******************************************************************************/
   @Test
   void testWeeklyMonthlyYearlyAndLabels()
   {
      List<RunInfo> runs = new ArrayList<>();
      int id = 0;
      ////////////////////////////////////////////////////////////////////////
      // a run on the 1st, 8th, 15th, 22nd and 29th of Sep, Oct and Nov 2026 //
      ////////////////////////////////////////////////////////////////////////
      for(int month : new int[] { 9, 10, 11 })
      {
         for(int day : new int[] { 1, 8, 15, 22, 29 })
         {
            runs.add(run(++id, String.format("2026-%02d-%02dT02:00:00Z", month, day)));
         }
      }
      runs.add(run(100, "2025-12-31T02:00:00Z"));
      runs.add(run(101, "2024-06-30T02:00:00Z"));

      List<Decision> decisions = PruneCalculator.calculate(runs, new Policy(2, 3, 2, 3, 0), UTC);
      Map<Integer, Decision> byId = decisions.stream().collect(Collectors.toMap(Decision::id, d -> d));

      /////////////////////////////////////////////////////////////////////////
      // newest two days: Nov 29 and Nov 22 (daily); but Nov 29 is also the    //
      // newest of November (monthly) and the newest of its week and its year  //
      /////////////////////////////////////////////////////////////////////////
      assertThat(byId.get(15).retentionClass()).isEqualTo(RetentionClass.YEARLY);
      assertThat(byId.get(14).retentionClass()).isEqualTo(RetentionClass.WEEKLY);
      assertThat(byId.get(13).retentionClass()).isEqualTo(RetentionClass.WEEKLY);
      /////////////////////////////////////////////////////////////////////////
      // monthly keeps the newest of Nov and Oct; the Oct one is also a week   //
      /////////////////////////////////////////////////////////////////////////
      assertThat(byId.get(10).retentionClass()).isEqualTo(RetentionClass.MONTHLY);
      assertThat(byId.get(12).keep()).isFalse();
      assertThat(byId.get(5).keep()).as("September is outside the last two months").isFalse();
      /////////////////////////////////////////////////////////////////////////
      // yearly: the newest of 2026, 2025 and 2024                              //
      /////////////////////////////////////////////////////////////////////////
      assertThat(byId.get(100).retentionClass()).isEqualTo(RetentionClass.YEARLY);
      assertThat(byId.get(101).retentionClass()).isEqualTo(RetentionClass.YEARLY);
      assertThat(PruneCalculator.keptByClass(decisions).get(RetentionClass.YEARLY)).isEqualTo(3);
   }



   /*******************************************************************************
    ** ISO weeks cross the new year: 29 Dec 2026 to 3 Jan 2027 is one ISO week (2026-W53).
    *******************************************************************************/
   @Test
   void testIsoWeekAcrossTheNewYear()
   {
      List<RunInfo> runs = List.of(run(1, "2026-12-29T02:00:00Z"), run(2, "2027-01-03T02:00:00Z"), run(3, "2027-01-04T02:00:00Z"));
      List<Decision> decisions = PruneCalculator.calculate(runs, new Policy(0, 2, 0, 0, 0), UTC);
      assertThat(kept(decisions)).as("2 and 1 share an ISO week, so 3 and 2 are the last two weeks").containsExactlyInAnyOrder(2, 3);
   }



   /*******************************************************************************
    ** The minimum keeps the newest runs even when no rule would.
    *******************************************************************************/
   @Test
   void testMinimum()
   {
      List<RunInfo> runs = List.of(run(1, "2026-01-01T02:00:00Z"), run(2, "2026-02-01T02:00:00Z"), run(3, "2026-03-01T02:00:00Z"), run(4, "2026-04-01T02:00:00Z"));
      List<Decision> decisions = PruneCalculator.calculate(runs, new Policy(0, 0, 0, 0, 3), UTC);
      assertThat(kept(decisions)).containsExactlyInAnyOrder(2, 3, 4);
      assertThat(decisions.stream().filter(d -> d.id() == 4).findFirst().orElseThrow().guards()).containsExactly(Guard.MINIMUM);
      assertThat(decisions.stream().filter(d -> d.id() == 4).findFirst().orElseThrow().retentionClass()).isEqualTo(RetentionClass.NONE);
   }



   /*******************************************************************************
    ** The newest verified run is always kept, and so is a run a recent restore used.
    *******************************************************************************/
   @Test
   void testVerifiedAndRestoreGuards()
   {
      List<RunInfo> runs = List.of(
         new RunInfo(1, Instant.parse("2026-01-01T02:00:00Z"), true, false),
         new RunInfo(2, Instant.parse("2026-01-02T02:00:00Z"), true, false),
         new RunInfo(3, Instant.parse("2026-01-03T02:00:00Z"), false, true),
         new RunInfo(4, Instant.parse("2026-01-04T02:00:00Z"), false, false),
         new RunInfo(5, Instant.parse("2026-01-05T02:00:00Z"), false, false));
      List<Decision> decisions = PruneCalculator.calculate(runs, new Policy(1, 0, 0, 0, 0), UTC);
      assertThat(kept(decisions)).containsExactlyInAnyOrder(5, 3, 2);
      assertThat(decisions.stream().filter(d -> d.id() == 2).findFirst().orElseThrow().guards()).containsExactly(Guard.NEWEST_VERIFIED);
      assertThat(decisions.stream().filter(d -> d.id() == 3).findFirst().orElseThrow().guards()).containsExactly(Guard.RESTORE_REFERENCE);
   }



   /*******************************************************************************
    ** Nothing to decide, and a zero policy with no runs of note.
    *******************************************************************************/
   @Test
   void testEmptyAndZeroPolicy()
   {
      assertThat(PruneCalculator.calculate(List.of(), new Policy(7, 4, 12, 0, 3), UTC)).isEmpty();
      List<Decision> decisions = PruneCalculator.calculate(List.of(run(1, "2026-01-01T02:00:00Z")), new Policy(0, 0, 0, 0, 0), UTC);
      assertThat(kept(decisions)).isEmpty();
      assertThat(PruneCalculator.keptByClass(decisions)).isEmpty();
   }
}
