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


import java.util.List;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.AbstractWidgetRenderer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetInput;
import com.kingsrook.qqq.backend.core.model.actions.widgets.RenderWidgetOutput;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.RawHTML;
import com.kingsrook.qqq.backend.core.model.dashboard.widgets.WidgetType;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppSection;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The Backups app (a sub-app under Admin when configured) and its widgets.  The
 ** home is the single owner of backup data: every card is built from the qbit
 ** tables, and each has an honest empty state.  The nav lists the main tables;
 ** sub-tables appear as child lists on their parents.
 *******************************************************************************/
public final class BackupsApp
{
   public static final String HOME_WIDGET        = "backupsHome";
   public static final String HEALTH_CARD_WIDGET = "backupsHealthCard";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsApp()
   {
   }



   /*******************************************************************************
    ** The widgets.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      return (List.of(
         new QWidgetMetaData().withName(HOME_WIDGET).withLabel("Backups").withType(WidgetType.HTML.getType())
            .withCodeReference(new QCodeReference(HomeRenderer.class)).withGridColumns(12).withIsCard(false).withPermissionRules(BackupsTables.permissionRules()),
         new QWidgetMetaData().withName(HEALTH_CARD_WIDGET).withLabel("Backups").withType(WidgetType.HTML.getType())
            .withCodeReference(new QCodeReference(HealthCardRenderer.class)).withGridColumns(12).withIsCard(true).withPermissionRules(BackupsTables.permissionRules())));
   }



   /*******************************************************************************
    ** The app: the main tables only; sub-tables are relations on their parents.
    *******************************************************************************/
   public static QAppMetaData app(BackupsQBitConfig config, List<QTableMetaData> tables)
   {
      QAppMetaData app = new QAppMetaData()
         .withName(config.getAppName())
         .withLabel(config.getAppLabel())
         .withIcon(new QIcon("backup"))
         .withPermissionRules(BackupsTables.permissionRules())
         .withWidgets(List.of(HOME_WIDGET))
         .withSection(new QAppSection().withName("backups").withLabel("Backups").withIcon(new QIcon("backup"))
            .withTables(List.of(BackupsTables.PLAN, BackupsTables.RUN)))
         .withSection(new QAppSection().withName("recovery").withLabel("Restore and verify").withIcon(new QIcon("restore"))
            .withTables(List.of(BackupsTables.RESTORE_RUN, BackupsTables.VERIFICATION)))
         .withSection(new QAppSection().withName("files").withLabel("Files").withIcon(new QIcon("attach_file"))
            .withTables(List.of(BackupsTables.FILE_OBJECT)));
      if(config.getParentAppName() != null)
      {
         app.setParentAppName(config.getParentAppName());
      }
      for(QTableMetaData table : tables)
      {
         if(BackupsTables.NAV_TABLES.contains(table.getName()))
         {
            app.withChild(table);
         }
      }
      return (app);
   }



   /*******************************************************************************
    ** The home.
    *******************************************************************************/
   public static class HomeRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(new RawHTML(null, BackupsHomeView.render(BackupsHomeData.load(), pathPrefix()))));
      }
   }



   /*******************************************************************************
    ** The status card for Admin, Health.
    *******************************************************************************/
   public static class HealthCardRenderer extends AbstractWidgetRenderer
   {
      /*******************************************************************************
       ** Render.
       *******************************************************************************/
      @Override
      public RenderWidgetOutput render(RenderWidgetInput input) throws QException
      {
         return (new RenderWidgetOutput(new RawHTML("Backups", BackupsHomeView.renderHealthCard(BackupsHomeData.load(), pathPrefix()))));
      }
   }



   /*******************************************************************************
    ** The path prefix of table and record links.
    *******************************************************************************/
   static String pathPrefix()
   {
      var config = com.kingsrook.qbits.backups.BackupsRuntime.getConfig();
      if(config == null)
      {
         return ("/backups");
      }
      return (config.getParentAppName() != null ? "/" + config.getParentAppName() + "/" + config.getAppName() : "/" + config.getAppName());
   }
}
