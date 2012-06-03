/**
 * 
 */
package nta.engine.planner.physical;

import com.google.common.base.Preconditions;
import nta.catalog.Column;
import nta.catalog.Schema;
import nta.conf.NtaConf;
import nta.engine.ipc.protocolrecords.Fragment;
import nta.engine.planner.logical.IndexWriteNode;
import nta.storage.StorageManager;
import nta.storage.Tuple;
import nta.storage.VTuple;
import org.apache.hadoop.fs.Path;
import tajo.index.bst.BSTIndex;
import tajo.index.bst.BSTIndex.BSTIndexWriter;

import java.io.IOException;

/**
 * @author Hyunsik Choi
 */
public class IndexWriteExec extends PhysicalExec {
  private PhysicalExec subOp;
  private int [] indexKeys = null;
  private final Schema inSchema;
  
  private final BSTIndexWriter indexWriter;
  private final TupleComparator comp;
  private final Fragment fragment;

  public IndexWriteExec(StorageManager sm, IndexWriteNode annotation, Fragment frag,
      PhysicalExec subOp) throws IOException {
    this.fragment = frag;
    this.subOp = subOp;    
    inSchema = annotation.getInputSchema();
    Preconditions.checkArgument(inSchema.equals(subOp.getSchema()));
    
    indexKeys = new int[annotation.getSortSpecs().length];
    Schema keySchema = new Schema();
    Column col;
    for (int i = 0 ; i < annotation.getSortSpecs().length; i++) {
      col = annotation.getSortSpecs()[i].getSortKey();
      indexKeys[i] = inSchema.getColumnId(col.getQualifiedName());
      keySchema.addColumn(inSchema.getColumn(col.getQualifiedName()));
    }
    this.comp = new TupleComparator(inSchema, annotation.getSortSpecs());
    
    BSTIndex bst = new BSTIndex(NtaConf.create());
    Path dir = new Path(frag.getPath().getParent().getParent(), "index");
    // TODO - to be improved
    this.indexWriter = bst.getIndexWriter(new Path(dir, "indexfile"), BSTIndex.TWO_LEVEL_INDEX, keySchema, comp);
  }

  @Override
  public Schema getSchema() {
    return new Schema();
  }

  @Override
  public Tuple next() throws IOException {
    indexWriter.open();
    Tuple tuple;
    Tuple keys = new VTuple(indexKeys.length);
    while ((tuple = subOp.next()) != null) {      
      for (int idx = 0; idx < indexKeys.length; idx++) {
        keys.put(idx, tuple.get(idx));
      }
      indexWriter.write(keys, tuple.getOffset());
    }
    
    indexWriter.flush();
    indexWriter.close();
    return null;
  }

  @Override
  public void rescan() throws IOException {
  }
}