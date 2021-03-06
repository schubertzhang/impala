// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.*;
import com.cloudera.impala.analysis.TupleDescriptor;
import com.cloudera.impala.catalog.HdfsPartition;
import com.cloudera.impala.catalog.HdfsTable;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.THdfsFileSplit;
import com.cloudera.impala.thrift.THdfsScanNode;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.cloudera.impala.thrift.TScanRange;
import com.cloudera.impala.thrift.TScanRangeLocation;
import com.cloudera.impala.thrift.TScanRangeLocations;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.plan.*;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import com.hugetable.common.HorizonConstants;
import com.hugetable.hive.io.HFileLayerInputFormat;
import com.hugetable.hive.io.HugetableInputFormatProxy;
/**
 * Scan of a single single table. Currently limited to full-table scans.
 * TODO: pass in range restrictions.
 */
public class HdfsScanNode extends ScanNode {
  private final static Logger LOG = LoggerFactory.getLogger(HdfsScanNode.class);

  private final HdfsTable tbl;

  // Partitions that are filtered in for scanning by the key ranges
  private final ArrayList<HdfsPartition> partitions = Lists.newArrayList();

  private List<SingleColumnFilter> keyFilters;
 
  //predicates on range partition column
  private SingleColumnFilter rangePartitionFilter = null;
  //the corresponding equivalent predicates of rangePartitionFilter in hive form.
  private ExprNodeDesc rangePartitionFilterExpr = null;


  // Total number of bytes from partitions
  private long totalBytes = 0;

  /**
   * Constructs node to scan given data files of table 'tbl'.
   */
  public HdfsScanNode(PlanNodeId id, TupleDescriptor desc, HdfsTable tbl) {
    super(id, desc, "SCAN HDFS");
    this.tbl = tbl;
  }

    public void setRangePartitionFilter(SingleColumnFilter filter)
    {
//        Preconditions.checkNotNull(filter);
        this.rangePartitionFilter = filter;
    }

  public void setKeyFilters(List<SingleColumnFilter> filters) {
    Preconditions.checkNotNull(filters);
    this.keyFilters = filters;
  }

  @Override
  protected String debugString() {
    ToStringHelper helper = Objects.toStringHelper(this);
    for (HdfsPartition partition: partitions) {
      helper.add("Partition " + partition.getId() + ":", partition.toString());
    }
    return helper.addValue(super.debugString()).toString();
  }

  /**
   * Compute file paths and key values based on key ranges.
   * This finalize() implementation also includes the computeStats() logic
   * (and there is no computeStats()), because it's easier to do that during
   * ValueRange construction.
   */
  @Override
  public void finalize(Analyzer analyzer) throws InternalException {
    Preconditions.checkNotNull(keyFilters);
    super.finalize(analyzer);

    LOG.info("collecting partitions for table " + tbl.getName());
    if (tbl.getPartitions().isEmpty()) {
      cardinality = tbl.getNumRows();
    } else {
      cardinality = 0;
      boolean hasValidPartitionCardinality = false;
      for (HdfsPartition p: tbl.getPartitions()) {
        if (p.getFileDescriptors().size() == 0) {
          // No point scanning partitions that have no data
          continue;
        }

        Preconditions.checkState(
            p.getPartitionValues().size() == tbl.getNumClusteringCols());
        // check partition key values against key ranges, if set
        Preconditions.checkState(keyFilters.size() == p.getPartitionValues().size());
        boolean matchingPartition = true;
        for (int i = 0; i < keyFilters.size(); ++i) {
          SingleColumnFilter keyFilter = keyFilters.get(i);
          if (keyFilter != null
              && !keyFilter.isTrue(analyzer, p.getPartitionValues().get(i))) {
            matchingPartition = false;
            break;
          }
        }
        if (!matchingPartition) {
          // skip this partition, it's outside the key filters
          continue;
        }
        // HdfsPartition is immutable, so it's ok to copy by reference
        partitions.add(p);

        // ignore partitions with missing stats in the hope they don't matter
        // enough to change the planning outcome
        if (p.getNumRows() > 0) {
          cardinality += p.getNumRows();
          hasValidPartitionCardinality = true;
        }
        totalBytes += p.getSize();
      }
      // if none of the partitions knew its number of rows, we fall back on
      // the table stats
      if (!hasValidPartitionCardinality) cardinality = tbl.getNumRows();
    }

        // in finalizer, we map rangePartition filter to corresponding representation for hive
        if (rangePartitionFilter != null)
        {
            this.rangePartitionFilterExpr = toHiveExprNodeDesc(rangePartitionFilter);
        }

  
    Preconditions.checkState(cardinality >= 0 || cardinality == -1);
    if (cardinality > 0) {
      LOG.info("cardinality=" + Long.toString(cardinality) + " sel=" + Double.toString(computeSelectivity()));
      cardinality = Math.round((double) cardinality * computeSelectivity());
    }
    LOG.info("finalize HdfsScan: cardinality=" + Long.toString(cardinality));

    // TODO: take actual partitions into account
    numNodes = tbl.getNumNodes();
    LOG.info("finalize HdfsScan: #nodes=" + Integer.toString(numNodes));
  }

   /**
     * Intend to extract predicates on range partition column to prune partition.
     * <p/>
     * Currently the partition pruning procedure is implemented in Hive.However
     * <p/>
     * impala and hive don't share frontend,so have to convert predicates from impala form
     * to hive form, to let predicate pruning works.
     *
     * @param filter
     * @return corresponding ExprNodeDesc for passed in <code>filter</code>
     */
    private ExprNodeDesc toHiveExprNodeDesc(SingleColumnFilter filter)
    {
        assert filter != null;
        List<Expr> conjuncts = filter.getConjuncts();

        //range partition column name
        String colName = filter.getSlotDesc().getColumn().getName();
        LOG.info("Range Partition column name #" + colName + "#");

        if (conjuncts.size() == 1)
        {
            ExprNodeDesc exprNodeDesc = toHiveExprNodeDesc(colName, conjuncts.get(0));
            LOG.info("Only one conjunct ,and generated hive expr string is " + exprNodeDesc.getExprString());
            return exprNodeDesc;
        }
        else if (conjuncts.size() == 2)
        {
            ExprNodeDesc child1 = toHiveExprNodeDesc(colName, conjuncts.get(0));
            ExprNodeDesc child2 = toHiveExprNodeDesc(colName, conjuncts.get(1));
            List<ExprNodeDesc> children = Lists.newArrayList();
            children.add(child1);
            children.add(child2);
            ExprNodeGenericFuncDesc exprNodeGenericFuncDesc = new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                    FunctionRegistry.getFunctionInfo("and").getGenericUDF(), children);
            LOG.info("There are two conjuncts, and generated hive expr string is " + exprNodeGenericFuncDesc.getExprString());
            return exprNodeGenericFuncDesc;
        }
        else
        {
            throw new IllegalArgumentException("range partition filter can't contains more than 2 conjuncts.");
        }
    }

    private ExprNodeDesc toHiveExprNodeDesc(String colName, Expr expr)
    {
        Preconditions.checkArgument(expr != null);
        Preconditions.checkState(expr instanceof BinaryPredicate, "Range Partition only support BinaryPredicate");

        BinaryPredicate predicate = (BinaryPredicate) expr;
        SlotRef lhs = (SlotRef) predicate.getChild(0);
        //TODO other integer type will OK.
        IntLiteral rhs = (IntLiteral) predicate.getChild(1);

        //TODO currently only support TYPE int
        ExprNodeColumnDesc columnDesc = new ExprNodeColumnDesc(
                TypeInfoFactory.getPrimitiveTypeInfo(lhs.getDesc().getType().toString().toLowerCase()), colName, "", false);
        ExprNodeConstantDesc constantDesc = new ExprNodeConstantDesc(rhs.getValue());
        List<ExprNodeDesc> children = Lists.newArrayList();
        children.add(columnDesc);
        children.add(constantDesc);

        switch (predicate.getOp())
        {
            case EQ:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo("==").getGenericUDF(), children);
            case NE:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo("!=").getGenericUDF(), children);
            case LE:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo("<=").getGenericUDF(), children);
            case GE:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo(">=").getGenericUDF(), children);
            case LT:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo("<").getGenericUDF(), children);
            case GT:
                return new ExprNodeGenericFuncDesc(TypeInfoFactory.booleanTypeInfo,
                        FunctionRegistry.getFunctionInfo(">").getGenericUDF(), children);
            default:
                throw new RuntimeException("range partition filter don't support operation " + predicate.getOp().toString());
        }
    }



  @Override
  protected void toThrift(TPlanNode msg) {
    // TODO: retire this once the migration to the new plan is complete
    msg.hdfs_scan_node = new THdfsScanNode(desc.getId().asInt());
    msg.node_type = TPlanNodeType.HDFS_SCAN_NODE;
  }

  /**
   * Return scan ranges (hdfs splits) plus their storage locations, including volume
   * ids.
   */
  @Override
  public List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength) {
    List<TScanRangeLocations> result = Lists.newArrayList();
 /***
         *  if rangePartitionFiler is not null, indicate this is a hugtable-backed hdfs table and there is predicate on
         *  range partition column. we does following works:
         *
         *  1. use rangePartitionFilter to prune partition.
         *
         *  2.  recompute all involving file descritpor,
         *
         *  3. and at the same time populate file location in file descriptor.
         *
         *  There are two places to load table's data files.
         *  <ul>
         *      <li>
         *            table is loaded first time or be refreshed, which will load all range partition's data file.
         *      </li>
         *      <li>
         *            Query statement which contains predicate on range partition column, will do partition pruning
         *            at this place.
         *      </li>
         *  </ul>
         *
         *
         */
        if (rangePartitionFilter != null)
        {

            List<HdfsPartition.FileDescriptor> fileDescriptors = Lists.newArrayList();

//            assert fileDescriptors.size() == 0 && msPartition == null;

            Configuration conf = new Configuration();
            Map<String, String> parameters = tbl.getMetaStoreTable().getParameters();
            conf.set(HorizonConstants.HIVE_TABLE_PARTITION_INFO_CONF_KEY, parameters.get(HorizonConstants.HIVE_TABLE_PARTITION_INFO_CONF_KEY));
            conf.set(HugetableInputFormatProxy.HIVE_TABLE_NAME, tbl.getMetaStoreTable().getTableName());
            conf.set(HugetableInputFormatProxy.HIVE_DATABASE_NAME, tbl.getMetaStoreTable().getDbName());
            conf.set(TableScanDesc.FILTER_EXPR_CONF_STR, Utilities.serializeExpression(rangePartitionFilterExpr));
            conf = HBaseConfiguration.addHbaseResources(conf);
            Path rootDir = null;
            Set<FileStatus> splits = null;
            try
            {
                rootDir = FSUtils.getRootDir(conf);
                LOG.info("hive table name is " + tbl.getMetaStoreTable().getTableName() + ", HBase root directory is " + rootDir.toString());
                splits = HFileLayerInputFormat.getTableManagedFiles(conf, rootDir);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }

            for (FileStatus status : splits)
            {
                LOG.info("#" + status.getPath().toString() + "#");
                fileDescriptors.add(new HdfsPartition.FileDescriptor(status.getPath().toString(), status.getLen(), status.getModificationTime()));
            }


            tbl.loadBlockMd(fileDescriptors);
            //FIXME
            // fileDescriptor don't include block locations so far.
            for (HdfsPartition.FileDescriptor fileDescriptor : fileDescriptors)
            {
                getScanRangeLocations(maxScanRangeLength, result, 0, fileDescriptor);
            }
            return result;
        }


	
    for (HdfsPartition partition: partitions) {
		long id = partition.getId();
      for (HdfsPartition.FileDescriptor fileDesc: partition.getFileDescriptors()) {
       	getScanRangeLocations(maxScanRangeLength, result, id, fileDesc);
      }
    }
    return result;
  }

private List<TScanRangeLocations> getScanRangeLocations(long maxScanRangeLength, List<TScanRangeLocations> result,
                                                            long id, HdfsPartition.FileDescriptor fileDesc)
{
 	for (HdfsPartition.FileBlock block: fileDesc.getFileBlocks()) {
          String[] blockHostPorts = block.getHostPorts();
          if (blockHostPorts.length == 0) {
            // we didn't get locations for this block; for now, just ignore the block
            // TODO: do something meaningful with that
            continue;
          }

          // record host/ports and volume ids
          Preconditions.checkState(blockHostPorts.length > 0);
          List<TScanRangeLocation> locations = Lists.newArrayList();
          for (int i = 0; i < blockHostPorts.length; ++i) {
            TScanRangeLocation location = new TScanRangeLocation();
            String hostPort = blockHostPorts[i];
            location.setServer(addressToTNetworkAddress(hostPort));
            location.setVolume_id(block.getDiskId(i));
            locations.add(location);
          }

          // create scan ranges, taking into account maxScanRangeLength
          long currentOffset = block.getOffset();
          long remainingLength = block.getLength();
          while (remainingLength > 0) {
            long currentLength = remainingLength;
            if (maxScanRangeLength > 0 && remainingLength > maxScanRangeLength) {
              currentLength = maxScanRangeLength;
            }
            TScanRange scanRange = new TScanRange();
            scanRange.setHdfs_file_split(
                new THdfsFileSplit(block.getFileName(), currentOffset,
                  currentLength,id, block.getFileSize()));
            TScanRangeLocations scanRangeLocations = new TScanRangeLocations();
            scanRangeLocations.scan_range = scanRange;
            scanRangeLocations.locations = locations;
            result.add(scanRangeLocations);
            remainingLength -= currentLength;
            currentOffset += currentLength;
          }
        }
	return result;
}

  @Override
  protected String getNodeExplainString(String prefix, TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(prefix + "table=" + desc.getTable().getFullName());
    output.append(" #partitions=" + partitions.size());
    output.append(" size=" + printBytes(totalBytes));
    if (compactData) {
      output.append(" compact\n");
    } else {
      output.append("\n");
    }
    if (!conjuncts.isEmpty()) {
      output.append(prefix + "predicates: " + getExplainString(conjuncts) + "\n");
    }
    return output.toString();
  }

  /**
   * Return the number in TB, GB, MB, KB with 2 decimal points. For example 5000 will be
   * returned as 4.88KB.
   * @param num
   * @return
   */
  static private String printBytes(long value) {
    long kb = 1024;
    long mb = kb * 1024;
    long gb = mb * 1024;
    long tb = gb * 1024;

    double result = value;
    if (value > tb) {
      return String.format("%.2f", result / tb) + "TB";
    }
    if (value > gb) {
      return String.format("%.2f", result / gb) + "GB";
    }
    if (value > mb) {
      return String.format("%.2f", result / mb) + "MB";
    }
    if (value > kb) {
      return String.format("%.2f", result / kb) + "KB";
    }
    return value + "B";
  }

  /**
   * Raises NotImplementedException if any of the partitions uses an unsupported file
   * format.  This is useful for experimental formats, which we currently don't have.
   * Can only be called after finalize().
   */
  public void validateFileFormat() throws NotImplementedException {
  }
}
