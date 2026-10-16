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
 ** Renders the Backups home: a plan status row per plan across the full width,
 ** then cards for what needs attention, the latest test restore, recent activity,
 ** the size trend and retention.  Every card has an honest empty state.  Layout
 ** is flow and grid only (no tables, no fixed widths, no inner scrolling): plan
 ** rows wrap their cells and the cards stack to one column on narrow screens,
 ** long names wrap, and colors are inherited (only the status dot is colored), so
 ** it reads at every width in light and dark mode.
 *******************************************************************************/
public final class BackupsHomeView
{
   private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

   private static final String ROOT  = "display:grid;gap:1rem;min-width:0;";
   private static final String GRID  = "display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,24rem),1fr));gap:1rem;align-items:start;min-width:0;";
   private static final String CARD  = "border:1px solid rgba(127,127,127,.3);border-radius:8px;padding:1rem;min-width:0;overflow-wrap:anywhere;";
   private static final String ROW   = "border:1px solid rgba(127,127,127,.25);border-radius:6px;padding:.75rem;margin:0 0 .75rem;min-width:0;";
   private static final String CELLS = "display:grid;grid-template-columns:repeat(auto-fit,minmax(9.5rem,1fr));gap:.6rem 1rem;margin:.6rem 0 0;font-size:.9rem;";
   private static final String LIST  = "display:grid;grid-template-columns:minmax(7rem,auto) 1fr;gap:.25rem .75rem;margin:0 0 .75rem;font-size:.9rem;";
   private static final String ITEMS = "margin:0 0 .75rem;padding-left:1.25rem;list-style:disc;font-size:.9rem;display:grid;gap:.35rem;";
   private static final String MUTED = "opacity:.75;";
   private static final String LINK  = "color:inherit;text-decoration:underline;text-underline-offset:2px;";
   private static final String CHIP  = "display:inline-flex;align-items:center;gap:.4rem;padding:.05rem .6rem;border-radius:999px;border:1px solid rgba(127,127,127,.4);font-size:.8rem;white-space:nowrap;";

   private static final int TREND_BARS = 30;



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
      StringBuilder html = new StringBuilder("<div class=\"backups-home\" style=\"" + ROOT + "\">");
      List<BackupsSetupStatus.Item> setupItems = BackupsSetupStatus.load(data.plans());
      planStatus(html, data, pathPrefix);
      setup(html, setupItems, pathPrefix);
      html.append("<div style=\"").append(GRID).append("\">");
      needsAttention(html, data, setupItems, pathPrefix);
      latestTestRestore(html, data, pathPrefix);
      recentActivity(html, data, pathPrefix);
      trend(html, data);
      retention(html, data);
      return (html.append("</div></div>").toString());
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
      return ("<div style=\"" + CARD + "\"><strong>Backups</strong><p>" + esc(text) + "</p><a style=\"" + LINK + "\" href=\"" + esc(pathPrefix) + "\">Open the Backups home</a></div>");
   }



   /*******************************************************************************
    ** Plan status: one row per plan, the cells wrap to the width.
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
         Long    age    = data.ageHours(plan);
         Integer sla    = plan.getValueInteger("slaHours");
         String  status = data.effectiveStatus(plan);
         QRecord last   = data.lastRun(plan);

         html.append("<div style=\"").append(ROW).append("\"><div style=\"display:flex;flex-wrap:wrap;align-items:center;gap:.5rem .75rem;\">");
         html.append("<strong>").append(recordLink(prefix, BackupsTables.PLAN, plan.getValue("id"), plan.getValueString("name"))).append("</strong>");
         html.append(chip(status));
         if(!Boolean.TRUE.equals(plan.getValueBoolean("isActive")))
         {
            html.append("<span style=\"").append(MUTED).append("\">Not active</span>");
         }
         html.append("</div><div style=\"").append(CELLS).append("\">");
         cell(html, "Last backup", plan.getValueInstant("lastSuccessDate") == null ? "No successful backup yet" : WHEN.format(plan.getValueInstant("lastSuccessDate")));
         cell(html, "Age against SLA", age != null && sla != null ? age + " h of " + sla + " h" : (sla == null ? "No SLA set" : "Not run yet"));
         cell(html, "Last run", last == null ? "None yet" : label(last.getValueString("status")) + (last.getValueInstant("startDate") == null ? "" : ", " + WHEN.format(last.getValueInstant("startDate"))));
         cell(html, "Schedule", plan.getValueString("cronExpression") == null ? "Manual only" : plan.getValueString("cronExpression"));
         cell(html, "Last verified", plan.getValueInstant("lastVerifiedDate") == null ? "Not verified yet" : WHEN.format(plan.getValueInstant("lastVerifiedDate")));
         if(plan.getValueString("objectLockMode") != null && !"NONE".equals(plan.getValueString("objectLockMode")))
         {
            cell(html, "Object lock", plan.getValueString("objectLockMode") + (plan.getValueInteger("objectLockDays") == null ? "" : ", " + plan.getValueInteger("objectLockDays") + " days"));
         }
         html.append("</div></div>");
      }
      link(html, prefix, BackupsTables.PLAN, "Backup plans");
      close(html);
   }



   /*******************************************************************************
    ** The Setup card: what a working backup needs, whether each part is in place,
    ** and the next step, linked to its table.
    *******************************************************************************/
   private static void setup(StringBuilder html, List<BackupsSetupStatus.Item> items, String prefix)
   {
      open(html, "Setup");
      long todo = items.stream().filter(i -> i.state() == BackupsSetupStatus.State.TODO).count();
      html.append("<p style=\"margin:0 0 .6rem;font-size:.9rem;\">")
         .append(todo == 0 ? "Everything backups need is set up." : esc(todo + (todo == 1 ? " item needs" : " items need") + " attention."))
         .append("</p><ul style=\"list-style:none;margin:0;padding:0;\">");
      for(BackupsSetupStatus.Item item : items)
      {
         String color = switch(item.state())
         {
            case OK -> "#16a34a";
            case TODO -> "#dc2626";
            case DEFAULT -> "#d97706";
            case OPTIONAL -> "#9ca3af";
         };
         String label = switch(item.state())
         {
            case OK -> "Set up";
            case TODO -> "Needs setup";
            case DEFAULT -> "Using defaults";
            case OPTIONAL -> "Optional";
         };
         html.append("<li style=\"").append(ROW).append("\"><div style=\"display:flex;flex-wrap:wrap;align-items:center;gap:.5rem .75rem;\">")
            .append("<strong>").append("<a style=\"").append(LINK).append("\" href=\"").append(esc(prefix + "/" + item.tableName())).append("\">").append(esc(item.label())).append("</a></strong>")
            .append("<span style=\"").append(CHIP).append("\"><span aria-hidden=\"true\" style=\"width:.55rem;height:.55rem;border-radius:50%;background:").append(color).append(";display:inline-block;\"></span>").append(esc(label)).append("</span>")
            .append("</div><div style=\"font-size:.9rem;margin-top:.35rem;\">").append(esc(item.detail())).append("</div>");
         if(item.nextStep() != null)
         {
            html.append("<div style=\"").append(MUTED).append("font-size:.9rem;margin-top:.2rem;\">Next: ").append(esc(item.nextStep())).append("</div>");
         }
         html.append("</li>");
      }
      html.append("</ul>");
      close(html);
   }



   /*******************************************************************************
    ** Needs attention: every Setup item that needs setup (the same list the Setup
    ** card counts), plus plans in a problem state and recent failed runs, so the two
    ** cards never disagree.
    *******************************************************************************/
   private static void needsAttention(StringBuilder html, BackupsHomeData data, List<BackupsSetupStatus.Item> setupItems, String prefix)
   {
      open(html, "Needs attention");
      List<BackupsSetupStatus.Item> setupTodo = setupItems.stream().filter(i -> i.state() == BackupsSetupStatus.State.TODO).toList();
      List<QRecord>                 plans     = data.plansNeedingAttention();
      if(setupTodo.isEmpty() && plans.isEmpty() && data.failedRuns().isEmpty())
      {
         empty(html, data.plans().isEmpty() ? "Nothing to check yet: no plans." : "Nothing needs attention.");
      }
      else
      {
         html.append("<ul style=\"").append(ITEMS).append("\">");
         for(BackupsSetupStatus.Item item : setupTodo)
         {
            html.append("<li>Setup: <a style=\"").append(LINK).append("\" href=\"").append(esc(prefix + "/" + item.tableName())).append("\">").append(esc(item.label())).append("</a>: ")
               .append(esc(item.detail())).append("</li>");
         }
         for(QRecord plan : plans)
         {
            html.append("<li>").append(recordLink(prefix, BackupsTables.PLAN, plan.getValue("id"), plan.getValueString("name")))
               .append(": ").append(esc(label(data.effectiveStatus(plan)))).append("</li>");
         }
         for(QRecord run : data.failedRuns())
         {
            html.append("<li>Run ").append(recordLink(prefix, BackupsTables.RUN, run.getValue("id"), run.getValueString("runKey")))
               .append(": ").append(esc(label(run.getValueString("status")))).append("</li>");
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
         row(html, "Compared", number(v.getValueLong("rowsChecked")) + " rows in " + number(toLong(v.getValueInteger("tablesChecked"))) + " tables, "
            + number(toLong(v.getValueInteger("mismatches"))) + " mismatches");
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
    ** Size trend per plan: bars for the stored size of each successful run (oldest
    ** left), then the numbers.
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
         html.append("<div style=\"margin:0 0 .75rem;\"><strong style=\"font-size:.9rem;\">").append(esc(plan.getValueString("name"))).append("</strong>");
         bars(html, runs);
         html.append("<dl style=\"").append(LIST).append("\">");
         row(html, "Successful runs", String.valueOf(runs.size()));
         row(html, "Stored", bytes(first.getValueLong("bytesStored")) + " at first, " + bytes(last.getValueLong("bytesStored")) + " latest");
         row(html, "Rows", number(first.getValueLong("rowsTotal")) + " at first, " + number(last.getValueLong("rowsTotal")) + " latest");
         row(html, "Duration", last.getValueLong("durationMs") == null ? "Not recorded" : duration(last.getValueLong("durationMs")) + " latest");
         html.append("</dl></div>");
      }
      if(!any)
      {
         empty(html, "Trends appear after the first successful run.");
      }
      close(html);
   }



   /*******************************************************************************
    ** A row of bars, one per run, tall in proportion to the stored bytes.
    *******************************************************************************/
   private static void bars(StringBuilder html, List<QRecord> runs)
   {
      List<QRecord> shown = runs.size() > TREND_BARS ? runs.subList(runs.size() - TREND_BARS, runs.size()) : runs;
      long          max   = 1;
      for(QRecord run : shown)
      {
         max = Math.max(max, run.getValueLong("bytesStored") == null ? 0 : run.getValueLong("bytesStored"));
      }
      html.append("<div role=\"img\" aria-label=\"Stored size of the last ").append(shown.size()).append(" successful runs\" style=\"display:flex;align-items:flex-end;gap:2px;height:2.5rem;margin:.4rem 0 .6rem;\">");
      for(QRecord run : shown)
      {
         long   value = run.getValueLong("bytesStored") == null ? 0 : run.getValueLong("bytesStored");
         long   pct   = Math.max(4, Math.round(100.0 * value / max));
         String title = (run.getValueInstant("startDate") == null ? "" : WHEN.format(run.getValueInstant("startDate")) + ": ") + bytes(run.getValueLong("bytesStored"));
         html.append("<span title=\"").append(esc(title)).append("\" style=\"flex:1 1 0;min-width:2px;max-width:1.5rem;height:").append(pct).append("%;background:currentColor;opacity:.55;border-radius:2px 2px 0 0;\"></span>");
      }
      html.append("</div>");
   }



   /*******************************************************************************
    ** Recent activity: the last runs, restores and verifications.
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
            html.append("<li>Backup ").append(recordLink(prefix, BackupsTables.RUN, run.getValue("id"), run.getValueString("runKey")))
               .append(": ").append(esc(label(run.getValueString("status")))).append(start == null ? "" : ", " + esc(WHEN.format(start))).append("</li>");
         });
         data.restores().stream().limit(2).forEach(restore -> html.append("<li>Restore ").append(recordLink(prefix, BackupsTables.RESTORE_RUN, restore.getValue("id"), "#" + restore.getValue("id")))
            .append(": ").append(esc(label(restore.getValueString("status")))).append("</li>"));
         data.verifications().stream().limit(2).forEach(v -> html.append("<li>Verify ").append(recordLink(prefix, BackupsTables.VERIFICATION, v.getValue("id"), label(v.getValueString("level"))))
            .append(": ").append(esc(label(v.getValueString("status")))).append("</li>"));
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
         row(html, "Kept", counts.entrySet().stream().map(e -> e.getValue() + " " + ("UNCLASSIFIED".equals(e.getKey()) ? "not classified yet (no prune has run)" : label(e.getKey()).toLowerCase()))
            .reduce((a, b) -> a + ", " + b).orElse(""));
         List<QRecord> runs   = data.successfulRuns(plan.getValueInteger("id"));
         Instant       oldest = runs.isEmpty() ? null : runs.get(0).getValueInstant("startDate");
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
    ** Open a card.
    *******************************************************************************/
   private static void open(StringBuilder html, String title)
   {
      html.append("<section style=\"").append(CARD).append("\"><h3 style=\"margin:0 0 .75rem;font-size:1rem;font-weight:600;\">").append(esc(title)).append("</h3>");
   }



   /*******************************************************************************
    ** Close a card.
    *******************************************************************************/
   private static void close(StringBuilder html)
   {
      html.append("</section>");
   }



   /*******************************************************************************
    ** A muted empty-state line.
    *******************************************************************************/
   private static void empty(StringBuilder html, String text)
   {
      html.append("<p style=\"").append(MUTED).append("margin:0 0 .75rem;font-size:.9rem;\">").append(esc(text)).append("</p>");
   }



   /*******************************************************************************
    ** A name and value pair in a definition list.
    *******************************************************************************/
   private static void row(StringBuilder html, String name, String value)
   {
      html.append("<dt style=\"").append(MUTED).append("\">").append(esc(name)).append("</dt><dd style=\"margin:0;\">").append(esc(value == null ? "" : value)).append("</dd>");
   }



   /*******************************************************************************
    ** A small label over its value (a plan row cell).
    *******************************************************************************/
   private static void cell(StringBuilder html, String name, String value)
   {
      html.append("<div style=\"min-width:0;\"><div style=\"").append(MUTED).append("font-size:.75rem;\">").append(esc(name)).append("</div><div>").append(esc(value)).append("</div></div>");
   }



   /*******************************************************************************
    ** A status chip: a colored dot (the only color) and the status label.
    *******************************************************************************/
   private static String chip(String status)
   {
      String color = switch(status == null ? "UNKNOWN" : status)
      {
         case "OK" -> "#16a34a";
         case "LATE", "UNVERIFIED", "UNKNOWN" -> "#d97706";
         default -> "#dc2626";
      };
      return ("<span style=\"" + CHIP + "\"><span aria-hidden=\"true\" style=\"width:.55rem;height:.55rem;border-radius:50%;background:" + color + ";display:inline-block;\"></span>" + esc(label(status)) + "</span>");
   }



   /*******************************************************************************
    ** A link to the table list at the end of a card.
    *******************************************************************************/
   private static void link(StringBuilder html, String prefix, String table, String text)
   {
      html.append("<a style=\"").append(LINK).append("font-size:.9rem;\" href=\"").append(esc(prefix + "/" + table)).append("\">").append(esc(text)).append("</a>");
   }



   /*******************************************************************************
    ** A link to a record.
    *******************************************************************************/
   private static String recordLink(String prefix, String table, Object id, String text)
   {
      return ("<a style=\"" + LINK + "\" href=\"" + esc(prefix + "/" + table + "/" + id) + "\">" + esc(text == null ? String.valueOf(id) : text) + "</a>");
   }



   /*******************************************************************************
    ** SOME_STATUS to Some status (OK stays OK).
    *******************************************************************************/
   static String label(String status)
   {
      if(status == null || status.isEmpty())
      {
         return ("Unknown");
      }
      if("OK".equals(status))
      {
         return ("OK");
      }
      String s = status.replace('_', ' ').toLowerCase();
      return (Character.toUpperCase(s.charAt(0)) + s.substring(1));
   }



   /*******************************************************************************
    ** An Integer as a Long, null-safe.
    *******************************************************************************/
   private static Long toLong(Integer value)
   {
      return (value == null ? null : value.longValue());
   }



   /*******************************************************************************
    ** Milliseconds as a short duration.
    *******************************************************************************/
   static String duration(long millis)
   {
      if(millis < 1000)
      {
         return (millis + " ms");
      }
      long seconds = millis / 1000;
      return (seconds < 60 ? seconds + " s" : seconds / 60 + " min " + seconds % 60 + " s");
   }



   /*******************************************************************************
    ** Bytes as a short size.
    *******************************************************************************/
   static String bytes(Long value)
   {
      if(value == null)
      {
         return ("Not recorded");
      }
      double   v     = value;
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
    ** A number with separators.
    *******************************************************************************/
   static String number(Long value)
   {
      return (value == null ? "Not recorded" : String.format("%,d", value));
   }



   /*******************************************************************************
    ** HTML-escape.
    *******************************************************************************/
   static String esc(String s)
   {
      return (s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"));
   }
}
