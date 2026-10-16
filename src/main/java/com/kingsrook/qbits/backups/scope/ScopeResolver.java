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

package com.kingsrook.qbits.backups.scope;


import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppChildMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Association;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Turns a plan (scope type, included roles, scope rows) into the ordered list of
 ** tables a run will back up (spec 4.2).
 **
 ** Resolution: start from the scope type and roles, apply the INCLUDE rows, then
 ** the EXCLUDE rows, add associated children, and drop SECRET and BACKUPS tables.
 ** Tables are ordered parents first, using associations, joins and table-backed
 ** possible values.  Every table that was left out is reported with its reason.
 *******************************************************************************/
public class ScopeResolver
{
   /** Exclusion reason of a table whose backend has no query interface (storage-only): it cannot be read, even when selected. */
   public static final String NOT_QUERYABLE = "NOT_QUERYABLE";

   private final QInstance qInstance;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public ScopeResolver(QInstance qInstance)
   {
      this.qInstance = qInstance;
   }



   /***************************************************************************
    ** One scope row.
    **
    ** @param include true for INCLUDE, false for EXCLUDE
    ** @param selectorType TABLE, APP, QBIT or ROLE
    ** @param selectorValue the table, app, qbit or role
    ** @param filterJson optional QQueryFilter json (TABLE only)
    ** @param includeAssociatedChildren also include tables reachable through associations
    ** @param fieldExclusions comma separated field names to leave out
    ***************************************************************************/
   public record ScopeRule(boolean include, String selectorType, String selectorValue, String filterJson, boolean includeAssociatedChildren, String fieldExclusions)
   {
   }



   /***************************************************************************
    ** One table that will be backed up.
    ***************************************************************************/
   public record ResolvedTable(String name, TableRole role, String filterJson, List<String> excludedFields)
   {
   }



   /***************************************************************************
    ** A table that was left out, and why.
    ***************************************************************************/
   public record ExcludedTable(String name, String reason)
   {
   }



   /***************************************************************************
    ** The outcome of resolving a scope.
    **
    ** @param tables the tables, parents first
    ** @param excluded tables left out, by reason
    ** @param cyclic tables that could not be ordered (reference cycle), in name order at the end
    ***************************************************************************/
   public record ResolvedScope(List<ResolvedTable> tables, List<ExcludedTable> excluded, List<String> cyclic)
   {
      /*******************************************************************************
       ** The table names, in restore order.
       *******************************************************************************/
      public List<String> names()
      {
         return (tables.stream().map(ResolvedTable::name).toList());
      }
   }



   /*******************************************************************************
    ** Resolve a scope.
    *******************************************************************************/
   public ResolvedScope resolve(PlanSettings settings, List<ScopeRule> rules)
   {
      List<TableRole> roles = settings.includeRoles();

      Set<String>         selected  = new LinkedHashSet<>();
      Set<String>         explicit  = new LinkedHashSet<>();
      Map<String, String> filters   = new LinkedHashMap<>();
      Map<String, String> fieldsOut = new LinkedHashMap<>();
      Set<String>         expandChildrenFor = new LinkedHashSet<>();

      if("INSTANCE".equals(settings.scopeType()))
      {
         for(QTableMetaData table : sortedTables())
         {
            if(roles.contains(TableRoles.roleOf(qInstance, table)))
            {
               selected.add(table.getName());
            }
         }
      }

      for(ScopeRule rule : rules)
      {
         if(!rule.include())
         {
            continue;
         }
         for(String name : tablesFor(rule, roles))
         {
            selected.add(name);
            if("TABLE".equals(rule.selectorType()))
            {
               explicit.add(name);
               if(rule.filterJson() != null && !rule.filterJson().isBlank())
               {
                  filters.put(name, rule.filterJson());
               }
               if(rule.fieldExclusions() != null && !rule.fieldExclusions().isBlank())
               {
                  fieldsOut.put(name, rule.fieldExclusions());
               }
            }
            if(rule.includeAssociatedChildren())
            {
               expandChildrenFor.add(name);
            }
         }
      }

      for(ScopeRule rule : rules)
      {
         if(rule.include())
         {
            continue;
         }
         for(String name : tablesFor(rule, List.of(TableRole.values())))
         {
            selected.remove(name);
            explicit.remove(name);
         }
      }

      for(String name : new ArrayList<>(expandChildrenFor))
      {
         addAssociatedChildren(name, selected);
      }

      List<ExcludedTable> excluded = new ArrayList<>();
      List<String>        kept     = new ArrayList<>();
      for(String name : selected)
      {
         QTableMetaData table = qInstance.getTable(name);
         if(table == null)
         {
            excluded.add(new ExcludedTable(name, "NOT_FOUND"));
            continue;
         }
         TableRole role = TableRoles.roleOf(qInstance, table);
         if(role == TableRole.SECRET)
         {
            excluded.add(new ExcludedTable(name, "SECRET"));
         }
         else if(role == TableRole.BACKUPS)
         {
            excluded.add(new ExcludedTable(name, "BACKUPS"));
         }
         else if(!TableRoles.isQueryable(qInstance, table))
         {
            excluded.add(new ExcludedTable(name, NOT_QUERYABLE));
         }
         else if((role == TableRole.EPHEMERAL || role == TableRole.DERIVED || role == TableRole.EXTERNAL) && !explicit.contains(name) && !roles.contains(role))
         {
            excluded.add(new ExcludedTable(name, role.name()));
         }
         else
         {
            kept.add(name);
         }
      }

      List<String> cyclic  = new ArrayList<>();
      List<String> ordered = order(kept, cyclic);

      List<ResolvedTable> tables = new ArrayList<>();
      for(String name : ordered)
      {
         List<String> out = new ArrayList<>();
         if(fieldsOut.get(name) != null)
         {
            for(String field : fieldsOut.get(name).split(","))
            {
               if(!field.isBlank())
               {
                  out.add(field.trim());
               }
            }
         }
         tables.add(new ResolvedTable(name, TableRoles.roleOf(qInstance, qInstance.getTable(name)), filters.get(name), out));
      }
      return (new ResolvedScope(tables, excluded, cyclic));
   }



   /*******************************************************************************
    ** The tables a rule selects.
    *******************************************************************************/
   private Collection<String> tablesFor(ScopeRule rule, List<TableRole> rolesForExpansion)
   {
      Set<String> names = new LinkedHashSet<>();
      switch(rule.selectorType())
      {
         case "TABLE" -> names.add(rule.selectorValue());
         case "ROLE" ->
         {
            TableRole role = TableRole.valueOf(rule.selectorValue());
            for(QTableMetaData table : sortedTables())
            {
               if(TableRoles.roleOf(qInstance, table) == role)
               {
                  names.add(table.getName());
               }
            }
         }
         case "QBIT" ->
         {
            for(QTableMetaData table : sortedTables())
            {
               if(rule.selectorValue().equals(table.getSourceQBitName()) && rolesForExpansion.contains(TableRoles.roleOf(qInstance, table)))
               {
                  names.add(table.getName());
               }
            }
         }
         case "APP" ->
         {
            QAppMetaData app = qInstance.getApp(rule.selectorValue());
            if(app != null)
            {
               Set<String> inApp = new LinkedHashSet<>();
               collectAppTables(app, inApp, new LinkedHashSet<>());
               for(String name : inApp)
               {
                  QTableMetaData table = qInstance.getTable(name);
                  if(table != null && rolesForExpansion.contains(TableRoles.roleOf(qInstance, table)))
                  {
                     names.add(name);
                  }
               }
            }
         }
         default ->
         {
         }
      }
      return (names);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void collectAppTables(QAppMetaData app, Set<String> tables, Set<String> seenApps)
   {
      if(!seenApps.add(app.getName()))
      {
         return;
      }
      for(QAppChildMetaData child : app.getChildren() == null ? List.<QAppChildMetaData>of() : app.getChildren())
      {
         if(child instanceof QAppMetaData childApp)
         {
            collectAppTables(childApp, tables, seenApps);
         }
         else if(child instanceof QTableMetaData table)
         {
            tables.add(table.getName());
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void addAssociatedChildren(String name, Set<String> selected)
   {
      QTableMetaData table = qInstance.getTable(name);
      if(table == null || table.getAssociations() == null)
      {
         return;
      }
      for(Association association : table.getAssociations())
      {
         if(selected.add(association.getAssociatedTableName()))
         {
            addAssociatedChildren(association.getAssociatedTableName(), selected);
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<QTableMetaData> sortedTables()
   {
      return (new ArrayList<>(new TreeMap<>(qInstance.getTables()).values()));
   }



   /*******************************************************************************
    ** The tables that must be restored before the given table (its parents).
    *******************************************************************************/
   Set<String> parentsOf(String name, Set<String> universe)
   {
      Set<String>    parents = new LinkedHashSet<>();
      QTableMetaData table   = qInstance.getTable(name);

      for(QTableMetaData other : qInstance.getTables().values())
      {
         if(other.getAssociations() != null)
         {
            for(Association association : other.getAssociations())
            {
               if(name.equals(association.getAssociatedTableName()))
               {
                  parents.add(other.getName());
               }
            }
         }
      }

      for(QJoinMetaData join : qInstance.getJoins().values())
      {
         if(join.getType() == JoinType.MANY_TO_ONE && name.equals(join.getLeftTable()))
         {
            parents.add(join.getRightTable());
         }
         else if(join.getType() == JoinType.ONE_TO_MANY && name.equals(join.getRightTable()))
         {
            parents.add(join.getLeftTable());
         }
      }

      for(QFieldMetaData field : table.getFields().values())
      {
         if(field.getPossibleValueSourceName() != null)
         {
            QPossibleValueSource pvs = qInstance.getPossibleValueSource(field.getPossibleValueSourceName());
            if(pvs != null && pvs.getType() == QPossibleValueSourceType.TABLE && pvs.getTableName() != null)
            {
               parents.add(pvs.getTableName());
            }
         }
      }

      parents.remove(name);
      parents.retainAll(universe);
      return (parents);
   }



   /*******************************************************************************
    ** Topological order, parents first, stable by name.  Tables in a reference cycle
    ** are appended in name order and reported through the cyclic list.
    *******************************************************************************/
   List<String> order(List<String> names, List<String> cyclic)
   {
      Set<String>              universe  = new LinkedHashSet<>(names);
      Map<String, Set<String>> remaining = new TreeMap<>();
      for(String name : names)
      {
         remaining.put(name, parentsOf(name, universe));
      }

      List<String> ordered = new ArrayList<>();
      while(!remaining.isEmpty())
      {
         List<String> ready = new ArrayList<>();
         for(Map.Entry<String, Set<String>> entry : remaining.entrySet())
         {
            if(entry.getValue().isEmpty())
            {
               ready.add(entry.getKey());
            }
         }
         if(ready.isEmpty())
         {
            cyclic.addAll(remaining.keySet());
            ordered.addAll(remaining.keySet());
            break;
         }
         for(String name : ready)
         {
            ordered.add(name);
            remaining.remove(name);
         }
         for(Set<String> parents : remaining.values())
         {
            parents.removeAll(ready);
         }
      }
      return (ordered);
   }
}
