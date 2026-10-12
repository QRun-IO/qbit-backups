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

package com.kingsrook.qbits.backups.run;


import java.util.ArrayList;
import java.util.List;


/*******************************************************************************
 ** What a backup run did.
 *******************************************************************************/
public class BackupRunResult
{
   private Integer      runId;
   private String       runKey;
   private String       runPrefix;
   private String       status;
   private String       manifestKey;
   private String       manifestSha256;
   private String       consistencyAchieved;
   private int          tables;
   private long         rows;
   private long         bytesStored;
   private long         bytesRaw;
   private int          filesReferenced;
   private int          filesCopied;
   private int          filesDeduplicated;
   private int          filesMissing;
   private long         fileBytesCopied;
   private String       errorMessage;
   private List<String> warnings = new ArrayList<>();



   /*******************************************************************************
    ** Whether the run produced a usable backup (has a manifest).
    *******************************************************************************/
   public boolean isUsable()
   {
      return (manifestKey != null && ("SUCCEEDED".equals(status) || "SUCCEEDED_WITH_WARNINGS".equals(status)));
   }



   /*******************************************************************************
    ** Getter for runId
    *******************************************************************************/
   public Integer getRunId()
   {
      return (runId);
   }



   /*******************************************************************************
    ** Setter for runId
    *******************************************************************************/
   public void setRunId(Integer runId)
   {
      this.runId = runId;
   }



   /*******************************************************************************
    ** Getter for runKey
    *******************************************************************************/
   public String getRunKey()
   {
      return (runKey);
   }



   /*******************************************************************************
    ** Setter for runKey
    *******************************************************************************/
   public void setRunKey(String runKey)
   {
      this.runKey = runKey;
   }



   /*******************************************************************************
    ** Getter for runPrefix
    *******************************************************************************/
   public String getRunPrefix()
   {
      return (runPrefix);
   }



   /*******************************************************************************
    ** Setter for runPrefix
    *******************************************************************************/
   public void setRunPrefix(String runPrefix)
   {
      this.runPrefix = runPrefix;
   }



   /*******************************************************************************
    ** Getter for status
    *******************************************************************************/
   public String getStatus()
   {
      return (status);
   }



   /*******************************************************************************
    ** Setter for status
    *******************************************************************************/
   public void setStatus(String status)
   {
      this.status = status;
   }



   /*******************************************************************************
    ** Getter for manifestKey
    *******************************************************************************/
   public String getManifestKey()
   {
      return (manifestKey);
   }



   /*******************************************************************************
    ** Setter for manifestKey
    *******************************************************************************/
   public void setManifestKey(String manifestKey)
   {
      this.manifestKey = manifestKey;
   }



   /*******************************************************************************
    ** Getter for manifestSha256
    *******************************************************************************/
   public String getManifestSha256()
   {
      return (manifestSha256);
   }



   /*******************************************************************************
    ** Setter for manifestSha256
    *******************************************************************************/
   public void setManifestSha256(String manifestSha256)
   {
      this.manifestSha256 = manifestSha256;
   }



   /*******************************************************************************
    ** Getter for consistencyAchieved
    *******************************************************************************/
   public String getConsistencyAchieved()
   {
      return (consistencyAchieved);
   }



   /*******************************************************************************
    ** Setter for consistencyAchieved
    *******************************************************************************/
   public void setConsistencyAchieved(String consistencyAchieved)
   {
      this.consistencyAchieved = consistencyAchieved;
   }



   /*******************************************************************************
    ** Getter for tables
    *******************************************************************************/
   public int getTables()
   {
      return (tables);
   }



   /*******************************************************************************
    ** Setter for tables
    *******************************************************************************/
   public void setTables(int tables)
   {
      this.tables = tables;
   }



   /*******************************************************************************
    ** Getter for rows
    *******************************************************************************/
   public long getRows()
   {
      return (rows);
   }



   /*******************************************************************************
    ** Setter for rows
    *******************************************************************************/
   public void setRows(long rows)
   {
      this.rows = rows;
   }



   /*******************************************************************************
    ** Getter for bytesStored
    *******************************************************************************/
   public long getBytesStored()
   {
      return (bytesStored);
   }



   /*******************************************************************************
    ** Setter for bytesStored
    *******************************************************************************/
   public void setBytesStored(long bytesStored)
   {
      this.bytesStored = bytesStored;
   }



   /*******************************************************************************
    ** Getter for bytesRaw
    *******************************************************************************/
   public long getBytesRaw()
   {
      return (bytesRaw);
   }



   /*******************************************************************************
    ** Setter for bytesRaw
    *******************************************************************************/
   public void setBytesRaw(long bytesRaw)
   {
      this.bytesRaw = bytesRaw;
   }



   /*******************************************************************************
    ** Getter for filesReferenced
    *******************************************************************************/
   public int getFilesReferenced()
   {
      return (filesReferenced);
   }



   /*******************************************************************************
    ** Setter for filesReferenced
    *******************************************************************************/
   public void setFilesReferenced(int filesReferenced)
   {
      this.filesReferenced = filesReferenced;
   }



   /*******************************************************************************
    ** Getter for filesCopied
    *******************************************************************************/
   public int getFilesCopied()
   {
      return (filesCopied);
   }



   /*******************************************************************************
    ** Setter for filesCopied
    *******************************************************************************/
   public void setFilesCopied(int filesCopied)
   {
      this.filesCopied = filesCopied;
   }



   /*******************************************************************************
    ** Getter for filesDeduplicated
    *******************************************************************************/
   public int getFilesDeduplicated()
   {
      return (filesDeduplicated);
   }



   /*******************************************************************************
    ** Setter for filesDeduplicated
    *******************************************************************************/
   public void setFilesDeduplicated(int filesDeduplicated)
   {
      this.filesDeduplicated = filesDeduplicated;
   }



   /*******************************************************************************
    ** Getter for filesMissing
    *******************************************************************************/
   public int getFilesMissing()
   {
      return (filesMissing);
   }



   /*******************************************************************************
    ** Setter for filesMissing
    *******************************************************************************/
   public void setFilesMissing(int filesMissing)
   {
      this.filesMissing = filesMissing;
   }



   /*******************************************************************************
    ** Getter for fileBytesCopied
    *******************************************************************************/
   public long getFileBytesCopied()
   {
      return (fileBytesCopied);
   }



   /*******************************************************************************
    ** Setter for fileBytesCopied
    *******************************************************************************/
   public void setFileBytesCopied(long fileBytesCopied)
   {
      this.fileBytesCopied = fileBytesCopied;
   }



   /*******************************************************************************
    ** Getter for errorMessage
    *******************************************************************************/
   public String getErrorMessage()
   {
      return (errorMessage);
   }



   /*******************************************************************************
    ** Setter for errorMessage
    *******************************************************************************/
   public void setErrorMessage(String errorMessage)
   {
      this.errorMessage = errorMessage;
   }



   /*******************************************************************************
    ** Getter for warnings
    *******************************************************************************/
   public List<String> getWarnings()
   {
      return (warnings);
   }
}
