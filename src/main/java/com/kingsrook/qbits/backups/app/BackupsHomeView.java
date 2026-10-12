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

package com.kingsrook.qbits.backups.app;


import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Renders the Backups home: one card per concern, each with an honest empty
 ** state.  Cards stack to one column on narrow screens, long names wrap, and
 ** there are no tables and no inner horizontal scroll (definition lists and
 ** lists only), so it reads at every width in light and dark mode (colors are
 ** inherited, not set).
 *******************************************************************************/
public final class BackupsHomeView
{
   private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

   private static final String GRID  = "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,22rem),1fr));gap:1rem;";
   private static final String CARD  = "border:1px solid rgba(127,127,127,.3);border-radius:8px;padding:1rem;min-width:0;overflow-wrap:anywhere;";
   private static final String LIST  = "display:grid;grid-template-columns:minmax(7rem,auto) 1fr;gap:.25rem .75rem;margin:0 0 .75rem;font-size:.9rem;";
   private static final String ITEMS = "margin:0 0 .75rem;padding-left:1.25rem;font-size:.9rem;";
   private static final String MUTED = "opacity:.75;";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsHomeView()
   {
   }



   /*******************************************************************************
    ** The whole home; pathPrefix is the prefix of table and record links.
    *******************************************************************************/
   public static String render(BackupsHomeData data, String pathPrefix)
   {
      StringBuilder html = new StringBuilder("<div class=\"backups-home\" style=\"" + GRID + "\">");
      planStatus(html, data, pathPrefix);
      needsAttention(html, data, pathPrefix);
      latestTestRestore(html, data, pathPrefix);
      trend(html, data);
      recentActivity(html, data, pathPrefix);
      retention(html, data);
      return (html.append("</div>").toString());
   }



   /*******************************************************************************
    ** The one status card for Admin, Health.
    *******************************************************************************/
   public static String renderHealthCard(BackupsHomeData data, String pathPrefix)
   {
      String text = switch(data.overallState())
      {
         case "NO_PLANS" -> "No backup plans yet.";
         case "ATTENTION" -> "Needs attention: see the Backups home.";
         default -> "All backups are within their SLA.";
      };
      return ("<div style=\"" + CARD + "\"><strong>Backups</strong><p>" + esc(text) + "</p><a href=\"" + esc(pathPrefix) + "\">Open the Backups home</a></div>");
   }



   /*******************************************************************************
    ** Plan status: last successful backup, age against the SLA, last run, verification.
    *******************************************************************************/
   private static void planStatus(StringBuilder html, BackupsHomeData data, String prefix)
   {
      open(html, "Plan status");
      if(data.plans().isEmpty())
      {
         empty(html, "No backup plans yet. Create one from Backup plans, New.");
      }
      for(QRecord plan : data.plans())
      {
         Long   age = data.ageHours(plan);
         Integer sla = plan.getValueInteger("slaHours");
         html.append("<dl style=\"").append(LIST).append("\">");
         html.append("<dt><strong>Plan</strong></dt><dd><a href=\"").append(esc(prefix + "/" + BackupsTables.PLAN + "/" + plan.getValue("id"))).append("\">")
            .append(esc(plan.getValueString("name"))).append("</a>").append(Boolean.TRUE.equals(plan.getValueBoolean("isActive")) ? "" : " (not active)").append("</dd>");
         row(html, "Status", label(plan.getValueString("slaStatus")));
         row(html, "Last backup", plan.getValueInstant("lastSuccessDate") == null ? "No successful backup yet" : WHEN.format(plan.getValueInstant("lastSuccessDate")));
         if(age != null && sla != null)
         {
            row(html, "Age against SLA", age + " h of " + sla + " h" + (age > sla ? " - late" : ""));
         }
         row(html, "Last verified", plan.getValueInstant("lastVerifiedDate") == null ? "Not verified yet" : WHEN.format(plan.getValueInstant("lastVerifiedDate")));
         row(html, "Schedule", plan.getValueString("cronExpression") == null ? "Manual only" : plan.getValueString("cronExpression"));
         if(plan.getValueString("objectLockMode") != null && !"NONE".equals(plan.getValueString("objectLockMode")))
         {
            row(html, "Object lock", plan.getValueString("objectLockMode") + ", " + plan.getValueInteger("objectLockDays") + " days");
         }
         html.append("</dl>");
      }
      link(html, prefix, BackupsTables.PLAN, "Backup plans");
      close(html);
   }



   /*******************************************************************************
    ** Needs attention.
    *******************************************************************************/
   private static void needsAttention(StringBuilder html, BackupsHomeData data, String prefix)
   {
      open(html, "Needs attention");
      List<QRecord> plans = data.plansNeedingAttention();
      if(data.plans().isEmpty())
      {
         empty(html, "Nothing to check yet: no plans.");
      }
      else if(plans.isEmpty() && data.failedRuns().isEmpty())
      {
         empty(html, "Nothing needs attention.");
      }
      else
      {
         html.append("<ul style=\"").append(ITEMS).append("\">");
         for(QRecord plan : plans)
         {
            html.append("<li><a href=\"").append(esc(prefix + "/" + BackupsTables.PLAN + "/" + plan.getValue("id"))).append("\">").append(esc(plan.getValueString("name")))
               .append("</a>: ").append(esc(label(plan.getValueString("slaStatus")))).append("</li>");
         }
         for(QRecord run : data.failedRuns())
         {
            html.append("<li><a href=\"").append(esc(prefix + "/" + BackupsTables.RUN + "/" + run.getValue("id"))).append("\">Run ").append(esc(run.getValueString("runKey")))
               .append("</a> ").append(esc(label(run.getValueString("status")))).append("</li>");
         }
         html.append("</ul>");
      }
      link(html, prefix, BackupsTables.RUN, "All runs");
      close(html);
   }



   /*******************************************************************************
    ** Latest test restore per plan.
    *******************************************************************************/
   private static void latestTestRestore(StringBuilder html, BackupsHomeData data, String prefix)
   {
      open(html, "Latest test restore");
      boolean any = false;
      for(QRecord plan : data.plans())
      {
         QRecord v = data.latestTestRestore(plan.getValueInteger("id"));
         if(v == null)
         {
            continue;
         }
         any = true;
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Plan", plan.getValueString("name"));
         row(html, "Result", label(v.getValueString("status")));
         row(html, "Compared", v.getValueInteger("tablesChecked") + " tables, " + v.getValueLong("rowsChecked") + " rows, " + v.getValueInteger("mismatches") + " mismatches");
         row(html, "Scratch target", v.getValueString("scratchTarget") == null ? "Not recorded" : v.getValueString("scratchTarget"));
         html.append("</dl>");
      }
      if(!any)
      {
         empty(html, "No test restore yet. Run Verify backup, Test restore on a run, or set a verify schedule on the plan.");
      }
      link(html, prefix, BackupsTables.VERIFICATION, "All verifications");
      close(html);
   }



   /*******************************************************************************
    ** Size and duration trend per plan, as numbers next to each other.
    *******************************************************************************/
   private static void trend(StringBuilder html, BackupsHomeData data)
   {
      open(html, "Size and duration trend");
      boolean any = false;
      for(QRecord plan : data.plans())
      {
         List<QRecord> runs = data.successfulRuns(plan.getValueInteger("id"));
         if(runs.isEmpty())
         {
            continue;
         }
         any = true;
         QRecord first = runs.get(0);
         QRecord last  = runs.get(runs.size() - 1);
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Plan", plan.getValueString("name"));
         row(html, "Successful runs", String.valueOf(runs.size()));
         row(html, "Stored (first, latest)", bytes(first.getValueLong("bytesStored")) + ", " + bytes(last.getValueLong("bytesStored")));
         row(html, "Rows (first, latest)", number(first.getValueLong("rowsTotal")) + ", " + number(last.getValueLong("rowsTotal")));
         row(html, "Duration (latest)", last.getValueLong("durationMs") == null ? "Not recorded" : last.getValueLong("durationMs") / 1000 + " s");
         html.append("</dl>");
      }
      if(!any)
      {
         empty(html, "Trends appear after the first successful run.");
      }
      close(html);
   }



   /*******************************************************************************
    ** Recent activity: the last runs and restores.
    *******************************************************************************/
   private static void recentActivity(StringBuilder html, BackupsHomeData data, String prefix)
   {
      open(html, "Recent activity");
      if(data.recentRuns().isEmpty() && data.restores().isEmpty() && data.verifications().isEmpty())
      {
         empty(html, "Nothing has run yet.");
      }
      else
      {
         html.append("<ul style=\"").append(ITEMS).append("\">");
         data.recentRuns().stream().limit(6).forEach(run ->
         {
            Instant start = run.getValueInstant("startDate");
            html.append("<li>Backup <a href=\"").append(esc(prefix + "/" + BackupsTables.RUN + "/" + run.getValue("id"))).append("\">").append(esc(run.getValueString("runKey")))
               .append("</a>: ").append(esc(label(run.getValueString("status")))).append(start == null ? "" : ", " + WHEN.format(start)).append("</li>");
         });
         data.restores().stream().limit(2).forEach(restore -> html.append("<li>Restore <a href=\"").append(esc(prefix + "/" + BackupsTables.RESTORE_RUN + "/" + restore.getValue("id")))
            .append("\">#").append(esc(String.valueOf(restore.getValue("id")))).append("</a>: ").append(esc(label(restore.getValueString("status")))).append("</li>"));
         data.verifications().stream().limit(2).forEach(v -> html.append("<li>Verify <a href=\"").append(esc(prefix + "/" + BackupsTables.VERIFICATION + "/" + v.getValue("id")))
            .append("\">").append(esc(label(v.getValueString("level")))).append("</a>: ").append(esc(label(v.getValueString("status")))).append("</li>"));
         html.append("</ul>");
      }
      link(html, prefix, BackupsTables.RUN, "All runs");
      close(html);
   }



   /*******************************************************************************
    ** Retention: runs kept per class and the oldest restorable point.
    *******************************************************************************/
   private static void retention(StringBuilder html, BackupsHomeData data)
   {
      open(html, "Retention");
      boolean any = false;
      for(QRecord plan : data.plans())
      {
         Map<String, Integer> counts = data.retentionCounts(plan.getValueInteger("id"));
         if(counts.isEmpty())
         {
            continue;
         }
         any = true;
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Plan", plan.getValueString("name"));
         row(html, "Kept", counts.entrySet().stream().map(e -> e.getValue() + " " + label(e.getKey()).toLowerCase()).reduce((a, b) -> a + ", " + b).orElse(""));
         List<QRecord> runs = data.successfulRuns(plan.getValueInteger("id"));
         Instant oldest = runs.isEmpty() ? null : runs.get(0).getValueInstant("startDate");
         row(html, "Oldest restorable", oldest == null ? "Not known" : WHEN.format(oldest));
         html.append("</dl>");
      }
      if(!any)
      {
         empty(html, "Retention starts once runs exist.");
      }
      close(html);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void open(StringBuilder html, String title)
   {
      html.append("<section style=\"").append(CARD).append("\"><h3 style=\"margin:0 0 .75rem;font-size:1rem;\">").append(esc(title)).append("</h3>");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void close(StringBuilder html)
   {
      html.append("</section>");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void empty(StringBuilder html, String text)
   {
      html.append("<p style=\"").append(MUTED).append("margin:0 0 .75rem;\">").append(esc(text)).append("</p>");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void row(StringBuilder html, String name, String value)
   {
      html.append("<dt style=\"").append(MUTED).append("\">").append(esc(name)).append("</dt><dd style=\"margin:0;\">").append(esc(value == null ? "" : value)).append("</dd>");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void link(StringBuilder html, String prefix, String table, String text)
   {
      html.append("<a href=\"").append(esc(prefix + "/" + table)).append("\">").append(esc(text)).append("</a>");
   }



   /*******************************************************************************
    ** SOME_STATUS to Some status.
    *******************************************************************************/
   static String label(String status)
   {
      if(status == null || status.isEmpty())
      {
         return ("Unknown");
      }
      String s = status.replace('_', ' ').toLowerCase();
      return (Character.toUpperCase(s.charAt(0)) + s.substring(1));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String bytes(Long value)
   {
      if(value == null)
      {
         return ("Not recorded");
      }
      double v = value;
      String[] units = { "B", "KB", "MB", "GB", "TB" };
      int      i     = 0;
      while(v >= 1024 && i < units.length - 1)
      {
         v /= 1024;
         i++;
      }
      return (i == 0 ? String.format("%d B", value) : String.format("%.1f %s", v, units[i]));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String number(Long value)
   {
      return (value == null ? "Not recorded" : String.format("%,d", value));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String esc(String s)
   {
      return (s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"));
   }
}
