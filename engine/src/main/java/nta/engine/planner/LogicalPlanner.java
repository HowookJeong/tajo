package nta.engine.planner;

import java.util.*;

import com.google.common.collect.Sets;
import nta.catalog.Column;
import nta.catalog.Schema;
import nta.catalog.SchemaUtil;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.engine.Context;
import nta.engine.exec.eval.*;
import nta.engine.exec.eval.EvalNode.Type;
import nta.engine.parser.CreateIndexStmt;
import nta.engine.parser.CreateTableStmt;
import nta.engine.parser.ParseTree;
import nta.engine.parser.QueryAnalyzer;
import nta.engine.parser.QueryBlock;
import nta.engine.parser.QueryBlock.FromTable;
import nta.engine.parser.QueryBlock.GroupByClause;
import nta.engine.parser.QueryBlock.GroupElement;
import nta.engine.parser.QueryBlock.GroupType;
import nta.engine.parser.QueryBlock.JoinClause;
import nta.engine.parser.QueryBlock.Target;
import nta.engine.parser.SetStmt;
import nta.engine.planner.logical.*;
import nta.engine.planner.logical.join.Edge;
import nta.engine.planner.logical.join.JoinTree;
import nta.engine.query.exception.InvalidQueryException;
import nta.engine.query.exception.NotSupportQueryException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.google.common.collect.Lists;
import org.apache.hadoop.thirdparty.guava.common.collect.Maps;

/**
 * This class creates a logical plan from a parse tree ({@link QueryBlock})
 * generated by {@link QueryAnalyzer}.
 * 
 * @author Hyunsik Choi
 *
 * @see QueryBlock
 */
public class LogicalPlanner {
  private static Log LOG = LogFactory.getLog(LogicalPlanner.class);

  private LogicalPlanner() {
  }

  /**
   * This generates a logical plan.
   * 
   * @param query a parse tree
   * @return a initial logical plan
   */
  public static LogicalNode createPlan(Context ctx, ParseTree query) {
    LogicalNode plan;
    
    plan = createPlanInternal(ctx, query);
    
    LogicalRootNode root = new LogicalRootNode();
    root.setInputSchema(plan.getOutputSchema());
    root.setOutputSchema(plan.getOutputSchema());
    root.setSubNode(plan);
    
    return root;
  }
  
  private static LogicalNode createPlanInternal(Context ctx, ParseTree query) {
    LogicalNode plan;
    
    switch(query.getType()) {
    case SELECT:
      LOG.info("Planning select statement");
      QueryBlock select = (QueryBlock) query;
      plan = buildSelectPlan(ctx, select);
      break;
      
    case UNION:
    case EXCEPT:
    case INTERSECT:
      SetStmt set = (SetStmt) query;
      plan = buildSetPlan(ctx, set);
      break;
      
    case CREATE_INDEX:
      LOG.info("Planning create index statement");
      CreateIndexStmt createIndex = (CreateIndexStmt) query;
      plan = buildCreateIndexPlan(ctx, createIndex);
      break;

    case CREATE_TABLE:
      LOG.info("Planning store statement");
      CreateTableStmt createTable = (CreateTableStmt) query;
      plan = buildCreateTablePlan(ctx, createTable);
      break;

    default:
      throw new NotSupportQueryException(query.toString());
    }
    
    return plan;
  }
  
  private static LogicalNode buildSetPlan(Context ctx,
      SetStmt stmt) {
    BinaryNode bin;
    switch (stmt.getType()) {
    case UNION:
      bin = new UnionNode();
      break;
    case EXCEPT:
      bin = new ExceptNode();
      break;
    case INTERSECT:
      bin = new IntersectNode();
      break;
    default:
      throw new IllegalStateException("the statement cannot be matched to any set operation type");
    }
    
    bin.setOuter(createPlanInternal(ctx, stmt.getLeftTree()));
    bin.setInner(createPlanInternal(ctx, stmt.getRightTree()));
    bin.setInputSchema(bin.getOuterNode().getOutputSchema());
    bin.setOutputSchema(bin.getOuterNode().getOutputSchema());
    return bin;
  }
  
  private static LogicalNode buildCreateIndexPlan(Context ctx,
      CreateIndexStmt stmt) {
    FromTable table = new FromTable(ctx.getTable(stmt.getTableName()));
    ScanNode scan = new ScanNode(table);
    scan.setInputSchema(table.getSchema());
    scan.setOutputSchema(table.getSchema());
    IndexWriteNode indexWrite = new IndexWriteNode(stmt);
    indexWrite.setSubNode(scan);
    indexWrite.setInputSchema(scan.getOutputSchema());
    indexWrite.setOutputSchema(scan.getOutputSchema());
    
    return indexWrite;
  }
  
  private static LogicalNode buildCreateTablePlan(Context ctx, 
      CreateTableStmt query) {
    LogicalNode node = null;
    if (query.hasDefinition())  {
      CreateTableNode createTable = 
          new CreateTableNode(query.getTableName(), query.getSchema(), 
              query.getStoreType(), query.getPath());
      if (query.hasOptions()) {
        createTable.setOptions(query.getOptions());
      }
      createTable.setInputSchema(query.getSchema());
      createTable.setOutputSchema(query.getSchema());
      node = createTable;
    } else if (query.hasSelectStmt()) {
      LogicalNode subNode = buildSelectPlan(ctx, query.getSelectStmt());
      
      StoreTableNode storeNode = new StoreTableNode(query.getTableName());
      storeNode.setInputSchema(subNode.getOutputSchema());
      storeNode.setOutputSchema(subNode.getOutputSchema());
      storeNode.setSubNode(subNode);
      node = storeNode;
    }
    
    return node;
  }
  
  /**
   * ^(SELECT from_clause? where_clause? groupby_clause? selectList)
   * 
   * @param query
   * @return the planed logical plan
   */
  private static LogicalNode buildSelectPlan(Context ctx, QueryBlock query) {
    LogicalNode subroot;
    EvalNode whereCondition = null;
    EvalNode [] cnf = null;
    if(query.hasWhereClause()) {
      whereCondition = query.getWhereCondition();
      whereCondition = AlgebraicUtil.simplify(whereCondition);
      cnf = EvalTreeUtil.getConjNormalForm(whereCondition);
    }

    if(query.hasFromClause()) {
      if (query.hasExplicitJoinClause()) {
        subroot = createExplicitJoinTree(ctx, query);
      } else {
        subroot = createImplicitJoinTree(ctx, query.getFromTables(), cnf);
      }
    } else {
      subroot = new EvalExprNode(query.getTargetList());
      subroot.setOutputSchema(getProjectedSchema(ctx, query.getTargetList()));
      return subroot;
    }
    
    if(whereCondition != null) {
      SelectionNode selNode = 
          new SelectionNode(query.getWhereCondition());
      selNode.setSubNode(subroot);
      selNode.setInputSchema(subroot.getOutputSchema());
      selNode.setOutputSchema(selNode.getInputSchema());
      subroot = selNode;
    }
    
    if(query.hasAggregation()) {
      if (query.isDistinct()) {
        throw new InvalidQueryException("Cannot support GROUP BY queries with distinct keyword");
      }

      GroupbyNode groupbyNode = null;
      if (query.hasGroupbyClause()) {
        if (query.getGroupByClause().getGroupSet().get(0).getType() == GroupType.GROUPBY) {          
          groupbyNode = new GroupbyNode(query.getGroupByClause().getGroupSet().get(0).getColumns());
          groupbyNode.setTargetList(ctx.getTargetList());
          groupbyNode.setSubNode(subroot);
          groupbyNode.setInputSchema(subroot.getOutputSchema());      
          Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
          groupbyNode.setOutputSchema(outSchema);
          subroot = groupbyNode;
        } else if (query.getGroupByClause().getGroupSet().get(0).getType() == GroupType.CUBE) {
          LogicalNode union = createGroupByUnionByCube(ctx, subroot, query.getGroupByClause());
          Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
          union.setOutputSchema(outSchema);
          subroot = union;
        }
        if(query.hasHavingCond())
          groupbyNode.setHavingCondition(query.getHavingCond());
      } else {
        // when aggregation functions are used without grouping fields
        groupbyNode = new GroupbyNode(new Column [] {});
        groupbyNode.setTargetList(ctx.getTargetList());
        groupbyNode.setSubNode(subroot);
        groupbyNode.setInputSchema(subroot.getOutputSchema());      
        Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
        groupbyNode.setOutputSchema(outSchema);
        subroot = groupbyNode;
      }
    }
    
    if(query.hasOrderByClause()) {
      SortNode sortNode = new SortNode(query.getSortKeys());
      sortNode.setSubNode(subroot);
      sortNode.setInputSchema(subroot.getOutputSchema());
      sortNode.setOutputSchema(sortNode.getInputSchema());
      subroot = sortNode;
    }

    ProjectionNode prjNode;
    if (query.getProjectAll()) {
      Schema merged = SchemaUtil.merge(query.getFromTables());
      Target [] allTargets = PlannerUtil.schemaToTargets(merged);
      prjNode = new ProjectionNode(allTargets);
      prjNode.setSubNode(subroot);
      prjNode.setInputSchema(merged);
      prjNode.setOutputSchema(merged);
      subroot = prjNode;
    } else {
      prjNode = new ProjectionNode(query.getTargetList());
      if (subroot != null) { // false if 'no from' statement
        prjNode.setSubNode(subroot);
      }
      prjNode.setInputSchema(subroot.getOutputSchema());
      Schema projected = getProjectedSchema(ctx, query.getTargetList());
      prjNode.setOutputSchema(projected);
      subroot = prjNode;
    }

    GroupbyNode dupRemoval;
    if (query.isDistinct()) {
      dupRemoval = new GroupbyNode(subroot.getOutputSchema().toArray());
      dupRemoval.setTargetList(ctx.getTargetList());
      dupRemoval.setSubNode(subroot);
      dupRemoval.setInputSchema(subroot.getOutputSchema());
      Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
      dupRemoval.setOutputSchema(outSchema);
      subroot = dupRemoval;
    }
    
    return subroot;
  }
  
  public static LogicalNode createGroupByUnionByCube(Context ctx, 
      LogicalNode subNode, GroupByClause clause) {
    GroupElement element = clause.getGroupSet().get(0);
    List<Column []> cuboids  = generateCuboids(element.getColumns());  

    return createGroupByUnion(ctx, subNode, cuboids, 0);
  }
  
  private static UnionNode createGroupByUnion(Context ctx, LogicalNode subNode, 
      List<Column []> cuboids, int idx) {
    UnionNode union;
    try {
    if ((cuboids.size() - idx) > 2) {
      GroupbyNode g1 = new GroupbyNode(cuboids.get(idx));
      g1.setTargetList(ctx.getTargetList());
      g1.setSubNode((LogicalNode) subNode.clone());
      g1.setInputSchema(g1.getSubNode().getOutputSchema());
      Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
      g1.setOutputSchema(outSchema);
      
      union = new UnionNode(g1, createGroupByUnion(ctx, subNode, cuboids, idx+1));
      union.setInputSchema(g1.getOutputSchema());
      union.setOutputSchema(g1.getOutputSchema());
      return union;
    } else {
      GroupbyNode g1 = new GroupbyNode(cuboids.get(idx));
      g1.setTargetList(ctx.getTargetList());
      g1.setSubNode((LogicalNode) subNode.clone());
      g1.setInputSchema(g1.getSubNode().getOutputSchema());      
      Schema outSchema = getProjectedSchema(ctx, ctx.getTargetList());
      g1.setOutputSchema(outSchema);
      
      GroupbyNode g2 = new GroupbyNode(cuboids.get(idx+1));
      g2.setTargetList(ctx.getTargetList());
      g2.setSubNode((LogicalNode) subNode.clone());
      g2.setInputSchema(g1.getSubNode().getOutputSchema());
      outSchema = getProjectedSchema(ctx, ctx.getTargetList());
      g2.setOutputSchema(outSchema);
      union = new UnionNode(g1, g2);
      union.setInputSchema(g1.getOutputSchema());
      union.setOutputSchema(g1.getOutputSchema());
      return union;
    }
    } catch (CloneNotSupportedException cnse) {
      LOG.error(cnse);
      throw new InvalidQueryException(cnse);
    }
  }
  
  public static final Column [] ALL 
    = Lists.newArrayList().toArray(new Column[0]);
  
  public static List<Column []> generateCuboids(Column [] columns) {
    int numCuboids = (int) Math.pow(2, columns.length);
    int maxBits = columns.length;    
    
    List<Column []> cube = Lists.newArrayList();
    List<Column> cuboidCols;
    
    cube.add(ALL);
    for (int cuboidId = 1; cuboidId < numCuboids; cuboidId++) {
      cuboidCols = Lists.newArrayList();
      for (int j = 0; j < maxBits; j++) {
        int bit = 1 << j;
        if ((cuboidId & bit) == bit) {
          cuboidCols.add(columns[j]);
        }
      }
      cube.add(cuboidCols.toArray(new Column[cuboidCols.size()]));
    }
    return cube;
  }

  private static LogicalNode createExplicitJoinTree(Context ctx,
                                                     QueryBlock block) {
    return createExplicitJoinTree_(ctx, block.getJoinClause());
  }
  
  private static LogicalNode createExplicitJoinTree_(Context ctx,
                                                     JoinClause joinClause) {
    JoinNode join = new JoinNode(joinClause.getJoinType(),
        new ScanNode(joinClause.getLeft()));
    if (joinClause.hasJoinQual()) {
      join.setJoinQual(joinClause.getJoinQual());
    } else if (joinClause.hasJoinColumns()) { 
      // for using clause of explicit join
      // TODO - to be implemented. Now, tajo only support 'ON' join clause.
    }
    
    if (joinClause.hasRightJoin()) {
      join.setInner(createExplicitJoinTree_(ctx, joinClause.getRightJoin()));
    } else {
      join.setInner(new ScanNode(joinClause.getRight()));      
    }
    
    // Determine Join Schemas
    Schema merged;
    if (join.getJoinType() == JoinType.NATURAL) {
      merged = getNaturalJoin(join.getOuterNode(), join.getInnerNode());
    } else {
      merged = SchemaUtil.merge(join.getOuterNode().getOutputSchema(),
          join.getInnerNode().getOutputSchema());
    }
    
    join.setInputSchema(merged);
    join.setOutputSchema(merged);
    
    // Determine join quals
    // if natural join, should have the equi join conditions on common columns
    if (join.getJoinType() == JoinType.NATURAL) {
      Schema leftSchema = join.getOuterNode().getOutputSchema();
      Schema rightSchema = join.getInnerNode().getOutputSchema();
      Schema commons = SchemaUtil.getCommons(
          leftSchema, rightSchema);
      EvalNode njCond = getNaturalJoinCondition(leftSchema, rightSchema, commons);
      join.setJoinQual(njCond);
    } else if (joinClause.hasJoinQual()) { 
      // otherwise, the given join conditions are set
      join.setJoinQual(joinClause.getJoinQual());
    }
    
    return join;
  }
  
  private static EvalNode getNaturalJoinCondition(Schema outer, Schema inner, Schema commons) {
    EvalNode njQual = null;
    EvalNode equiQual;
    
    Column leftJoinKey;
    Column rightJoinKey;
    for (Column common : commons.getColumns()) {
      leftJoinKey = outer.getColumnByName(common.getColumnName());
      rightJoinKey = inner.getColumnByName(common.getColumnName());
      equiQual = new BinaryEval(Type.EQUAL, 
          new FieldEval(leftJoinKey), new FieldEval(rightJoinKey));
      if (njQual == null) {
        njQual = equiQual;
      } else {
        njQual = new BinaryEval(Type.AND,
            njQual, equiQual);
      }
    }
    
    return njQual;
  }
  
  private static LogicalNode createImplicitJoinTree(Context ctx, FromTable [] tables, EvalNode [] cnf) {
    if (cnf == null) {
      return createCatasianProduct(ctx, tables);
    } else {
      return createCrossJoinFromJoinCondition(ctx, tables, cnf);
    }
  }

  private static LogicalNode createCrossJoinFromJoinCondition(Context ctx, FromTable [] tables, EvalNode [] cnf) {
    Map<String, FromTable> fromTableMap = Maps.newHashMap();
    for (FromTable f : tables) {
      // TODO - to consider alias and self-join
      fromTableMap.put(f.getTableName(), f);
    }

    JoinTree joinTree = new JoinTree(); // to infer join order
    for (EvalNode expr : cnf) {
      if (PlannerUtil.isJoinQual(expr)) {
        joinTree.addJoin(expr);
      }
    }

    List<String> remain = Lists.newArrayList(fromTableMap.keySet());
    remain.removeAll(joinTree.getTables()); // only remain joins not matched to any join condition
    List<Edge> joinOrder = null;
    LogicalNode subroot = null;
    JoinNode join;
    Schema joinSchema;

    // if there are at least one join matched to the one of join conditions,
    // we try to traverse the join tree in the depth-first manner and
    // determine the initial join order. Here, we do not consider the join cost.
    // The optimized join order will be considered in the optimizer.
    if (joinTree.getJoinNum() > 0) {
      Stack<String> stack = new Stack<String>();
      Set<String> visited = Sets.newHashSet();


      // initially, one table is pushed into the stack
      String seed = joinTree.getTables().iterator().next();
      stack.add(seed);

      joinOrder = Lists.newArrayList();

      while (!stack.empty()) {
        String table = stack.pop();
        if (visited.contains(table)) {
          continue;
        }
        visited.add(table);

        // 'joinOrder' will contain all tables corresponding to the given join conditions.
        for (Edge edge : joinTree.getEdges(table)) {
          if (!visited.contains(edge.getTarget()) && !edge.getTarget().equals(table)) {
            stack.add(edge.getTarget());
            joinOrder.add(edge);
          }
        }
      }

      subroot = new ScanNode(fromTableMap.get(joinOrder.get(0).getSrc()));
      LogicalNode inner;
      for (int i = 0; i < joinOrder.size(); i++) {
        Edge edge = joinOrder.get(i);
        inner = new ScanNode(fromTableMap.get(edge.getTarget()));
        join = new JoinNode(JoinType.CROSS_JOIN, subroot, inner);
        subroot = join;

        joinSchema = SchemaUtil.merge(
            join.getOuterNode().getOutputSchema(),
            join.getInnerNode().getOutputSchema());
        join.setInputSchema(joinSchema);
        join.setOutputSchema(joinSchema);
      }
    }

    // Here, there are two cases:
    // 1) there already exists the join plan.
    // 2) there are no join plan.
    if (joinOrder != null) { // case 1)
      // if there are join tables corresponding to any join condition,
      // the join plan is placed as the outer plan of the product.
      remain.remove(joinOrder.get(0).getSrc());
      remain.remove(joinOrder.get(0).getTarget());
    } else { // case 2)
      // if there are no inferred joins, the one of the remain join tables is placed as the left table
      subroot = new ScanNode(fromTableMap.get(remain.get(0)));
      remain.remove(remain.get(0));
    }

    // Here, the variable 'remain' contains join tables which are not matched to any join conditions.
    // Thus, they will be joined by catasian product
    for (String table : remain) {
      join = new JoinNode(JoinType.CROSS_JOIN,
          subroot, new ScanNode(fromTableMap.get(table)));
      joinSchema = SchemaUtil.merge(
          join.getOuterNode().getOutputSchema(),
          join.getInnerNode().getOutputSchema());
      join.setInputSchema(joinSchema);
      join.setOutputSchema(joinSchema);
      subroot = join;
    }

    return subroot;
  }

  // TODO - this method is somewhat duplicated to createCrossJoinFromJoinCondition. Later, it should be removed.
  private static LogicalNode createCatasianProduct(Context ctx, FromTable [] tables) {
    LogicalNode subroot = new ScanNode(tables[0]);
    Schema joinSchema;
    if(tables.length > 1) {
      for(int i=1; i < tables.length; i++) {
        JoinNode join = new JoinNode(JoinType.CROSS_JOIN,
            subroot, new ScanNode(tables[i]));
        joinSchema = SchemaUtil.merge(
            join.getOuterNode().getOutputSchema(),
            join.getInnerNode().getOutputSchema());
        join.setInputSchema(joinSchema);
        join.setOutputSchema(joinSchema);
        subroot = join;
      }
    }

    return subroot;
  }
  
  public static Schema getProjectedSchema(Context ctx, Target [] targets) {
    Schema projected = new Schema();
    for(Target t : targets) {
      DataType type = t.getEvalTree().getValueType();
      String name;
      if (t.hasAlias()) {
        name = t.getAlias();
      } else if (t.getEvalTree().getName().equals("?")) {
        name = ctx.getUnnamedColumn();
      } else {
        name = t.getEvalTree().getName();
      }      
      projected.addColumn(name,type);
    }
    
    return projected;
  }
  
  private static Schema getNaturalJoin(LogicalNode outer, LogicalNode inner) {
    Schema joinSchema = new Schema();
    Schema commons = SchemaUtil.getCommons(outer.getOutputSchema(),
        inner.getOutputSchema());
    joinSchema.addColumns(commons);
    for (Column c : outer.getOutputSchema().getColumns()) {
      for (Column common : commons.getColumns()) {
        if (!common.getColumnName().equals(c.getColumnName())) {
          joinSchema.addColumn(c);
        }
      }
    }

    for (Column c : inner.getOutputSchema().getColumns()) {
      for (Column common : commons.getColumns()) {
        if (!common.getColumnName().equals(c.getColumnName())) {
          joinSchema.addColumn(c);
        }
      }
    }
    return joinSchema;
  }
}