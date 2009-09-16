/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.zebra.types;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.DataBag;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import org.apache.pig.backend.executionengine.ExecException;


public class SubColumnExtraction {
	/**
	 * This class extracts a subfield from a column or subcolumn stored
   * in entirety on disk
	 * It should be used only by readers whose serializers do not
   * support projection
	 */
	public static class SubColumn {
		Schema physical;
		Projection projection;
		ArrayList<SplitColumn> exec = null;
		SplitColumn top = null; // directly associated with physical schema 
		SplitColumn leaf = null; // target tuple corresponding to projection

    // list of SplitColumns to be created maps on their children
    ArrayList<SplitColumn> sclist = new ArrayList<SplitColumn>();

		SubColumn(Schema physical, Projection projection) throws ParseException, ExecException
		{
			this.physical = physical;
			this.projection = projection;
			top = new SplitColumn(Partition.SplitType.RECORD);
			exec = new ArrayList<SplitColumn>();
			exec.add(top); // breadth-first 
		
			SplitColumn sc;
			leaf = new SplitColumn(Partition.SplitType.RECORD);
			Schema.ColumnSchema fs;
			Schema.ParsedName pn = new Schema.ParsedName();
			String name;
			int j;
			HashSet<String> keySet;
			for (int i = 0; i < projection.getSchema().getNumColumns(); i++)
			{
				fs = projection.getColumnSchema(i);
				if (fs == null)
				  continue;
				name = fs.name;
				if (name == null)
					continue;
				if (projection.getKeys() != null)
				  keySet = projection.getKeys().get(fs);
				else
				  keySet = null;
				pn.setName(name);
				fs = physical.getColumnSchema(pn);
				if (keySet != null)
				  pn.mDT = ColumnType.MAP;
				if (fs == null)
		     		continue; // skip non-existing field
		
				j = fs.index;
				if (pn.mDT == ColumnType.MAP || pn.mDT == ColumnType.RECORD || pn.mDT == ColumnType.COLLECTION)
				{
					// record/map subfield is expected
					sc = new SplitColumn(j, pn.mDT);
          if (pn.mDT == ColumnType.MAP)
            sclist.add(sc);
					exec.add(sc); // breadth-first
					// (i, j) represents the mapping between projection and physical schema
					buildSplit(sc, fs, pn, i, (projection.getKeys() == null ? null :
                keySet));
				} else {
					// (i, j) represents the mapping between projection and physical schema
					sc = new SplitColumn(j, i, leaf, null, Partition.SplitType.NONE);
					// no split on a leaf
				}
				top.addChild(sc);
		 	}
		}

    /**
     * build the split executions
     */
		private void buildSplit(SplitColumn parent, Schema.ColumnSchema fs,
        Schema.ParsedName pn, final int projIndex, HashSet<String> keys) throws ParseException, ExecException
		{
			// recursive call to get the next level schema
			if (pn.mDT != fs.type)
	      	throw new ParseException(fs.name+" is not of proper type.");
	
			String prefix;
			int fieldIndex;
			SplitColumn sc;
			Partition.SplitType callerDT = (pn.mDT == ColumnType.MAP ? Partition.SplitType.MAP :
				                               (pn.mDT == ColumnType.RECORD ? Partition.SplitType.RECORD :
				                                 (pn.mDT == ColumnType.COLLECTION ? Partition.SplitType.COLLECTION :
				                        	         Partition.SplitType.NONE)));
			prefix = pn.parseName(fs);
			if (callerDT == Partition.SplitType.RECORD || callerDT == Partition.SplitType.COLLECTION)
			{
        if (keys != null)
          throw new AssertionError("Internal Logical Error: empty key map expected.");
				 if ((fieldIndex = fs.schema.getColumnIndex(prefix)) == -1)
	        		return; // skip non-existing fields
				 fs = fs.schema.getColumn(fieldIndex);
			} else {       
        parent.setKeys(keys); // map key is set at parent which is of type MAP
        fs = fs.schema.getColumn(0); // MAP value is a singleton type!
				fieldIndex = 0;
			}
	
			if (pn.mDT != ColumnType.ANY)
			{
				// record subfield is expected
			 	sc = new SplitColumn(fieldIndex, pn.mDT);
        if (pn.mDT == ColumnType.MAP)
          sclist.add(sc);
			 	exec.add(sc); // breadth-first
			 	buildSplit(sc, fs, pn, projIndex, null);
			} else {
				sc = new SplitColumn(fieldIndex, projIndex, leaf, null, Partition.SplitType.NONE);
				// no split on a leaf
			}
			parent.addChild(sc);
		}

    /**
     * dispatch the source tuple from disk
     */
		void dispatchSource(Tuple src)
		{
			top.dispatch(src);
	 	}

    /**
     * dispatch the target tuple
     */
		private void dispatch(Tuple tgt) throws ExecException
		{
			leaf.dispatch(tgt);
			createMaps();
			leaf.setBagFields();
		}

    /**
     * the execution
     */
		void splitColumns(Tuple dest) throws ExecException, IOException
		{
			int i;
			dispatch(dest);
      clearMaps();
			for (i = 0; i < exec.size(); i++)
			{
				if (exec.get(i) != null)
				{
					// split is necessary
					exec.get(i).split();
				}
			}
		}

    /**
     * create MAP fields if necessary
     */
    private void createMaps() throws ExecException
    {
      for (int i = 0; i < sclist.size(); i++)
        sclist.get(i).createMap();
    }

    /**
     * clear map fields if necessary
     */
    private void clearMaps() throws ExecException
    {
      for (int i = 0; i < sclist.size(); i++)
        sclist.get(i).clearMap();
    }
	}

  /**
   * helper class to represent one execution
   */
  private static class SplitColumn {
	 int fieldIndex = -1; // field index to parent
	 int projIndex = -1; // index in projection: only used by leaves
	 ArrayList<SplitColumn> children = null;
	 int index = -1; // index in the logical schema 
	 Object field = null;
	 SplitColumn leaf = null; // leaf holds the target tuple
	 Partition.SplitType st = Partition.SplitType.NONE;
	 HashSet<String> keys;
	 Schema scratchSchema; // a temporary 1-column schema to be used to create a tuple
	                       // for a COLLETION column
	 ArrayList<Integer> bagFieldIndices;

	 void dispatch(Object field) { this.field = field; }

	 void setKeys(HashSet<String> keys) { this.keys = keys; }

	 SplitColumn(Partition.SplitType st)
	 {
		 this.st = st;
	 }

	 SplitColumn(ColumnType ct)
	 {
		 if (ct == ColumnType.MAP)
			 st = Partition.SplitType.MAP;
		 else if (ct == ColumnType.RECORD)
			 st = Partition.SplitType.RECORD;
		 else if (ct == ColumnType.COLLECTION)
		 {
		   st = Partition.SplitType.COLLECTION;
		   try {
		      scratchSchema = new Schema("foo");
		   } catch (ParseException e) {
		     // no-op: should not throw at all.
		   }
		 } else
			 st = Partition.SplitType.NONE;
	 }

	 SplitColumn(int fieldIndex, ColumnType ct)
	 {
		 this(ct);
		 this.fieldIndex = fieldIndex;
	 }

	 SplitColumn(int fieldIndex, Partition.SplitType st)
	 {
		 this.fieldIndex = fieldIndex;
		 this.st = st;
	 }

	 SplitColumn(int fieldIndex, HashSet<String> keys, Partition.SplitType st)
	 {
		 this.fieldIndex = fieldIndex;
		 this.keys = keys;
		 this.st = st;
	 }

	 SplitColumn(int fieldIndex,int projIndex, SplitColumn leaf, HashSet<String> keys, Partition.SplitType st)
	 {
		 this(fieldIndex, keys, st);
		 this.projIndex = projIndex;
		 this.leaf = leaf;
	 }
	 
   /**
    * the split op
    */
	 @SuppressWarnings("unchecked")
	 void split() throws IOException, ExecException
	 {
		 if (children == null)
			 return;
		 
		 int size = children.size();
		 if (st == Partition.SplitType.RECORD)
		 {
			 for (int i = 0; i < size; i++)
			 {
				 if (children.get(i).projIndex != -1) // a leaf: set projection directly
			 		((Tuple)children.get(i).leaf.field).set(children.get(i).projIndex, ((Tuple) field).get(children.get(i).fieldIndex));
				 else
					 children.get(i).field = ((Tuple) field).get(children.get(i).fieldIndex);
			 }
		 } else if (st == Partition.SplitType.COLLECTION) {
		    DataBag srcBag, tgtBag;
		    srcBag = (DataBag) field;
		    Tuple tuple;
		    for (int i = 0; i < size; i++)
		    {
		      if (children.get(i).projIndex != -1) // a leaf: set projection directly
		      {
		        tgtBag = (DataBag)((Tuple)children.get(i).leaf.field).get(children.get(i).projIndex);
		      } else {
		        tgtBag = (DataBag) children.get(i).field;
		        tgtBag.clear();
		      }
		      for (Iterator<Tuple> it = srcBag.iterator(); it.hasNext(); )
		      {
		        tuple = TypesUtils.createTuple(scratchSchema);
		        tuple.set(0, it.next().get(children.get(i).fieldIndex));
		        tgtBag.add(tuple);
		      }
		    }
		 } else if (st == Partition.SplitType.MAP && keys != null) {
       String key;
       Iterator<String> it;

			 for (int i = 0; i < size; i++)
			 {
				 if (children.get(i).projIndex != -1) // a leaf: set projection directly
         {
           for (it = keys.iterator(); it.hasNext(); )
           {
             key = it.next();
			 		   ((Map<String, Object>) (((Tuple)children.get(i).leaf.field).get(children.get(i).projIndex))).put(key, ((Map<String, Object>) field).get(key));
           }
         } else {
           for (it = keys.iterator(); it.hasNext(); )
           {
             key = it.next();
					   children.get(i).field = ((Map<String, Object>) field).get(key);
           }
         }
			 }
		 }
	 }

   /**
    * add a child that needs a subfield of this (sub)column
    */
	 void addChild(SplitColumn child) throws ExecException {
		 if (children == null)
			 children = new ArrayList<SplitColumn>();
		 children.add(child);
		 if (st == Partition.SplitType.COLLECTION) {
		   if (child.projIndex != -1)
		   {
		     child.leaf.addBagFieldIndex(child.projIndex);
		   } else {
		     ((Tuple) child.field).set(child.fieldIndex, TypesUtils.createBag());
		   }
		 }
	 }
	 
	 /**
	  * add a bag field index
	  */
	 void addBagFieldIndex(int i)
	 {
	   if (bagFieldIndices == null)
	     bagFieldIndices = new ArrayList<Integer>();
	   bagFieldIndices.add(i);
	 }
	 
	 /**
	  * set bag fields if necessary
	  */
	 void setBagFields() throws ExecException
	 {
	   if (bagFieldIndices == null)
	     return;
	   for (int i = 0; i < bagFieldIndices.size(); i++)
	   {
	     ((Tuple) field).set(bagFieldIndices.get(i), TypesUtils.createBag());
	   }
	 }

   /**
    * create MAP fields for children
    */
   void createMap() throws ExecException
   {
     if (st == Partition.SplitType.MAP)
     {
       int size = children.size();
       for (int i = 0; i < size; i++)
       {
				 if (children.get(i).projIndex != -1)
			 		 ((Tuple)children.get(i).leaf.field).set(children.get(i).projIndex, new HashMap<String, Object>());
				 else
           children.get(i).field = new HashMap<String, Object>();
       }
     }
   }
   
    /**
     * clear map for children
     */
	  @SuppressWarnings("unchecked")
    void clearMap() throws ExecException
    {
      if (st == Partition.SplitType.MAP)
      {
        int size = children.size();
        for (int i = 0; i < size; i++)
        {
	 			  if (children.get(i).projIndex != -1)
	 		 		  ((Map)((Tuple)children.get(i).leaf.field).get(children.get(i).projIndex)).clear();
          else
            ((Map)children.get(i).field).clear();
        }
      }
    }
   
  }
}