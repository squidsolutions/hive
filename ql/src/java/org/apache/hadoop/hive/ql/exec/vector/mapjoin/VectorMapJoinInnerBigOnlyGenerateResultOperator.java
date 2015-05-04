/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.mapjoin;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.JoinUtil;
import org.apache.hadoop.hive.ql.exec.vector.VectorizationContext;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashMultiSet;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashTableResult;
import org.apache.hadoop.hive.ql.exec.vector.mapjoin.hashtable.VectorMapJoinHashMultiSetResult;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;

/**
 * This class has methods for generating vectorized join results for the big table only
 * variation of inner joins.
 *
 * When an inner join does not have any small table columns in the join result, we use this
 * variation we call inner big only.  This variation uses a hash multi-set instead of hash map
 * since there are no values (just a count).
 *
 * Note that if a inner key appears in the small table results area, we use the inner join
 * projection optimization and are able to use this variation.
 */
public abstract class VectorMapJoinInnerBigOnlyGenerateResultOperator
        extends VectorMapJoinGenerateResultOperator {

  private static final long serialVersionUID = 1L;
  private static final Log LOG = LogFactory.getLog(VectorMapJoinInnerBigOnlyGenerateResultOperator.class.getName());

  //---------------------------------------------------------------------------
  // Inner big-table only join specific members.
  //

  // An array of hash multi-set results so we can do lookups on the whole batch before output result
  // generation.
  protected transient VectorMapJoinHashMultiSetResult hashMultiSetResults[];

  // Pre-allocated member for storing the (physical) batch index of matching row (single- or
  // multi-small-table-valued) indexes during a process call.
  protected transient int[] allMatchs;

  /*
   *  Pre-allocated members for storing information on single- and multi-valued-small-table matches.
   *
   *  ~ValueCounts
   *                Number of (empty) small table values.
   *  ~AllMatchIndices
   *                (Logical) indices into allMatchs to the first row of a match of a
   *                possible series of duplicate keys.
   *  ~DuplicateCounts
   *                The duplicate count for each matched key.
   *
   */
  protected transient long[] equalKeySeriesValueCounts;
  protected transient int[] equalKeySeriesAllMatchIndices;
  protected transient int[] equalKeySeriesDuplicateCounts;


  // Pre-allocated member for storing the (physical) batch index of rows that need to be spilled.
  protected transient int[] spills;

  // Pre-allocated member for storing index into the hashMultiSetResults for each spilled row.
  protected transient int[] spillHashMapResultIndices;

  public VectorMapJoinInnerBigOnlyGenerateResultOperator() {
    super();
  }

  public VectorMapJoinInnerBigOnlyGenerateResultOperator(VectorizationContext vContext, OperatorDesc conf)
              throws HiveException {
    super(vContext, conf);
  }

  /*
   * Setup our inner big table only join specific members.
   */
  protected void commonSetup(VectorizedRowBatch batch) throws HiveException {
    super.commonSetup(batch);

    // Inner big-table only join specific.
    VectorMapJoinHashMultiSet baseHashMultiSet = (VectorMapJoinHashMultiSet) vectorMapJoinHashTable;

    hashMultiSetResults = new VectorMapJoinHashMultiSetResult[batch.DEFAULT_SIZE];
    for (int i = 0; i < hashMultiSetResults.length; i++) {
      hashMultiSetResults[i] = baseHashMultiSet.createHashMultiSetResult();
    }

    allMatchs = new int[batch.DEFAULT_SIZE];

    equalKeySeriesValueCounts = new long[batch.DEFAULT_SIZE];
    equalKeySeriesAllMatchIndices = new int[batch.DEFAULT_SIZE];
    equalKeySeriesDuplicateCounts = new int[batch.DEFAULT_SIZE];

    spills = new int[batch.DEFAULT_SIZE];
    spillHashMapResultIndices = new int[batch.DEFAULT_SIZE];
  }

  //-----------------------------------------------------------------------------------------------

  /*
   * Inner big table only join (hash multi-set).
   */

  /**
   * Generate the inner big table only join output results for one vectorized row batch.
   *
   * @param batch
   *          The big table batch with any matching and any non matching rows both as
   *          selected in use.
   * @param allMatchs
   *          A subset of the rows of the batch that are matches.
   * @param allMatchCount
   *          Number of matches in allMatchs.
   * @param equalKeySeriesValueCounts
   *          For each equal key series, whether the number of (empty) small table values.
   * @param equalKeySeriesAllMatchIndices
   *          For each equal key series, the logical index into allMatchs.
   * @param equalKeySeriesDuplicateCounts
   *          For each equal key series, the number of duplicates or equal keys.
   * @param equalKeySeriesCount
   *          Number of single value matches.
   * @param spills
   *          A subset of the rows of the batch that are spills.
   * @param spillHashMapResultIndices
   *          For each entry in spills, the index into the hashMapResult.
   * @param spillCount
   *          Number of spills in spills.
   * @param hashTableResults
   *          The array of all hash table results for the batch. We need the
   *          VectorMapJoinHashTableResult for the spill information.
   * @param hashMapResultCount
   *          Number of entries in hashMapResults.
   *
   **/
  protected int finishInnerBigOnly(VectorizedRowBatch batch,
      int[] allMatchs, int allMatchCount,
      long[] equalKeySeriesValueCounts, int[] equalKeySeriesAllMatchIndices,
      int[] equalKeySeriesDuplicateCounts, int equalKeySeriesCount,
      int[] spills, int[] spillHashMapResultIndices, int spillCount,
      VectorMapJoinHashTableResult[] hashTableResults, int hashMapResultCount)
          throws HiveException, IOException {

    int numSel = 0;

    /*
     * Optimize by running value expressions only over the matched rows.
     */
    if (allMatchCount > 0 && bigTableValueExpressions != null) {
      performValueExpressions(batch, allMatchs, allMatchCount);
    }

    for (int i = 0; i < equalKeySeriesCount; i++) {
      long count = equalKeySeriesValueCounts[i];
      int allMatchesIndex = equalKeySeriesAllMatchIndices[i];
      int duplicateCount = equalKeySeriesDuplicateCounts[i];

      if (count == 1) {
        numSel = generateHashMultiSetResultSingleValue(
            batch, allMatchs, allMatchesIndex, duplicateCount, numSel);
      } else {
        generateHashMultiSetResultMultiValue(batch,
            allMatchs, allMatchesIndex,
            duplicateCount, count);
      }
    }

    if (spillCount > 0) {
      spillHashMapBatch(batch, hashTableResults,
          spills, spillHashMapResultIndices, spillCount);
    }

    return numSel;
  }

  /**
   * Generate the single value match inner big table only join output results for a match.
   *
   * @param batch
   *          The big table batch.
   * @param allMatchs
   *          A subset of the rows of the batch that are matches.
   * @param allMatchesIndex
   *          The logical index into allMatchs of the first equal key.
   * @param duplicateCount
   *          The number of duplicates or equal keys.
   * @param numSel
   *          The current count of rows in the rebuilding of the selected array.
   *
   * @return
   *          The new count of selected rows.
   */
  private int generateHashMultiSetResultSingleValue(VectorizedRowBatch batch,
      int[] allMatchs, int allMatchesIndex, int duplicateCount, int numSel)
          throws HiveException, IOException {

    // LOG.info("generateHashMultiSetResultSingleValue enter...");

    // Generate result within big table batch itself.

    // LOG.info("generateHashMultiSetResultSingleValue with big table...");

    for (int i = 0; i < duplicateCount; i++) {

      int batchIndex = allMatchs[allMatchesIndex + i];

      // Use the big table row as output.
      batch.selected[numSel++] = batchIndex;
    }
 
    return numSel;
  }

  /**
   * Generate results for a N x M cross product.
   *
   * @param batch
   *          The big table batch.
   * @param allMatchs
   *          The all match selected array that contains (physical) batch indices.
   * @param allMatchesIndex
   *          The index of the match key.
   * @param duplicateCount
   *          Number of equal key rows.
   * @param count
   *          Value count.
   */
  private void generateHashMultiSetResultMultiValue(VectorizedRowBatch batch,
      int[] allMatchs, int allMatchesIndex,
      int duplicateCount, long count) throws HiveException, IOException {

    // LOG.info("generateHashMultiSetResultMultiValue allMatchesIndex " + allMatchesIndex + " duplicateCount " + duplicateCount + " count " + count);

    // TODO: Look at repeating optimizations...

    for (int i = 0; i < duplicateCount; i++) {

      int batchIndex = allMatchs[allMatchesIndex + i];

      for (long l = 0; l < count; l++) {

        // Copy the BigTable values into the overflow batch. Since the overflow batch may
        // not get flushed here, we must copy by value.
        if (bigTableRetainedVectorCopy != null) {
          bigTableRetainedVectorCopy.copyByValue(batch, batchIndex,
                                                 overflowBatch, overflowBatch.size);
        }

        overflowBatch.size++;
        if (overflowBatch.size == overflowBatch.DEFAULT_SIZE) {
          forwardOverflow();
        }
      }
    }
  }

  /**
   * Generate the inner big table only join output results for one vectorized row batch with
   * a repeated key.
   *
   * @param batch
   *          The big table batch with any matching and any non matching rows both as
   *          selected in use.
   * @param hashMultiSetResult
   *          The hash multi-set results for the batch.
   */
  protected int generateHashMultiSetResultRepeatedAll(VectorizedRowBatch batch,
          VectorMapJoinHashMultiSetResult hashMultiSetResult) throws HiveException {

    long count = hashMultiSetResult.count();

    if (batch.selectedInUse) {
      // The selected array is already filled in as we want it.
    } else {
      int[] selected = batch.selected;
      for (int i = 0; i < batch.size; i++) {
        selected[i] = i;
      }
      batch.selectedInUse = true;
    }

    do {
      forwardBigTableBatch(batch);
      count--;
    } while (count > 0);

    // We forwarded the batch in this method.
    return 0;
  }

  protected int finishInnerBigOnlyRepeated(VectorizedRowBatch batch, JoinUtil.JoinResult joinResult,
      VectorMapJoinHashMultiSetResult hashMultiSetResult) throws HiveException, IOException {

    int numSel = 0;

    switch (joinResult) {
    case MATCH:

      if (bigTableValueExpressions != null) {
        // Run our value expressions over whole batch.
        for(VectorExpression ve: bigTableValueExpressions) {
          ve.evaluate(batch);
        }
      }

      // Generate special repeated case.
      numSel = generateHashMultiSetResultRepeatedAll(batch, hashMultiSetResult);
      break;

    case SPILL:
      // Whole batch is spilled.
      spillBatchRepeated(batch, (VectorMapJoinHashTableResult) hashMultiSetResult);
      break;

    case NOMATCH:
      // No match for entire batch.
      break;
    }

    return numSel;
  }
}