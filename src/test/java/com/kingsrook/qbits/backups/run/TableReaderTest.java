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
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.consistentread.ConsistentReadInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.QueryOrGetInputInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Integration coverage for the core pager adapter's ordering, bounded fetches,
 ** raw fields and transaction lifetime.
 *******************************************************************************/
class TableReaderTest
{
   private QTableMetaData table;



   /*******************************************************************************
    ** Seed more records than fit in a page.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(7);
      table = QContext.getQInstance().getTable("blogPost");
      table.getField("body").setIsHeavy(true);
      table.getField("secretNote").setIsHidden(true);
      table.withCustomizer(TableCustomizers.POST_QUERY_RECORD, new QCodeReference(CaptureQueries.class));
      CaptureQueries.inputs.clear();
   }



   /*******************************************************************************
    ** An OR filter and a resume cursor are ANDed, with ascending keys even when
    ** the caller requests descending order. Every query has the requested bound.
    *******************************************************************************/
   @Test
   void testFilterResumeOrderingAndRawFields() throws Exception
   {
      String filter = "{\"booleanOperator\":\"OR\",\"criteria\":[{\"fieldName\":\"id\",\"operator\":\"LESS_THAN_OR_EQUALS\",\"values\":[3]},{\"fieldName\":\"id\",\"operator\":\"GREATER_THAN\",\"values\":[5]}],\"orderBys\":[{\"fieldName\":\"id\",\"isAscending\":false}]}";
      List<QRecord> rows = new ArrayList<>();
      new TableReader.Keyset().read(table, filter, 2, 2, null, rows::add);
      assertThat(rows).extracting(r -> r.getValueInteger("id")).containsExactly(3, 6, 7);
      assertThat(rows.get(0).getValueString("body")).isEqualTo("Body of 3, with a comma");
      assertThat(rows.get(0).getValueString("secretNote")).isEqualTo("do-not-leak-3");
      assertThat(CaptureQueries.inputs).hasSize(2).allSatisfy(input ->
      {
         assertThat(input.getFilter().getLimit()).isEqualTo(2);
         assertThat(input.getFilter().getSkip()).isNull();
         assertThat(input.getShouldOmitHiddenFields()).isFalse();
         assertThat(input.getShouldMaskPasswords()).isFalse();
         assertThat(input.getShouldFetchHeavyFields()).isTrue();
         assertThat(input.getShouldTranslatePossibleValues()).isFalse();
         assertThat(input.getShouldGenerateDisplayValues()).isFalse();
      });
   }



   /*******************************************************************************
    ** Changes made after a snapshot was opened are invisible on every page. The
    ** reader leaves the transaction open, with its commit callbacks unexecuted.
    *******************************************************************************/
   @Test
   void testConsistentSnapshotIsRetainedAcrossPages() throws Exception
   {
      List<Boolean> commits = new ArrayList<>();
      try(QBackendTransaction transaction = QBackendTransaction.openConsistentReadFor(new ConsistentReadInput().withBackendName(table.getBackendName())))
      {
         transaction.addAfterCommitCallback(() -> commits.add(true));
         new UpdateAction().execute(new UpdateInput("blogPost").withRecords(List.of(new QRecord().withValue("id", 7).withValue("title", "Changed after snapshot"))));
         CaptureQueries.inputs.clear();
         List<QRecord> rows = new ArrayList<>();
         new TableReader.Keyset().read(table, null, null, 2, transaction, rows::add);
         assertThat(rows).extracting(r -> r.getValueInteger("id")).containsExactly(1, 2, 3, 4, 5, 6, 7);
         assertThat(rows.get(6).getValueString("title")).isEqualTo("Post 7");
         assertThat(CaptureQueries.inputs).hasSize(4).allSatisfy(input -> assertThat(input.getTransaction()).isSameAs(transaction));
         assertThat(commits).isEmpty();
         transaction.commit();
         assertThat(commits).containsExactly(true);
      }
   }



   /*******************************************************************************
    ** Exact page multiples, empty scans, invalid bounds and unsupported key types
    ** must terminate or fail explicitly rather than drop rows or stream unordered.
    *******************************************************************************/
   @Test
   void testBoundariesAndInvalidPaging() throws Exception
   {
      List<QRecord> rows = new ArrayList<>();
      new TableReader.Keyset().read(table, null, 3, 2, null, rows::add);
      assertThat(rows).extracting(r -> r.getValueInteger("id")).containsExactly(4, 5, 6, 7);
      rows.clear();
      new TableReader.Keyset().read(table, null, 7, 2, null, rows::add);
      assertThat(rows).isEmpty();
      assertThatThrownBy(() -> new TableReader.Keyset().read(table, null, null, 0, null, rows::add)).hasMessageContaining("Page size");
      assertThatThrownBy(() -> new TableReader.Keyset().read(table, "{\"limit\":1}", null, 2, null, rows::add)).hasMessageContaining("skip or limit");
      assertThatThrownBy(() -> new TableReader.Keyset().read(table, "{", null, 2, null, rows::add)).hasMessageContaining("scope filter");
      table.getField("id").setType(QFieldType.BOOLEAN);
      assertThatThrownBy(() -> new TableReader.Keyset().read(table, null, null, 2, null, rows::add)).hasMessageContaining("orderable primary key");
   }



   /*******************************************************************************
    ** Observe actual QueryAction inputs without replacing the backend or pager.
    *******************************************************************************/
   public static class CaptureQueries implements TableCustomizerInterface
   {
      static final List<QueryInput> inputs = new ArrayList<>();



      /*******************************************************************************
       ** Record each page and preserve the result unchanged.
       *******************************************************************************/
      @Override
      public List<QRecord> postQuery(QueryOrGetInputInterface input, List<QRecord> records)
      {
         inputs.add((QueryInput) input);
         return (records);
      }
   }
}
