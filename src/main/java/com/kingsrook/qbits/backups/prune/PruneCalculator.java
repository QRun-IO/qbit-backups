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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/*******************************************************************************
 ** The grandfather-father-son keep-set (spec 10.3).  Pure: it works on run
 ** summaries and a clock, so tests can use a synthetic one.
 **
 ** The rules, over the successful, not yet pruned runs, newest first:
 ** - DAILY: the newest run of each of the last retainDaily calendar days that have
 **   a run
 ** - WEEKLY: the newest run of each of the last retainWeekly ISO weeks that have one
 ** - MONTHLY: the newest run of each of the last retainMonthly months that have one
 ** - YEARLY: the newest run of each of the last retainYearly years that have one
 **   ("last N" counts the periods that have a run, so a quiet week does not use up
 **   the allowance)
 ** - the newest retainMinimum runs are always kept
 ** - the newest run that passed any verification is always kept
 ** - a run a restore used in the last 30 days (as its source, or its pre-restore
 **   safety backup) is never deleted
 **
 ** A run kept by several rules is labelled with the longest lived one (YEARLY, then
 ** MONTHLY, WEEKLY, DAILY); a run kept only by a guard (minimum, verified, restore)
 ** is labelled NONE and carries the reasons.  A run that is not kept is labelled NONE.
 *******************************************************************************/
public final class PruneCalculator
{
   public static final int RESTORE_PROTECTION_DAYS = 30;

   /** Prefixes with no manifest older than this are debris and are always deleted. */
   public static final int DEBRIS_DAYS = 7;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PruneCalculator()
   {
   }



   /*******************************************************************************
    ** The retention classes, longest lived first.
    *******************************************************************************/
   public enum RetentionClass
   {
      YEARLY, MONTHLY, WEEKLY, DAILY, NONE
   }



   /*******************************************************************************
    ** Why a run is kept, besides the class.
    *******************************************************************************/
   public enum Guard
   {
      MINIMUM, NEWEST_VERIFIED, RESTORE_REFERENCE
   }



   /*******************************************************************************
    ** What the calculator needs to know about one run.
    **
    ** @param id the run id
    ** @param startDate when the run started
    ** @param verified whether any verification passed for the run
    ** @param usedByRecentRestore whether a restore in the last 30 days used the run
    *******************************************************************************/
   public record RunInfo(Integer id, Instant startDate, boolean verified, boolean usedByRecentRestore)
   {
   }



   /*******************************************************************************
    ** The retention numbers of a plan.
    *******************************************************************************/
   public record Policy(int daily, int weekly, int monthly, int yearly, int minimum)
   {
   }



   /*******************************************************************************
    ** The decision for one run.
    *******************************************************************************/
   public record Decision(Integer id, boolean keep, RetentionClass retentionClass, Set<Guard> guards)
   {
   }



   /*******************************************************************************
    ** Decide every run.  The result keeps the input order of runs newest first.
    *******************************************************************************/
   public static List<Decision> calculate(List<RunInfo> runs, Policy policy, ZoneId zone)
   {
      List<RunInfo> sorted = new ArrayList<>(runs);
      sorted.sort(Comparator.comparing(RunInfo::startDate).reversed().thenComparing(RunInfo::id, Comparator.reverseOrder()));

      Map<Integer, RetentionClass> best   = new LinkedHashMap<>();
      Map<Integer, Set<Guard>>     guards = new LinkedHashMap<>();
      for(RunInfo run : sorted)
      {
         best.put(run.id(), RetentionClass.NONE);
         guards.put(run.id(), new LinkedHashSet<>());
      }

      mark(sorted, policy.daily(), RetentionClass.DAILY, run -> run.startDate().atZone(zone).toLocalDate().toString(), best);
      mark(sorted, policy.weekly(), RetentionClass.WEEKLY, run -> isoWeek(run.startDate().atZone(zone).toLocalDate()), best);
      mark(sorted, policy.monthly(), RetentionClass.MONTHLY, run -> run.startDate().atZone(zone).getYear() + "-" + run.startDate().atZone(zone).getMonthValue(), best);
      mark(sorted, policy.yearly(), RetentionClass.YEARLY, run -> String.valueOf(run.startDate().atZone(zone).getYear()), best);

      for(int i = 0; i < Math.min(policy.minimum(), sorted.size()); i++)
      {
         guards.get(sorted.get(i).id()).add(Guard.MINIMUM);
      }
      sorted.stream().filter(RunInfo::verified).findFirst().ifPresent(run -> guards.get(run.id()).add(Guard.NEWEST_VERIFIED));
      sorted.stream().filter(RunInfo::usedByRecentRestore).forEach(run -> guards.get(run.id()).add(Guard.RESTORE_REFERENCE));

      List<Decision> decisions = new ArrayList<>();
      for(RunInfo run : sorted)
      {
         RetentionClass retentionClass = best.get(run.id());
         boolean        keep           = retentionClass != RetentionClass.NONE || !guards.get(run.id()).isEmpty();
         decisions.add(new Decision(run.id(), keep, retentionClass, guards.get(run.id())));
      }
      return (decisions);
   }



   /*******************************************************************************
    ** Count the kept runs by class (NONE counts the guard-kept ones).
    *******************************************************************************/
   public static Map<RetentionClass, Integer> keptByClass(List<Decision> decisions)
   {
      Map<RetentionClass, Integer> counts = new EnumMap<>(RetentionClass.class);
      for(Decision decision : decisions)
      {
         if(decision.keep())
         {
            counts.merge(decision.retentionClass(), 1, Integer::sum);
         }
      }
      return (counts);
   }



   /*******************************************************************************
    ** Keep the newest run of each of the first `limit` distinct periods.
    *******************************************************************************/
   private static void mark(List<RunInfo> newestFirst, int limit, RetentionClass retentionClass, java.util.function.Function<RunInfo, String> periodOf, Map<Integer, RetentionClass> best)
   {
      if(limit <= 0)
      {
         return;
      }
      Set<String> seen = new LinkedHashSet<>();
      for(RunInfo run : newestFirst)
      {
         String period = periodOf.apply(run);
         if(seen.contains(period))
         {
            continue;
         }
         if(seen.size() >= limit)
         {
            return;
         }
         seen.add(period);
         if(retentionClass.ordinal() < best.get(run.id()).ordinal())
         {
            best.put(run.id(), retentionClass);
         }
      }
   }



   /*******************************************************************************
    ** The ISO week of a date, as week-based-year-week.
    *******************************************************************************/
   private static String isoWeek(LocalDate date)
   {
      return (date.get(IsoFields.WEEK_BASED_YEAR) + "-W" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
   }
}
