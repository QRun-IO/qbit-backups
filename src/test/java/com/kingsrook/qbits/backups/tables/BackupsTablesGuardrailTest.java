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

package com.kingsrook.qbits.backups.tables;


import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.processes.DefaultPlanProcess;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Guardrail (owner requirement 2026-10-14: "all tables should be populated and
 ** the qbit should be fully using them"). For every backups_* table:
 ** - runtime code (not just its model, metadata or home screen) reads or writes it,
 **   so no table exists only on paper;
 ** - it is listed here with why it starts empty and, where a user sets it up, how
 **   (this qbit ships no DDL and its one setup value, the storage bucket alias, is
 **   the host's, so nothing is seeded by a changelog);
 ** - it has bundle help that says what it is for and how its rows get created
 **   (shown on the table screen and when it is empty).
 ** A new table fails this test until it is wired and explained.
 *******************************************************************************/
class BackupsTablesGuardrailTest
{
   private static final Path MAIN = Path.of("src", "main", "java", "com", "kingsrook", "qbits", "backups");

   /*******************************************************************************
    ** Why each table legitimately starts empty, and where its rows come from.
    *******************************************************************************/
   static final Map<String, String> STARTS_EMPTY = Map.ofEntries(
      Map.entry(BackupsTables.PLAN, "host-specific: its storage bucket alias names a bucket the host provides (the Create default plan action adds Nightly full for an alias)"),
      Map.entry(BackupsTables.PLAN_SCOPE, "user content: include/exclude rules; an INSTANCE plan needs none"),
      Map.entry(BackupsTables.RUN, "runtime: a plan runs, or Import backups from storage"),
      Map.entry(BackupsTables.ARTIFACT, "runtime: every object a run stores"),
      Map.entry(BackupsTables.VERIFICATION, "runtime: Verify backup, or a plan verify schedule"),
      Map.entry(BackupsTables.RESTORE_RUN, "runtime: a restore"),
      Map.entry(BackupsTables.RESTORE_RUN_TABLE, "runtime: each table of a restore"),
      Map.entry(BackupsTables.FILE_OBJECT, "runtime: each distinct file a plan copies"),
      Map.entry(BackupsTables.ID_MAP, "runtime: working data of a restore with new ids, cleared when it ends"));

   private QInstance qInstance;



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      qInstance = BackupsTestUtils.init();
   }



   /*******************************************************************************
    ** Every table is used by runtime code.
    *******************************************************************************/
   @Test
   void everyTableIsUsedAtRuntime() throws Exception
   {
      List<String> runtimeSource = new ArrayList<>();
      try(Stream<Path> files = Files.walk(MAIN))
      {
         for(Path file : files.filter(p -> p.toString().endsWith(".java")).filter(p -> !p.startsWith(MAIN.resolve("model")) && !p.startsWith(MAIN.resolve("app"))).toList())
         {
            runtimeSource.add(Files.readString(file, StandardCharsets.UTF_8));
         }
      }
      for(String table : backupsTables())
      {
         String  constant = constantFor(table);
         Pattern use      = Pattern.compile("\\bBackupsTables\\." + constant + "\\b");
         assertThat(runtimeSource.stream().anyMatch(source -> use.matcher(source).find()))
            .as(table + " (BackupsTables." + constant + ") must be read or written by runtime code, not only declared").isTrue();
      }
   }



   /*******************************************************************************
    ** Every table is explained: where its rows come from.
    *******************************************************************************/
   @Test
   void everyTableIsExplained()
   {
      assertThat(backupsTables()).containsExactlyInAnyOrderElementsOf(STARTS_EMPTY.keySet());
      assertThat(BackupsTables.ALL).containsExactlyInAnyOrderElementsOf(STARTS_EMPTY.keySet());
      assertThat(STARTS_EMPTY.values()).allMatch(reason -> !reason.isBlank());
   }



   /*******************************************************************************
    ** Every table and the app have bundle help that says how rows get created, and
    ** the plan help names the action that sets it up.
    *******************************************************************************/
   @Test
   void everyTableHasEmptyStateHelp() throws Exception
   {
      var bundle = HelpBundles.of(qInstance).getBundles().get(0);
      for(String table : backupsTables())
      {
         assertThat(bundle.getEntries()).filteredOn(entry -> entry.getKey().equals("table:" + table + ";slot:help"))
            .singleElement().satisfies(entry -> assertThat(entry.getContent()).contains("How rows get created"));
      }
      assertThat(bundle.getEntries()).filteredOn(entry -> entry.getKey().equals("app:backups;slot:help")).hasSize(1);
      assertThat(bundle.getEntries()).filteredOn(entry -> entry.getKey().equals("table:backups_plan;slot:help"))
         .singleElement().satisfies(entry -> assertThat(entry.getContent()).contains("Create default plan"));
      // RC.7 validates supplied widget/value-source entries but does not require their coverage.
      for(String name : qInstance.getWidgets().keySet().stream().filter(name -> name.startsWith("backups")).toList())
      {
         assertThat(bundle.getEntries()).anyMatch(entry -> entry.getKey().startsWith("widget:" + name + ";slot:"));
      }
      for(String name : qInstance.getPossibleValueSources().keySet().stream().filter(name -> name.startsWith("backups_")).toList())
      {
         assertThat(bundle.getEntries()).anyMatch(entry -> entry.getKey().startsWith("possibleValueSource:" + name + ";slot:"));
      }
      assertThat(qInstance.getProcess(DefaultPlanProcess.NAME)).as("the action the plan help names").isNotNull();
      assertThat(qInstance.getProcess(DefaultPlanProcess.NAME).getLabel()).isEqualTo("Create default plan");
   }



   /*******************************************************************************
    ** A host's own help in the slot is never replaced by the bundle.
    *******************************************************************************/
   @Test
   void hostHelpIsKept() throws Exception
   {
      QTableMetaData table = qInstance.getTable(BackupsTables.RUN);
      table.withHelpContent("help", new QHelpContent("Host help for runs."));
      qInstance.getApp("backups").withHelpContent("help", new QHelpContent("Host help for the app."));
      HelpBundles.register(qInstance, "help/qbit-backups.yaml");
      assertThat(qInstance.getTable("helpContent")).isNull();
      HelpBundles.syncOnStartup(qInstance);
      assertThat(table.getHelpContent().get("help")).singleElement().satisfies(h -> assertThat(h.getContent()).isEqualTo("Host help for runs."));
      assertThat(qInstance.getApp("backups").getHelpContent().get("help")).singleElement().satisfies(h -> assertThat(h.getContent()).isEqualTo("Host help for the app."));
      assertThat(HelpBundles.of(qInstance).getBundles()).isNotEmpty();
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<String> backupsTables()
   {
      List<String> tables = qInstance.getTables().values().stream().map(QTableMetaData::getName).filter(n -> n.startsWith("backups_")).sorted().toList();
      assertThat(tables).hasSize(9);
      return (tables);
   }



   /*******************************************************************************
    ** The BackupsTables constant whose value is the table name.
    *******************************************************************************/
   private static String constantFor(String table) throws Exception
   {
      for(Field field : BackupsTables.class.getFields())
      {
         if(field.getType() == String.class && table.equals(field.get(null)) && !field.getName().equals("PVS_PREFIX"))
         {
            return (field.getName());
         }
      }
      throw (new IllegalStateException("no constant for " + table));
   }
}
