package nta.engine.query;

/**
 * @author jihoon
 */

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import nta.catalog.*;
import nta.catalog.proto.CatalogProtos.StoreType;
import nta.catalog.statistics.TableStat;
import nta.common.exception.NotImplementedException;
import nta.engine.MasterInterfaceProtos.Partition;
import nta.engine.QueryId;
import nta.engine.QueryIdFactory;
import nta.engine.ScheduleUnitId;
import nta.engine.SubQueryId;
import nta.engine.cluster.QueryManager;
import nta.engine.exec.eval.EvalTreeUtil;
import nta.engine.ipc.protocolrecords.Fragment;
import nta.engine.parser.QueryBlock.FromTable;
import nta.engine.planner.PlannerUtil;
import nta.engine.planner.global.MasterPlan;
import nta.engine.planner.global.QueryUnit;
import nta.engine.planner.global.ScheduleUnit;
import nta.engine.planner.global.ScheduleUnit.PARTITION_TYPE;
import nta.engine.planner.logical.*;
import nta.engine.utils.TupleUtil;
import nta.storage.StorageManager;
import nta.storage.TupleRange;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.thirdparty.guava.common.collect.Maps;
import org.apache.hadoop.thirdparty.guava.common.collect.Sets;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.Map.Entry;

public class GlobalPlanner {
  private static Log LOG = LogFactory.getLog(GlobalPlanner.class);

  private StorageManager sm;
  private QueryManager qm;
  private CatalogService catalog;
  private SubQueryId subId;

  public GlobalPlanner(StorageManager sm, QueryManager qm, CatalogService catalog)
      throws IOException {
    this.sm = sm;
    this.qm = qm;
    this.catalog = catalog;
  }

  /**
   * Builds a master plan from the given logical plan.
   * @param subQueryId
   * @param logicalPlan
   * @return
   * @throws IOException
   */
  public MasterPlan build(SubQueryId subQueryId, LogicalNode logicalPlan)
      throws IOException {
    this.subId = subQueryId;
    // insert store at the subnode of the root
    UnaryNode root = (UnaryNode) logicalPlan;
    IndexWriteNode indexNode = null;
    // TODO: check whether the type of the subnode is CREATE_INDEX
    if (root.getSubNode().getType() == ExprType.CREATE_INDEX) {
      indexNode = (IndexWriteNode) root.getSubNode();
      root = (UnaryNode)root.getSubNode();
    } 
    if (root.getSubNode().getType() != ExprType.STORE) {
      insertStore(QueryIdFactory.newScheduleUnitId(subQueryId).toString(), 
          root).setLocal(false);
    }
    
    // convert 2-phase plan
    LogicalNode tp = convertTo2Phase(logicalPlan);

    // make query graph
    MasterPlan globalPlan = convertToGlobalPlan(subQueryId, indexNode, tp);

    return globalPlan;
  }
  
  private StoreTableNode insertStore(String tableId, LogicalNode parent) {
    StoreTableNode store = new StoreTableNode(tableId);
    store.setLocal(true);
    PlannerUtil.insertNode(parent, store);
    return store;
  }
  
  /**
   * Transforms a logical plan to a two-phase plan. 
   * Store nodes are inserted for every logical nodes except store and scan nodes
   * 
   * @author jihoon
   *
   */
  private class GlobalPlanBuilder implements LogicalNodeVisitor {
    @Override
    public void visit(LogicalNode node) {
      String tableId;
      if (node.getType() == ExprType.GROUP_BY) {
        // transform group by to two-phase plan 
        GroupbyNode groupby = (GroupbyNode) node;
        // insert a store for the child of first group by
        if (groupby.getSubNode().getType() != ExprType.UNION &&
            groupby.getSubNode().getType() != ExprType.STORE &&
            groupby.getSubNode().getType() != ExprType.SCAN) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          insertStore(tableId, groupby);
        }
        tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
        // insert (a store for the first group by) and (a second group by)
        PlannerUtil.transformGroupbyTo2PWithStore((GroupbyNode)node, tableId);
      } else if (node.getType() == ExprType.SORT) {
        // transform sort to two-phase plan 
        SortNode sort = (SortNode) node;
        // insert a store for the child of first sort
        if (sort.getSubNode().getType() != ExprType.UNION &&
            sort.getSubNode().getType() != ExprType.STORE &&
            sort.getSubNode().getType() != ExprType.SCAN) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          insertStore(tableId, sort);
        }
        tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
        // insert (a store for the first sort) and (a second sort)
        PlannerUtil.transformSortTo2PWithStore((SortNode)node, tableId);
      } else if (node.getType() == ExprType.JOIN) {
        // transform join to two-phase plan 
        // the first phase of two-phase join can be any logical nodes
        JoinNode join = (JoinNode) node;
        // insert stores for the first phase
        if (join.getOuterNode().getType() != ExprType.UNION &&
            join.getOuterNode().getType() != ExprType.STORE) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          StoreTableNode store = new StoreTableNode(tableId);
          store.setLocal(true);
          PlannerUtil.insertOuterNode(node, store);
        }
        if (join.getInnerNode().getType() != ExprType.UNION &&
            join.getInnerNode().getType() != ExprType.STORE) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          StoreTableNode store = new StoreTableNode(tableId);
          store.setLocal(true);
          PlannerUtil.insertInnerNode(node, store);
        }
      } else if (node.getType() == ExprType.UNION) {
        // not two-phase transform
        UnionNode union = (UnionNode) node;
        // insert stores
        if (union.getOuterNode().getType() != ExprType.UNION &&
            union.getOuterNode().getType() != ExprType.STORE) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          StoreTableNode store = new StoreTableNode(tableId);
          store.setLocal(true);
          PlannerUtil.insertOuterNode(node, store);
        }
        if (union.getInnerNode().getType() != ExprType.UNION &&
            union.getInnerNode().getType() != ExprType.STORE) {
          tableId = QueryIdFactory.newScheduleUnitId(subId).toString();
          StoreTableNode store = new StoreTableNode(tableId);
          store.setLocal(true);
          PlannerUtil.insertInnerNode(node, store);
        }
      }
    }
  }

  /**
   * Convert the logical plan to a two-phase plan by the post-order traverse.
   * 
   * @param logicalPlan
   * @return
   */
  private LogicalNode convertTo2Phase(LogicalNode logicalPlan) {
    LogicalRootNode root = (LogicalRootNode) logicalPlan;
    root.postOrder(new GlobalPlanBuilder());
    return logicalPlan;
  }
  
  private Map<StoreTableNode, ScheduleUnit> convertMap = 
      new HashMap<StoreTableNode, ScheduleUnit>();
  
  /**
   * Logical plan을 후위 탐색하면서 ScheduleUnit을 생성
   * 
   * @param node 현재 방문 중인 노드
   * @throws IOException
   */
  private void recursiveBuildScheduleUnit(LogicalNode node) 
      throws IOException {
    ScheduleUnit unit = null;
    StoreTableNode store;
    if (node instanceof UnaryNode) {
      recursiveBuildScheduleUnit(((UnaryNode) node).getSubNode());
      
      if (node.getType() == ExprType.STORE) {
        store = (StoreTableNode) node;
        ScheduleUnitId id = null;
        if (store.getTableName().startsWith(QueryId.PREFIX)) {
          id = new ScheduleUnitId(store.getTableName());
        } else {
          id = QueryIdFactory.newScheduleUnitId(subId);
        }
        unit = new ScheduleUnit(id);

        switch (store.getSubNode().getType()) {
        case SCAN:  // store - scan
          unit = makeScanUnit(unit);
          unit.setLogicalPlan(node);
          break;
        case SELECTION:
        case PROJECTION:
          unit = makeUnifiableUnit(store, store.getSubNode(), unit);
          unit.setLogicalPlan(node);
          break;
        case GROUP_BY:
          unit = makeTwoPhaseUnit(store, node, unit);
          unit.setLogicalPlan(node);
          break;
        case SORT:
          unit = makeSortUnit(store, node, unit);
          unit.setLogicalPlan(node);
          break;
        case JOIN:  // store - join
          unit = makeJoinUnit(store, node, unit);
          unit.setLogicalPlan(node);
          break;
        case UNION:
          unit = makeUnionUnit(store, node, unit);
          unit.setLogicalPlan(node);
          break;
        default:
          unit = null;
          break;
        }
        convertMap.put(store, unit);
      }
    } else if (node instanceof BinaryNode) {
      recursiveBuildScheduleUnit(((BinaryNode) node).getOuterNode());
      recursiveBuildScheduleUnit(((BinaryNode) node).getInnerNode());
    } else {
      
    }
  }
  
  private ScheduleUnit makeScanUnit(ScheduleUnit unit) {
    unit.setOutputType(PARTITION_TYPE.LIST);
    return unit;
  }
  
  /**
   * Unifiable node(selection, projection)을 자식 플랜과 같은 ScheduleUnit으로 생성
   * 
   * @param rootStore 생성할 ScheduleUnit의 store
   * @param plan logical plan
   * @param unit 생성할 ScheduleUnit
   * @return
   * @throws IOException
   */
  private ScheduleUnit makeUnifiableUnit(StoreTableNode rootStore, 
      LogicalNode plan, ScheduleUnit unit) throws IOException {
    UnaryNode unary = (UnaryNode) plan;
    switch (unary.getSubNode().getType()) {
    case SCAN:
      unit = makeScanUnit(unit);
      break;
    case SELECTION:
    case PROJECTION:
      unit = makeUnifiableUnit(rootStore, unary.getSubNode(), unit);
      break;
    case SORT:
      unit = makeSortUnit(rootStore, plan, unit);
      break;

    case GROUP_BY:
      unit = makeTwoPhaseUnit(rootStore, plan, unit);
      break;
    case JOIN:
      unit = makeJoinUnit(rootStore, plan, unit);
      break;
    case UNION:
      unit = makeUnionUnit(rootStore, plan, unit);
      break;
    }
    return unit;
  }
  
  /**
   * Two-phase ScheduleUnit을 생성. 
   * 
   * @param rootStore 생성할 ScheduleUnit의 store
   * @param plan logical plan
   * @param unit 생성할 ScheduleUnit
   * @return
   * @throws IOException
   */
  private ScheduleUnit makeTwoPhaseUnit(StoreTableNode rootStore, 
      LogicalNode plan, ScheduleUnit unit) throws IOException {
    UnaryNode unary = (UnaryNode) plan;
    UnaryNode unaryChild;
    StoreTableNode prevStore;
    ScanNode newScan;
    ScheduleUnit prev;
    unaryChild = (UnaryNode) unary.getSubNode();  // groupby
    ExprType curType = unaryChild.getType();
    if (unaryChild.getSubNode().getType() == ExprType.STORE) {
      // store - groupby - store
      unaryChild = (UnaryNode) unaryChild.getSubNode(); // store
      prevStore = (StoreTableNode) unaryChild;
      TableMeta meta = TCatUtil.newTableMeta(prevStore.getOutputSchema(), 
          StoreType.CSV);
      newScan = (ScanNode)insertScan(unary.getSubNode(), 
          prevStore.getTableName(), meta);
      prev = convertMap.get(prevStore);
      if (prev != null) {
        prev.setParentQuery(unit);
        unit.addChildQuery(newScan, prev);
        if (unaryChild.getSubNode().getType() == curType) {
          prev.setOutputType(PARTITION_TYPE.HASH);
        } else {
          prev.setOutputType(PARTITION_TYPE.LIST);
        }
      }
      if (unaryChild.getSubNode().getType() == curType) {
        // the second phase
        unit.setOutputType(PARTITION_TYPE.LIST);
      } else {
        // the first phase
        unit.setOutputType(PARTITION_TYPE.HASH);
      }
    } else if (unaryChild.getSubNode().getType() == ExprType.SCAN) {
      // the first phase
      // store - groupby - scan
      unit.setOutputType(PARTITION_TYPE.HASH);
    } else if (unaryChild.getSubNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)unaryChild.getSubNode(), unit, 
          null, PARTITION_TYPE.LIST);
    } else {
      // error
    }
    return unit;
  }
  
  /**
   * 
   * 
   * @param rootStore 생성할 ScheduleUnit의 store
   * @param plan logical plan
   * @param unit 생성할 ScheduleUnit
   * @return
   * @throws IOException
   */
  private ScheduleUnit makeUnionUnit(StoreTableNode rootStore, 
      LogicalNode plan, ScheduleUnit unit) throws IOException {
    UnaryNode unary = (UnaryNode) plan;
    StoreTableNode outerStore, innerStore;
    ScheduleUnit prev;
    UnionNode union = (UnionNode) unary.getSubNode();
    unit.setOutputType(PARTITION_TYPE.LIST);
    
    if (union.getOuterNode().getType() == ExprType.STORE) {
      outerStore = (StoreTableNode) union.getOuterNode();
      TableMeta outerMeta = TCatUtil.newTableMeta(outerStore.getOutputSchema(), 
          StoreType.CSV);
      insertOuterScan(union, outerStore.getTableName(), outerMeta);
      prev = convertMap.get(outerStore);
      if (prev != null) {
        prev.getStoreTableNode().setTableName(rootStore.getTableName());
        prev.setOutputType(PARTITION_TYPE.LIST);
        prev.setParentQuery(unit);
        unit.addChildQuery((ScanNode)union.getOuterNode(), prev);
      }
    } else if (union.getOuterNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, union, unit, null, PARTITION_TYPE.LIST);
    }
    
    if (union.getInnerNode().getType() == ExprType.STORE) {
      innerStore = (StoreTableNode) union.getInnerNode();
      TableMeta innerMeta = TCatUtil.newTableMeta(innerStore.getOutputSchema(), 
          StoreType.CSV);
      insertInnerScan(union, innerStore.getTableName(), innerMeta);
      prev = convertMap.get(innerStore);
      if (prev != null) {
        prev.getStoreTableNode().setTableName(rootStore.getTableName());
        prev.setOutputType(PARTITION_TYPE.LIST);
        prev.setParentQuery(unit);
        unit.addChildQuery((ScanNode)union.getInnerNode(), prev);
      }
    } else if (union.getInnerNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, union, unit, null, PARTITION_TYPE.LIST);
    }

    return unit;
  }

  private ScheduleUnit makeSortUnit(StoreTableNode rootStore,
    LogicalNode plan, ScheduleUnit unit) throws IOException {

    UnaryNode unary = (UnaryNode) plan;
    UnaryNode unaryChild;
    StoreTableNode prevStore;
    ScanNode newScan;
    ScheduleUnit prev;
    unaryChild = (UnaryNode) unary.getSubNode();  // groupby
    ExprType curType = unaryChild.getType();
    if (unaryChild.getSubNode().getType() == ExprType.STORE) {
      // store - groupby - store
      unaryChild = (UnaryNode) unaryChild.getSubNode(); // store
      prevStore = (StoreTableNode) unaryChild;
      TableMeta meta = TCatUtil.newTableMeta(prevStore.getOutputSchema(),
          StoreType.CSV);
      newScan = (ScanNode)insertScan(unary.getSubNode(),
          prevStore.getTableName(), meta);
      prev = convertMap.get(prevStore);
      if (prev != null) {
        prev.setParentQuery(unit);
        unit.addChildQuery(newScan, prev);
        if (unaryChild.getSubNode().getType() == curType) {
          // TODO - this is duplicated code
          prev.setOutputType(PARTITION_TYPE.RANGE);
        } else {
          prev.setOutputType(PARTITION_TYPE.LIST);
        }
      }
      if (unaryChild.getSubNode().getType() == curType) {
        // the second phase
        unit.setOutputType(PARTITION_TYPE.LIST);
      } else {
        // the first phase
        unit.setOutputType(PARTITION_TYPE.HASH);
      }
    } else if (unaryChild.getSubNode().getType() == ExprType.SCAN) {
      // the first phase
      // store - sort - scan
      unit.setOutputType(PARTITION_TYPE.RANGE);
    } else if (unaryChild.getSubNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)unaryChild.getSubNode(), unit,
          null, PARTITION_TYPE.LIST);
    } else {
      // error
    }
    return unit;
  }
  
  private ScheduleUnit makeJoinUnit(StoreTableNode rootStore, 
      LogicalNode plan, ScheduleUnit unit) throws IOException {
    UnaryNode unary = (UnaryNode)plan;
    StoreTableNode outerStore, innerStore;
    ScheduleUnit prev;
    JoinNode join = (JoinNode) unary.getSubNode();
    Schema outerSchema = join.getOuterNode().getOutputSchema();
    Schema innerSchema = join.getInnerNode().getOutputSchema();
    unit.setOutputType(PARTITION_TYPE.LIST);
    List<Column> outerCollist = new ArrayList<Column>();
    List<Column> innerCollist = new ArrayList<Column>();   
    
    // TODO: set partition for store nodes
    if (join.hasJoinQual()) {
      // repartition
      Set<Column> cols = EvalTreeUtil.findDistinctRefColumns(join.getJoinQual());
      Iterator<Column> it = cols.iterator();
      while (it.hasNext()) {
        Column col = it.next();
        if (outerSchema.contains(col.getQualifiedName())) {
          outerCollist.add(col);
        } else if (innerSchema.contains(col.getQualifiedName())) {
          innerCollist.add(col);
        }
      }
    } else {
      // broadcast
    }
    
    Column[] outerCols = new Column[outerCollist.size()];
    Column[] innerCols = new Column[innerCollist.size()];
    outerCols = outerCollist.toArray(outerCols);
    innerCols = innerCollist.toArray(innerCols);
    
    // outer
    if (join.getOuterNode().getType() == ExprType.STORE) {
      outerStore = (StoreTableNode) join.getOuterNode();
      TableMeta outerMeta = TCatUtil.newTableMeta(outerStore.getOutputSchema(), 
          StoreType.CSV);
      insertOuterScan(join, outerStore.getTableName(), outerMeta);
      prev = convertMap.get(outerStore);
      if (prev != null) {
        prev.setOutputType(PARTITION_TYPE.HASH);
        prev.setParentQuery(unit);
        unit.addChildQuery((ScanNode)join.getOuterNode(), prev);
      }
      outerStore.setPartitions(PARTITION_TYPE.HASH, outerCols, 1);
    } else if (join.getOuterNode().getType() != ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)join.getOuterNode(), unit, 
          outerCols, PARTITION_TYPE.HASH);
    }
    
    // inner
    if (join.getInnerNode().getType() == ExprType.STORE) {
      innerStore = (StoreTableNode) join.getInnerNode();
      TableMeta innerMeta = TCatUtil.newTableMeta(innerStore.getOutputSchema(), 
          StoreType.CSV);
      insertInnerScan(join, innerStore.getTableName(), innerMeta);
      prev = convertMap.get(innerStore);
      if (prev != null) {
        prev.setOutputType(PARTITION_TYPE.HASH);
        prev.setParentQuery(unit);
        unit.addChildQuery((ScanNode)join.getInnerNode(), prev);
      }
      innerStore.setPartitions(PARTITION_TYPE.HASH, innerCols, 1);
    } else if (join.getInnerNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)join.getInnerNode(), unit,
          innerCols, PARTITION_TYPE.HASH);
    }
    
    return unit;
  }
  
  /**
   * Recursive하게 union의 자식 plan들을 설정
   * 
   * @param rootStore 생성할 ScheduleUnit의 store
   * @param union union을 root로 하는 logical plan
   * @param cur 생성할 ScheduleUnit
   * @param cols partition 정보를 설정하기 위한 column array
   * @param prevOutputType 자식 ScheduleUnit의 partition type
   * @throws IOException
   */
  private void _handleUnionNode(StoreTableNode rootStore, UnionNode union, 
      ScheduleUnit cur, Column[] cols, PARTITION_TYPE prevOutputType) 
          throws IOException {
    StoreTableNode store;
    TableMeta meta;
    ScheduleUnit prev;
    
    if (union.getOuterNode().getType() == ExprType.STORE) {
      store = (StoreTableNode) union.getOuterNode();
      meta = TCatUtil.newTableMeta(store.getOutputSchema(), StoreType.CSV);
      insertOuterScan(union, store.getTableName(), meta);
      prev = convertMap.get(store);
      if (prev != null) {
        prev.getStoreTableNode().setTableName(rootStore.getTableName());
        prev.setOutputType(prevOutputType);
        prev.setParentQuery(cur);
        cur.addChildQuery((ScanNode)union.getOuterNode(), prev);
      }
      if (cols != null) {
        store.setPartitions(PARTITION_TYPE.LIST, cols, 1);
      }
    } else if (union.getOuterNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)union.getOuterNode(), cur, cols, 
          prevOutputType);
    }
    
    if (union.getInnerNode().getType() == ExprType.STORE) {
      store = (StoreTableNode) union.getInnerNode();
      meta = TCatUtil.newTableMeta(store.getOutputSchema(), StoreType.CSV);
      insertInnerScan(union, store.getTableName(), meta);
      prev = convertMap.get(store);
      if (prev != null) {
        prev.getStoreTableNode().setTableName(rootStore.getTableName());
        prev.setOutputType(prevOutputType);
        prev.setParentQuery(cur);
        cur.addChildQuery((ScanNode)union.getInnerNode(), prev);
      }
      if (cols != null) {
        store.setPartitions(PARTITION_TYPE.LIST, cols, 1);
      }
    } else if (union.getInnerNode().getType() == ExprType.UNION) {
      _handleUnionNode(rootStore, (UnionNode)union.getInnerNode(), cur, cols, 
          prevOutputType);
    }
  }
  
  private LogicalNode insertScan(LogicalNode parent, String tableId, TableMeta meta) 
      throws IOException {
    TableDesc desc = TCatUtil.newTableDesc(tableId, meta, sm.getTablePath(tableId));
    ScanNode newScan = new ScanNode(new FromTable(desc));
    newScan.setLocal(true);
    newScan.setInputSchema(meta.getSchema());
    newScan.setOutputSchema(meta.getSchema());
    ((UnaryNode)parent).setSubNode(newScan);
    return newScan;
  }
  
  private LogicalNode insertOuterScan(BinaryNode parent, String tableId, 
      TableMeta meta) throws IOException {
    TableDesc desc = TCatUtil.newTableDesc(tableId, meta, sm.getTablePath(tableId));
    ScanNode scan = new ScanNode(new FromTable(desc));
    scan.setLocal(true);
    scan.setInputSchema(meta.getSchema());
    scan.setOutputSchema(meta.getSchema());
    parent.setOuter(scan);
    return parent;
  }
  
  private LogicalNode insertInnerScan(BinaryNode parent, String tableId, 
      TableMeta meta) throws IOException {
    TableDesc desc = TCatUtil.newTableDesc(tableId, meta, sm.getTablePath(tableId));
    ScanNode scan = new ScanNode(new FromTable(desc));
    scan.setLocal(true);
    scan.setInputSchema(meta.getSchema());
    scan.setOutputSchema(meta.getSchema());
    parent.setInner(scan);
    return parent;
  }
  
  private MasterPlan convertToGlobalPlan(SubQueryId subQueryId,
      IndexWriteNode index, LogicalNode logicalPlan) throws IOException {
    recursiveBuildScheduleUnit(logicalPlan);
    ScheduleUnit root = convertMap.get(((LogicalRootNode)logicalPlan).getSubNode());
    if (index != null) {
      index.setSubNode(root.getLogicalPlan());
      root.setLogicalPlan(index);
    }
    return new MasterPlan(root);
  }
  
  private ScheduleUnit setPartitionNumberForTwoPhase(ScheduleUnit unit, int n) {
    // if the next query is join, 
    // set the partition number for the current logicalUnit
    // TODO: union handling when join has union as its child
    ScheduleUnit parentQueryUnit = unit.getParentQuery();
    if (parentQueryUnit != null) {
      if (parentQueryUnit.getStoreTableNode().getSubNode().getType() == ExprType.JOIN) {
        unit.getStoreTableNode().setPartitions(unit.getOutputType(),
            unit.getStoreTableNode().getPartitionKeys(), n);
      }
    }

    StoreTableNode store = unit.getStoreTableNode();
    Column[] keys = null;
    // set the partition number for group by and sort
    if (unit.getOutputType() == PARTITION_TYPE.HASH) {
      if (store.getSubNode().getType() == ExprType.GROUP_BY) {
        GroupbyNode groupby = (GroupbyNode)store.getSubNode();
        keys = groupby.getGroupingColumns();
      }
    } else if (unit.getOutputType() == PARTITION_TYPE.RANGE) {
      if (store.getSubNode().getType() == ExprType.SORT) {
        SortNode sort = (SortNode)store.getSubNode();
        keys = new Column[sort.getSortKeys().length];
        for (int i = 0; i < keys.length; i++) {
          keys[i] = sort.getSortKeys()[i].getSortKey();
        }
      }
    }
    if (keys != null) {
      if (keys.length == 0) {
        store.setPartitions(unit.getOutputType(), new Column[]{}, 1);
      } else {
        store.setPartitions(unit.getOutputType(), keys, n);
      }
    } else {
      // TODO: error
    }
    return unit;
  }

  /**
   * 입력 받은 ScheduleUnit을 QueryUnit들로 localize
   * 
   * @param unit localize할 ScheduleUnit
   * @param n localize된 QueryUnit의 최대 개수
   * @return
   * @throws IOException
   * @throws URISyntaxException
   */
  public QueryUnit[] localize(ScheduleUnit unit, int n) 
      throws IOException, URISyntaxException {
    FileStatus[] files;
    Fragment[] frags;
    List<Fragment> fragList;
    List<URI> uriList;
    // fragments and fetches are maintained for each scan of the ScheduleUnit
    Map<ScanNode, List<Fragment>> fragMap = new HashMap<ScanNode, List<Fragment>>();
    Map<ScanNode, List<URI>> fetchMap = new HashMap<ScanNode, List<URI>>();
    
    // set partition numbers for two phase algorithms
    // TODO: the following line might occur a bug. see the line 623
    unit = setPartitionNumberForTwoPhase(unit, n);

    Schema sortSchema = null;
    
    // make fetches and fragments for each scan
    Path tablepath = null;
    ScanNode[] scans = unit.getScanNodes();
    for (ScanNode scan : scans) {
      if (scan.getTableId().startsWith(QueryId.PREFIX)) {
        tablepath = sm.getTablePath(scan.getTableId());
      } else {
        tablepath = catalog.getTableDesc(scan.getTableId()).getPath();
      }
      if (scan.isLocal()) {
        // make fetches from the previous query units
        uriList = new ArrayList<URI>();
        fragList = new ArrayList<Fragment>();

        ScheduleUnit prev = unit.getChildIterator().next();
        if (prev.getOutputType() == PARTITION_TYPE.RANGE) {
          StoreTableNode store = (StoreTableNode) prev.getLogicalPlan();
          SortNode sort = (SortNode) store.getSubNode();
          sortSchema = PlannerUtil.sortSpecsToSchema(sort.getSortKeys());
          TableStat stat = qm.getSubQuery(prev.getId().getSubQueryId()).getTableStat();
          TupleRange mergedRange = TupleUtil.columnStatToRange(sort.getOutputSchema(), sortSchema, stat.getColumnStats());
          TupleRange [] ranges = TupleUtil.getPartitions(sortSchema, n, mergedRange);
          String [] queries = TupleUtil.rangesToQueries(sortSchema, ranges);
          for (QueryUnit qu : unit.getChildQuery(scan).getQueryUnits()) {
            for (Partition p : qu.getPartitions()) {
              for (String query : queries) {
                uriList.add(new URI(p.getFileName() + "&" + query));
//                System.out.println("Partition: " + uriList.get(uriList.size() - 1));
              }
            }
          }
        } else {
          for (QueryUnit qu : unit.getChildQuery(scan).getQueryUnits()) {
            for (Partition p : qu.getPartitions()) {
              uriList.add(new URI(p.getFileName()));
//              System.out.println("Partition: " + uriList.get(uriList.size() - 1));
            }
          }
        }
        
        fetchMap.put(scan, uriList);
        // one fragment is shared by query units
        Fragment frag = new Fragment(scan.getTableId(), tablepath,
            TCatUtil.newTableMeta(scan.getInputSchema(),StoreType.CSV), 
            0, 0);
        fragList.add(frag);
        fragMap.put(scan, fragList);
      } else {
        fragList = new ArrayList<Fragment>();
        // set fragments for each scan
        if (unit.hasChildQuery() &&
            (unit.getChildQuery(scan).getOutputType() == PARTITION_TYPE.HASH ||
            unit.getChildQuery(scan).getOutputType() == PARTITION_TYPE.RANGE)
            ) {
          files = sm.getFileSystem().listStatus(tablepath);
        } else {
          files = new FileStatus[1];
          files[0] = sm.getFileSystem().getFileStatus(tablepath);
        }
        for (FileStatus file : files) {
          // make fragments
          frags = sm.split(file.getPath());
          for (Fragment f : frags) {
            // TODO: the fragment ID should be set before
            f.setId(scan.getTableId());
            fragList.add(f);
          }
        }
        fragMap.put(scan, fragList);
      }
    }

    List<QueryUnit> unitList = null;
    if (scans.length == 1) {
      unitList = makeUnaryQueryUnit(unit, n, fragMap, fetchMap, sortSchema);
    } else if (scans.length == 2) {
      unitList = makeBinaryQueryUnit(unit, n, fragMap, fetchMap);
    }
    // TODO: The partition number should be set here,
    // because the number of query units is decided above.
    
    QueryUnit[] units = new QueryUnit[unitList.size()];
    units = unitList.toArray(units);
    unit.setQueryUnits(units);
    
    return units;
  }
  
  /**
   * 2개의 scan을 가진 QueryUnit들에 fragment와 fetch를 할당
   * 
   * @param unit
   * @param n
   * @param fragMap
   * @param fetchMap
   * @return
   */
  private List<QueryUnit> makeBinaryQueryUnit(ScheduleUnit unit, int n, 
      Map<ScanNode, List<Fragment>> fragMap, 
      Map<ScanNode, List<URI>> fetchMap) {
    List<QueryUnit> unitList = new ArrayList<QueryUnit>();
    int maxQueryUnitNum = n;
    ScanNode[] scans = unit.getScanNodes();
    
    if (unit.hasChildQuery()) {
      ScheduleUnit prev = unit.getChildQuery(scans[0]);
      switch (prev.getOutputType()) {
      case BROADCAST:
        throw new NotImplementedException();
      case HASH:
        if (scans[0].isLocal()) {
          unitList = assignFetchesToBinaryByHash(unit, unitList,
              fetchMap, maxQueryUnitNum);
          unitList = assignEqualFragment(unitList, fragMap);
        } else {
          throw new NotImplementedException();
        }
        break;
      case LIST:
        throw new NotImplementedException();
      }
    } else {
      unitList = assignFragmentsByRoundRobin(unit, unitList, fragMap, 
          maxQueryUnitNum);
    }

    return unitList;
  }
  
  /**
   * 1개의 scan을 가진 QueryUnit들에 대해 fragment와 fetch를 할당
   * 
   * @param unit
   * @param n
   * @param fragMap
   * @param fetchMap
   * @return
   */
  private List<QueryUnit> makeUnaryQueryUnit(ScheduleUnit unit, int n,
      Map<ScanNode, List<Fragment>> fragMap, 
      Map<ScanNode, List<URI>> fetchMap, Schema rangeSchema) throws UnsupportedEncodingException {
    List<QueryUnit> unitList = new ArrayList<QueryUnit>();
    int maxQueryUnitNum = 0;
    ScanNode scan = unit.getScanNodes()[0];
    maxQueryUnitNum = n;
    if (unit.hasChildQuery()) {
      ScheduleUnit prev = unit.getChildQuery(scan);
      if (prev.getStoreTableNode().getSubNode().getType() == 
          ExprType.GROUP_BY) {
        GroupbyNode groupby = (GroupbyNode) prev.getStoreTableNode().
            getSubNode();
        if (groupby.getGroupingColumns().length == 0) {
          maxQueryUnitNum = 1;
        }
      }
      switch (prev.getOutputType()) {
        case BROADCAST:
        throw new NotImplementedException();

        case HASH:
          if (scan.isLocal()) {
            unitList = assignFetchesToUnaryByHash(unit, unitList, scan,
                fetchMap.get(scan), maxQueryUnitNum);
            unitList = assignEqualFragment(unitList, scan,
                fragMap.get(scan).get(0));
          } else {
            unitList = assignFragmentsByHash(unit, unitList, scan,
                fragMap.get(scan), maxQueryUnitNum);
          }
          break;
        case RANGE:
          if (scan.isLocal()) {
            unitList = assignFetchesByRange(unit, unitList, scan,
                fetchMap.get(scan), maxQueryUnitNum, rangeSchema);
            unitList = assignEqualFragment(unitList, scan,
                fragMap.get(scan).get(0));
          } else {
            unitList = assignFetchesByRange(unit, unitList, scan,
                fetchMap.get(scan), maxQueryUnitNum, rangeSchema);
          }
          break;

        case LIST:
          if (scan.isLocal()) {
            unitList = assignFetchesByRoundRobin(unit, unitList, scan,
                fetchMap.get(scan), maxQueryUnitNum);
            unitList = assignEqualFragment(unitList, scan,
                fragMap.get(scan).get(0));
          } else {
            unitList = assignFragmentsByRoundRobin(unit, unitList, scan,
                fragMap.get(scan), maxQueryUnitNum);
          }
          break;
      }
    } else {
      unitList = assignFragmentsByRoundRobin(unit, unitList, scan,
          fragMap.get(scan), maxQueryUnitNum);
    }
    return unitList;
  }
  
  private QueryUnit newQueryUnit(ScheduleUnit scheduleUnit) {
    QueryUnit unit = new QueryUnit(
        QueryIdFactory.newQueryUnitId(scheduleUnit.getId()));
    unit.setLogicalPlan(scheduleUnit.getLogicalPlan());
    return unit;
  }
  
  /**
   * Binary QueryUnit들에 hash 파티션된 fetch를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param fetchMap
   * @param n
   * @return
   */
  private List<QueryUnit> assignFetchesToBinaryByHash(ScheduleUnit scheduleUnit,
      List<QueryUnit> unitList, Map<ScanNode, List<URI>> fetchMap, int n) {
    QueryUnit unit = null;
    int i = 0;
    Map<String, Map<ScanNode, List<URI>>> hashed = hashFetches(fetchMap);
    Iterator<Entry<String, Map<ScanNode, List<URI>>>> it = 
        hashed.entrySet().iterator();
    Entry<String, Map<ScanNode, List<URI>>> e = null;
    while (it.hasNext()) {
      e = it.next();
      if (e.getValue().size() == 2) { // only if two matched partitions
        if (unitList.size() == n) {
          unit = unitList.get(i++);
          if (i == unitList.size()) {
            i = 0;
          }
        } else {
          unit = newQueryUnit(scheduleUnit);
          unitList.add(unit);
        }
        Map<ScanNode, List<URI>> m = e.getValue();
        for (ScanNode scan : m.keySet()) {
          for (URI uri : m.get(scan)) {
            unit.addFetch(scan.getTableId(), uri);
          }
        }
      }
    }
    
    return unitList;
  }
  
  /**
   * Unary QueryUnit들에 hash 파티션된 fetch를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param scan
   * @param uriList
   * @param n
   * @return
   */
  private List<QueryUnit> assignFetchesToUnaryByHash(ScheduleUnit scheduleUnit,
      List<QueryUnit> unitList, ScanNode scan, List<URI> uriList, int n) {
    Map<String, List<URI>> hashed = hashFetches(uriList); // hash key, uri
    QueryUnit unit = null;
    int i = 0;
    // TODO: units에 hashed 할당
    Iterator<Entry<String, List<URI>>> it = hashed.entrySet().iterator();
    Entry<String, List<URI>> e;
    while (it.hasNext()) {
      e = it.next();
      if (unitList.size() == n) {
        unit = unitList.get(i++);
        if (i == unitList.size()) {
          i = 0;
        }
      } else {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      }
      unit.addFetches(scan.getTableId(), e.getValue());
    }
    return unitList;
  }

  private List<QueryUnit> assignFetchesByRange(ScheduleUnit scheduleUnit,
                                              List<QueryUnit> unitList, ScanNode scan, List<URI> uriList, int n,
                                              Schema rangeSchema)
      throws UnsupportedEncodingException {
    Map<TupleRange, Set<URI>> partitions = rangeFetches(rangeSchema, uriList); // grouping urls by range
    QueryUnit unit = null;
    int i = 0;
    Iterator<Entry<TupleRange, Set<URI>>> it = partitions.entrySet().iterator();
    Entry<TupleRange, Set<URI>> e;
    while (it.hasNext()) {
      e = it.next();
      if (unitList.size() == n) {
        unit = unitList.get(i++);
        if (i == unitList.size()) {
          i = 0;
        }
      } else {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      }
      unit.addFetches(scan.getTableId(), Lists.newArrayList(e.getValue()));
    }
    return unitList;
  }
  
  /**
   * Unary QueryUnit들에 list 파티션된 fetch를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param scan
   * @param uriList
   * @param n
   * @return
   */
  private List<QueryUnit> assignFetchesByRoundRobin(ScheduleUnit scheduleUnit, 
      List<QueryUnit> unitList, ScanNode scan, List<URI> uriList, int n) { 
    QueryUnit unit = null;
    int i = 0;
    for (URI uri : uriList) {
      if (unitList.size() < n) {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      } else {
        unit = unitList.get(i++);
        if (i == unitList.size()) {
          i = 0;
        }
      }
      unit.addFetch(scan.getTableId(), uri);
    }
    return unitList;
  }
  
  private Map<String, Map<ScanNode, List<URI>>> hashFetches(Map<ScanNode, 
      List<URI>> uriMap) {
    SortedMap<String, Map<ScanNode, List<URI>>> hashed =
        new TreeMap<String, Map<ScanNode,List<URI>>>();
    String uriPath, key;
    Map<ScanNode, List<URI>> m = null;
    List<URI> uriList = null;
    for (Entry<ScanNode, List<URI>> e : uriMap.entrySet()) {
      for (URI uri : e.getValue()) {
        uriPath = uri.toString();
        key = uriPath.substring(uriPath.lastIndexOf("=")+1);
        if (hashed.containsKey(key)) {
          m = hashed.get(key);
        } else {
          m = new HashMap<ScanNode, List<URI>>();
        }
        if (m.containsKey(e.getKey())) {
          uriList = m.get(e.getKey());
        } else {
          uriList = new ArrayList<URI>();
        }
        uriList.add(uri);
        m.put(e.getKey(), uriList);
        hashed.put(key, m);
      }
    }
    
    return hashed;
  }
  
  private Map<String, List<URI>> hashFetches(List<URI> uriList) {
    SortedMap<String, List<URI>> hashed = new TreeMap<String, List<URI>>();
    String uriPath, key;
    for (URI uri : uriList) {
      // TODO
      uriPath = uri.toString();
      key = uriPath.substring(uriPath.lastIndexOf("=")+1);
      if (hashed.containsKey(key)) {
        hashed.get(key).add(uri);
      } else {
        List<URI> list = new ArrayList<URI>();
        list.add(uri);
        hashed.put(key, list);
      }
    }
    
    return hashed;
  }

  private Map<TupleRange, Set<URI>> rangeFetches(Schema schema, List<URI> uriList) throws UnsupportedEncodingException {
    SortedMap<TupleRange, Set<URI>> map = Maps.newTreeMap();
    TupleRange range;
    Set<URI> uris;
    for (URI uri : uriList) {
      range = TupleUtil.queryToRange(schema, uri.getQuery());
      if (map.containsKey(range)) {
        uris = map.get(range);
        uris.add(uri);
      } else {
        uris = Sets.newHashSet();
        uris.add(uri);
        map.put(range, uris);
      }
    }

    return map;
  }
  
  /**
   * Unary QueryUnit들에 broadcast partition된 fetch를 할당
   * 
   * @param units
   * @param scan
   * @param uriList
   */
  private void assignFetchesByBroadcast(QueryUnit[] units, ScanNode scan, List<URI> uriList) {
    for (URI uri : uriList) {
      for (QueryUnit unit : units) {
        // TODO: add each uri to every units
        unit.addFetch(scan.getTableId(), uri);
      }
    }
  }
  
  /**
   * Unary QueryUnit들에 hash 파티션된 fragment를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param scan
   * @param fragList
   * @param n
   * @return
   */
  private List<QueryUnit> assignFragmentsByHash(ScheduleUnit scheduleUnit, 
      List<QueryUnit> unitList, ScanNode scan, List<Fragment> fragList, int n) {
    Collection<List<Fragment>> hashed = hashFragments(fragList);
    QueryUnit unit = null;
    int i = 0;
    for (List<Fragment> frags : hashed) {
      if (unitList.size() < n) {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      } else {
        unit = unitList.get(i++);
        if (i == unitList.size()) {
          i = 0;
        }
      }
      unit.addFragments(scan.getTableId(), frags);
    }
    return unitList;
  }
  
  /**
   * Binary QueryUnit들에 hash 파티션된 fragment를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param fragMap
   * @param n
   * @return
   */
  private List<QueryUnit> assignFragmentsByRoundRobin(ScheduleUnit scheduleUnit,
      List<QueryUnit> unitList, Map<ScanNode, List<Fragment>> fragMap, int n) {
    QueryUnit unit = null;
    int i = 0;
    ScanNode[] scans = scheduleUnit.getScanNodes();
    Preconditions.checkArgument(fragMap.get(scans[0]).size() == 
        fragMap.get(scans[1]).size());
    int fragNum = fragMap.get(scans[0]).size();
    
    for (int k = 0; k < fragNum; k++) {
      if (unitList.size() < n) {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      } else {
        unit = unitList.get(i);
        if (i == unitList.size()) {
          i = 0;
        }
      }
      
      for (ScanNode scan : scans) {
        unit.addFragment(scan.getTableId(), fragMap.get(scan).get(k));
      }
    }
    
    return unitList;
  }

  /**
   * Unary QueryUnit들에 list 파티션된 fragment를 할당
   * 
   * @param scheduleUnit
   * @param unitList
   * @param scan
   * @param frags
   * @param n
   * @return
   */
  private List<QueryUnit> assignFragmentsByRoundRobin(ScheduleUnit scheduleUnit,
      List<QueryUnit> unitList, ScanNode scan, List<Fragment> frags, int n) {
    QueryUnit unit = null;
    int i = 0;
    for (Fragment f : frags) {
      if (unitList.size() < n) {
        unit = newQueryUnit(scheduleUnit);
        unitList.add(unit);
      } else {
        unit = unitList.get(i++);
        if (i == unitList.size()) {
          i = 0;
        }
      }
      unit.addFragment(scan.getTableId(), f);
    }
    return unitList;
  }
  
  /**
   * Unary QueryUnit들에 대하여 동일한 fragment를 할당
   * 
   * @param unitList
   * @param scan
   * @param frag
   * @return
   */
  private List<QueryUnit> assignEqualFragment(List<QueryUnit> unitList, 
      ScanNode scan, Fragment frag) {
    for (int i = 0; i < unitList.size(); i++) {
      unitList.get(i).addFragment(scan.getTableId(), frag);
    }
    
    return unitList;
  }
  
  /**
   * Binary QueryUnit들에 대하여 scan별로 동일한 fragment를 할당
   * @param unitList
   * @param fragMap
   * @return
   */
  private List<QueryUnit> assignEqualFragment(List<QueryUnit> unitList, 
      Map<ScanNode, List<Fragment>> fragMap) {
    for (int i = 0; i < unitList.size(); i++) {
      for (ScanNode scan : fragMap.keySet()) {
        unitList.get(i).addFragment(scan.getTableId(), fragMap.get(scan).get(0));
      }
    }
    return unitList;
  }
  
  private Map<String, Map<ScanNode, List<Fragment>>> hashFragments(Map<ScanNode, 
      List<Fragment>> fragMap) {
    SortedMap<String, Map<ScanNode, List<Fragment>>> hashed = 
        new TreeMap<String, Map<ScanNode,List<Fragment>>>();
    String key;
    Map<ScanNode, List<Fragment>> m = null;
    List<Fragment> fragList = null;
    for (Entry<ScanNode, List<Fragment>> e : fragMap.entrySet()) {
      for (Fragment f : e.getValue()) {
        key = f.getPath().getName();
        if (hashed.containsKey(key)) {
          m = hashed.get(key);
        } else {
          m = new HashMap<ScanNode, List<Fragment>>();
        }
        if (m.containsKey(e.getKey())) {
          fragList = m.get(e.getKey());
        } else {
          fragList = new ArrayList<Fragment>();
        }
        fragList.add(f);
        m.put(e.getKey(), fragList);
        hashed.put(key, m);
      }
    }
    
    return hashed;
  }
  
  private Collection<List<Fragment>> hashFragments(List<Fragment> frags) {
    SortedMap<String, List<Fragment>> hashed = new TreeMap<String, List<Fragment>>();
    for (Fragment f : frags) {
      if (hashed.containsKey(f.getPath().getName())) {
        hashed.get(f.getPath().getName()).add(f);
      } else {
        List<Fragment> list = new ArrayList<Fragment>();
        list.add(f);
        hashed.put(f.getPath().getName(), list);
      }
    }

    return hashed.values();
  }
}