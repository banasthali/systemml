/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.hops.globalopt;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.ibm.bi.dml.hops.DataOp;
import com.ibm.bi.dml.hops.Hop;
import com.ibm.bi.dml.hops.Hop.DataOpTypes;
import com.ibm.bi.dml.hops.Hop.VisitStatus;
import com.ibm.bi.dml.hops.HopsException;
import com.ibm.bi.dml.hops.Hop.FileFormatTypes;
import com.ibm.bi.dml.hops.OptimizerUtils;
import com.ibm.bi.dml.hops.cost.CostEstimationWrapper;
import com.ibm.bi.dml.hops.globalopt.gdfgraph.GDFGraph;
import com.ibm.bi.dml.hops.globalopt.gdfgraph.GDFLoopNode;
import com.ibm.bi.dml.hops.globalopt.gdfgraph.GDFNode;
import com.ibm.bi.dml.hops.globalopt.gdfgraph.GDFNode.NodeType;
import com.ibm.bi.dml.hops.rewrite.HopRewriteUtils;
import com.ibm.bi.dml.lops.LopsException;
import com.ibm.bi.dml.lops.LopProperties.ExecType;
import com.ibm.bi.dml.lops.compile.Recompiler;
import com.ibm.bi.dml.parser.DMLTranslator;
import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.controlprogram.LocalVariableMap;
import com.ibm.bi.dml.runtime.controlprogram.Program;
import com.ibm.bi.dml.runtime.controlprogram.ProgramBlock;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContext;
import com.ibm.bi.dml.runtime.controlprogram.context.ExecutionContextFactory;
import com.ibm.bi.dml.runtime.controlprogram.parfor.stat.Timing;

/**
 * Global data flow optimization via enumeration-based optimizer (dynamic programming). 
 * 
 * 
 * ADDITIONAL PERFORMANCE OPT (once everything is completely working)
 * TODO cache for interesting properties
 * TODO partial runtime plan generation
 * TODO partial runtime plan costing  
 * 
 * FIXME: hash for interesting properties
 */
public class GDFEnumOptimizer extends GlobalOptimizer
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";

	private static final Log LOG = LogFactory.getLog(GDFEnumOptimizer.class);

	//internal configuration parameters 
	//note: that branch and bound pruning is invalid if we cost entire programs
	public static final boolean BRANCH_AND_BOUND_PRUNING = true; 
	public static final boolean PREFERRED_PLAN_SELECTION = true;
	public static final boolean COST_FULL_PROGRAMS       = false;
	
	//internal configuration parameters //TODO remove -1 
	public static final int[] BLOCK_SIZES         = new int[]{1000/*-1,2000,4000*/};
	public static final int[] REPLICATION_FACTORS = new int[]{1,3,5};
			
	private MemoStructure _memo = null; //plan memoization table
	private static long _enumeratedPlans = 0;
	private static long _prunedInvalidPlans = 0;
	private static long _prunedSuboptimalPlans = 0;
	private static long _compiledPlans = 0;
	private static long _costedPlans = 0;

	
	public GDFEnumOptimizer( ) 
	{
		//init internal memo structure
		_memo = new MemoStructure();
	}

	@Override
	public GDFGraph optimize(GDFGraph gdfgraph) 
		throws DMLRuntimeException, DMLUnsupportedOperationException, HopsException, LopsException 
	{
		Timing time = new Timing(true);
		
		Program prog = gdfgraph.getRuntimeProgram();
		ExecutionContext ec = ExecutionContextFactory.createContext(prog);
		ArrayList<GDFNode> roots = gdfgraph.getGraphRootNodes();
		
		//Step 1: baseline costing for branch and bound costs
		double initCosts = Double.MAX_VALUE;
		if( BRANCH_AND_BOUND_PRUNING ) {
			initCosts = CostEstimationWrapper.getTimeEstimate(prog, ec);
		}
		
		//Step 2: dynamic programming plan generation
		//(finally, pick optimal root plans over all interesting property sets)
		ArrayList<Plan> rootPlans = new ArrayList<Plan>(); 
		for( GDFNode node : roots ) {
			PlanSet ps = enumOpt(node, _memo, initCosts);
			Plan optPlan = ps.getPlanWithMinCosts();
			rootPlans.add( optPlan );
		}
		
		//check for final containment of independent roots and pick optimal
		HashMap<Long, Plan> memo = new HashMap<Long,Plan>();
		for( Plan p : rootPlans )
			rSetRuntimePlanConfig(p, memo);
		
		//generate final runtime plan (w/ optimal config)
		Recompiler.recompileProgramBlockHierarchy(prog.getProgramBlocks(), new LocalVariableMap(), 0, false);
		
		ec = ExecutionContextFactory.createContext(prog);
		double optCosts = CostEstimationWrapper.getTimeEstimate(prog, ec);
		
		//print optimization summary
		LOG.info("Optimization summary:");
		LOG.info("-- costs of initial plan:  "+initCosts);
		LOG.info("-- costs of optimal plan:  "+optCosts);
		LOG.info("-- # enumerated plans:     "+_enumeratedPlans);
		LOG.info("-- # pruned invalid plans: "+_prunedInvalidPlans);
		LOG.info("-- # pruned subopt plans:  "+_prunedSuboptimalPlans);
		LOG.info("-- # of program compiles:  "+_compiledPlans);
		LOG.info("-- # of program costings:  "+_costedPlans);
		LOG.info("-- optimization time:      "+String.format("%.3f", (double)time.stop()/1000)+" sec.");
		
		return gdfgraph;
	}
	
	/**
	 * Core dynamic programming enumeration algorithm
	 * for global data flow optimization.
	 * 
	 * @param node
	 * @param maxCosts
	 * @return
	 * @throws DMLRuntimeException 
	 * @throws DMLUnsupportedOperationException 
	 */
	public static PlanSet enumOpt( GDFNode node, MemoStructure memo, double maxCosts )
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		//memoization of already enumerated subgraphs
		if( memo.constainsEntry(node) )
			return memo.getEntry(node);
		
		//enumerate node plans
		PlanSet P = enumNodePlans( node, memo, maxCosts );
		//System.out.println("Plans after enumNodePlan:\n"+P.toString());
		
		//combine local node plan with optimal child plans
		for( GDFNode c : node.getInputs() )
		{
			//recursive optimization
			PlanSet Pc = enumOpt( c, memo, maxCosts );
			if( c instanceof GDFLoopNode )
				Pc = Pc.selectChild( node );
			
			//combine parent-child plans
			P = P.crossProductChild(Pc);
			_enumeratedPlans += P.size();			
			
			//prune invalid plans
			pruneInvalidPlans( P );
		}

		//prune suboptimal plans
		pruneSuboptimalPlans( P, maxCosts );
		
		//memoization of created entries
		memo.putEntry(node, P);
		
		return P;
	}
	
	/**
	 * 
	 * @param node
	 * @param memo 
	 * @return
	 * @throws DMLUnsupportedOperationException 
	 * @throws DMLRuntimeException 
	 */
	private static PlanSet enumNodePlans( GDFNode node, MemoStructure memo, double maxCosts ) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		ArrayList<Plan> plans = new ArrayList<Plan>();
		
		//ENUMERATE HOP PLANS
		// CASE 1: core hop enumeration (other than persistent/transient read/write) 
		if( node.getNodeType() == NodeType.HOP_NODE && !(node.getHop() instanceof DataOp ) ) 
		{
			//create cp plan, if allowed (note: most interesting properties are irrelevant for CP)
			if( node.getHop().getMemEstimate() < OptimizerUtils.getLocalMemBudget() ) {
				RewriteConfig rccp = new RewriteConfig(ExecType.CP, -1, FileFormatTypes.BINARY);
				InterestingProperties ipscp = rccp.deriveInterestingProperties();
				Plan cpplan = new Plan(node, ipscp, rccp, null);
				plans.add( cpplan );
			}
			
			//create mr plans, if required
			if( node.requiresMREnumeration() ) {
				for( Integer bs : BLOCK_SIZES )
				{
					RewriteConfig rcmr = new RewriteConfig(ExecType.MR, bs, FileFormatTypes.BINARY);
					InterestingProperties ipsmr = rcmr.deriveInterestingProperties();
					Plan mrplan = new Plan(node, ipsmr, rcmr, null);
					plans.add( mrplan );			
				}
			}
		}
		//CASE 2: dataop hop enumeration 
		else if( node.getHop() instanceof DataOp )
		{
			DataOp dhop = (DataOp)node.getHop();
			
			if(    dhop.get_dataop()==DataOpTypes.PERSISTENTREAD
				|| dhop.get_dataop()==DataOpTypes.PERSISTENTWRITE )
			{
				//for persistent read/write the interesting properties are fixed by the input (read)
				//and default configuration or specification (write)
				ExecType et = (dhop.getMemEstimate()>OptimizerUtils.getLocalMemBudget()) ? 
						       ExecType.MR : ExecType.CP;
				int blocksize = dhop.getFormatType() == FileFormatTypes.BINARY ? 
						       DMLTranslator.DMLBlockSize : -1; //e.g., -1 for text
				RewriteConfig rcmr = new RewriteConfig(et, blocksize, dhop.getFormatType());
				InterestingProperties ipsmr = rcmr.deriveInterestingProperties();
				Plan mrplan = new Plan(node, ipsmr, rcmr, null);
				plans.add( mrplan );	
			}
			else if(   dhop.get_dataop()==DataOpTypes.TRANSIENTREAD
					|| dhop.get_dataop()==DataOpTypes.TRANSIENTWRITE)
			{
				//do nothing for transient read/write (leads to pass-through on cross product)			
			}
		}
		//ENUMERATE LOOP PLANS
		else if( node.getNodeType() == NodeType.LOOP_NODE )
		{
			//TODO consistency checks inputs and outputs (updated vars)
			
			GDFLoopNode lnode = (GDFLoopNode) node;
			
			//step 0: recursive call optimize on inputs
			//no additional pruning (validity, optimality) required
			for( GDFNode in : lnode.getLoopInputs().values() )
				enumOpt(in, memo, maxCosts);
			
			//step 1: enumerate loop plan, incl partitioning/checkpoints/reblock for inputs
			RewriteConfig rc = new RewriteConfig(ExecType.CP, -1, null);
			InterestingProperties ips = rc.deriveInterestingProperties();
			Plan lplan = new Plan(node, ips, rc, null);
			plans.add( lplan );
			
			//step 2: recursive call optimize on predicate
			//(predicate might be null if single variable)
			if( lnode.getLoopPredicate() != null )
				enumOpt(lnode.getLoopPredicate(), memo, maxCosts);
			
			//step 3: recursive call optimize on outputs
			//(return union of all output plans, later selected by output var)
			PlanSet Pout = new PlanSet();
			for( GDFNode out : lnode.getLoopOutputs().values() )
				Pout = Pout.union( enumOpt(out, memo, maxCosts) );
			plans.addAll(Pout.getPlans());
			
			//note: global pruning later done when returning to enumOpt
			//for the entire loop node			
		}
		//CREATE DUMMY CROSSBLOCK PLAN
		else if( node.getNodeType() == NodeType.CROSS_BLOCK_NODE )
		{
			//do nothing (leads to pass-through on crossProductChild)
		}
		
		return new PlanSet(plans);
	}
	
	/**
	 * 
	 * @param plans
	 */
	private static void pruneInvalidPlans( PlanSet plans )
	{
		ArrayList<Plan> valid = new ArrayList<Plan>();
		
		//check each plan in planset for validity
		for( Plan plan : plans.getPlans() )
		{
			//a) check matching blocksizes if operation in MR
			if( !plan.checkValidBlocksizesInMR() ) {
				//System.out.println("pruned invalid blocksize");
				continue;
			}
			
			//b) check valid format in MR
			if( !plan.checkValidFormatInMR() ) {
				//System.out.println("pruned invalid format: "+plan.getNode().getHop().getClass());
				continue;
			}
				
			//c) check valid execution type per hop (e.g., function, reblock)
			if( !plan.checkValidExecutionType() ) {
				//System.out.println("pruned invalid execution type: "+plan.getNode().getHop().getClass());
				continue;
			}
			
			valid.add( plan );
		}
		
		//debug output
		int sizeBefore = plans.size();
		int sizeAfter = valid.size();
		_prunedInvalidPlans += (sizeBefore-sizeAfter);
		LOG.debug("Pruned invalid plans: "+sizeBefore+" --> "+sizeAfter);
		
		plans.setPlans( valid );
	}
	
	/**
	 * 
	 * @param plans
	 * @param maxCosts 
	 * @throws DMLRuntimeException 
	 * @throws DMLUnsupportedOperationException 
	 */
	private static void pruneSuboptimalPlans( PlanSet plans, double maxCosts ) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		//costing of all plans incl containment check
		for( Plan p : plans.getPlans() ) {
			p.setCosts( costRuntimePlan(p) );
		}
		
		//build and probe for optimal plans (hash-groupby on IPC, min costs) 
		HashMap<InterestingProperties, Plan> probeMap = new HashMap<InterestingProperties, Plan>();
		for( Plan p : plans.getPlans() )
		{
			//max cost pruning filter (branch-and-bound)
			if( BRANCH_AND_BOUND_PRUNING && p.getCosts() > maxCosts ) {
				continue;
			}
			
			//plan cost per IPS pruning filter (allow smaller or equal costs)
			Plan best = probeMap.get(p.getInterestingProperties());
			if( best!=null && p.getCosts() > best.getCosts() ) {
				continue;
			}
			
			//non-preferred plan pruning filter (allow smaller cost or equal cost and preferred plan)
			if( PREFERRED_PLAN_SELECTION && best!=null && 
				p.getCosts() == best.getCosts() && !p.isPreferredPlan() ) {
				continue;
			}
				
			//add plan as best per IPS
			probeMap.put(p.getInterestingProperties(), p);
			
		}
		
		//copy over plans per IPC into one plan set
		ArrayList<Plan> optimal = new ArrayList<Plan>(probeMap.values());
		
		int sizeBefore = plans.size();
		int sizeAfter = optimal.size();
		_prunedSuboptimalPlans += (sizeBefore-sizeAfter);
		LOG.debug("Pruned suboptimal plans: "+sizeBefore+" --> "+sizeAfter);
		
		plans.setPlans(optimal);
	}
	
	/**
	 * 
	 * @param p
	 * @return
	 * @throws DMLRuntimeException 
	 * @throws DMLUnsupportedOperationException 
	 */
	private static double costRuntimePlan(Plan p) 
		throws DMLRuntimeException, DMLUnsupportedOperationException
	{
		Program prog = p.getNode().getProgram();
		if( prog == null )
			throw new DMLRuntimeException("Program not available for runtime plan costing.");
		
		//put data flow configuration into program
		rSetRuntimePlanConfig(p, new HashMap<Long,Plan>());
		
		double costs = -1;
		if( COST_FULL_PROGRAMS || 
		   (p.getNode().getHop()==null || p.getNode().getProgramBlock()==null) )
		{
			//recompile entire runtime program
			Recompiler.recompileProgramBlockHierarchy(prog.getProgramBlocks(), new LocalVariableMap(), 0, false);
			_compiledPlans++;
			
			//cost entire runtime program
			ExecutionContext ec = ExecutionContextFactory.createContext(prog);
			costs = CostEstimationWrapper.getTimeEstimate(prog, ec);
		}
		else
		{
			Hop currentHop = p.getNode().getHop();
			ProgramBlock pb = p.getNode().getProgramBlock();
			
			try
			{
				//keep the old dag roots
				ArrayList<Hop> oldRoots = pb.getStatementBlock().get_hops();
				Hop tmpHop = null;
				if( !(currentHop instanceof DataOp && ((DataOp)currentHop).isWrite()) ){
					ArrayList<Hop> newRoots = new ArrayList<Hop>();
					tmpHop = new DataOp("_tmp", currentHop.getDataType(), currentHop.getValueType(), currentHop, DataOpTypes.TRANSIENTWRITE, "tmp");
					tmpHop.setVisited(VisitStatus.DONE); //ensure recursive visitstatus reset on recompile
					newRoots.add(tmpHop);
					pb.getStatementBlock().set_hops(newRoots);
				}
				
				//recompile modified runtime program
				Recompiler.recompileProgramBlockHierarchy(prog.getProgramBlocks(), new LocalVariableMap(), 0, false);
				_compiledPlans++;
				
				//cost partial runtime program up to current hop
				ExecutionContext ec = ExecutionContextFactory.createContext(prog);
				costs = CostEstimationWrapper.getTimeEstimate(prog, ec);	
				
				//restore original hop dag
				if( tmpHop !=null )
					HopRewriteUtils.removeChildReference(tmpHop, currentHop);
				pb.getStatementBlock().set_hops(oldRoots);	
			}
			catch(HopsException ex)
			{
				throw new DMLRuntimeException(ex);
			}
		}
		
		//release forced data flow configuration from program
		rResetRuntimePlanConfig(p, new HashMap<Long,Plan>());		
		_costedPlans++;
		
		return costs;
	}
	
	private static void rSetRuntimePlanConfig( Plan p, HashMap<Long, Plan> memo )
	{
		//basic memoization including containment check 
		if( memo.containsKey(p.getNode().getID()) ) {
			Plan pmemo = memo.get(p.getNode().getID());
			if( !p.getInterestingProperties().equals( pmemo.getInterestingProperties()) ) {
				LOG.warn("Configuration mismatch on shared node ("+p.getNode().getHop().getHopID()+"). Falling back to heuristic 'FIRST'.");
				LOG.warn(p.getInterestingProperties().toString());
				LOG.warn(memo.get(p.getNode().getID()).getInterestingProperties());
				return;
			}
		}
		
		//set plan configuration
		Hop hop = p.getNode().getHop();
		if( hop!=null ) {
			hop.setForcedExecType(p.getRewriteConfig().getExecType());
			//TODO extend as needed
		}
		
		//process childs
		if( p.getChilds() != null )
			for( Plan c : p.getChilds() )
				rSetRuntimePlanConfig(c, memo);
		
		//memoization (mark as processed)
		memo.put(p.getNode().getID(), p);
	}
	
	private static void rResetRuntimePlanConfig( Plan p, HashMap<Long, Plan> memo )
	{
		//basic memoization including containment check 
		if( memo.containsKey(p.getNode().getID()) ) {
			return;
		}
		
		//release forced plan configuration
		Hop hop = p.getNode().getHop();
		if( hop!=null ) {
			hop.setForcedExecType(null);
			//TODO extend as needed
		}
		
		//process childs
		if( p.getChilds() != null )
			for( Plan c : p.getChilds() )
				rResetRuntimePlanConfig(c, memo);
		
		//memoization (mark as processed)
		memo.put(p.getNode().getID(), p);
	}
}
