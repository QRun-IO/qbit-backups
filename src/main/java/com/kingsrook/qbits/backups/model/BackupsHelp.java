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

package com.kingsrook.qbits.backups.model;


import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Applies help/qbit-backups.yaml (the QQQ help-bundle format) to the Backups app
 ** and its backups_* tables: what each table is for and how its rows get created,
 ** shown on the table screen and when it is empty.
 ** STAND-IN(core=QRun-IO/qqq#1198, removeBy=4.1.0-RC.8): replace with HelpBundles.register once the pinned QQQ
 ** release includes it; the bundle file stays the same.
 *******************************************************************************/
public final class BackupsHelp
{
   public static final String RESOURCE = "/help/qbit-backups.yaml";
   public static final String SLOT     = "help";
   public static final String APP_KEY  = "app:backups;slot:help";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsHelp()
   {
   }



   /*******************************************************************************
    ** The bundle's entries by key, validated (fails startup if malformed).
    *******************************************************************************/
   public static Map<String, JsonNode> entries() throws QException
   {
      try(InputStream stream = BackupsHelp.class.getResourceAsStream(RESOURCE))
      {
         if(stream == null)
         {
            throw (new IOException("missing resource"));
         }
         JsonNode bundle = new ObjectMapper(new YAMLFactory()).readTree(stream);
         if(!"qbit-backups".equals(bundle.path("bundle").asText()) || !"QBIT".equals(bundle.path("layer").asText()))
         {
            throw (new IOException("invalid bundle header"));
         }
         Map<String, JsonNode> entries = new LinkedHashMap<>();
         for(JsonNode entry : bundle.path("entries"))
         {
            String key     = entry.path("key").asText();
            String summary = entry.path("summary").asText();
            if(key.isBlank() || summary.isBlank() || summary.length() > 120 || entry.path("content").asText().isBlank() || entries.put(key, entry) != null)
            {
               throw (new IOException("invalid or duplicate entry: " + key));
            }
         }
         return (entries);
      }
      catch(IOException | RuntimeException e)
      {
         throw (new QException("Could not load " + RESOURCE, e));
      }
   }



   /*******************************************************************************
    ** Apply the help to the app and every backups_* table in the instance. A host's
    ** own help in the slot is kept (the bundle only fills it).
    *******************************************************************************/
   public static void apply(QInstance qInstance, String appName) throws QException
   {
      Map<String, JsonNode> entries = entries();
      for(QTableMetaData table : qInstance.getTables().values())
      {
         JsonNode entry = entries.get("table:" + table.getName() + ";slot:" + SLOT);
         if(entry != null && (table.getHelpContent() == null || !table.getHelpContent().containsKey(SLOT)))
         {
            table.withHelpContent(SLOT, content(entry));
         }
      }
      QAppMetaData app      = qInstance.getApp(appName);
      JsonNode     appEntry = entries.get(APP_KEY);
      if(app != null && appEntry != null && (app.getHelpContent() == null || !app.getHelpContent().containsKey(SLOT)))
      {
         app.withHelpContent(SLOT, content(appEntry));
      }
   }



   /*******************************************************************************
    ** Summary as the first paragraph, then the content, as markdown.
    *******************************************************************************/
   static QHelpContent content(JsonNode entry)
   {
      return (new QHelpContent().withContentAsMarkdown(entry.path("summary").asText().strip() + "\n\n" + entry.path("content").asText().strip()));
   }
}
